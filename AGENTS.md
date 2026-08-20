# AGENTS.md — OpenICE / MD PnP

Project `1.5.0-SNAPSHOT`. Gradle 9.0.0, Java source/target 25, JavaFX 25 (demo-apps). No lint/formatter — verify via compile + tests only. See `CLAUDE.md` for expanded walkthroughs (ICE app / simulated device creation).

## Important Build Prerequisites

- **7 non-Maven JARs must exist in `artifacts/`**: `nddsjava.jar`, `pixelmed.jar`, `Utility-0.0.1.jar`, `mdpnp-sounds-0.1.0.jar`, `rtiddsgen2.jar`, `rtiusagemetrics-api.jar`, `cpp-bin-1.2.8-SNAPSHOT.zip`. Build fails without them.
- **`RTI_LICENSE_FILE`** must point to a valid license (e.g. `interop-lab/demo-apps/src/main/resources/OpenICE_license.dat`).
- **`LD_LIBRARY_PATH`** must include `native/libs/linux` (or `aarch`, `macosx`, `windows`) — already configured in `build.gradle` `test` and `run` blocks.
- Tests need `SEC_ARTIFACT_DIR` env var; `RTI_LICENSE_FILE` and `LD_LIBRARY_PATH` are set automatically in the `test`/`run` blocks of `demo-apps/build.gradle`. `DOCBOX_RTPS_HOST_ID`/`DOCBOX_RTPS_APP_ID` are set **only on Windows** (`run` block, not `test`).
- The `:setupLocalDb` task runs `sudo -u postgres psql` — requires postgres superuser. The SQL it runs (`setup_local_timescale.sql`) is legacy/reference-only; the file itself warns "NO USEN ESTO COMO REFERENCIA". No runtime code uses this schema.
- **`flat/`** directory under `demo-apps/` is the Docker build artifact (all JARs + native `.so` files). Created by `makeFlatRuntime`; `build.sh --skip-gradle` skips regeneration if it already exists.

## CI / Stale Config

Only CI workflow is `.github/workflows/gradle.yml` — targets **JDK 1.8** on `windows-latest`, and the build step is `continue-on-error: true` (non-blocking). This is stale (project now uses Java 25). Ignore CI failures; use local `./gradlew build` for truth.

## Module Structure & Entry Points

| Module | Purpose |
|---|---|
| `interop-lab/demo-apps` | JavaFX GUI (Supervisor). Entry point: `org.mdpnp.apps.testapp.Main` |
| `headless-adapter` | No-JavaFX embedded device runtime. Entry point: `org.mdpnp.headless.HeadlessMain` |
| `interop-lab/demo-devices` | Device adapter framework + Spring XML config, shared by both above |
| `interop-lab/demo-guis-javafx` | JavaFX GUI components (builds with JavaFX **17**, not 25) |
| `devices/*` | 40+ physical device protocol implementations (serial/TCP) |
| `data-types/x73-idl` | IEEE 11073 IDL type definitions |
| `data-types/x73-idl-rti-dds` | RTI code-generated Java types from IDL |

`Main` runs GUI by default, headless when `-domain -app -device` args passed. `HeadlessMain` takes `-domain -device [-address <serial|ip>] [-peers <hosts>]` — no `-app` (it runs a single device).

## Key Packages

| Package | Role |
|---|---|
| `org.mdpnp.apps.testapp` | Main app, device factory, ICE app container |
| `org.mdpnp.devices` | `AbstractDevice` base class, device protocol implementations |
| `org.mdpnp.rtiapi.data` | DDS data model (Numeric, SampleArray, Alert, etc.) |
| `org.mdpnp.apps.testapp.pca` | PCA (infusion pump safety) app |
| `org.mdpnp.apps.testapp.chart` | Waveform charting app |

## ServiceLoader SPI — Two Separate Registries

When adding a device driver, **both** SPI files must be updated:

| SPI file | Lines |
|---|---|
| `interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` | 41 |
| `headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` | 51 |

ICE applications (charting, PCA, EMR, etc.) register at:
`interop-lab/demo-apps/src/main/resources/META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider` (25 lines).

## Commands

```bash
./gradlew build                                          # full build + tests
./gradlew :interop-lab:demo-apps:run                     # GUI supervisor
./gradlew :interop-lab:demo-apps:runDevice --args="..."  # headless via Main (same entry point, no GUI)
./gradlew :headless-adapter:run --args="..."             # headless (no JavaFX at all)
./gradlew :interop-lab:demo-apps:distZip                 # ~126 MB distribution
./gradlew :headless-adapter:distZip                      # headless distribution
./gradlew :interop-lab:demo-apps:test                    # all tests in module
./gradlew :interop-lab:demo-apps:test --tests "*.MyTest" # single class
./gradlew :interop-lab:demo-apps:test --tests "*.MyTest.testMethod" # single method
./gradlew :interop-lab:demo-apps:makeFlatRuntime --no-daemon -x test  # before Docker build
./gradlew :interop-lab:demo-apps:generateDeviceList   # list supported devices
./gradlew :data-types:x73-idl-rti-dds:rtiddsgenExplodeResources  # unpack RTI native libs before IDL gen
./gradlew :data-types:x73-idl-rti-dds:iceddsgenPython    # IDL → Python
./gradlew :data-types:x73-idl-rti-dds:iceddsgenJava      # IDL → Java
```

