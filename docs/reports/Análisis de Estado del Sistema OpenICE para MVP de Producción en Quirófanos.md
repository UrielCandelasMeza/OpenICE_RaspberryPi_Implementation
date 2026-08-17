# Reporte Técnico: Análisis de Estado del Sistema OpenICE para MVP de Producción en Quirófanos

**Fecha:** 17 de agosto de 2026

**Sistema:** OpenICE / MD PnP — Supervisor, Headless Adapter, FHIR Exporter, EMR

**Alcance:** Evaluación de madurez del sistema completo (dispositivo → DDS → Supervisor → FHIR → EMR) para despliegue en 50 quirófanos con servidor central

---

## 1. Contexto

El proyecto OpenICE despliega headless adapters en Raspberry Pi 3 para capturar telemetría de dispositivos médicos y transmitirla vía DDS a un Supervisor centralizado. El escenario de producción define las siguientes restricciones:

- **50 quirófanos**, cada uno con su propio Raspberry Pi 3 y su propio Supervisor (aplicación Android).
- **Servidor central** con HAPI FHIR, Keycloak y FHIR Gateway, accesible vía LAN hospitalaria.
- **EMR web** (React/Next.js) para consulta de datos, consultado el HAPI central.
- **Sin EMR existente** — hay que construirlo.
- **Red LAN garantizada** entre todos los edificios y el servidor central.
- **DDS en Android** pendiente de definir: RTI Connext (con licencia) o Cyclone DDS (open source).
- La PCB con DS3231 + RS232 está en desarrollo para sincronización de tiempo en las Pi.

## 2. Arquitectura Objetivo

```
                 ┌──────────── QUIRÓFANO N ────────────┐
                 │                                     │
                 │  [Dispositivo Médico]               │
                 │       │ serial/TCP                  │
                 │       ▼                             │
                 │  [Raspberry Pi 3]                   │
                 │  (headless adapter)                 │
                 │       │ DDS (domain N)              │
                 │       ▼                             │
                 │  [App Android]                      │
                 │  (Supervisor + FHIR client)         │
                 └───────────────┬─────────────────────┘
                                 │ HTTPS/FHIR
                                 ▼
                 ┌────────── SERVIDOR CENTRAL ──────────┐
                 │                                      │
                 │  ┌─────────┐ ┌──────────┐ ┌───────┐  │
                 │  │  HAPI   │ │ Keycloak │ │Gateway│  │
                 │  │  (8099) │ │  (9080)  │ │ (8080)│  │
                 │  └────┬────┘ └──────────┘ └───┬───┘  │
                 │       │                       │      │
                 │       └───────────┬───────────┘      │
                 │                   ▼                  │
                 │         [React/Next.js EMR]          │
                 │         (Web UI para consulta)       │
                 └──────────────────────────────────────┘
```

Cada quirófano es una unidad autónoma en cuanto a datos clínicos (DDS local), pero comparte el servidor central para persistencia FHIR y consulta EMR.

## 3. Estado Actual del Sistema

### 3.1 Componentes completos (no requieren cambios)

| Componente | Ubicación | Estado |
|---|---|---|
| Headless adapter (Pi → DDS) | `HeadlessMain.java` | Listo para producción |
| Drivers de dispositivo (40+) | `devices/*` | Listos |
| DDS domain por quirófano | `-domain N` / `ICE_DOMAIN_ID=N` | Funcional |
| Publicación DDS (Numeric, SampleArray, Alert) | `AbstractDevice.java` | Funcional |
| QoS profiles (ordenamiento por reception timestamp) | `ice_library.xml` | Funcional |
| Configuración DDS (participant, publisher, subscriber) | `RtConfig.xml` | Funcional |

### 3.2 Componentes parciales (requieren corrección)

| Componente | Ubicación | Estado |qué falta |
|---|---|---|---|
| FhirEmitter | `FhirEmitter.java` | Escrito, funcional parcialmente | Mapeo LOINC/UCUM, `System.out` → SLF4J, manejo de errores |
| TokenProvider | `TokenProvider.java` | Escrito, funcional | Credenciales hardcoded en `ice.properties` |
| FhirEMRImpl | `FhirEMRImpl.java` | Parcial | `deleteDevicePatientAssociation()` y `updateDevicePatientAssociation()` son NO-OP |
| ValidationOracle | `ValidationOracle.java` | Funcional | Sin cambios necesarios |
| MQTT Send bridge | `MqttSend.java` | Funcional | Solo publisher; sin subscriber ni converter a FHIR |
| TimeManager (DDS Time Sync) | `TimeManager.java` | Funcional con bugs | Umbral incorrecto, one-shot, requiere sudo |
| DS3231 (PCB) | Hardware | En desarrollo | Configuración en `/boot/config.txt` pendiente |

