# Reporte Técnico: Evaluación de integración de ZettaScale / Zenoh en OpenICE

**Fecha:** 9 de septiembre de 2026  
**Proyecto:** OpenICE / MD PnP (`1.5.1-SNAPSHOT`)  
**Sistema:** Middleware DDS (RTI Connext) ↔ Zenoh  
**Alcance:** Evaluar si la incorporación de Zenoh/ZettaScale aporta valor al despliegue actual de OpenICE (quirófanos, múltiples edificios, Raspberry Pi headless)

---

## 1. Contexto

### 1.1 Comunicación actual del sistema

OpenICE usa **DDS sobre RTI Connext** como único bus de datos entre procesos. La configuración canónica está en `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` (copia ejecutada desde `data-types/x73-idl-rti-dds/src/main/resources/META-INF/ice_library.xml`) y los beans DDS en `interop-lab/demo-devices/src/main/resources/RtConfig.xml`.

Tipos y topics definidos en `data-types/x73-idl/src/main/idl/ice/ice.idl`:

| Constante | Valor del topic |
|---|---|
| `HeartBeatTopic` | `HeartBeat` |
| `TimeSyncTopic` | `TimeSync` |
| `DeviceIdentityTopic` | `DeviceIdentity` |
| `DeviceConnectivityTopic` | `DeviceConnectivity` |
| `MDSConnectivityTopic` | `MDSConnectivity` |
| `MDSConnectivityObjectiveTopic` | `MDSConnectivityObjective` |
| `NumericTopic` | `Numeric` |
| `SampleArrayTopic` | `SampleArray` |
| `InfusionObjectiveTopic` | `InfusionObjective` |
| `InfusionProgramTopic` | `InfusionProgram` |
| `VentModeObjectiveTopic` | `VentModeObjective` |
| `KeyValueObjectiveTopic` | `KeyValueObjective` |
| … | Alarms (`PatientAlert`, `TechnicalAlert`), etc. |

Descubrimiento DDS: **multicast UDPv4** (`udpv4://239.255.0.1` como `multicast_receive_addresses` e `initial_peers`, `ice_library.xml:47-56`), `accept_unknown_peers=true`. No cruza routers/VLANs: cada LAN es un dominio DDS aislado.

### 1.2 QoS de los topics de datos (relevante para integridad)

Perfil `default_profile` del `ice_library.xml`:

- `datawriter_qos`: **durabilidad TRANSIENT_LOCAL** (`:331-337`), fiabilidad RELIABLE, historial KEEP_ALL.
- Perfil `state` (alarms, objetivos, estado): durabilidad TRANSIENT_LOCAL lectura/escritura, historial KEEP_LAST depth 1 (`:463-517`).
- Perfil `observed_data` (Numeric, SampleArray): durabilidad TRANSIENT_LOCAL, con **lifespan del writer de 15 s** para los datos numéricos (`:588-596`). El comentario del propio XML señala: *"A permanent store could make it reasonable to store more"* (`:590`).

Consecuencia: **el último valor y el historial solo existen mientras el escritor está vivo y, para numerics, solo ~15 s**. Un suscriptor que llega tras un reinicio o un corte no recupera nada más allá de esa ventana.

### 1.3 Precedente de puente en el proyecto: MQTT Send

`org.mdpnp.apps.testapp.mqtt.MqttSend` ya implementa un puente DDS→JSON: se suscribe a `Numeric`, `SampleArray`, `PatientAlert` y `TechnicalAlert` y publica en un broker Mosquitto con claves `openice/{udi}/{metric_id}`. El broker del repo ya está configurado con **persistencia activa** (`mosquitto-broker/config/mosquitto.conf:6-8`: `persistence true`, `mosquitto.db`), límite de 256M y `restart: always` (`docker-compose.yml`).

### 1.4 Despliegue objetivo

- ~50 Raspberry Pi 3 ejecutando `headless-adapter` (sin JavaFX), una por dispositivo.
- Varios edificios en un hospital; **una LAN por edificio**.
- Cada LAN agrupa N supervisores/adaptadores; todos emiten al **mismo destino central por internet** (pipeline MQTT/FHIR actual).
- Los dominios DDS se reutilizan por edificio (p. ej. domains 0-9 en el edificio A y también 0-9 en el edificio B) sin conflicto, porque cada LAN es un dominio aislado.

---

## 2. Problema

