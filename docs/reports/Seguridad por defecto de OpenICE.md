# Reporte Técnico: Seguridad por defecto de OpenICE (MD PnP)

**Fecha:** 11 de agosto de 2026

**Sistema:** OpenICE / MD PnP (`1.5.0-SNAPSHOT`), transporte DDS sobre RTI Connext

**Alcance:** Análisis de los mecanismos de seguridad habilitados por defecto en el núcleo DDS y en las capas de integración (FHIR/HL7, MQTT, base de datos)

---

## 1. Resumen ejecutivo

OpenICE no tiene seguridad en el transporte principal de datos (DDS). Todo el tráfico clínico viaja **en claro, sin autenticación y sin control de acceso** en la red local. La única seguridad real se concentra en las capas de integración:

- **FHIR/HL7 Exporter**: OAuth2 (`grant_type=password` + refresh) contra Keycloak y autorización por lista de pacientes en la gateway FHIR.
- **Broker MQTT**: autenticación por usuario/contraseña (`allow_anonymous false`).
- **Base de datos**: autenticación por usuario/contraseña.

Además, tanto FHIR como MQTT operan por defecto sobre **HTTP/MQTT en texto plano (sin TLS)**.

## 2. Capa DDS — SIN seguridad (núcleo del sistema)

### 2.1 Estado actual

La configuración QoS de DDS (`data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` y su copia en `data-types/x73-idl-rti-dds/src/main/resources/META-INF/ice_library.xml`) **no contiene ninguna directiva del plugin de seguridad de RTI** (`dds.sec.auth.*`, `dds.sec.access.*`). Consecuencias:

| Aspecto | Estado |
|---|---|
| Autenticación de participantes | **Ninguna** |
| Cifrado de datos / payload | **Ninguno** (datos en claro) |
| Control de acceso a tópicos | **Ninguno** |
| Descubrimiento | **Promiscuo** (`accept_unknown_peers=true`), multicast `udpv4://239.255.0.1` |

Cualquier host en la LAN puede unirse al dominio DDS (0 por defecto), suscribirse y publicar en cualquier tópico (`DeviceIdentity`, `Numeric`, `SampleArray`, `Alert`, objetivos, etc.) — o **envenenar** el dominio con datos falsos.

### 2.2 Evidencia

- `ice_library.xml` (ambas copias): sin elementos `dds.sec`. Las etiquetas `<sec>` que aparecen son duraciones (segundos) de QoS, no seguridad.
- `DomainParticipantFactoryFactory.java`: crea el participante con el factory por defecto, sin tocar la política de seguridad.
- Variable `SEC_ARTIFACT_DIR`: se sigue definiendo en los bloques `test`/`run` de `interop-lab/demo-apps/build.gradle` apuntando a `data-types/x73-idl-rti-dds/src/main/resources/META-INF`, pero ese directorio **solo contiene `ice_library.xml`** (sin certificados ni documentos de gobierno/permisos). Es un residuo de la infraestructura de seguridad.
- La librería nativa `libnddssecurity.so` se distribuye en `interop-lab/demo-apps/native/libs/linux/`, pero **nunca se configura ni se carga** el plugin.

### 2.3 Trabajo de DDS Security descartado (nunca integrado a `main`)

En el historial de git existe el commit **`f7a5fe23`** ("Issue #40 – merge in current trunk and all build file changes for secure DDS", enero 2021) que implementó DDS Security completo:

- Certificados: `root-ca-cert.pem`, `a-cert.pem`, `a-key.pem`, `b-cert.pem`, `b-key.pem`.
- Documento de gobierno: `GovernanceEncryptPayload.p7s` (firmado, **cifrado de payload**).
- Permisos: `PermissionsA.p7s`, `PermissionsB.p7s` (firmados, por participante).
- Configuración en `ice_library.xml` vía propiedades `dds.sec.auth.identity_ca`, `dds.sec.auth.identity_certificate`, `dds.sec.auth.private_key`, `dds.sec.access.permissions_ca`, `dds.sec.access.governance`, `dds.sec.access.permissions`, todas referenciando `$(SEC_ARTIFACT_DIR)/cert/...` y `$(SEC_ARTIFACT_DIR)/xml/...`.

**Verificación:** `git merge-base --is-ancestor f7a5fe23 main` → **NO**. El trabajo nunca se fusionó a `main`; solo existe en el object store de git (no alcanzable desde `main` ni desde `origin/raspberry/*`). Los artefactos (`META-INF/cert/`, `META-INF/xml/`) no existen en el árbol actual.

## 3. Capa FHIR/HL7 Exporter — OAuth2 (Keycloak) + autorización por lista

La única autenticación real del sistema está en el emisor HL7 FHIR R4 (`org.mdpnp.apps.testapp.hl7`).

### 3.1 Obtención de tokens (`TokenProvider.java`)

