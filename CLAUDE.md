# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System

This project uses **Gradle** (not Maven). Use the included wrapper:

```bash
# Build entire project
./gradlew build

# Run the main OpenICE application (JavaFX GUI)
./gradlew :interop-lab:demo-apps:run

# Build distribution ZIP
./gradlew :interop-lab:demo-apps:distZip
```

## Testing

```bash
# Run all tests in a module
./gradlew :interop-lab:demo-apps:test

# Run a specific test class
./gradlew :interop-lab:demo-apps:test --tests org.mdpnp.apps.testapp.DeviceFactoryTest

# Run a specific test method
./gradlew :interop-lab:demo-apps:test --tests org.mdpnp.apps.testapp.DeviceFactoryTest.testLocateDrivers

# Other modules follow the same pattern, e.g.:
./gradlew :interop-lab:demo-devices:test --tests org.mdpnp.devices.RtConfigTest
```

Test framework is JUnit 4 (run via JUnit 5 vintage engine). Tests run with timezone forced to EST.

## Prerequisites & Artifacts

Several pre-built JARs are required in the `artifacts/` directory — these are not in a public Maven repo:
- `artifacts/nddsjava.jar` — RTI DDS Java bindings (requires RTI license)
- `artifacts/pixelmed.jar` — DICOM library
- `artifacts/Utility-0.0.1.jar`, `artifacts/mdpnp-sounds-0.1.0.jar`

For RTI DDS, set `RTI_LICENSE_FILE=/path/to/OpenICE_license.dat`.

Optional OpenSplice DDS support requires `OSPL_HOME` and `SPLICE_TARGET` env vars.

## Architecture Overview

### Module Layout

```
data-types/x73-idl/          # IEEE x73 IDL type definitions (medical device data model)
data-types/x73-idl-rti-dds/  # RTI DDS code-generated Java types from IDL
devices/                     # 40+ device driver implementations (Philips, Dräger, GE, etc.)
interop-lab/demo-devices/    # Device adapter framework + Spring XML config
interop-lab/demo-apps/       # Main JavaFX application (entry point)
interop-lab/demo-guis/       # Platform-agnostic GUI components
interop-lab/demo-guis-javafx/ # JavaFX bindings for GUI components
```

### Core Architecture

**Entry point:** `org.mdpnp.apps.testapp.Main`

The application runs in two modes:
- **GUI mode** (default): JavaFX, loads `.JumpStartSettings` config from current dir or home dir
- **Headless mode**: pass `--domain`, `--app`, `--device` args

**DDS Middleware (RTI DDS)** is the communication backbone — all medical device data flows through DDS topics defined in the x73 IDL. The Spring XML config at `interop-lab/demo-devices/src/main/resources/RtConfig.xml` sets up the DDS DomainParticipant, Publisher, and Subscriber.

**Spring IoC** wires together the application. Key Spring XML files:
- `RtConfig.xml` — DDS infrastructure
- `DeviceAdapterContext.xml` — device adapter lifecycle
- `IceAppContainerContext.xml` — main app container with data writer factories
- `DriverContext.xml` — per-device driver initialization

**Device plugin system:** Device drivers implement `DeviceDriverProvider` and are discovered via `java.util.ServiceLoader`. The registry is in `META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider` (note: this file is modified in current working state).

**Application plugin system:** The 30+ ICE applications (charting, ECG, PCA safety, OpenEMR integration, etc.) each implement `IceApplicationProvider` and are similarly discovered via ServiceLoader.

**JavaFX data binding:** Medical data objects (NumericFx, SampleArrayFx, AlertFx) are observable FxBeans wrappers around DDS-received data, used to drive live UI updates.

### Key Packages

| Package | Role |
|---|---|
| `org.mdpnp.apps.testapp` | Main app, device factory, app container |
| `org.mdpnp.devices` | AbstractDevice base class, device protocol implementations |
| `org.mdpnp.rtiapi.data` | DDS data model (Numeric, SampleArray, Alert, etc.) |
| `org.mdpnp.apps.testapp.pca` | PCA (infusion pump safety) application |
| `org.mdpnp.apps.testapp.chart` | Waveform charting application |

