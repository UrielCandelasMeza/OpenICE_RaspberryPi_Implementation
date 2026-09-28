# AGENTS.md — OpenICE / MD PnP

Version `1.5.1-SNAPSHOT` (`gradle.properties:MDPNP_VERSION_NUMBER` + `BUILD_NUMBER=SNAPSHOT` in root `build.gradle`). Gradle 9.0.0, Java source/target 25, JavaFX 25 in `demo-apps`. **No lint or formatter** — verify with compile + tests only.

`CLAUDE.md` holds the longer walkthroughs (ICE app / simulated device creation, FHIR flow). This file wins on conflicts; where code wins over both, trust the code.

## Build Prerequisites

- **7 non-Maven artifacts must exist in `artifacts/`**: `nddsjava.jar`, `pixelmed.jar`, `Utility-0.0.1.jar`, `mdpnp-sounds-0.1.0.jar`, `rtiddsgen2.jar`, `rtiusagemetrics-api.jar`, `cpp-bin-1.2.8-SNAPSHOT.zip`. Referenced via `implementation files(...)`, so the build fails without them. They are gitignored (`*.jar`).
- **`RTI_LICENSE_FILE`** must point to a valid license. The `test`/`run`/`runDevice` blocks of `interop-lab/demo-apps/build.gradle` and the `run` block of `headless-adapter/build.gradle` set it automatically; anything launched outside Gradle must export it yourself.
- **`LD_LIBRARY_PATH`** must include `interop-lab/demo-apps/native/libs/linux` (or `aarch`/`macosx`/`windows`).
- Tests need `SEC_ARTIFACT_DIR`; it and `RTI_LICENSE_FILE` are set by the Gradle `test`/`run` blocks. `DOCBOX_RTPS_HOST_ID`/`DOCBOX_RTPS_APP_ID`/`COORDINATOR_DOMAIN_ID` are set **only on Windows** and **only in `run`**, not `test`.
- `:setupLocalDb` runs `sudo -u postgres psql -f setup_local_timescale.sql` with `ignoreExitValue = true`. Nothing depends on it any more (see "No Database" below); the SQL file itself warns `NO USEN ESTO COMO REFERENCIA`. The `build`-dependsOn-`:setupLocalDb` hook is commented out in root `build.gradle`.
- **RTI code generation is automatic**: `compileJava.dependsOn iceddsgenJava` → `rtiddsgenExplodeResources`. Run `./gradlew :data-types:x73-idl-rti-dds:rtiddsgenExplodeResources` explicitly only when the native RTI resource unpack looks broken. `iceddsgenPython` / `iceddsgenJava` regenerate `ice.py` and the Java DDS types from `data-types/x73-idl/src/main/idl/ice/ice.idl`.

## CI is Stale — Ignore It

`.github/workflows/gradle.yml` is the only workflow: JDK **1.8** on `windows-latest`, branch `master`, and the build step is `continue-on-error: true`. The project is Java 25 on Linux with module `headless-adapter`. Local `./gradlew build` is the only source of truth.

## Modules & Entry Points

| Module | Role |
|---|---|
| `interop-lab/demo-apps` | Supervisor JavaFX GUI. `org.mdpnp.apps.testapp.Main` |
| `headless-adapter` | No-JavaFX device runtime. `org.mdpnp.headless.HeadlessMain` |
| `interop-lab/demo-devices` | Device framework + Spring XML (`RtConfig.xml`, `DriverContext.xml`, `DeviceAdapterContext.xml`) |
| `interop-lab/demo-guis`, `demo-guis-javafx`, `demo-guis-swing`, `demo-guis-jogl` | GUI components (javafx variant pins JavaFX **17.0.11**, not 25 — classpath conflict risk) |
| `interop-lab/demo-jserialcomm`, `purejavacomm` | Serial port providers |
| `devices/*` (12 subprojects) | Protocol implementations (`common`, `draeger`, `philips`, `masimo`, `nellcor`, `nonin`, `covidien`, `cpc`, `fluke`, `ge`, `oridion`, `puritanbennett`, `simulated`) |
| `data-types/x73-idl` | IEEE 11073 IDL + DDS QoS profiles |
| `data-types/x73-idl-rti-dds` | RTI-generated Java types (built, not authored) |

