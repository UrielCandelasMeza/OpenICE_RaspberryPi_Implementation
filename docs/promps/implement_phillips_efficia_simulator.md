# Prompt: Implement Philips Efficia CM Series Monitor Simulator

## Objective

Create a simulated device that emulates a **Philips Efficia CM Series** monitor using HL7 v2.4 over MLLP (TCP). The simulator must periodically generate clinical data, publish it to DDS for OpenICE integration, expose a TCP server for external hosts to connect and receive HL7 ORU messages, plus a UDP channel to receive control instructions.

## Technical Context

The real Efficia CM Series exports data using HL7 v2.4 with these characteristics:

- **Transport**: MLLP over TCP/IP (LAN) or RS-232 (Serial)
- **MLLP Format**: `<SB>0x0B` message `<EB>0x1C` `<CR>0x0D`
- **HL7 Delimiters**: `|` field, `^` component, `&` subcomponent, `~` repetition, `\` escape
- **LAN Mode**: Unsolicited push — monitor sends ORU and expects ACK
- **Frequency**: Every 60s trends + aperiodic for alarms/completed NBP
- **Retries**: 3 retries if no ACK received, then wait for next cycle
- **Keep-alive**: Empty ORU (MSH only) at startup and every 1 hour
- **Serial Mode**: Query/Response — Host sends QRY, monitor responds with ORF
- **MDIL Codes**: Format `<Partition>-<TermCode>`, e.g. `0002-4182` for HR
- **Key Partitions**: `0x0002` (SCADA), `0x0004` (units)
- **OBX Alarms**: TX format with `MDIL-ALERT` system, priorities 1=Red, 2=Yellow, 6=Soft Inop

## Simulator Architecture

### File locations

```
interop-lab/demo-devices/src/main/java/org/mdpnp/devices/philips/efficia/
├── SimEfficiaMonitor.java            ← Device driver (AbstractSimulatedConnectedDevice)
└── EfficiaClinicalEngine.java        ← Clinical simulation logic + HL7 engine
```

### SPI Registration

Both files must be updated:

- **Headless**: `headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider`
  - Line to add: `org.mdpnp.headless.HeadlessDeviceFactory$EfficiaMonitorProvider`

- **GUI**: `interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider`
  - Line to add: `org.mdpnp.apps.testapp.DeviceFactory$EfficiaMonitorProvider`

### Provider in HeadlessDeviceFactory.java

Add inner class:

```java
public static class EfficiaMonitorProvider extends SpringLoadedDriver {
    @Override
    public DeviceType getDeviceType() {
        return new DeviceType(
            ice.ConnectionType.Network,
            "Philips",
            "Efficia CM Series",
            "EfficiaMonitor",
            1
        );
    }

    @Override
    public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
        return new SimEfficiaMonitor(
            ctx.getBean("subscriber", Subscriber.class),
            ctx.getBean("publisher", Publisher.class),
            ctx.getBean("eventLoop", EventLoop.class)
        );
    }
}
```

### Provider in DeviceFactory.java (GUI)

Same content but inside `DeviceFactory` instead of `HeadlessDeviceFactory`.

### Dependencies

In `interop-lab/demo-devices/build.gradle`, verify the HAPI dependency is present (it already exists in demo-apps but demo-devices may need it):

```groovy
implementation group: 'ca.uhn.hapi', name: 'hapi-base', version: '2.2'
implementation group: 'ca.uhn.hapi', name: 'hapi-structures-v26', version: '2.2'
```

## SimEfficiaMonitor.java Implementation

### Base class

Extend `AbstractSimulatedConnectedDevice` (like `SimControllablePump`).

The class must:
1. Register the device type: `deviceIdentity.manufacturer = "Philips"`, `deviceIdentity.model = "Efficia CM Series"`
2. Implement `connect()` and `disconnect()`
3. Start the clinical simulation engine + HL7 TCP server + UDP listener

### Constructor structure

```java
public class SimEfficiaMonitor extends AbstractSimulatedConnectedDevice {

    private EfficiaClinicalEngine clinicalEngine;
    private int hl7Port = 2575;       // Default Efficia real port
    private int udpPort = 24106;      // UDP control port

    public SimEfficiaMonitor(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
        super(subscriber, publisher, eventLoop);
    }

    @Override
    public void writeDeviceIdentity() {
        deviceIdentity.manufacturer = "Philips";
        deviceIdentity.model = "Efficia CM Series";
        super.writeDeviceIdentity();
    }