### 3.3 Componentes faltantes (no existen)

| Componente | Descripción |
|---|---|
| Docker Compose (HAPI + Keycloak + Gateway) | No hay ningún Docker Compose para servicios FHIR |
| HAPI FHIR Server desplegado | No existe en el repo; solo se usa vía HTTP en `FhirEmitter` |
| Keycloak desplegado | No existe; `TokenProvider` apunta a localhost:9080 pero no hay config |
| FHIR Gateway desplegado | No existe; `ListAccessChecker` no está implementado |
| EMR web (React/Next.js) | No existe; `HL7-FHIR Exporter.md` está vacío |
| App Android (Supervisor) | No existe; el Supervisor actual es JavaFX, hay que portar a Android |
| Mapeo LOINC/UCUM | `FhirEmitter` usa strings MDC directamente, no códigos estándar |
| TLS/HTTPS | Todo el tráfico FHIR es HTTP plano |
| Instalador Android | No hay APK ni proceso de distribución |

## 4. Brechas para Producción

### 4.1 Docker Compose (HAPI + Keycloak + Gateway)

No existe ningún Docker Compose para levantar los servicios FHIR. Los Dockerfiles existentes (`Dockerfile`, `Dockerfile_pump`, `Dockerfile_monitor`) son solo para el modo dispositivo del Supervisor. Se necesita un `docker-compose.yml` que levante:

- **HAPI FHIR Server** (imagen `hapifhir/hapi-fhir-jpaserver`) en puerto 8099
- **Keycloak** (imagen `quay.io/keycloak/keycloak`) en puerto 9080
- **FHIR Gateway** (con `ListAccessChecker`) en puerto 8080
- **PostgreSQL** (backend de HAPI)

### 4.2 Mapeo LOINC/UCUM en FhirEmitter

El código actual usa strings MDC como códigos FHIR:

```java
obs.setCode(new CodeableConcept()
    .addCoding(new Coding()
        .setSystem("OpenICE")
        .setCode(data.getMetric_id())));  // "MDC_PULS_OXIM_SAT_O2"
```

Para interoperabilidad real, se necesita mapear a códigos LOINC estándar. La tabla `metric_types` en `db.sql` ya tiene columnas `loinc_code` y `ucum_code` — solo falta usarlas en `FhirEmitter`.

### 4.3 FhirEMRImpl — Asociación device-patient

Los métodos `deleteDevicePatientAssociation()` y `updateDevicePatientAssociation()` en `FhirEMRImpl.java` son NO-OP (no hacen nada). Para producción, deben crear/actualizar/eliminar `Device` resources en HAPI.

### 4.4 Credenciales fuera de source control

`ice.properties` en el classpath contiene credenciales reales (`dr.gomez`/`demo1234`). Para producción, las credenciales deben venir de variables de entorno o un vault, nunca del repo.

### 4.5 TLS/HTTPS

Todo el tráfico FHIR es HTTP plano. En producción con datos de pacientes, se necesita TLS. HAPI y Keycloak pueden configurarse con certificados autofirmados por OR.

### 4.6 App Android (port del Supervisor)

El Supervisor actual es JavaFX. Hay que portar la lógica de visualización a Android. El flujo DDS → Android requiere elegir entre RTI Connext DDS o Cyclone DDS (ver sección 5).

### 4.7 EMR web (React/Next.js)

No existe ninguna aplicación web para consultar datos FHIR. HAPI incluye una UI de prueba básica (`http://localhost:8099/`), pero para producción se necesita una interfaz custom.

### 4.8 Logging y manejo de errores

`FhirEmitter` usa `System.out.println` en lugar de SLF4J. No hay reintentos en caso de caída del gateway. No hay health checks.

## 5. RTI Connext DDS vs Cyclone DDS para Android

La decisión de qué librería DDS usar en la app Android afecta directamente la viabilidad del MVP.

