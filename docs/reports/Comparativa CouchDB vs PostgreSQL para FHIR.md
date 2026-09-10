# Reporte Técnico: CouchDB vs PostgreSQL como almacenamiento FHIR para el API del proyecto

**Fecha:** 9 de septiembre de 2026  
**Proyecto:** OpenICE / MD PnP (`1.5.1-SNAPSHOT`)  
**Sistema:** Backend FHIR R4 (HAPI)  
**Alcance:** Evaluación de reemplazar PostgreSQL por CouchDB como motor de almacenamiento del API FHIR

---

## 1. Contexto

El flujo actual de datos FHIR del proyecto es:

```
OpenICE HL7 Emitter (FhirEmitter.java)
   │  Transaction Bundle (FhirEmitter.java:310)
   ▼
FHIR Gateway (localhost:8080, ListAccessChecker)
   │  POST Bearer token
   ▼
HAPI FHIR Backend (localhost:8099) ──► PostgreSQL (almacenamiento)
```

Los usos concretos del API FHIR en el código del proyecto son:

| Interacción FHIR | Código | Nota |
|---|---|---|
| Search de Patient por `identifier` (MRN) | `FhirEmitter.java:111-116` | Búsqueda tipada por identificador |
| Upsert condicional de `Patient` (PUT `conditional` + MRN) | `FhirEmitter.java:128-132` | Idempotencia por identificador |
| Upsert condicional de `Device` (UDI) | `FhirEmitter.java:159-163` | Idempotencia por identificador |
| Transaction bundle de Observations (all-or-nothing) | `FhirEmitter.java:310` | **Atomicidad multi-recurso obligatoria** |
| Read-modify-write de `patient-list-example` (List) | `FhirEmitter.java:180-199` | Actualización de un solo documento |

**Fuera de alcance:** la telemetría TimescaleDB/`db.sql` (numéricos, waveforms, alertas) no es FHIR y no se toca en esta comparativa. El backend HAPI actualmente se ejecuta contra PostgreSQL (configuración estándar del *starter* HAPI: Hibernate + dialecto `HapiFhirPostgresDialect`).

---

## 2. Problema

Antes de decidir un motor de datos hay que confrontarlo contra los **requisitos que FHIR impone a cualquier sistema de almacenamiento** y los que el proyecto usa hoy:

1. **Atomicidad de `Bundle.type = transaction`** — la spec exige all-or-nothing sobre la operación completa (varias Observaciones + sus referencias). El proyecto depende de ello (`FhirEmitter.java:310`).
2. **Idempotencia condicional** — `If-None-Exist` / conditional PUT exigen "crear solo si el identificador no existe" dado un mismo identificador.
3. **Integridad referencial** — `Observation.subject → Patient/123`, `Observation.device → Device/udi`, referencias dentro de bundles (`urn:uuid:`).
4. **Versionado `_history`** — auditar versiones anteriores de cada recurso.
5. **Búsqueda tipada FHIR** — parámetros `token`, `date`, `reference`, `string` (case/accent-insensitive), con modificadores (`:exact`, `:missing`, `:not`) y comparadores (`gt/lt/ge/le`) sobre **combinaciones** de parámetros.

CouchDB opera con un modelo fundamentalmente distinto: **ACID por documento** (MVCC + `_rev`), sin transacciones multi-documento, consistencia eventual bajo replicación, consultas exclusivamente vía *views* (MapReduce precomputado) o **Mango** (declarativo, sin JOIN). La pregunta es si ese modelo puede sostener el mismo contrato FHIR que hoy cumple HAPI + PostgreSQL.

---

## 3. Hipótesis