- `Main` runs the GUI by default; headless when `-domain -app -device` are passed.
- `HeadlessMain`: `-domain -device [-address <serial|ip>] [-baud <rate>] [-peers <hosts>]`. **No `-app`** — it runs a single device.
- `-baud` sets the system property `mdpnp.serial.baudrate` (default **9600**). Only `MasimoRadical7` (`DemoRadical7.java:190`) reads it today; other drivers hardcode their rate and will ignore the flag.

## Commands

```bash
./gradlew build                                          # full build + tests
./gradlew assemble                                       # compile only, skip tests (faster loop)
./gradlew :interop-lab:demo-apps:run                     # Supervisor GUI
./gradlew :interop-lab:demo-apps:runDevice --args="-domain 0 -device DraegerV500 -address /dev/ttyUSB0 -baud 19200"
./gradlew :headless-adapter:run --args="--help"         # list supported devices
./gradlew :headless-adapter:run --args="-domain 0 -device Pump_Simulator"
./gradlew :interop-lab:demo-apps:test                    # all tests in module
./gradlew :interop-lab:demo-apps:test --tests "*.MyTest"
./gradlew :interop-lab:demo-apps:test --tests "*.MyTest.testMethod"
./gradlew :interop-lab:demo-apps:generateDeviceList     # dump supported device list
./gradlew :interop-lab:demo-apps:makeFlatRuntime -x test  # rebuild demo-apps/flat/ for Docker
./gradlew :headless-adapter:distZip                      # OpenICE-headless-<ver>.zip
./gradlew :interop-lab:demo-apps:distZip                 # GUI distribution (~126 MB)
```

## ServiceLoader SPI — Three Registries

| File | Current size |
|---|---|
| `interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` | 41 lines |
| `headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` | 52 lines |
| `interop-lab/demo-apps/src/main/resources/META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider` | 25 lines |

**A new device driver must be registered in BOTH `DeviceDriverProvider` files.** They are not interchangeable: the `demo-devices` list points at `org.mdpnp.apps.testapp.DeviceFactory$*` inner classes (which need `demo-apps` on the classpath), while `headless-adapter` has its own `org.mdpnp.headless.HeadlessDeviceFactory$*` mirror because it must run without JavaFX or `demo-apps`. Adding to only one gives you a device that works in the GUI and vanishes on the Pi (or vice versa).

## Adding Things

**Device driver** (`org.mdpnp.devices.*`):
1. Read the protocol docs in full (`docs/manuals/` holds the Dräger MEDIBUS and Philips manuals as PDFs).
2. Write `ASSUMPTIONS.md` next to the driver: every assumption as **Parameter / Source (section or page) / Risk**. Mark undocumented points "not specified".
3. Flag every ambiguous, silent, or self-contradicting spec point.
4. **Wait for explicit user confirmation before writing any driver code.**
5. Commit `ASSUMPTIONS.md` alongside the driver; keep it updated.

Assumptions that must always be declared: units (mL/hr vs mL/min, 0.1x scaling), byte order/endianness, signed vs unsigned, polling intervals, behavior on connection loss, defaults for absent/zero fields.

**Supervisor device panel**: add the class to `DevicePanelFactory.PANELS` (`org.mdpnp.apps.device`) and give it a `public static boolean supported(Set<String> tags)` method. Panel selection is reflective, keyed on the device's tags — forgetting either step makes the panel silently never appear.

**ICE application**: controller gets a `start(EventLoop, Subscriber)` and receives `DeviceListModel`, `NumericFxList`, `AlertFxList`, `EventLoop` via Spring — do not open DDS connections directly. Pair it with an `IceApplicationProvider` factory, then register it in the `IceApplicationProvider` SPI file.

## Field Scripts — `scripts/` Is Not Gradle

`scripts/` holds standalone field-prototyping programs and captured serial logs from the operating-room Raspberry Pi work. They are **plain `javac`/`python3` programs, not Gradle subprojects** — they are not compiled, not tested, and not on any classpath.

- `scripts/DragerAtlanHandshake.java` — single-file (`java scripts/DragerAtlanHandshake.java`) Medibus RS-232 handshake reverse-engineering for the Dräger Atlan A-350XL. Committed `.class` files alongside it are stale artifacts.
- `scripts/simula_massimo.py` (replays `datos_massimo*.txt` over a fake serial port), `scripts/massimo_connector.py`, `scripts/hex_ascii.py`, `scripts/EfficiaHL7Listener.java`.
- `scripts/*.txt`, `scripts/*.log` are captured device captures; the working set for the simulator is hardcoded in `simula_massimo.py`.
- Python needs `pyserial`; there is a `scripts/venv/`.

