# AGENTS.md — OpenICE / MD PnP

## Entry Points (two independent ones)

- **`org.mdpnp.apps.testapp.Main`** (`interop-lab/demo-apps`) — GUI mode by default (launches JavaFX); headless mode when CLI args (`-domain`, `-app`, `-device`) are passed.
- **`org.mdpnp.headless.HeadlessMain`** (`headless-adapter/`) — pure headless, no JavaFX dependency. Runs on embedded devices.

Both can launch the same device drivers, but `HeadlessMain` uses its own parallel SPI file.

## ServiceLoader SPI — two registries for devices

| SPI file | Location | Purpose |
|---|---|---|
| `org.mdpnp.devices.DeviceDriverProvider` | `interop-lab/demo-devices/src/main/resources/META-INF/services/` | References `DeviceFactory$*` inner classes (require demo-apps/JavaFX) |
| `org.mdpnp.devices.DeviceDriverProvider` | `headless-adapter/src/main/resources/META-INF/services/` | References `HeadlessDeviceFactory$*` inner classes (no JavaFX) |

When adding a new device driver, both SPI files must be updated — they shadow each other because `demo-devices` entries reference `org.mdpnp.apps.testapp.DeviceFactory$*` which isn't on the headless classpath.

ICE application SPI lives at `interop-lab/demo-apps/src/main/resources/META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider`.

## Build gotchas

- **`./gradlew build` requires PostgreSQL/TimescaleDB** — root `build.gradle` makes every subproject's `build` depend on `setupLocalDb` which runs `sudo -u postgres psql -f setup_local_timescale.sql`. Use `-x setupLocalDb` to skip: `./gradlew build -x setupLocalDb`.
- **Artifacts not in Maven Central**: 4 JARs must exist in `artifacts/`: `nddsjava.jar`, `pixelmed.jar`, `Utility-0.0.1.jar`, `mdpnp-sounds-0.1.0.jar`.
- **Docker build**: run `./gradlew :interop-lab:demo-apps:makeFlatRuntime --no-daemon -x test` to create `flat/` first, then `docker build`. Script: `build.sh`.
- **Docker containers need `privileged: true`** and `network_mode: host` — RTI DDS native libs call `mprotect(PROT_EXEC)`, blocked by seccomp. JavaFX GUI cannot run in Docker (only headless mode).
- **JavaFX JVM args**: `--add-exports=javafx.graphics/com.sun.javafx.iio=ALL-UNNAMED` (and 4 more) are required at runtime — see the `run` block in `interop-lab/demo-apps/build.gradle:308`.
- **Java module system disabled**: `modularity.inferModulePath.set(false)` — the project does not use JPMS modules.

## Testing

```bash
# Skip the PostgreSQL dependency
./gradlew :interop-lab:demo-apps:test -x setupLocalDb --tests ...

# Test framework: JUnit 4 via JUnit 5 Vintage engine
# Timezone forced to EST (systemProperty 'user.timezone' , 'EST')
```

Tests need `LD_LIBRARY_PATH` pointing to `native/libs/linux` (or `aarch`, `macosx`, `windows`) and `RTI_LICENSE_FILE` set — both configured already in `build.gradle` `test` block.

## Key CLI commands

```bash
# Run GUI app
./gradlew :interop-lab:demo-apps:run

# Run headless device (from demo-apps module — Main.java detects args)
./gradlew :interop-lab:demo-apps:runDevice --args="-domain 0 -device Pump_Simulator"

# Run headless (headless-adapter module, no JavaFX)
./gradlew :headless-adapter:run --args="-domain 0 -device Controllable_Pump"

# Distribution ZIP (includes native libs)
./gradlew :interop-lab:demo-apps:distZip
./gradlew :headless-adapter:distZip
```

## Architecture wiring

Spring XML context chain:
- `DeviceAdapterContext.xml` → imports `RtConfig.xml` (DDS participant, subscriber, publisher, event loop)
- `IceAppContainerContext.xml` → imports `RtConfig.xml`, adds FX observable lists, data writers, EMR facade
- `DriverContext.xml` → per-device driver, executor, partition assignment, TimeManager, JMX

Property placeholder `${mdpnp.domain}` must be supplied (default `0` in `ice.properties` or via `-Dmdpnp.domain=N`).

Settings persist to `.JumpStartSettings` in current dir or `$HOME/.JumpStartSettings` — the file is loaded on GUI startup to restore last config.

## Config files

- `ice.properties` (classpath) — `mdpnp.domain`, `mdpnp.fhir.url`, `dds.discovery.peers`
- `ice.system.properties` (classpath + cwd override) — loaded by `Main.loadSystemProps()` before any app logic
- `log4j2-test.xml` — logs to `~/demo-apps.log`, `~/easy-tiva.log`

## Uncommitted in-progress work

- `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/numericviewer/` — new numeric viewer app
- `META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider` — modified to register the viewer
- `data-types/x73-idl/src/main/idl/ice/ice.py` — Python bindings generated from IDL