Dos posibles motivaciones para incorporar Zenoh/ZettaScale:

1. **Interconectividad multi-edificio** — conectar los dominios DDS de distintos edificios a través de Zenoh (rota el límite multicast). Se evalúa si es un requisito real.
2. **Integridad / consulta de datos en el borde** — en zonas de difícil acceso y conectividad intermitente, no hay ningún punto de retención **durable** de los datos en el borde: la durabilidad DDS es TRANSIENT_LOCAL (depende del escritor vivo) y `observed_data` expira a los 15 s. La pregunta es si Zenoh (con sus *storages* y búsqueda/query por clave) es el mecanismo adecuado para mantener los datos "lo más íntegros posible" y consultables cuando la conectividad con el destino central falla.

---

## 3. Hipótesis

- **H1.** La interconectividad multi-edificio **no es un requisito real**: la topología por edificio (LAN aislada + agregación central por internet + reutilización de dominios) ya lo resuelve sin Zenoh.
- **H2.** El único gap real es la **integridad/consulta de datos en el borde** bajo conectividad difícil, y debe evaluarse si Zenoh Storages lo cubre mejor que la infraestructura ya existente (persistencia de Mosquitto + QoS 1 + sesiones persistentes).
- **H3.** Un puente `zenoh-bridge-dds` es viable técnicamente **solo si** Cyclone DDS (que lo sustenta) interoperaba sobre RTPS con RTI Connext en nuestro stack — riesgo a validar empíricamente, no asumible por documentación.

---

## 4. Proceso

### 4.1 Qué es Zenoh / ZettaScale

Zenoh (Eclipse, liderado por ZettaScale) es un middleware nueva generación: pub/sub + almacenamiento (data-at-rest) + consulta (data-in-query) + computación, con transporte tolerante a pérdida, ligero y embebible. Licencia **EPL-2.0 o Apache-2.0** (doble licencia, verificada en `projects.eclipse.org` y el `LICENSE` del repo `eclipse-zenoh/zenoh`). El plugin `zenoh-plugin-dds` y el binario `zenoh-bridge-dds` usan la misma doble licencia. Cyclone DDS (el DDS que usa el bridge) es EPL-2.0.

### 4.2 Cómo mapea el puente DDS→Zenoh

Verificado en la documentación de `zenoh-plugin-dds`:

- Un topic DDS `A` sin partición → recurso Zenoh `/A`. Con `scope /S` → `/S/A`. Con partición `P` → `/P/A` o `/S/P/A`.
- **OpenICE no usa particiones DDS** → las claves Zenoh resultantes serían idénticas a los nombres de topic: `/Numeric`, `/SampleArray`, `/HeartBeat`, `/DeviceIdentity`, `/PatientAlert`, etc.
- El puente es **agnóstico de tipo**: reenvía el payload crudo *as-is*. Para OpenICE eso significa bytes **CDR (DDSI-RTPS §10, CDR §9.3)**, no JSON. Cualquier consumidor Zenoh debería decodificar CDR con las structs de `ice.idl` (el proyecto ya genera código RTI con typecodes para ello — `x73-idl-rti-dds`).
- El puente **depende de Cyclone DDS**. Descubre los endpoints DDS por el protocolo de descubrimiento estándar (multicast UDP) y crea readers/writers espejo con las mismas QoS.
- **El descubrimiento DDS no se enruta a través de Zenoh**: si dos dominios DDS se conectan mediante dos bridges, el grafo DDS de un lado no se propaga al otro; solo se reenvía dato si hay writers/readers declarados en cada dominio.
- Docker: el bridge requiere `--net host` (el multicast UDP no cruza el límite host↔contenedor).

### 4.3 Opciones de integración evaluadas

| Opción | Descripción | Cambio de código | Payload | Riesgo clave |
|---|---|---|---|---|
| **A. `zenoh-bridge-dds`** | Proceso externo al lado de cada dominio DDS (o un solo dominio central) | Ninguno | CDR crudo | Interop RTPS RTI↔Cyclone; consumo requiere decodificar CDR |
| **B. Publisher nativo "ZenohSend"** | Espejo de `MqttSend` en Java: suscribe DDS, serializa a JSON, publica en claves Zenoh | Nuevo módulo (p. ej. en `demo-apps`) con binding `zenoh-java` (JNI) | JSON | Carga de libs nativas ARM en RPi; aún un bus más junto a DDS y MQTT |
| **C. Reemplazo de DDS por Zenoh** | Sustituir RTI por Zenoh nativo en toda la pila | Masivo (todo `x73-idl-rti-dds`, `RtConfig.xml`, drivers, apps) | — | Rompe el ecosistema existente; **descartada** |
| **D. Soporte comercial ZettaScale (routers)** | Infraestructura gestionada ZettaScale con routers Zenoh entre sitios | Ninguno (operativo) | CDR o como se configure | Costo/lock-in comercial; innecesaria si H1 es cierta |

