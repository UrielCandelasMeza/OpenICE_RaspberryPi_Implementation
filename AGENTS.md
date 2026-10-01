# AGENTS.md — OpenICE / MD PnP

Version `1.5.1-SNAPSHOT` (Gradle 9.0.0, Java 25, JavaFX 25 in `demo-apps`). **No lint or formatter** — verify with compile + tests only.

`CLAUDE.md` has fuller walkthroughs; this file wins on conflicts. Where code conflicts with either, trust code.

## Build Prerequisites

- **7 non-Maven artifacts required in `artifacts/`** (gitignored): `nddsjava.jar`, `pixelmed.jar`, `Utility-0.0.1.jar`, `mdpnp-sounds-0.1.0.jar`, `rtiddsgen2.jar`, `rtiusagemetrics-api.jar`, `cpp-bin-1.2.8-SNAPSHOT.zip`. Referenced via `files(...)`; missing causes build failure.
- **`RTI_LICENSE_FILE`** must be set. Gradle sets it for `test`/`run`/`runDevice` (demo-apps) and `run` (headless-adapter). If running outside Gradle, export it.
- **`LD_LIBRARY_PATH`** must include `interop-lab/demo-apps/native/libs/linux` (or platform variant).
- **`SEC_ARTIFACT_DIR`** needed for tests; Gradle sets it in test/run blocks. `DOCBOX_RTPS_*`/`COORDINATOR_DOMAIN_ID` are Windows-only and only in `run` (not `test`).
- RTI codegen is automatic (`compileJava.dependsOn iceddsgenJava`). Only run `:data-types:x73-idl-rti-dds:rtiddsgenExplodeResources` if native RTI resources look broken. `iceddsgenJava/Python` regenerate DDS types from `data-types/x73-idl/src/main/idl/ice/ice.idl`.
- `:setupLocalDb` exists but nothing depends on it (DB removed); `build`-dependsOn hook is commented. Do not reintroduce DB connectivity.

## CI

`.github/workflows/gradle.yml` is stale (JDK 8 on Windows, branch master, `continue-on-error`). Treat local `./gradlew build` as source of truth.

## Modules & Entry Points

| Module | Role |
|---|---|
| `interop-lab/demo-apps` | Supervisor JavaFX GUI. `org.mdpnp.apps.testapp.Main` |
| `headless-adapter` | No-JavaFX device runtime. `org.mdpnp.headless.HeadlessMain` |
| `interop-lab/demo-devices` | Device framework + Spring XML (`RtConfig.xml`, `DriverContext.xml`, `DeviceAdapterContext.xml`) |
| `devices/*` | Protocol implementations (12 subprojects) |
| `data-types/x73-idl` | IEEE 11073 IDL + DDS QoS profiles |
| `data-types/x73-idl-rti-dds` | RTI-generated Java types (built, not authored) |

- `Main` defaults to GUI; headless when `-domain -app -device` are passed (GUI also accepts `-device`/`-domain` forms).
- `HeadlessMain`: `-domain -device [-address <serial|ip>] [-baud <rate>] [-peers <hosts>]`. No `-app`. `-baud` sets `mdpnp.serial.baudrate` (default 9600); only `MasimoRadical7` reads it today.

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

## ServiceLoader SPI — Dual Registration Required

Two separate `DeviceDriverProvider` registries exist:
- `interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` (points to GUI inner classes in `demo-apps`)
- `headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` (points to headless factory classes)

**A new device driver must be registered in BOTH.** They are not interchangeable; headless runs without JavaFX/demo-apps. `IceApplicationProvider` lives at `interop-lab/demo-apps/src/main/resources/META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider`.

## Adding Devices (Critical)

**Before writing any driver code:**
1. Read protocol docs in `docs/manuals/` (Dräger MEDIBUS, Philips, etc.)
2. Write `ASSUMPTIONS.md` next to the driver: **Parameter / Source (section/page) / Risk**. Mark undocumented as "not specified". Flag ambiguities.
3. **Wait for explicit user confirmation** before coding.

Assumptions must cover units (scaling), byte order, signed/unsigned, polling, connection loss behavior, defaults for absent/zero fields.

**Supervisor panel**: add to `DevicePanelFactory.PANELS` and implement `public static boolean supported(Set<String> tags)`.
**ICE app**: receives `DeviceListModel`, `NumericFxList`, `AlertFxList`, `EventLoop` via Spring; do not open DDS connections directly. Register with `IceApplicationProvider` SPI.

## Field Scripts — `scripts/` (Not Gradle)

`scripts/` are standalone prototyping tools (`javac`/`python3`), not Gradle subprojects. `scripts/DragerAtlanHandshake.java` is RS-232 reverse engineering for Dräger Atlan A-350XL (committed `.class` files may be stale). `simula_massimo.py` replays captured logs. **No real Atlan driver exists yet** — SPI points to simulated `SimDraegerAtlan`. Do not assume hardware Atlan support.

## Deployment & Runtime Highlights

- JavaFX needs `--add-exports` (6 flags) + `--enable-native-access=ALL-UNNAMED` in exec tasks and patched into start scripts (copy if adding new exec tasks).
- Spring: `DeviceAdapterContext.xml`/`IceAppContainerContext.xml` import `RtConfig.xml`; `DeviceAdapterContext.xml` reads classpath `ice.properties` then `${user.dir}/ice.properties` override. `ice.system.properties` enforces `java.net.preferIPv4Stack=true`.
- DDS: domain `${mdpnp.domain}` (default 0), multicast 239.255.0.1, UDPv4 only, shared memory off, `accept_unknown_peers: true`. Peers via `dds.discovery.peers`.
- Settings persist to `.JumpStartSettings`. Tests log to `~/demo-apps.log`, `~/easy-tiva.log` (log4j2-test.xml).

## Testing

JUnit 4 via JUnit 5 Vintage; `useJUnitPlatform()`, timezone EST. `modularity.inferModulePath.set(false)` in `demo-apps`. No lint/typecheck — `./gradlew build` is the gate.

## FHIR / HL7 (Critical)

DDS Numeric → ValidationOracle → sendFHIR(): create Patient/Device in HAPI (8099), authorize Patient in List, then Transaction POST to gateway (8080) with bearer token. **Gateway (8080)** enforces list access; **HAPI (8099)** is unauthenticated. Device written to 8099 is outside patient compartment — gateway rejects. Authorized List id: `mdpnp.fhir.list.auth` (default `patient-list-example` in code; committed `ice.properties` uses `lista-dr-gomez`). `FhirEMRImpl` client is intentionally unauthenticated. Two emitters: `FhirEmitter` (FHIR R4) and `HL7v26Emitter`/`HL7Emitter`.

## No Database

Zero PostgreSQL/TimescaleDB connectivity in core paths. Old DB flags/classes removed; no JDBC pipeline. Do not reintroduce.

## MQTT Bridge

`org.mdpnp.apps.testapp.mqtt` republishes to `openice/{device_udi}/{metric_id}` on Mosquitto (auth required). Subscribe with `openice/#`.

## DDS Topics

Subscribe/publish via RTI profiles (`QosProfiles.ice_library/state`) using generated types. Adding to `ice.idl` requires `@key` on primary keys; codegen runs automatically.

## Docs Style

`docs/` (Spanish): follow required header skeleton with closing `*... generado a partir de ...*` line. Reports in `docs/reports/`, use cases in `docs/usecases/`, architecture in `docs/architecture/`. See full rules in existing docs if writing extensively.