**The Dräger Atlan has no Medibus driver yet.** The SPI entry `DraegerAtlanProvider` resolves to `org.mdpnp.devices.simulation.atlan.SimDraegerAtlan` (`ConnectionType.Simulated`). The real work is being characterized in `scripts/` first — do not assume a working Atlan hardware driver exists.

## Deployment

- `./device_adapter.sh list` / `install DEVICE [DOMAIN] [ADDR] [BAUD] [PEERS]` / `device` — builds `:headless-adapter:distZip`, unzips to `~/OpenICE`, writes `~/device.this` with the CLI args, and registers a `headless-adapter` init service. Uses `su - $SUDO_USER` so Gradle picks up `JAVA_HOME`/sdkman; don't run it as plain root.
- `headless-adapter.init` / `device-adapter` are the SysV init scripts used on the Pi (hardcode `/home/debian`).
- `./build.sh` runs `makeFlatRuntime` then `sudo docker build` (images `openice:1.0`, `openice-wis:1.0`). `--skip-gradle` reuses `demo-apps/flat/` if present. Docker profiles: `wis`, `pump`, `monitor` (`docker-compose.yml`).
- Containers need `privileged: true` + `network_mode: host` (RTI native libs call `mprotect(PROT_EXEC)`, blocked by seccomp) and `--enable-native-access=ALL-UNNAMED`. JavaFX cannot run in Docker — headless only.

## Runtime Quirks

- JavaFX `--add-exports` (6 flags) plus `--enable-native-access=ALL-UNNAMED` are required; duplicated in `run`, `runDevice`, and patched into generated start scripts by the `startScripts { doLast { ... } }` blocks. If you add a new exec task, copy them.
- Spring wiring: `DeviceAdapterContext.xml` / `IceAppContainerContext.xml` import `RtConfig.xml` (DDS participant/publisher/subscriber/event loop); `DriverContext.xml` is loaded per device. `DeviceAdapterContext.xml` reads classpath `ice.properties` then `${user.dir}/ice.properties` as an override.
- `ice.system.properties` (classpath + cwd) forces `java.net.preferIPv4Stack=true`; it loads before app logic.
- `${mdpnp.domain}` defaults to `0`; override with `-Dmdpnp.domain=N`. Every participant must share the domain or nothing is discovered.
- DDS discovery: multicast `239.255.0.1`, UDPv4 only, shared memory off, `accept_unknown_peers: true`, domain announcements disabled. `dds.discovery.peers` (comma-separated) is the static-seed alternative.
- Settings persist to `.JumpStartSettings` in cwd or `$HOME`.
- `log4j2-test.xml` writes to `~/demo-apps.log` and `~/easy-tiva.log` — check those, not stdout, when debugging tests.

## Testing

- JUnit 4 (`junit:4.13.2`) through the JUnit 5 Vintage engine; `useJUnitPlatform()`, timezone forced to `EST`.
- `modularity.inferModulePath.set(false)` in `demo-apps` — JPMS is not in play despite the `org.javamodularity.moduleplugin`.
- There is no lint, no formatter, no typecheck task. `./gradlew build` is the gate.

## FHIR / HL7 Emission

```
DDS Numeric → ValidationOracle → recentUpdates → sendFHIR()
  → fhirObservation() per validation:
      MRN: selectedPatientMRN → deviceUdiToPatientMRN fallback
      getPatientResource(mrn)  → search/create Patient in HAPI (8099)
      addToAuthorizedList()    → ensure Patient is in the authorized List
      getDeviceResource(udi)   → search/create Device in HAPI (8099)
  → sendObservations()  → Transaction POST to the gateway (8080) with bearer token
```

- **Gateway** `:8080` enforces patient-list access (`ListAccessChecker`); 403 if the Observation's `subject` is not on the list. **HAPI backend** `:8099` is the unauthenticated store. **Keycloak** `:9080` issues 5-minute bearer tokens (`TokenProvider`).
- `Device` resources are written **straight to 8099** — Device is not in the patient compartment, so the gateway rejects it.
- The authorized FHIR List id is **configurable**: `mdpnp.fhir.list.auth` in `ice.properties`, defaulting to `patient-list-example` (`FhirEmitter.java:104`). The committed value is `lista-dr-gomez` — do not assume the hardcoded default is in play.
- Two emitters exist in `org.mdpnp.apps.testapp.hl7`: `FhirEmitter` (`EmitterType.FHIR_R4`, gateway + bearer) and `HL7Emitter`/`HL7v26Emitter` (`EmitterType.V26`). Only the FHIR path uses OAuth2 tokens; `FhirEMRImpl`'s client is intentionally unauthenticated — do not attach tokens there.
- `EMRFacade` / `EmbeddedDB` is HSQLDB (`~/icepatientdb`), **not** FHIR, despite `mdpnp.fhir.url` existing.

