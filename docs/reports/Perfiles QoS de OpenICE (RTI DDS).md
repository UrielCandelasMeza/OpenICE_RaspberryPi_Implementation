# Reporte Técnico: Perfiles QoS utilizados por OpenICE (RTI Connext DDS)

**Fecha:** 11 de agosto de 2026

**Sistema:** OpenICE / MD PnP (`1.5.0-SNAPSHOT`), transporte DDS sobre RTI Connext

**Alcance:** Identificación de los perfiles QoS definidos y de cuáles se aplican realmente a cada tópico en el código

---

## 1. Resumen ejecutivo

OpenICE no define QoS por tópico de forma ad-hoc: concentra toda la configuración en una **única librería QoS llamada `ice_library`**, con **9 perfiles**. El código Java referencia 8 de ellos a través de la interfaz `QosProfiles` (`state`, `device_identity`, `numeric_data`, `waveform_data`, `heartbeat`, `timesync`, `default_profile`), más el perfil `himss` (usado por literal `"himss"`). El perfil `observed_data` existe en el XML pero **no se referencia en ningún lugar del código Java**.

Los tres perfiles que dominan el tráfico real del sistema son:

| Perfil | Uso |
|---|---|
| `state` | Alarms, objetivos, estado de conexión, InfusionStatus |
| `numeric_data` | Datos numéricos (`Numeric`) |
| `waveform_data` | Formas de onda (`SampleArray`) |

## 2. Dónde se define el QoS

El archivo canónico es:

- `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml`

Existe una **copia idéntica** (verificada con `diff`) en el classpath:

- `data-types/x73-idl-rti-dds/src/main/resources/META-INF/ice_library.xml` — es la que realmente se carga como `/META-INF/ice_library.xml`.

> **Ojo:** si se edita un archivo y no el otro, la configuración efectiva no cambia. Ambos deben mantenerse sincronizados.

También existe `data-types/x73-idl/src/main/idl/ice/samples/USER_QOS_PROFILES.xml`, pero es un ejemplo generado por RTI (perfil `ice_Profile` basado en `BuiltinQosLib::Generic.StrictReliable`) que **no usa el sistema**.

## 3. Cómo se carga la librería

Clase `IceQos.loadAndSetIceQos()` (`data-types/x73-idl-rti-dds/src/main/java/org/mdpnp/rtiapi/qos/IceQos.java`), instanciada como bean `iceQos` en `RtConfig.xml`:

1. Si existe un `USER_QOS_PROFILES.xml` en el cwd que defina un `qos_library` con nombre `ice_library`, se usa ese (status `USER`). _No es el caso actual en el repo._
2. Si no, carga `/META-INF/ice_library.xml` del classpath (status `SYSTEM`). La ruta puede forzarse con `-Dmdpnp.dds.qos=file:///...`.
3. Además fija `qos.resource_limits.max_objects_per_thread = 8192` en el `DomainParticipantFactory`.

## 4. Perfiles de la librería `ice_library`

### 4.1 `default_profile` (perfil por defecto, `is_default_qos="true"`)

Es la base de todos los demás perfiles. Define tres bloques:

**Participante** (`participant_qos`):

| Parámetro | Valor |
|---|---|
| Discovery | Promiscuo: `accept_unknown_peers=true` |
| `multicast_receive_addresses` | `udpv4://239.255.0.1` |
| `initial_peers` | `udpv4://239.255.0.1` |
| Transporte | **Solo UDPv4** (`mask=DDS_TRANSPORTBUILTIN_UDPv4`); shared memory deshabilitado |
| Anuncios de dominio | `ignore_default_domain_announcements=true`, periodo ~infinito |
| Liveliness del participante | lease 10 s / assert 3 s / detección pérdida 5 s |
| Heartbeat writers de discovery (pub/sub) | 3 s; fast y late-joiner 500 ms |
| Publish mode writers de discovery | `DDS_ASYNCHRONOUS_PUBLISH_MODE_QOS` |
| `message_size_max` (UDPv4) | 65507 |
| TypeCode/TypeObject max serializado | 8192 |

**Reader por defecto** (`datareader_qos`):

| Política | Valor |
|---|---|
| Reliability | `DDS_BEST_EFFORT_RELIABILITY_QOS` |
| Liveliness | `DDS_AUTOMATIC_LIVELINESS_QOS`, lease ~infinito |
| Durability | `DDS_VOLATILE_DURABILITY_QOS` (comunicación directa) |
| History | `DDS_KEEP_LAST_HISTORY_QOS`, depth **500** |
| Resource limits | `max_samples/max_instances/max_samples_per_instance = -1` |
| Fragments por muestra | 5000 |

**Writer por defecto** (`datawriter_qos`):

| Política | Valor |
|---|---|
| Reliability | `DDS_RELIABLE_RELIABILITY_QOS`, max blocking 2 s, acks protocolo |
| Liveliness | `DDS_AUTOMATIC_LIVELINESS_QOS`, lease **1 s** |
| Durability | `DDS_TRANSIENT_LOCAL_DURABILITY_QOS` |
| Destination order | `DDS_BY_RECEPTION_TIMESTAMP_DESTINATIONORDER_QOS` |
| Publish mode | `DDS_ASYNCHRONOUS_PUBLISH_MODE_QOS` |
| History | `DDS_KEEP_ALL_HISTORY_QOS` |
| Resource limits | todos `-1` |

### 4.2 Perfiles especializados