| Criterio | RTI Connext DDS | Cyclone DDS |
|---|---|---|
| **Licencia** | Comercial (pago) | Eclipse Public License (gratis) |
| **Android SDK** | Sí (oficial, documentado) | Sí (C/C++ con JNI, menos documentado) |
| **Rendimiento** | Alto (optimizado por años) | Alto (comparable en benchmarks) |
| **QoS** | Completo (100+ perfiles configurables) | Básico (los perfiles esenciales) |
| **Comunidad** | Empresarial, soporte pagado | Open source, comunidad activa |
| **Interoperabilidad RTPS** | RTPS 2.x (estándar DDS) | RTPS 2.x (compatible) |
| **Madurez en Android** | Probado en producción industrial | Menos maduro en Android |
| **Complejidad de integración** | Media (SDK oficial con wrapper Java) | Alta (JNI + configuración manual de CMake) |
| **Interoperabilidad con Pi** | La Pi usa RTI; compatibilidad garantizada | RTPS es estándar; debería funcionar |

**Recomendación:** Si el presupuesto permite la licencia RTI, es la opción más simple y probada. Si no, Cyclone DDS es viable pero requiere más trabajo de integración JNI y pruebas de compatibilidad RTPS con las Pi (que usan RTI).

## 6. MQTT como Capa de Transporte — Análisis

### 6.1 Estado actual de MQTT en OpenICE

OpenICE ya tiene un bridge MQTT funcional (`MqttSend.java`, 489 líneas) que publica datos DDS a un broker Mosquitto:

- **Publicación:** Numeric, SampleArray, Alert → JSON → MQTT
- **Topic format:** `openice/{udi}/{metric_id}`
- **QoS:** QoS 1 (at-least-once)
- **Broker:** Mosquitto vía Docker (`mosquitto-broker/docker-compose.yml`)
- **Autenticación:** Username/password habilitada
- **Librería:** Eclipse Paho v3 (`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`)

**Lo que NO existe:**
- Subscriber/consumer en el core de OpenICE (solo publisher)
- Converter MQTT → FHIR (no hay camino de MQTT a persistencia)
- TLS en el broker (todo en texto plano)
- MQTT v5 (solo v3.1.1)
- Integración con Android

### 6.2 Por qué NO usar MQTT como reemplazo de HTTP/FHIR para el MVP

| Razón | Explicación |
|---|---|
| **No hay consumer MQTT** | El `MqttSend.java` solo publica. No existe código en OpenICE que reciba datos MQTT y los procese. Construir un converter MQTT→FHIR sería un componente nuevo completo. |
| **FHIR es el estándar healthcare** | Si el objetivo es interoperar con sistemas hospitalarios (EMR, HL7), FHIR es el formato obligatorio. MQTT es un transport genérico, no un formato de datos clínicos. |
| **FHIR provee query nativo** | HAPI permite búsquedas como `GET /Observation?patient=123&code=2708-6`. Con MQTT, tendrías que construir una base de datos propia y un API REST desde cero. |
| **Complejidad doble** | Usar MQTT como transport implica: Android → MQTT → Broker → Converter → FHIR → HAPI. El flujo directo (Android → FHIR → HAPI) es más simple y tiene menos puntos de fallo. |
| **FHIR ya está casi implementado** | `FhirEmitter.java` ya convierte datos DDS a FHIR Observations. Solo falta mapeo LOINC, credenciales y logging. Un converter MQTT→FHIR no existe y hay que construirlo desde cero. |
| **Retención de datos** | MQTT con `cleanSession=true` (configuración actual) descarta mensajes cuando el consumer no está conectado. FHIR/HAPI persiste todo permanentemente. |

### 6.3 Cuándo SÍ vale la pena MQTT

MQTT es valioso para escenarios específicos que NO son el MVP actual, pero que pueden surgir como evolución:

| Escenario | Por qué MQTT es mejor | Ejemplo |
|---|---|---|
| **Dashboard real-time en múltiples tablets** | MQTT pub/sub permite broadcasting natural: un publicador, N subscriptores sin configurar cada uno | Enfermera supervisa 5 ORs desde una tablet vía MQTT |
| **Alertas push a dispositivos móviles** | MQTT QoS garantiza entrega; MQTT push en Android es nativo ( Firebase Cloud Messaging usaMQTT internamente) | Alarma de SpO2 bajo se envía a la tablet del doctor |
| **Redes lentas o inestables** | MQTT tiene menos overhead que HTTP (2 bytes de header vs cientos en HTTP); reconexión automática con session persistence | Conexión WiFi intermitente en ORs viejos |
| **Múltiples consumidores independientes** | El patrón pub/sub desacopla productor de consumidor; agregar un nuevo consumer no requiere cambiar el productor | EMR + dashboard + sistema de alertas consumen los mismos datos |
| **Supervisor como app Android ligera** | Si el Android debe ser lo más delgado posible, MQTT es más ligero que un client FHIR completo (que requiere OAuth2, TLS, parsing de resources) | Android de bajo rendimiento o con poca RAM |