- **H1.** CouchDB **no puede ser un reemplazo directo** (drop-in) del almacenamiento de HAPI JPA: HAPI JPA es Hibernate/JPA y requiere una base relacional (SQL). Adoptar CouchDB implica abandonar HAPI JPA o implementar un *Plain Server* con DAOs propios.
- **H2.** La **atomicidad multi-recurso** (transaction bundle, referencias `urn:uuid:`) es el principal punto de fricción, y el proyecto la usa explícitamente en la ruta crítica de observaciones.
- **H3.** Las fortalezas de CouchDB (JSON nativo, replicación master-master offline, adjuntos, REST) no se aprovechan en un backend central FHIR, sino en un rol **periférico** (borde/offline), no como fuente de verdad.

---

## 4. Proceso

### 4.1 Compatibilidad con HAPI FHIR (oficial)

De la documentación de soporte de HAPI FHIR JPA Server (verificado):

| Motor | Estado oficial en HAPI JPA |
|---|---|
| MS SQL Server | Soportado |
| **PostgreSQL** | **Soportado** |
| Oracle | Soportado |
| CockroachDB | Experimental (contribución comunitaria) |
| MySQL / MariaDB | Deprecado (mal rendimiento) |
| **CouchDB / MongoDB** | **No soportado** |

HAPI JPA usa Hibernate ORM; el "Plain Server" permite un backend arbitrario solo si **uno mismo implementa los DAOs** (búsqueda, indexación y persistencia). Es decir: hoy no hay capa de abstracción de almacenamiento NoSQL ya resuelta en HAPI.

### 4.2 Mapeo requisito FHIR → soporte CouchDB

| Requisito del API FHIR (usado por el proyecto) | Soporte nativo en CouchDB | Evaluación |
|---|---|---|
| Transaction bundle atómico multi-recurso (`FhirEmitter.java:310`) | ❌ Sin transacciones multi-documento. `_bulk_docs` es atómico *por documento*; el `all_or_nothing` fue retirado en CouchDB 3 por ser imposible con *sharding* | **Crítico.** El envío actual de N observaciones perdería la garantía all-or-nothing; habría que degradar a `batch` + reconciliación manual vía `_changes` |
| Upsert condicional por identificador (`:128-132`, `:159-163`) | ⚠️ Emulable: view `identifier → docid` + reintento con `_rev` (compare-and-swap) | Implementable, pero **con carreras** entre dos escritores concurrentes (la actualización del identificador y el PUT no son atómicos entre sí) |
| Search por `identifier` / `token` / `date` (`:111-116`) | ⚠️ Sí, con *views* o índices Mango, **una por SearchParameter** | La spec permite implementar solo un subconjunto de parámetros (`_id` es el único obligatorio), lo que ayuda; pero cada parámetro nuevo exige su view/índice y re-indexación |
| Parámetros encadenados (`Observation?subject=Patient/x&code=...`), `revinclude`, `_filter` | ❌ Inválidos sobre Mango/views; requieren lógica de aplicación (varias queries + merge) | Alto costo de implementación para igualar a HAPI |
| Texto libre `_text`/`_content` | ❌ Mango no incluye índices de texto (son de terceros, p. ej. Clouseau) | El proyecto no lo usa hoy; no bloquea |
| `_history` (auditoría de versiones) | ⚠️ CouchDB conserva `_rev` pero **purga revisiones viejas en la compactación**; no son consultables | Debe implementarse explícitamente (history como documento/pila por recurso) |
| Integridad referencial (`subject`, `device`) | ❌ No verificada por el motor (sin FK) | Debe validarse en la capa de aplicación (igual que hoy el Gateway valida contra la Lista `patient-list-example`) |
| Adjuntos (ej. `Binary`) | ✅ Adjuntos nativos de CouchDB | Ventaja niche |
| Almacenar el JSON FHIR tal cual | ⚠️ El JSON FHIR usa claves de nivel superior con prefijo `_` (`_birthDate`, `_text`, `_id`, `_rev`) | CouchDB **reserva** `_` a nivel superior del documento (issue oficial `apache/couchdb#4907`); obliga a envolver el recurso en un contenedor meta (un nivel abajo) que luego hay que desempaquetar en cada read/write |

### 4.3 Precedentes reales (CouchDB en salud)