## No Database — Do Not Reintroduce

The headless adapter, the Supervisor, and all drivers have **zero** PostgreSQL/TimescaleDB connectivity: no JDBC pipeline, no HikariCP, no psql logging. `org.mdpnp.headless.db`, the `-dbhost/-dbport/-dbname/-dbuser/-dbpassword` options, and all `SQLLogging` calls in `AbstractDevice`/`DemoPanel`/`PatientInfoController` were removed. Patient–device association goes through the EMR (HSQLDB).

`SQLLogging` and the `PlaceboConnection` / `PlaceboPreparedStatement` no-op JDBC stubs in `devices/common/src/main/java/org/mdpnp/sql/` survive only for optional test applications. No PostgreSQL driver is on any classpath; adding one back requires explicit approval.

## Configuration Files

| File | Purpose |
|---|---|
| `interop-lab/demo-apps/src/main/resources/ice.properties` | Effective defaults: domain, FHIR URLs, `mdpnp.fhir.*` tokens, `mdpnp.fhir.list.auth` |
| `headless-adapter/src/main/resources/ice.properties` | Minimal subset for headless |
| `ice.properties` (repo root) | Comments only — **not** on any classpath |
| `interop-lab/demo-devices/src/main/resources/RtConfig.xml` | DDS participant, publisher, subscriber, event loop |
| `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` | QoS profiles (`ice_library`, `state`, `observed_data`) |

**Security:** the tracked `demo-apps/src/main/resources/ice.properties` contains real Keycloak credentials (`mdpnp.fhir.token.user` / `.password`) for the `fhir-quirofano` realm. They are environment-specific — never substitute real production credentials, and keep them out of any new file you add.

## MQTT Send Bridge

Supervisor app `org.mdpnp.apps.testapp.mqtt` republishes DDS `Numeric`/`SampleArray`/`Alert` data to Mosquitto at `openice/{device_udi}/{metric_id}`. Broker: `cd mosquitto-broker && docker compose up -d` (auth required, `allow_anonymous false`). Subscribe with the `openice/#` wildcard, never bare `openice`. Payload schemas and Java/Python subscriber examples are in `README.md` § "MQTT Send".

## DDS Topic Development

**Subscribe:** `ice.MyTopicTypeSupport.register_type(participant, ...)` → `TopicUtil.findOrCreateTopic(participant, ice.MyTopicTopic.VALUE, ...)` → `subscriber.create_datareader_with_profile(...)` using `QosProfiles.ice_library` + `QosProfiles.state` → `ReadCondition` + `ConditionHandler` attached via `EventLoop`.

**Publish:** same registration, then `publisher.create_datawriter_with_profile(...)` with the same QoS, populate the generated struct, `dataWriter.write(msg, InstanceHandle_t.HANDLE_NIL)`.

Adding a struct to `ice.idl` means annotating primary key fields with `@key`; RTI generates `MyTopic`, `MyTopicDataReader`, `MyTopicDataWriter` during the build.

## Documentation Format (`docs/*.md`)

New or edited docs under `docs/` must be in **Spanish** and use this skeleton — header order fixed, closing origin line mandatory:

```markdown
# <Título>

**Fecha:** <date>  **Proyecto:** OpenICE / MD PnP (`1.5.1-SNAPSHOT`)  **Sistema:** <component>  **Alcance:** <scope>

**Versión:** <x.y.z>  ← only in proposals (title must contain "Propuesta"); omit elsewhere

---

<content>

---

*<Tipo> generado a partir de <source>.*
```

- **Reports** (`docs/reports/`): Contexto → Problema → Hipótesis → Proceso → Resultado → Conclusión → Recomendaciones.
- **Use cases** (`docs/usecases/`) use their own `**ID:**` / `**Actor Principal:**` header variant; the title and origin-line rules still apply.
- `docs/architecture/` uses the report header. `docs/prompts/` holds the original implementation prompts — read the relevant one before re-implementing a feature.