### 6.4 Arquitectura con MQTT (evolución futura)

Si en el futuro se necesita real-time + persistencia, la arquitectura sería:

```
[Pi] ──DDS──→ [Android] ──MQTT──→ [Mosquitto] ──→ [Dashboard real-time]
                    │
                    └──FHIR──→ [HAPI] ──→ [EMR web]
```

- **MQTT canal izquierdo:** real-time para dashboards, alertas, monitoreo remoto
- **FHIR canal derecho:** persistencia, consultas históricas, interoperabilidad hospitalaria
- **Ambos canales son paralelos,** no dependen el uno del otro

El bridge MQTT ya existe (`MqttSend.java`). Solo falta un subscriber en el lado del servidor y un dashboard que consuma los topics.

### 6.5 Resumen: MQTT vs FHIR para MVP

| Criterio | MQTT | HTTP/FHIR | Ganador para MVP |
|---|---|---|---|
| **Implementado en OpenICE** | Solo publisher | Casi completo | FHIR |
| **Estándar healthcare** | No | Sí (FHIR R4) | FHIR |
| **Query nativo** | No (requiere DB propia) | Sí (HAPI search) | FHIR |
| **Persistencia** | No (clean session) | Sí (HAPI + PostgreSQL) | FHIR |
| **Real-time broadcasting** | Sí (pub/sub nativo) | No (request/response) | MQTT |
| **Overhead de red** | Bajo (2 bytes header) | Alto (HTTP + JSON) | MQTT |
| **Complejidad de implementación** | Alta (falta consumer + converter) | Media (falta LOINC + credenciales) | FHIR |
| **Interoperabilidad hospitalaria** | No | Sí | FHIR |

**Conclusión MQTT:** Para el MVP, **HTTP/FHIR es la opción correcta**. MQTT se agrega después como canal paralelo para real-time y alertas, sin reemplazar FHIR.

## 7. Flujo de Datos Completo

### 7.1 Cadena de producción de datos

```
1. Dispositivo médico genera dato
        │
        ▼
2. Raspberry Pi 3 recibe vía serial/TCP
   (headless adapter + driver de dispositivo)
        │
        ▼
3. AbstractDevice.numericSample() / publish()
   - presentation_time = DomainClock (reloj del sistema)
   - device_time = reloj del dispositivo (si tiene)
        │
        ▼ DDS write() (domain N, QoS: reception timestamp ordering)
        │
4. Android App recibe vía DDS
   (NumericFxList / SampleArrayFxList / AlertFxList)
        │
        ▼
5. ValidationOracle valida el dato
        │
        ▼
6. FhirEmitter.fhirObservation()
   - Resolve MRN → Patient resource
   - Resolve UDI → Device resource
   - Crear Observation con:
       code = LOINC (mapeado desde metric_types)
       value = valor numérico
       unit = UCUM (mapeado desde metric_types)
       subject = Patient/{id}
       device = Device/{id}
       effectiveDateTime = timestamp del paso 3
        │
        ▼
7. TokenProvider obtiene bearer token de Keycloak
        │
        ▼
8. POSTTransaction vía HTTPS al FHIR Gateway (8080)
        │
        ▼
9. Gateway valida con ListAccessChecker
   - Verifica que Patient esté en patient-list-example
   - Si no está → 403 Forbidden
        │
        ▼
10. Gateway reenvía a HAPI (8099)
    - HAPI almacena Observation, Patient, Device
        │
        ▼
11. React/Next.js EMR consulta HAPI
    - GET /Observation?patient={id}
    - GET /Patient
    - GET /Device
```

### 7.2 Latencia estimada por salto

