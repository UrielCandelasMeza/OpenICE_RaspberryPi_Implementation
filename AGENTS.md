# AGENTS.md — OpenICE / MD PnP

## Versions

- Project: `1.5.0-SNAPSHOT` (defined in `gradle.properties`)
- Gradle wrapper: **9.0.0**
- Java source/target: **25**
- JavaFX: **25** (modules: base, controls, fxml, swing, web)
- Spring: **6.0.2**
- RTI Connext DDS: 5.1.0 (QoS XML schema version)
- `rtiddsgen` code generator: 4.3.0

## Build & Test Commands

```bash
# Full build + tests
./gradlew build

# Compile only (no tests)
./gradlew assemble

# Run GUI app
./gradlew :interop-lab:demo-apps:run

# Run headless device (from demo-apps module — Main.java detects args)
./gradlew :interop-lab:demo-apps:runDevice --args="-domain 0 -device Pump_Simulator"

# Run headless (headless-adapter module, no JavaFX)
./gradlew :headless-adapter:run --args="-domain 0 -device Controllable_Pump"

# Run all tests in a module
./gradlew :interop-lab:demo-apps:test

# Run a single test class
./gradlew :interop-lab:demo-apps:test --tests org.mdpnp.apps.testapp.DeviceFactoryTest

# Run a single test method
./gradlew :interop-lab:demo-apps:test --tests org.mdpnp.apps.testapp.DeviceFactoryTest.testLocateDrivers

# Distribution ZIP (includes native libs)
./gradlew :interop-lab:demo-apps:distZip        # ~126 MB
./gradlew :headless-adapter:distZip

# Python code generation (IDL → ice.py)
./gradlew :data-types:x73-idl-rti-dds:iceddsgenPython
./gradlew :data-types:x73-idl-rti-dds:copyIceDotPy

# Setup local TimescaleDB (requires postgres superuser, runs setup_local_timescale.sql)
./gradlew setupLocalDb
```

**No lint or formatting tools are configured** — there is no Checkstyle, Spotless, or similar. Verify correctness through compilation and tests only.

## Entry Points (two independent ones)

- **`org.mdpnp.apps.testapp.Main`** (`interop-lab/demo-apps`) — GUI mode by default (launches JavaFX); headless mode when CLI args (`-domain`, `-app`, `-device`) are passed.
- **`org.mdpnp.headless.HeadlessMain`** (`headless-adapter/`) — pure headless, no JavaFX dependency. Runs on embedded devices.

Both can launch the same device drivers, but `HeadlessMain` uses its own parallel SPI file.

## ServiceLoader SPI — two registries for devices

| SPI file | Location | Purpose |
|---|---|---|
| `org.mdpnp.devices.DeviceDriverProvider` | `interop-lab/demo-devices/src/main/resources/META-INF/services/` | References `DeviceFactory$*` inner classes (require demo-apps/JavaFX). ~42 entries. |
| `org.mdpnp.devices.DeviceDriverProvider` | `headless-adapter/src/main/resources/META-INF/services/` | References `HeadlessDeviceFactory$*` inner classes (no JavaFX). ~51 entries. |

When adding a new device driver, both SPI files must be updated — they shadow each other because `demo-devices` entries reference `org.mdpnp.apps.testapp.DeviceFactory$*` which isn't on the headless classpath.

ICE application SPI lives at `interop-lab/demo-apps/src/main/resources/META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider` (~25 entries).

## Build Gotchas

- **Artifacts not in Maven Central**: 7 files must exist in `artifacts/`: `nddsjava.jar`, `pixelmed.jar`, `Utility-0.0.1.jar`, `mdpnp-sounds-0.1.0.jar`, `rtiddsgen2.jar`, `rtiusagemetrics-api.jar`, `cpp-bin-1.2.8-SNAPSHOT.zip`.
- **Docker build**: run `./gradlew :interop-lab:demo-apps:makeFlatRuntime --no-daemon -x test` to create `flat/` first, then `docker build`. Script: `build.sh` (also builds `openice-wis:1.0` image from `wis-docker/`).
- **Docker containers need `privileged: true`** and `network_mode: host` — RTI DDS native libs call `mprotect(PROT_EXEC)`, blocked by seccomp. JavaFX GUI cannot run in Docker (only headless mode).
- **JavaFX JVM args**: `--add-exports=javafx.graphics/com.sun.javafx.iio=ALL-UNNAMED` (and 4 more) are required at runtime — see the `run` block in `interop-lab/demo-apps/build.gradle:308`.
- **Java 25 native access**: `--enable-native-access=ALL-UNNAMED` is required in Docker commands (see `docker-compose.yml`).
- **Java module system disabled**: `modularity.inferModulePath.set(false)` — the project does not use JPMS modules.

## Testing

