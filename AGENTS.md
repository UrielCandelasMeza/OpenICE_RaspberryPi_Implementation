# AGENTS.md — OpenICE / MD PnP

Project `1.5.0-SNAPSHOT`. Gradle 9.0.0, Java source/target 25, JavaFX 25 (demo-apps). No lint/formatter — verify via compile + tests only.

## Important Build Prerequisites

- **7 non-Maven JARs must exist in `artifacts/`**: `nddsjava.jar`, `pixelmed.jar`, `Utility-0.0.1.jar`, `mdpnp-sounds-0.1.0.jar`, `rtiddsgen2.jar`, `rtiusagemetrics-api.jar`, `cpp-bin-1.2.8-SNAPSHOT.zip`. Build fails without them.
- **`RTI_LICENSE_FILE`** must point to a valid license (e.g. `interop-lab/demo-apps/src/main/resources/OpenICE_license.dat`).
- **`LD_LIBRARY_PATH`** must include `native/libs/linux` (or `aarch`, `macosx`, `windows`) — already configured in `build.gradle` `test` and `run` blocks.
- Tests need `SEC_ARTIFACT_DIR`, `DOCBOX_RTPS_HOST_ID`, `DOCBOX_RTPS_APP_ID` env vars (set in `demo-apps/build.gradle:300-314`).
- The `:setupLocalDb` task runs `sudo -u postgres psql` — requires postgres superuser.

## CI / Stale Config

Only CI workflow is `.github/workflows/gradle.yml` — targets **JDK 1.8** on `windows-latest`. This is stale (project now uses Java 25). Ignore CI failures; use local `./gradlew build` for truth.

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

`Main` runs GUI by default, headless when `-domain -app -device` args passed.

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
./gradlew :data-types:x73-idl-rti-dds:iceddsgenPython    # IDL → Python
./gradlew :data-types:x73-idl-rti-dds:iceddsgenJava      # IDL → Java
```

## Docker

- `./build.sh` runs `makeFlatRuntime` then `sudo docker build` (images: `openice:1.0`, `openice-wis:1.0`).
- Containers require `privileged: true` + `network_mode: host` (RTI DDS native libs call `mprotect(PROT_EXEC)`, blocked by seccomp).
- JavaFX GUI cannot run in Docker — only headless device mode (`-Djava.awt.headless=true`).
- Docker profiles: `wis`, `pump`, `monitor` (see `docker-compose.yml`).

## Testing

- JUnit 4 via JUnit 5 Vintage engine (`junit-vintage-engine:5.9.1`).
- Timezone forced to `EST`.
- `modularity.inferModulePath.set(false)` — JPMS not used.

## Runtime Quirks

- JavaFX `--add-exports` JVM args required (6 exports, see `demo-apps/build.gradle:316-322`).
- `--enable-native-access=ALL-UNNAMED` required in Docker.
- Spring XML context chain: `RtConfig.xml` (DDS infra) → `DeviceAdapterContext.xml` / `IceAppContainerContext.xml` → `DriverContext.xml`.
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

## DDS Discovery

- Multicast: `239.255.0.1`, UDPv4 only (shared memory disabled).
- Promiscuous discovery (`accept_unknown_peers: true`).
- Domain announcements disabled.

## Headless Database

PostgreSQL/TimescaleDB at `localhost:5432`, user `openice`, db `openice_local`. HikariCP pool. Setup via `./gradlew setupLocalDb` or `setup_local_timescale.sql`.