## Development Patterns

### Creating a New ICE Application

1. **App controller** (`MyApp.java`) — implement a `start(EventLoop eventLoop, Subscriber subscriber)` method. Do not create DDS connections directly; receive shared OpenICE resources via Spring injection:
   - `DeviceListModel` — observable list of connected devices
   - `NumericFxList` — observable list of real-time numeric data (HR, SpO2, etc.)
   - `AlertFxList` — observable list of active alarms
   - `EventLoop` — attach DDS `ConditionHandler` callbacks on the correct thread

2. **App factory** (`MyAppFactory.java`) — implement `org.mdpnp.apps.testapp.IceApplicationProvider`. The `create(ApplicationContext parentContext)` method pulls shared Spring beans, loads the `.fxml` file, and injects dependencies into the controller.

3. **Register** — add the factory's fully qualified class name to:
   `interop-lab/demo-apps/src/main/resources/META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider`

### Creating a New Simulated Device

1. **Device class** — extend `AbstractSimulatedConnectedDevice` (or `AbstractSimulatedDevice`). In the constructor:
   - Set `deviceIdentity.manufacturer` and `deviceIdentity.model`
   - Call `createNumericInstance(metric_id, vendor_metric_id)` to register published parameters
   - Use a timer/thread to periodically call `numericSample(...)` to publish values

2. **Register in DeviceFactory** (`interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/DeviceFactory.java`) — add a static inner class extending `SpringLoadedDriver`:
   - `getDeviceType()` → return a `DeviceType` with `ice.ConnectionType.Simulated`
   - `newInstance()` → instantiate your device class

3. **Register the provider** — add the inner class's fully qualified name to:
   `interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider`

### Reading and Writing Custom DDS Topics

When adding a new struct to `ice.idl`, annotate primary key fields with `@key` and assign a topic string constant. RTI auto-generates `MyTopic`, `MyTopicDataReader`, and `MyTopicDataWriter` during the Gradle build.

**To subscribe:**
1. `ice.MyTopicTypeSupport.register_type(participant, ...)`
2. `TopicUtil.findOrCreateTopic(participant, ice.MyTopicTopic.VALUE, ...)`
3. `subscriber.create_datareader_with_profile(...)` using `QosProfiles.ice_library` + `QosProfiles.state` (or `QosProfiles.observed_data`)
4. Create a `ReadCondition` and attach a `ConditionHandler` via `EventLoop`

**To publish:**
1. Register type and create topic as above
2. `publisher.create_datawriter_with_profile(...)` with the same QoS profile
3. Populate your generated struct and call `dataWriter.write(msg, InstanceHandle_t.HANDLE_NIL)`

### DDS Environment Variables

In addition to `RTI_LICENSE_FILE`, the DDS QoS profiles in `RtConfig.xml` may require:
- `DOCBOX_RTPS_HOST_ID` — identifies the DDS host
- `DOCBOX_RTPS_APP_ID` — identifies the DDS application instance

### FHIR R4 Auth (HL7 Emitter only)

OAuth2 bearer tokens (`TokenProvider`, props `mdpnp.fhir.token.*` in `ice.properties`) are used **only** by the HL7 FHIR R4 emitter (`org.mdpnp.apps.testapp.hl7.HL7Emitter`) to add `Authorization: Bearer` headers. The EMR app (`FhirEMRImpl`, `org.mdpnp.apps.testapp.patient`) does **not** use the token — its FHIR client is unauthenticated. Do not attach tokens there.

### FHIR Gateway Architecture

OpenICE sends FHIR R4 observations through a **Google FHIR Info Gateway** with Keycloak + ListAccessChecker:

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
- The `HL7Emitter.addToAuthorizedList()` method automatically adds newly created Patients to this List via `backendClient` (8099, no auth).
- `Device` resources are written directly to the backend (8099) because Device is NOT in the patient compartment (`CompartmentDefinition-patient.json` has Device with no params, and `patient_paths.json` has no Device entry).