## Docker

- `./build.sh` runs `makeFlatRuntime` then `sudo docker build` (images: `openice:1.0`, `openice-wis:1.0`). Needs sudo for docker; `--skip-gradle` skips Gradle if `flat/` already exists.
- Containers require `privileged: true` + `network_mode: host` (RTI DDS native libs call `mprotect(PROT_EXEC)`, blocked by seccomp).
- JavaFX GUI cannot run in Docker — only headless device mode (`-Djava.awt.headless=true`).
- Docker profiles: `wis`, `pump`, `monitor` (see `docker-compose.yml`).

## Testing

- JUnit 4 via JUnit 5 Vintage engine (`junit-vintage-engine:5.9.1`).
- Timezone forced to `EST`.
- `modularity.inferModulePath.set(false)` — JPMS not used.

## Runtime Quirks

- JavaFX `--add-exports` JVM args required (6 exports, see the `run`, `runDevice`, and `startScripts` blocks of `demo-apps/build.gradle`).
- `--enable-native-access=ALL-UNNAMED` required in Docker.
- Spring XML context: `DeviceAdapterContext.xml` or `IceAppContainerContext.xml` import `RtConfig.xml` (DDS infra); `DriverContext.xml` loaded by device adapters. `DeviceAdapterContext.xml` also loads `ice.properties` from classpath + `${user.dir}/ice.properties` override.
- `${mdpnp.domain}` placeholder defaults to `0` via `ice.properties`; override with `-Dmdpnp.domain=N`.
- Settings persist to `.JumpStartSettings` in cwd or `$HOME`.
- `ice.system.properties` (classpath + cwd override) sets `java.net.preferIPv4Stack=true`.
- `demo-guis-javafx` pins JavaFX **17.0.11** (not 25 like demo-apps). May cause classpath conflicts.

## DDS Topic Development

**To subscribe:**
1. `ice.MyTopicTypeSupport.register_type(participant, ...)`
2. `TopicUtil.findOrCreateTopic(participant, ice.MyTopicTopic.VALUE, ...)`
3. `subscriber.create_datareader_with_profile(...)` using `QosProfiles.ice_library` + `QosProfiles.state` (or `observed_data`)
4. Create `ReadCondition`, attach `ConditionHandler` via `EventLoop`

**To publish:**
1. Register type + create topic (same as above)
2. `publisher.create_datawriter_with_profile(...)` with same QoS profile
3. Populate generated struct, call `dataWriter.write(msg, InstanceHandle_t.HANDLE_NIL)`

When adding a struct to `ice.idl`, annotate primary keys with `@key`. RTI auto-generates `MyTopic`, `MyTopicDataReader`, `MyTopicDataWriter` during build.

## Device Driver Requirements (must follow)

1. Read protocol docs in full.
2. Produce `ASSUMPTIONS.md` in driver source dir (parameter, source, risk for every assumption).
3. Flag every ambiguous/silent spec point.
4. Wait for `confirmed` before writing code.
5. Commit `ASSUMPTIONS.md` alongside driver.

## Configuration Files

| File | Purpose |
|---|---|
| `ice.properties` (classpath) | `mdpnp.domain`, `mdpnp.fhir.url`, `dds.discovery.peers` |
| `ice.system.properties` (classpath + cwd) | Sets `java.net.preferIPv4Stack=true`, loaded before all app logic |
| `log4j2-test.xml` | Logs to `~/demo-apps.log`, `~/easy-tiva.log` |
| `interop-lab/demo-devices/src/main/resources/RtConfig.xml` | DDS participant, publisher, subscriber, event loop |
| `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` | DDS QoS profiles |

Three `ice.properties` files exist: root (comments only, NOT on classpath), `demo-apps/src/main/resources/ice.properties` (effective defaults, includes FHIR tokens), `headless-adapter/src/main/resources/ice.properties` (minimal). Edit the classpath copies to change defaults.

**Security:** The classpath `ice.properties` currently contains live FHIR token credentials (`mdpnp.fhir.token.user`, `mdpnp.fhir.token.password`). These are environment-specific — never commit real credentials.

## DDS Discovery

- Multicast: `239.255.0.1`, UDPv4 only (shared memory disabled).
- Promiscuous discovery (`accept_unknown_peers: true`).
- Domain announcements disabled.

## MQTT Send Bridge

The Supervisor app **MQTT Send** (`org.mdpnp.apps.testapp.mqtt`) publishes DDS Numeric/SampleArray/Alert data to a Mosquitto broker at `openice/{udi}/{metric_id}`. Broker: `docker compose up -d` in `mosquitto-broker/` (auth required, `allow_anonymous false`). Full topic/payload reference in README "MQTT Send".

## FHIR R4 Auth (HL7 Emitter only)