- **Medic Mobile / Community Health Toolkit** — CouchDB + PouchDB en producción para salud de campo con replicación offline. Es el caso de uso natural de CouchDB (protocolo de réplica robusto, filtered replication), pero **sin FHIR**: su modelo de datos es propio y la replicación en escala (100 M de pacientes) requirió algoritmos a medida, no el protocolo nativo.
- **HospitalRun** — HIS sobre CouchDB/PouchDB con offline-first. **Abandonado/deprecado.**
- **iCure** — construido sobre CouchDB con resolución de conflictos propia; usa un **modelo de datos agnóstico** (no FHIR nativo) precisamente porque "FHIR-on-CouchDB" no existe como producto maduro.
- **HealthCouch** — repositorio de documentos **IHE XDS.b** (no FHIR) sobre CouchDB.
- **DICOM en CouchDB** (PubMed 2012) — archivado de metadatos con *views* y adjuntos: valida el rol *document repository*, no un servidor FHIR transaccional.
- **No existe** un servidor FHIR sobre CouchDB de grado productivo.

### 4.4 Comparación con alternativas document-store que sí tienen FHIR

| Motor | Estado | FHIR |
|---|---|---|
| **PostgreSQL + HAPI JPA** (hoy) | Maduro, soportado oficialmente | Completo (search, transaction, history) |
| MongoDB (Kodjin, Asymmetrik FHIR) | Comercial/open-source | Servidores FHIR reales sobre documento |
| Couchbase (N1QL FHIR) | Enterprise | Aproximación document-oriented |
| Microsoft FHIR Server (Cosmos DB) | Producción | Server FHIR sobre NoSQL con capa de query traducida a SQL de Cosmos |
| **CouchDB** | Apache, sin servidor FHIR | **Ninguna implementación madura** |

---

## 5. Resultado

### 5.1 Ventajas de CouchDB

| Ventaja | Relevancia para este proyecto |
|---|---|
| JSON nativo (FHIR es JSON), REST puro (`:5984`), sin ORM camp | Ajuste conceptual y operativo simple con la capa de emisión |
| Replicación **bidireccional + PouchDB offline** | Enorme para el escenario edge (Raspberry Pi en quirófano, conectividad intermitente) como **caché/replica** |
| ACID por documento (append-only, crash-safe, MVCC `_rev`) | Escrituras individuales sólidas; adecuado para el read-modify-write de la List (una sola doc) |
| Adjuntos para `Binary`/waveforms empaquetados | Complemento, no requisito actual |
| Escalado de lectura y HA por clustering/reactor | Útil para volumetría de observaciones solo-lectura |

### 5.2 Desventajas de CouchDB

| Desventaja | Impacto concreto |
|---|---|
| **Sin transacciones multi-documento** | Rompe la garantía all-or-nothing del transaction bundle de `FhirEmitter.java:310`; el Gateway y el backend podrían quedar con Observaciones huérfanas |
| **No es drop-in de HAPI JPA** | Adopción = abandonar HAPI JPA o escribir DAOs propios (search + indexación + persistencia) desde cero |
| **Búsqueda FHIR compleja** | Cada SearchParameter encadenado/comparador/modificador exige views/índices propios; el subset actual (identifier, subject) es viable, el resto no |
| `_` reservado a nivel superior | Todo recurso FHIR requiere *wrapping* meta (issue `#4907`) y desempaquetado en cada operación |
| `_history` no es retenido por la DB | Auditoría clínica debe implementarse a mano |
| Consistencia eventual bajo réplica | Riesgo para una **fuente de verdad** clínica si se replica bidireccionalmente |
| Resolución de conflictos = responsabilidad de la app | Lógica adicional que hoy no existe en el proyecto |
| Escalado productivo más difícil | Clustering con shards fijos; filtered replication a escala requiere desarrollo propio (lección de Medic Mobile) |
| Curva/ecosistema | Java+Spring+HAPI+SQL es el mainstream del equipo; views en JavaScript y novo paradigma |

### 5.3 Veredicto por escenario