- Test framework: JUnit 4 (4.13.2) via JUnit 5 Vintage engine (`junit-vintage-engine:5.9.1`)
- Tests run with `useJUnitPlatform()` and timezone forced to EST (`systemProperty 'user.timezone', 'EST'`)
- Tests need `LD_LIBRARY_PATH` pointing to `native/libs/linux` (or `aarch`, `macosx`, `windows`) and `RTI_LICENSE_FILE` set — both configured already in `build.gradle` `test` block.

## Medical Device Driver Requirements

When writing or modifying any device driver (`org.mdpnp.devices`):

1. Read all provided protocol documentation in full.
2. Produce an `ASSUMPTIONS.md` in the driver's source directory listing every assumption (parameter values, byte order, timing, error handling, defaults for absent fields, etc.) with source and risk.
3. Flag every ambiguous or silent point in the spec.
4. Wait for explicit user confirmation before writing code.
5. Commit `ASSUMPTIONS.md` alongside the driver; update if assumptions change.

## Architecture Wiring

Spring XML context chain:
- `DeviceAdapterContext.xml` → imports `RtConfig.xml` (DDS participant, subscriber, publisher, event loop)
- `IceAppContainerContext.xml` → imports `RtConfig.xml`, adds FX observable lists, data writers, EMR facade
- `DriverContext.xml` → per-device driver, executor, partition assignment, TimeManager, JMX

Property placeholder `${mdpnp.domain}` must be supplied (default `0` in `ice.properties` or via `-Dmdpnp.domain=N`).

Settings persist to `.JumpStartSettings` in current dir or `$HOME/.JumpStartSettings` — the file is loaded on GUI startup to restore last config.

## DDS Discovery Configuration

- **Multicast address**: `239.255.0.1` (RTPS standard default, defined in `ice_library.xml`)
- **Transport**: UDPv4 only (shared memory disabled)
- **Discovery mode**: Promiscuous (`accept_unknown_peers: true`) — suitable for lab environments
- **Domain announcements**: Disabled (`ignore_default_domain_announcements: true`)
- QoS profiles defined in `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml`

## Headless Adapter — Database Integration

The `headless-adapter` module includes PostgreSQL/TimescaleDB persistence:
- **`db/ConnectionPool.java`** — HikariCP-based connection pool (PostgreSQL 42.7.2, HikariCP 5.1.0)
- **`db/TimescalePersister.java`** — writes device data to TimescaleDB hypertables
- **`db/DeviceRegistry.java`** — device registry backed by PostgreSQL
- Connection configured in `headless-adapter/src/main/resources/ice.properties`: `localhost:5432`, user `openice`, db `openice_local`

## Docker / Deployment

- **`Dockerfile`** — based on `eclipse-temurin:25-jre`, copies `flat/` runtime
- **`docker-compose.yml`** — profiles: `wis`, `pump`, `monitor`. Uses `x-openice-base` template with `privileged: true`, `network_mode: host`, domain ID via `ICE_DOMAIN_ID` env var (default 10)
- **`build.sh`** — two-step: Gradle `makeFlatRuntime` → Docker build. Builds both `openice:1.0` and `openice-wis:1.0`
- **`wis-docker/`** — Fedora-based Docker image for RTI Web Integration Service (port 8080)
- **`device_adapter.sh`** — SysV init script for Raspberry Pi deployment (`headless-adapter.init`)

## Config Files

- `ice.properties` (classpath) — `mdpnp.domain`, `mdpnp.fhir.url`, `dds.discovery.peers`
- `ice.system.properties` (classpath + cwd override) — loaded by `Main.loadSystemProps()` before any app logic; sets `java.net.preferIPv4Stack=true`
- `log4j2-test.xml` — logs to `~/demo-apps.log`, `~/easy-tiva.log`

## Python Bindings

Python bindings are auto-generated from IDL via `rtiddsgen`:
- **Root**: `data-types/x73-idl/src/main/idl/ice/ice.py` (~730 lines, all IDL types)
- **Subdirectories** (also receive copies): `pump_controller/`, `samples/`, `numeric_subscriber/`
- **Gradle tasks**: `iceddsgenPython` (generate), `copyIceDotPy` (distribute to subdirs)
- Additional Python scripts: `pump_controller/pumpcontroller.py`, `samples/ice_program.py`, `numeric_subscriber/read_numerics.py`

## Project Structure — Additional Directories

- **`scripts/`** — Integration scripts: `atlan_receiver.py`, `massimo_connector.py`, `DragerAtlanHandshake.java`, `EfficiaHL7Listener.java`, plus sample data files
- **`devices/mindray/`** — Mindray device support (exists on disk but **not** in `settings.gradle`)
- **`docs/`** — Architecture diagrams (PlantUML), manuals (PDFs), reports, prompts, class diagrams, use cases