### 4.4 Comparativa para el gap de integridad en el borde

Criterio: ¿qué cubre el hueco de "datos con conectividad intermitente / difícil acceso" sin perder integridad?

| Mecanismo | Durabilidad ante corte→reconexión | Consulta/query fuera de línea | Sobrecarga RPi Pi3 | Estado actual en el repo |
|---|---|---|---|---|
| **Mosquitto persistente + QoS 1 + sesión persistente** | ✅ El broker local retiene y reenvía mensajes al suscriptor central al reconectar (config ya persistente) | Solo suscripción, sin query del keyspace histórico | Mínima (contenedor 256M, ya desplegado) | ✅ Desplegado (`mosquitto-broker/`) |
| **Zenoh Storage (RocksDB)** | ✅ Estado persistente por clave que sobrevive reinicios; *query* pull del keyspace | ✅ Query nativa de Zenoh (`z_get`) | Depende de libs nativas (RocksDB) en ARM32 | ❌ No presente |
| **Store local simple (SQLite/HSQLDB)** | ✅ Persistencia local | Por SQL, no por keyspace | Mínima | ⚠️ HSQLDB ya se usa (EMR), no como buffer de telemetría |

El gap que Zenoh cubriría de forma única es la **consulta pull del keyspace y el estado durable por clave desacoplado de los writers DDS** (TRANSIENT_LOCAL solo mantiene el estado mientras el editor vive y los numerics 15 s). Mosquitto resuelve el "no perder datos mientras el destino central está caído", pero no el "poder preguntar cuál es el último estado conocido de un dispositivo que se reinició".

---

## 5. Resultado

### 5.1 Los dominios por edificio no necesitan Zenoh

La reutilización de domains por edificio con LAN aislada y agregación central por internet **no requiere** un plano de datos inter-edificio. La topología es *star* hacia el destino central, no *mesh* entre edificios. Por tanto **la opción A con múltiples bridges entre sitios y la opción D (routers gestionados) no tienen caso de uso actual**: H1 confirmada.

### 5.2 Viabilidad del puente (opción A) — condicionada

- Mapeo directo: `/Numeric`, `/SampleArray`, `/HeartBeat`, `/DeviceIdentity`, `/PatientAlert`, … (sin particiones).
- Costo cero de integración en el código, pero el **payload es CDR crudo**: cualquier consumidor (web, script, otro servicio) debería decodificar con `ice.idl`.
- El eslabón crítico es **Cyclone DDS ↔ RTI Connext**: ambos implementan RTPS, pero el intercambio de typecodes y el emparejamiento de QoS con los perfiles `ice_library` (RELIABLE/TTRANSIENT_LOCAL, historiales) deben validarse empíricamente. No es un supuesto seguro por documentación.
- El descubrimiento DDS no se propaga por Zenoh: para datos entre dominios haría falta declarar readers/writers en ambos lados, lo que invalida la gracia de "puente transparente" en multi-sitio (aunque, por 5.1, ese caso no existe hoy).

### 5.3 Integridad en el borde (opción B o storage) — el único caso defendible

El único gap objetivo es la **retención y consulta durable en el borde ante conectividad difícil/intermitente**, porque la durabilidad DDS actual es TRANSIENT_LOCAL y `observed_data` expira a los 15 s. Sin embargo:

- **Ya hay una vía sin Zenoh**: Mosquitto está configurado con persistencia (`mosquitto.conf:6-8`); con QoS 1 + sesión persistente el broker local retiene mensajes durante el corte y los entrega al reconectar al destino central.
- **Lo que Zenoh añadiría exclusivamente**: estado estable **por clave** (sobrevive al reinicio del dispositivo) y **query pull** del keyspace — es decir, "último valor conocido de este dispositivo" consultable aunque el editor haya muerto-reiniciado.
- En un Pi 3 la opción B implica agregar bindings nativos (`zenoh-java` JNI / `zenoh-pico`) y, si se usa storage, RocksDB en ARM32: coste real de empaquetado y recursos.