OAuth2 bearer tokens (`TokenProvider`, props `mdpnp.fhir.token.*` in `ice.properties`) are used **only** by the HL7 FHIR R4 emitter (`org.mdpnp.apps.testapp.hl7.HL7Emitter`) to add `Authorization: Bearer` headers. The EMR app (`FhirEMRImpl`, `org.mdpnp.apps.testapp.patient`) does **not** use the token — its FHIR client is unauthenticated. Do not attach tokens there.

### FHIR Gateway Architecture

```
OpenICE HL7 Emitter → FHIR Gateway (localhost:8080) → HAPI FHIR Backend (localhost:8099)
                                    ↑
                              Keycloak (localhost:9080) for token endpoint
```

- **Gateway** (`localhost:8080`): enforces patient-based list access control via `ListAccessChecker`. Every resource write must reference a patient in the user's authorized list.
- **HAPI Backend** (`localhost:8099`): the actual FHIR store, no auth required.
- **Keycloak** (`localhost:9080`): issues OAuth2 bearer tokens (5 min TTL).

### ListAccessChecker

The gateway's `ListAccessChecker` validates that every Observation's `subject` reference points to a Patient that exists in the `patient-list-example` List resource. If the Patient is not in the list, the gateway returns 403 "User is not authorized".

- `patient-list-example` is a FHIR `ListResource` in HAPI (8099) containing authorized Patient references (e.g., `Patient/3177`, `Patient/2835`).
- `HL7Emitter.addToAuthorizedList()` automatically adds newly created Patients to this List via `backendClient` (8099, no auth).
- `Device` resources are written directly to the backend (8099) because Device is NOT in the patient compartment.

### HL7 Emitter Data Flow

```
DDS Numeric events → ValidationOracle → recentUpdates → sendFHIR()
  → fhirObservation() per validation:
      1. Resolve MRN: selectedPatientMRN → deviceUdiToPatientMRN fallback
      2. getPatientResource(mrn): search/create Patient in HAPI (8099)
      3. addToAuthorizedList(): ensure Patient is in patient-list-example
      4. getDeviceResource(udi, resourceId): search/create Device in HAPI (8099)
  → sendObservations(): always display in console, only bundle if obs.hasSubject()
  → Transaction POST to gateway (8080) with bearer token
```

### Key Files

| File | Role |
|---|---|
| `HL7Emitter.java` | Core emission logic: Patient/Device creation, Observation bundling, gateway submission |
| `HL7Application.java` | JavaFX controller: patient ComboBox, Start/Stop button, frequency slider |
| `HL7Application.fxml` | FXML layout with ComboBox, host/port fields, protocol radio buttons |
| `HL7ApplicationFactory.java` | Spring factory: wires `EMRFacade`, `ValidationOracle`, `FhirContext` into emitter |
| `TokenProvider.java` | OAuth2 token fetch from Keycloak (5 min TTL, `setToken()` for force-refresh) |
| `FhirEMRImpl.java` | FHIR-based EMR: creates patients in HAPI with OID system `urn:oid:2.16.840.1.113883.3.1974` |
| `EMRFacade.java` | Abstract EMR facade, `fetchAllPatients()` returns patients from HSQLDB |

Full FHIR walkthrough (patient selection, gateway config, Keycloak setup) in `CLAUDE.md`.

## Headless Database

No database. The headless-adapter, the Supervisor GUI, and all device drivers have **zero** PostgreSQL/TimescaleDB connectivity (no JDBC pipeline, no HikariCP, no psql logging). Removed: `org.mdpnp.headless.db` (`ConnectionPool`, `DeviceRegistry`, `TimescalePersister`), `-dbhost/-dbport/-dbname/-dbuser/-dbpassword` CLI options, and all `SQLLogging` usage from `AbstractDevice`, `DemoPanel`, and `PatientInfoController`. Patient–device association goes through the EMR (HSQLDB `EmbeddedDB`), not psql.

`SQLLogging` (in `devices/common`) still exists only for the optional test applications (OpenEMRTestApplication, pump/BP/closed-loop timing apps). No PostgreSQL driver is on any classpath; postgres must not be reintroduced without approval.

`devices/common/src/main/java/org/mdpnp/sql/` also contains `PlaceboConnection` and `PlaceboPreparedStatement` — stub/no-op JDBC implementations, not real database drivers.

## Documentation Format (docs/*.md)

Every new or edited document under `docs/` **must** use this skeleton (header order fixed, closing origin line mandatory):

```markdown
# <Título>

**Fecha:** <date>  **Proyecto:** OpenICE / MD PnP (`1.5.0-SNAPSHOT`)  **Sistema:** <component>  **Alcance:** <scope>

**Versión:** <x.y.z>  ← ONLY in proposals (title must say "Propuesta"); omit in reports/guides

---

<content>

---

*<Tipo> generado a partir de <source>.*
```

- Documents in **Spanish**.
- **Reports** (`docs/reports/*`): Contexto → Problema → Hipótesis → Proceso → Resultado → Conclusión → Recomendaciones.
- **Use cases** (`docs/usecases/*`): use their existing `**ID:**`/`**Versión:**`/`**Fecha:**`/`**Actor Principal:**`/`**Sistema:**` variant; title + origin-line rules still apply.