    @Override
    public ConnectionType getConnectionType() {
        // Use Simulated because this is a simulator (no real hardware needed)
        return ice.ConnectionType.Simulated;
    }
}
```

### Connect

```java
@Override
public boolean connect(String address) {
    if (!super.connect(address)) return false;

    // Start clinical engine
    clinicalEngine = new EfficiaClinicalEngine(this, executor);
    clinicalEngine.start();

    log.info("Efficia Monitor simulator started on HL7 port {} and UDP port {}", hl7Port, udpPort);
    return true;
}
```

### Disconnect

```java
@Override
public void disconnect() {
    if (clinicalEngine != null) {
        clinicalEngine.stop();
        clinicalEngine = null;
    }
    super.disconnect();
}
```

## EfficiaClinicalEngine.java Implementation

### Responsibilities

1. **Generate clinical data**: Maintain simulated patient state with values for HR, SpO2, NIBP, RESP, Temp
2. **HL7 Engine**: Build ORU messages in HL7 v2.4 format using MLLP
3. **TCP Server**: Accept connections from external hosts and push ORU every 60s
4. **UDP Listener**: Receive commands to change values, trigger alarms, trigger NBP
5. **Publish to DDS**: Convert simulated data to ice.Numeric and publish

### Structure

```java
public class EfficiaClinicalEngine {

    private final AbstractDevice device;
    private final ScheduledExecutorService executor;
    private ServerSocket tcpServer;
    private DatagramSocket udpSocket;
    private List<Socket> clientConnections = new ArrayList<>();
    private volatile boolean running;

    // Simulated patient state
    private final PatientState state = new PatientState();

    static class PatientState {
        volatile int heartRate = 72;
        volatile int spo2 = 98;
        volatile int respRate = 14;
        volatile int nibpSystolic = 120;
        volatile int nibpDiastolic = 80;
        volatile float temperature = 36.8f;
        volatile String alarmType;      // null if no active alarm
        volatile int alarmPriority;     // 1=High, 2=Medium, 6=Low
    }
}
```

### MDIL Dictionary

A mapping from MDIL codes to OpenICE metrics is needed:

| Metric | MDIL Code | MDC Metric ID | Unit |
|--------|-----------|---------------|------|
| Heart Rate | `0002-4182` | `MDC_ECG_HEART_RATE` | bpm |
| SpO2 | `0002-4190` | `MDC_PULS_OXIM_SAT_O2` | % |
| Resp Rate | `0002-4184` | `MDC_CO2_RESP_RATE` | rpm |
| NBP Systolic | `0002-4186` | `MDC_PRESS_BLD_NONINV_SYS` | mmHg |
| NBP Diastolic | `0002-4187` | `MDC_PRESS_BLD_NONINV_DIA` | mmHg |
| Temperature | `0002-4188` | `MDC_TEMP_BLD` | Cel |

The mapping should be in a resource file (`efficia.map`) or a static Map.

### ORU Generation (HL7 Builder)

Every 60 seconds, build an ORU^R01 message with:

```
MSH|^~\&|EfficiaSim|Philips|HostSystem|OpenICE|20260101000000||ORU^R01|MSG00001|P|2.4
PID|||PAT001^^^^MRN||Doe^John^^^^^L||19800101|M
PV1|||ICU_BED_01||||||||||||||||||||||||||||||||||||||||||||||||||
ORC|RE||||||||||||||||||||||||||||||||||||||||||||||||||||||||||
OBR|1|||0000-0000^General^MDIL|||||||||||||||||||||||||||||||||||||||||||||||||||
OBX|1|NM|0002-4182^HR^MDIL||72|0004-0aa0^bpm^MDIL|||||F
OBX|2|NM|0002-4190^SpO2^MDIL||98|0004-0aa0^%^MDIL|||||F
OBX|3|NM|0002-4184^RR^MDIL||14|0004-0aa0^rpm^MDIL|||||F
```

Steps to build the message:
1. Use HAPI `PipeParser` to build the message programmatically
2. Populate MSH with encoding characters, sending application, timestamp, message control ID
3. Add PID, PV1, ORC, OBR segments
4. For each metric, add an OBX segment with MDIL format
5. Encode to string and wrap in MLLP: `\x0B` + message + `\x1C\x0D`
6. Send to all connected TCP clients

### Publishing to DDS

Using the same pattern as `SimControllablePump`:

```java
// Create InstanceHolder for each metric
final InstanceHolder<Numeric> hrHolder = createNumericInstance(rosetta.MDC_ECG_HEART_RATE.VALUE, "");