### HL7 Emitter Patient Selection

The HL7 Exporter app has a **ComboBox** (`patientCombo`) that lists patients from the local EMR database (HSQLDB via `EMRFacade`). When a patient is selected:

1. `HL7Application.patientCombo.setOnAction()` calls `model.setSelectedPatientMRN(mrn)`
2. `HL7Emitter.fhirObservation()` uses `selectedPatientMRN` as the primary MRN source
3. `getPatientResource(mrn)` searches HAPI via `backendClient` (8099) by `patientIdentifierSystem` + MRN
4. If not found, creates the Patient in HAPI via conditional PUT, then adds it to `patient-list-example` via `addToAuthorizedList()`
5. Observations are sent as a transaction bundle to the gateway (8080) with `Authorization: Bearer` header

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

### Key FHIR Files

| File | Role |
|---|---|
| `HL7Emitter.java` | Core emission logic: Patient/Device creation, Observation bundling, gateway submission |
| `HL7Application.java` | JavaFX controller: patient ComboBox, Start/Stop button, frequency slider |
| `HL7Application.fxml` | FXML layout with ComboBox, host/port fields, protocol radio buttons |
| `HL7ApplicationFactory.java` | Spring factory: wires `EMRFacade`, `ValidationOracle`, `FhirContext` into emitter |
| `TokenProvider.java` | OAuth2 token fetch from Keycloak (5 min TTL, `setToken()` for force-refresh) |
| `FhirEMRImpl.java` | FHIR-based EMR: creates patients in HAPI with OID system `urn:oid:2.16.840.1.113883.3.1974` |
| `EMRFacade.java` | Abstract EMR facade, `fetchAllPatients()` returns patients from HSQLDB |

### ice.properties (FHIR-related)

```properties
mdpnp.fhir.url=                              # EMR FHIR URL (empty = JDBC-only EMR)
mdpnp.fhir.backend.url=http://localhost:8099/fhir   # HAPI backend (no auth)
mdpnp.fhir.token.url=http://localhost:9080/auth/realms/test/protocol/openid-connect/token
mdpnp.fhir.token.user=testuser
mdpnp.fhir.token.password=testpass
mdpnp.fhir.token.clientId=my-fhir-client
mdpnp.fhir.patient.identifier.system=urn:oid:2.16.840.1.113883.3.1974  # OID for patient MRN lookup
```

## Medical Device Driver Requirements

These rules apply whenever writing or modifying a device driver (any class under `org.mdpnp.devices`).

### Mandatory assumptions review before coding

Before writing any device driver code, you MUST:

1. Read all provided protocol documentation in full.
2. Produce a file `ASSUMPTIONS.md` in the driver's source directory listing every assumption being made, structured as:
   - **Parameter** — the value or behaviour assumed
   - **Source** — where in the spec this comes from (section/page), or "not specified" if absent
   - **Risk** — what would go wrong if the assumption is incorrect
3. Flag every point where the spec is ambiguous, silent, or contradicted.
4. Wait for explicit user confirmation (`confirmed` or equivalent) before writing any code.

The `ASSUMPTIONS.md` file must be committed alongside the driver and updated if assumptions change.

### Examples of assumptions that must always be declared

- Units for any numeric value (mL/hr vs mL/min, 0.1x scaling, etc.)
- Byte order / endianness
- Timing and polling intervals
- Behaviour on connection loss or device error
- Whether a field is signed or unsigned
- Default values used when a field is absent or zero

### In-Progress Work (Uncommitted)

- `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/numericviewer/` — new numeric viewer application
- Modified `META-INF/services/org.mdpnp.apps.testapp.IceApplicationProvider` — likely registering the new viewer
- `data-types/x73-idl/src/main/idl/ice/ice.py` and variants — Python bindings generated from IDL