| Escenario | CouchDB | PostgreSQL (HAPI) |
|---|---|---|
| **Fuente de verdad FHIR central (API actual)** | ❌ Inviable sin reescribir capa de almacenamiento y degradar garantías | ✅ Mantiene contrato FHIR completo |
| **Borde/offline (RPi, quirófano, sincronización intermitente)** | ✅ Excelente (réplica + PouchDB) | ❌ No aporta |
| **Document repository / Binary / archivado** | ✅ Buenos antecedentes (DICOM, HealthCouch) | ⚠️ Correcto pero sin ventaja |
| **Almacén de telemetría de alta escritura (no FHIR)** | ⚠️ Posible, sin ventaja clara | ✅ TimescaleDB ya resuelve esto en el proyecto |

---

## 6. Conclusión

> **CouchDB no debe reemplazar a PostgreSQL como motor de almacenamiento del API FHIR del proyecto.** Los tres motivos determinantes son: (1) HAPI FHIR JPA no soporta CouchDB por diseño (es JPA/Hibernate → relacional), por lo que la adopción implicaría reescribir la capa de persistencia y búsqueda; (2) la ruta crítica de escritura actual (`FhirEmitter.java:310`) depende de **transaction bundles atómicos** — una garantía que CouchDB no ofrece por construcción (ACID únicamente por documento); y (3) la búsqueda FHIR tipada, encadenada y versionada (`_history`) que HAPI+PostgreSQL entrega hoy exigiría reconstrucción manual sobre *views*/Mango con pérdida de funcionalidad y riesgos de integridad referencial. Las ventajas reales de CouchDB (replicación offline, JSON nativo, adjuntos) se ejercen en un rol **periférico**, no en el núcleo transaccional del API.

---

## 7. Recomendaciones

1. **Mantener PostgreSQL + HAPI JPA como fuente de verdad FHIR** en el backend `:8099`. Es el único camino soportado oficialmente y mantiene intactas las garantías que el proyecto ya usa.
2. **Si se busca document-store de verdad:** evaluar **MongoDB** (Kodjin, Asymmetrik FHIR Server) o **Microsoft FHIR Server sobre Cosmos DB**, que ya resuelven FHIR sobre NoSQL con capa de queries; no elegir CouchDB.
3. **Si CouchDB se usa, limitarlo a un rol complementario:**
   - Caché/replica de lectura **unidireccional** (OpenICE → CouchDB/PouchDB) para el nodo de quirófano (Raspberry Pi), nunca como fuente de verdad.
   - Repositorio de adjuntos/`Binary` y archivo de documentos (estilo HealthCouch/DICOM).
4. **Si (contra la recomendación anterior) se insistiera en un servidor FHIR sobre CouchDB:**
   - Partir del *Plain Server* de HAPI con DAOs propios y **declarar en el CapabilityStatement** el subconjunto de interacciones soportado (la spec solo exige `_id`).
   - Envolver cada recurso FHIR en un contenedor meta (el prefijo `_` de FHIR colisiona con el espacio reservado de CouchDB).
   - **Degradar `transaction` a `batch` + reconciliación** vía `_changes` (crear → enviar → confirmar → corregir), con correlación por identificador y compensación explícita.
   - Implementar `_history` explícito y resolución de conflictos por la aplicación.
   - **PoC obligatorio:** importar el volumen real de Observaciones de quirófano y ejecutar las mismas búsquedas de `FhirEmitter.java` (`Patient?identifier`, `Observation?subject`) contra views CouchDB, midiendo latencia y correctitud bajo escritores concurrentes.

---

*Reporte generado a partir del análisis cruzado de la documentación oficial de HAPI FHIR y Apache CouchDB, la especificación FHIR R4 (search y bundles), el flujo de datos FHIR del proyecto (`FhirEmitter.java`, `Fhir.java`) y antecedentes de sistemas de salud basados en CouchDB (Medic Mobile/CHT, HospitalRun, iCure, HealthCouch, archivado DICOM).*