| Salto | Protocolo | Latencia estimada |
|---|---|---|
| Dispositivo → Pi | Serial/TCP | <1 ms |
| Pi → Android | DDS (LAN) | ~1-5 ms |
| Android → Gateway | HTTPS | ~5-50 ms (LAN) |
| Gateway → HAPI | HTTP interno | ~1-5 ms |
| HAPI → EMR | HTTP | ~10-100 ms (depende del query) |
| **Total** | | **~20-160 ms** |

## 8. Mapa de Riesgos

| # | Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|---|
| 1 | RTI no aprueba licencia Android; Cyclone DDS incompatible con RTI en Pi | Media | Alto | Evaluar Cyclone DDS temprano; hacer pruebas de interop RTPS antes de comprometerse |
| 2 | HAPI FHIR no escala a 50 streams simultáneos | Baja | Alto | HAPI soporta cientos de conexiones; si hay problemas, escalar PostgreSQL |
| 3 | Keycloak se cae y los 50 Androids no pueden autenticarse | Media | Crítico | Implementar refresh token; si Keycloak cae, buffer local en Android y reintentar |
| 4 | Gateway rechaza Observaciones válidas (falso positivo en ListAccessChecker) | Media | Alto | Logging detallado en Gateway; prueba de integração con datos reales antes de producción |
| 5 | FHIR credentials comprometidas (ya están en source control) | Alta | Crítico | Mover a variables de entorno inmediatamente; rotar credenciales existentes |
| 6 | App Android no funciona en tablets hospitalarias (versiones Android viejas) | Media | Medio | Probar en Android 8+ (API 26); usar targetSdkVersion conservador |
| 7 | LOINC mapping incompleto o incorrecto | Media | Medio | Validar con bibliotecario de códigos clínicos; usar tabla `metric_types` como fuente |
| 8 | Red LAN entre edificios tiene latencia inesperada | Baja | Alto | Medir latencia real antes del despliegue; considerar edge computing si la latencia es alta |

## 9. Plan de Implementación

### Fase 1 — Servidor central

Levantar la infraestructura FHIR completa en el servidor central:

- Docker Compose (HAPI + Keycloak + Gateway + PostgreSQL)
- Keycloak realm config (usuarios, clientes OAuth2)
- Gateway config (ListAccessChecker, patient-list-example)
- Prueba de integración vía curl/Postman

### Fase 2 — FhirEmitter fixes

Corregir el código de emisión FHIR:

- Mapeo LOINC/UCUM en `FhirEmitter.java`
- Credenciales via variables de entorno (no en `ice.properties`)
- `FhirEMRImpl` device-patient association (implementar los NO-OP)
- Logging SLF4J (reemplazar `System.out.println`)
- Manejo de errores y reintentos

### Fase 3 — App Android

Portar el Supervisor de JavaFX a Android:

- Elegir DDS: RTI Connext o Cyclone DDS
- Port de `NumericFxList`, `SampleArrayFxList`, `AlertFxList` a Android
- Integración FHIR client en Android
- TokenProvider en Android
- UI de visualización (dashboards, alertas)

### Fase 4 — EMR web

Construir la interfaz de consulta:

- React/Next.js app
- Conexión a HAPI vía FHIR API
- Consulta de pacientes, observaciones, dispositivos
- Dashboard básico con gráficas

### Fase 5 — Integración y pruebas

Validar el flujo completo:

- End-to-end: Pi → Android → HAPI → EMR
- Pruebas con 5+ dispositivos simultáneos
- Pruebas de caída y reconexión (Keycloak, Gateway, HAPI)
- Pruebas de rendimiento con 50 streams

## 10. Conclusión

> El sistema OpenICE tiene la capa de dispositivos (Pi + drivers) y la capa DDS completamente funcional para producción. La capa FHIR está escrita pero requiere correcciones (mapeo LOINC, credenciales, logging). Las capas faltantes son: infraestructura Docker (HAPI + Keycloak + Gateway), app Android (port del Supervisor), y EMR web (React/Next.js). La decisión de DDS en Android (RTI vs Cyclone) es bloqueante para la Fase 3. MQTT no es necesario para el MVP — ya existe el publisher pero falta el consumer y el converter a FHIR; se recomienda agregar MQTT después como canal paralelo para real-time y alertas. El flujo completo (dispositivo → DDS → Android → FHIR → HAPI → EMR) es viable con las correcciones documentadas en este reporte.

---

*Reporte generado a partir del análisis técnico de código fuente, documentación existente y arquitectura del sistema OpenICE.*