// On each simulation tick (1s for trends)
numericSample(hrHolder, state.heartRate, clock.instant());
```

Data is published to DDS on the `ice.Numeric` topic so `TimescalePersister` automatically persists it in `vital_values`.

### TCP Server (HL7 MLLP)

```java
private void startTcpServer() {
    Thread t = new Thread(() -> {
        try (ServerSocket server = new ServerSocket(hl7Port)) {
            while (running) {
                Socket client = server.accept();
                synchronized (clientConnections) {
                    clientConnections.add(client);
                }
                // For simplicity, ORU messages are written to all clients
            }
        } catch (IOException e) {
            if (running) log.error("TCP server error", e);
        }
    }, "efficia-tcp");
    t.setDaemon(true);
    t.start();
}
```

### UDP Listener (Control)

Receive commands to manipulate the simulated patient state:

```java
private void startUdpListener() {
    Thread t = new Thread(() -> {
        try (DatagramSocket socket = new DatagramSocket(udpPort)) {
            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String cmd = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                processCommand(cmd);
            }
        } catch (IOException e) {
            if (running) log.error("UDP listener error", e);
        }
    }, "efficia-udp");
    t.setDaemon(true);
    t.start();
}
```

Supported UDP commands:
- `HR=<value>` — set heart rate
- `SPO2=<value>` — set SpO2
- `NIBP=<sys>/<dia>` — trigger NBP measurement with result
- `ALARM=<type>,<priority>` — trigger alarm (e.g. `ARRHYTHMIA`, priority 1/2/6)
- `ALARM_CLEAR` — clear active alarm
- `STATUS` — query current patient state (responds via UDP)

### Alarm Handling

When an alarm command is received or a critical value is detected:
1. Build additional OBX with `TX` type and `MDIL-ALERT` system
2. Include in the next ORU push
3. Publish to DDS as `ice.PatientAlert` using `writeAlert()` from AbstractDevice
4. If `ALARM_CLEAR` is received, remove the active alarm

```java
// Publish alert to DDS (reuses AbstractDevice.alert)
Alert alert = new Alert();
alert.unique_device_identifier = deviceIdentity.unique_device_identifier;
alert.identifier = "MDIL-ALERT_" + alarmType;
alert.text = alarmType + " priority " + alarmPriority;
writeAlert(alert, null);
```

### Keep-alive

At startup and every 1 hour, send an empty ORU (MSH only) for clock sync:

```java
private void sendKeepAlive() {
    Message keepAlive = parser.init();
    MSH msh = keepAlive.getMSH();
    msh.getDateTimeOfMessage().setValue(getCurrentTimestamp());
    msh.getMessageControlID().setValue("KA_" + System.currentTimeMillis());
    // No PID, no OBX — MSH only
    broadcastMllp(encode(keepAlive));
}
```

## Files to Create/Modify Summary

| File | Action |
|------|--------|
| `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/philips/efficia/SimEfficiaMonitor.java` | **CREATE** — device driver |
| `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/philips/efficia/EfficiaClinicalEngine.java` | **CREATE** — clinical engine, HL7, TCP, UDP |
| `headless-adapter/src/main/java/org/mdpnp/headless/HeadlessDeviceFactory.java` | **MODIFY** — add `EfficiaMonitorProvider` |
| `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/testapp/DeviceFactory.java` | **MODIFY** — add `EfficiaMonitorProvider` |
| `headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` | **MODIFY** — add provider line |
| `interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider` | **MODIFY** — add provider line |
| `interop-lab/demo-devices/build.gradle` | **VERIFY** HAPI dependency present |
| `docs/promps/implement_phillips_efficia_simulator.md` | This file |

## Usage

```bash
# Headless
./gradlew :headless-adapter:run -x setupLocalDb \
  -PJAVA_VERSION_SOURCE=17 -PJAVA_VERSION_CLASSES=17 \
  --args="-domain 0 -device EfficiaMonitor"

# Connect external host to HL7 MLLP on port 2575
# Send UDP commands to port 24106:
echo "HR=120" > /dev/udp/127.0.0.1/24106
echo "ALARM=ASYSTOLE,1" > /dev/udp/127.0.0.1/24106
```