| Perfil | Base | Cambios respecto a la base | Uso real en el código |
|---|---|---|---|
| `state` | `default_profile` | Reader: RELIABLE (block 2 s), liveliness automática lease **5 s**, TRANSIENT_LOCAL, KEEP_LAST depth 1. Writer: KEEP_LAST depth 1 | Alarms (`AlarmLimit`, `Alert`), objetivos (infusión, BP, oximetría, ventilador, key-value), estado de conexión, `InfusionStatus` |
| `device_identity` | `state` | Reader con liveliness lease ~infinita (la identidad casi no cambia) | `DeviceIdentity`, `Patient` |
| `observed_data` | `default_profile` | Reader: RELIABLE, liveliness 5 s, TRANSIENT_LOCAL, KEEP_ALL. Writer: lifespan **15 s** | **Definido pero sin uso** en el código Java |
| `numeric_data` | `observed_data` | Sin cambios | `Numeric` |
| `waveform_data` | `observed_data` | Writer: batching deshabilitado | `SampleArray` |
| `heartbeat` | `default_profile` | Liveliness `MANUAL_BY_TOPIC` (lease reader 5 s / writer 3 s), VOLATILE, publish mode **SYNCHRONOUS**, KEEP_LAST depth 1 | `HeartBeat` (TimeManager) |
| `timesync` | `default_profile` | KEEP_LAST depth 1 (reader y writer) | `TimeSync` (TimeManager) |
| `himss` | `default_profile` | Reader: RELIABLE, **SHARED ownership**, TRANSIENT_LOCAL, KEEP_LAST depth 5. Writer: SHARED ownership | `PatientAssessment`, `MetricDataQuality` (HimssEmitter/HimssIntaker, literal `"himss"`) |

### 4.3 Constantes expuestas en Java

`data-types/x73-idl-rti-dds/src/main/java/org/mdpnp/rtiapi/data/QosProfiles.java`:

```java
String ice_library = "ice_library";
String default_profile = "default_profile";
String state = "state";
String device_identity = "device_identity";
String numeric_data = "numeric_data";
String waveform_data = "waveform_data";
String heartbeat = "heartbeat";
String timesync = "timesync";
```

> Nota: `observed_data` y `himss` **no** están en `QosProfiles.java`. `himss` se usa con el literal `"himss"` (HimssEmitter/HimssIntaker) y `observed_data` no se usa.

## 5. Mapeo tópico → perfil (evidencia en el código)

Fuente principal: `AbstractDevice.java` (creación de DataWriters, líneas 779-830):

| Tópico | Perfil | Referencia |
|---|---|---|
| `DeviceIdentity` | `device_identity` | `AbstractDevice.java:779` |
| `Numeric` | `numeric_data` | `AbstractDevice.java:787` |
| `SampleArray` | `waveform_data` | `AbstractDevice.java:795` |
| `AlarmLimit` | `state` | `AbstractDevice.java:803` |
| `GlobalAlarmLimitObjective` | `state` | `AbstractDevice.java:810, 815` |
| `Alert` (patient / technical) | `state` | `AbstractDevice.java:825, 829` |
| Objetivos (infusión, BP, flow rate, oximetría, vent mode, key-value, patient assessment, pause/resume) | `state` | `*DataWriterFactory.java` (demo-devices) |
| `InfusionStatus` | `state` | `InfusionStatusInstanceModelFactory.java:25` |
| `HeartBeat` | `heartbeat` | `TimeManager.java:132, 142` |
| `TimeSync` | `timesync` | `TimeManager.java:148, 150` |
| `Patient` | `device_identity` | `PublishPatients.java:63` |
| `PatientAssessment`, `MetricDataQuality` | `himss` | `HimssEmitter.java:142`, `HimssIntaker.java:63` |

Readers (lado supervisor): `DeviceDataMonitor.java:57-61` usa `device_identity`, `state`, `numeric_data`, `waveform_data` para los tópicos de identidad, conexión, numéricos y formas de onda.

## 6. Implicaciones prácticas

1. **Los tópicos de estado son RELIABLE + TRANSIENT_LOCAL** (`state`, `device_identity`), por lo que un lector que se une tarde recibe el estado actual del dispositivo.
2. **Los tópicos de datos observados son RELIABLE + TRANSIENT_LOCAL con lifespan 15 s** (`numeric_data`, `waveform_data`), con KEEP_ALL en el reader; si el reader se satura, el writer conserva todo hasta el lifespan.
3. **`default_profile` (reader) es BEST_EFFORT**: cualquier reader que no elija un perfil específico puede perder muestras sin retransmisión.
4. **El discovery depende 100 % de multicast** `udpv4://239.255.0.1` (sin `initial_peers` unicast de respaldo y con shared memory deshabilitado): en redes sin IGMP querier activo puede fallar el descubrimiento.
5. **`heartbeat` usa liveliness MANUAL_BY_TOPIC y publish mode síncrono**: escribe heartbeats periódicos a propósito (TimeManager), distinto al resto de tópicos.

## 7. Archivos de referencia

| Archivo | Rol |
|---|---|
| `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` | Definición canónica de la librería `ice_library` |
| `data-types/x73-idl-rti-dds/src/main/resources/META-INF/ice_library.xml` | Copia en classpath que se carga realmente |
| `data-types/x73-idl-rti-dds/src/main/java/org/mdpnp/rtiapi/qos/IceQos.java` | Lógica de carga (`loadAndSetIceQos`) |
| `data-types/x73-idl-rti-dds/src/main/java/org/mdpnp/rtiapi/data/QosProfiles.java` | Constantes de perfiles usadas por el código |
| `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/AbstractDevice.java` | Creación de DataWriters por tópico |
| `data-types/x73-idl-rti-dds/src/main/java/org/mdpnp/rtiapi/data/DeviceDataMonitor.java` | Creación de DataReaders por tópico |