### 5.4 Veredicto por escenario

| Escenario | ¿Zenoh aporta? | Veredicto |
|---|---|---|
| Interconectividad entre edificios (¿mesh?) | No — topología star resuelta por LAN + central | ❌ No implementar |
| Puente DDS↔Zenoh transparente para consumidores externos | ⚠️ Solo si se valida interop RTI↔Cyclone y se acepta decodificar CDR | Diferido hasta PoC |
| Integridad/consulta durable en el borde | ✅ Único caso real — pero compite con Mosquitto persistente | ⚠️ Decidir solo contra el requisito concreto |
| Reemplazar DDS por Zenoh | No — rompe el ecosistema | ❌ Descartada |

---

## 6. Conclusión

**Zenoh/ZettaScale no se justifica hoy como plano de datos multi-edificio**: la arquitectura real (una LAN por edificio + agregación central por internet + reutilización de dominios DDS) ya resuelve la interconectividad sin necesidad de enrutar entre dominios; y la vía de puente (`zenoh-bridge-dds`) arrastra un riesgo de interoperabilidad RTPS RTI↔Cyclone que solo una prueba empírica podría descartar, además de exponer payloads CDR crudos que ningún consumidor del proyecto consume hoy.

El único caso de uso defendible es la **integridad y consulta durable de datos en el borde** (zonas de difícil acceso y conectividad intermitente), donde la durabilidad TRANSIENT_LOCAL + lifespan 15 s de los perfiles DDS actuales deja un hueco real. Pero ese hueco **ya está parcialmente cubierto** por la persistencia del broker Mosquitto (QoS 1 + sesión persistente); Zenoh solo lo superaría si el requisito exigiera además *query pull del keyspace* y estado por clave independiente del ciclo de vida del dispositivo. Mientras ese requisito no esté confirmado explícitamente, incorporar Zenoh añade un tercer bus (DDS + MQTT + Zenoh), dependencias nativas ARM y una nueva superficie de operación → **overengineering**.

---

## 7. Recomendaciones

1. **No incorporar Zenoh en el estado actual.** Documentar el tema como requerimiento futuro con umbrales de activación:
   - Multi-sitio: solo si algún día se requiere **interoperar datos entre edificios en tiempo real** (no solo agregar al central). Mientras la topología sea star-hacia-central, no aplicar.
   - Borde/integridad: solo si se formaliza el requisito de "último estado conocido/consulta del keyspace fuera de línea"; mientras baste "no perder mensajes durante el corte", usar **Mosquitto con QoS 1 + sesión persistente** (ya desplegado y persistente).
2. **Antes de cualquier adopción, un PoC acotado y desechable** (máx. un día, sin tocar código del repo):
   - Ejecutar un `headless-adapter` o simulador publicando `Numeric` en dominio 0 (RTI de verdad).
   - Levantar `zenoh-bridge-dds -d 0` y suscribirse a la clave `/Numeric` con `zenoh-python`.
   - Verificar dos cosas: (a) que Cyclone DDS recibe datos de RTI Connext con las QoS `ice_library` (sin pérdidas ni topics fantasma), y (b) que el payload CDR se decodifica correctamente con `ice.idl`.
   - Resultado: si falla, descartar la opción A y evaluar solo la opción B (publisher JSON propio) cuando — y solo cuando — surja un consumidor real de datos Zenoh.
3. **Si se confirma el requisito de query durable en el borde**, comparar en un segundo documento Zenoh Storage (RocksDB) contra SQLite local + `MqttSend` ampliado, con mediciones de CPU/RAM en Raspberry Pi 3 (ARM32), antes de decidir.
4. **No tocar la persistencia del broker existente durante la evaluación** (es la red de seguridad actual contra pérdida de datos en cortes).

---

*Reporte generado a partir de la documentación oficial de Eclipse Zenoh y `zenoh-plugin-dds`/`zenoh-bridge-dds`, la configuración QoS canónica del repo (`ice_library.xml`), el mapeo de topics en `ice.idl`, el puente MQTT existente (`MqttSend.java`, `mosquitto-broker/`) y la arquitectura de despliegue actual (LAN por edificio, dominios reutilizados, agregación central por internet).*