- **Flujo inicial** (`fetchTokenPassword`, TokenProvider.java:143): `POST` urlencoded al token endpoint de Keycloak con `grant_type=password`, `username`, `password`, `client_id`. Devuelve `access_token` + `refresh_token`.
- **Renovación** (`fetchTokenRefresh`, TokenProvider.java:212): `POST` con `grant_type=refresh_token`. Si el servidor responde 400 con `error=invalid_grant`, se invalida el refresh y se vuelve a pedir con credenciales.
- **Configuración** (`ice.properties` de `demo-apps`): `mdpnp.fhir.token.url` (Keycloak, `http://localhost:9080`), `mdpnp.fhir.token.user/password/clientId`, `mdpnp.fhir.list.auth`.
- **Caché persistente**: los tokens se guardan en Java `Preferences` (`~/.java/.userPrefs`) y sobreviven al cierre de la app.

### 3.2 Verificación de expiración (`JwtService.java`)

- `verifyExpired()` (JwtService.java:21) decodifica el **payload** del JWT en base64url y compara la reclamación `exp`.
- **No verifica la firma** del token (no valida integridad ni emisor). Solo controla la expiración para decidir si renovar.

### 3.3 Envío autenticado a la gateway

- `FhirEmitter.java:84-95`: adjunta `Authorization: Bearer <token>` vía `BearerTokenAuthInterceptor` (hapi-fhir). Si no hay token válido, envía **sin header** (y loguea un warning).
- Destino: gateway FHIR `http://localhost:8080` (HTTP plano).
- **Autorización por lista**: la gateway usa `ListAccessChecker` — rechaza con **403** toda Observation cuyo `subject` no exista en la lista autorizada (`patient-list-example` / en la config activa `lista-dr-gomez`). `HL7Emitter.addToAuthorizedList()` agrega pacientes nuevos a la lista vía el backend.
- El **backend HAPI (8099) no tiene autenticación**; el gateway es quien aplica el control.
- La app EMR (`FhirEMRImpl`, `org.mdpnp.apps.testapp.patient`) **no usa token**: cliente FHIR sin autenticar.

### 3.4 Riesgos de esta capa

- **Sin TLS**: el token endpoint, la gateway y el backend son `http://` por defecto. Las credenciales (`dr.gomez`/`demo1234`) y los tokens viajan en claro y están **commiteados en `ice.properties`**.
- El `JwtService` solo chequea expiración; la confianza queda delegada a que el endpoint y la gateway validen el token.
- `grant_type=password` con credenciales en texto plano (depende de TLS para no exponerlas).

## 4. Capa MQTT (Mosquitto broker)

Configuración en `mosquitto-broker/config/mosquitto.conf`:

```conf
allow_anonymous false
password_file /mosquitto/config/pwfile
listener 1883
listener 9001
protocol websockets
```

- **Autenticación**: `allow_anonymous false` + `password_file` con hashes `$7$...` (PBKDF2 de Mosquitto) para los usuarios `user1`/`user2` (pwfile). El cliente `MqttSend` pide usuario/contraseña en la UI y los aplica con `MqttConnectOptions.setUserName/setPassword` (MqttSend.java:185-188).
- **Sin TLS**: listeners 1883 (MQTT) y 9001 (websockets) en texto plano; las credenciales y los datos viajan en claro.
- Tópicos publicados: `openice/{udi}/{metric_id}` (suscribirse a `openice/#`).

## 5. Capa de base de datos

- **PostgreSQL/TimescaleDB** en `localhost:5432`, usuario `openice`, base `openice_local`, contraseña por defecto `openice` (HikariCP). Autenticación por contraseña, sin TLS (local).
- En `HeadlessMain` la config se sobreescribe por CLI (`-dbhost/-dbport/-dbname/-dbuser/-dbpassword`) o por propiedades `postgresql.*`.

## 6. Conclusión

1. El **núcleo DDS no tiene ninguna protección**: datos clínicos en claro, sin autenticación ni autorización, con descubrimiento promiscuo en la LAN.
2. La **infraestructura para asegurar DDS ya fue diseñada** (Issue #40, commit `f7a5fe23`) con cifrado de payload y permisos por participante, pero **nunca se integró a `main`**; los artefactos ya no existen en el árbol.
3. La **única seguridad funcional** está en FHIR (OAuth2 + lista de pacientes) y en MQTT (usuario/contraseña), ambas sobre transporte plano (sin TLS) por defecto.
4. Hay **secretos commiteados** en `ice.properties` (usuario/contraseña del token de Keycloak) y residuos de config de seguridad (`SEC_ARTIFACT_DIR`, `libnddssecurity.so`) que no cumplen ninguna función actual.

## 7. Archivos de referencia

| Archivo | Rol |
|---|---|
| `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` | QoS de DDS — sin config `dds.sec` |
| `interop-lab/demo-apps/build.gradle` | Define `SEC_ARTIFACT_DIR` (residuo) |
| `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/hl7/TokenProvider.java` | OAuth2 password + refresh contra Keycloak |
| `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/JwtService.java` | Verifica expiración de JWT (sin validar firma) |
| `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/hl7/FhirEmitter.java` | Adjunta `Authorization: Bearer` |
| `interop-lab/demo-apps/src/main/resources/ice.properties` | Credenciales y URLs del token (texto plano) |
| `mosquitto-broker/config/mosquitto.conf` | MQTT: `allow_anonymous false`, sin TLS |
| `mosquitto-broker/config/pwfile` | Hashes `$7$` de usuarios MQTT |
| (git) `f7a5fe23` | DDS Security completo — no integrado a `main` |
