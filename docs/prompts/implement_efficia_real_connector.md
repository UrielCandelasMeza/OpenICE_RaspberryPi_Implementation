# Prompt: Implement Philips Efficia CM — Real Connector (HL7 → DDS)

## Objetivo

Crear un driver de dispositivo real que conecte con un **Philips Efficia CM Series** monitor por LAN usando HL7 v2.4 sobre MLLP (TCP). El driver actúa como servidor MLLP, recibe mensajes ORU^R01, los parsea y publica los datos como DDS Numeric en OpenICE.

## Contexto Técnico

### Protocolo del Efficia real

- **Transporte**: MLLP sobre TCP/IP (LAN) — el Efficia es el CLIENTE, nuestro driver es el SERVIDOR
- **Puerto**: 4202 (configurable via `efficia.real.hl7.port`)
- **Formato MLLP**: `<SB>0x0B` + mensaje + `<EB>0x1C` + `<CR>0x0D`
- **HL7 versión**: v2.4 (identificado en MSH-12)
- **Mensajes**: ORU^R01 (unsolicited push) — datos cada ~60s, alarmas aperiódicas
- **ACK**: Responder con MSA|AA|<messageControlId> para cada ORU recibido
- **Códigos MDIL**: Formato `Partition-TermCode`, ej. `0002-4182` para HR

### Datos del Efficia (ejemplo real)

```
MSH|^~\&|^EfficiaCM||||20130103021726||ORU^R01^ORU_R01|CN92390246...|P|2.4
PID|||1234||^Erick^
PV1|||^^|||||||||||||||A|1234
ORC|NW|||||||||||||||||CN92390246
OBR||||MONITOR|||20130103021700|||
OBX||NM|0002-4182^HR^MDIL||60|0004-0aa0^bpm^MDIL|||||F
OBX||NM|0002-4bb8^SpO2^MDIL||96|0004-0220^%^MDIL|||||F
OBX||NM|0002-5000^Resp^MDIL||20|0004-0ae0^rpm^MDIL|||||F
OBX||NM|0002-480a^Pulse^MDIL||59|0004-0aa0^bpm^MDIL|||||F
```

### Mapeo MDIL → DDS

| OBX Code | Nombre | MDC Metric | Unidad |
|----------|--------|------------|--------|
| `0002-4182` | Heart Rate | `MDC_ECG_HEART_RATE` | bpm |
| `0002-4bb8` | SpO2 | `MDC_PULS_OXIM_SAT_O2` | % |
| `0002-5000` | Resp Rate | `MDC_CO2_RESP_RATE` | rpm |
| `0002-480a` | Pulse | `MDC_PULS_OXIM_PULS_RATE` | bpm |
| `0002-4bb0` | Perfusion Index | (nuevo o `MDC_PERFUSION_INDEX`) | dimless |
| `0002-0302` | ST-II | (waveform/alarm) | mm |
| `0002-4261` | PVC | (alarm) | /min |

### Alarmas (TODO — por implementar después)

Las alarmas llegan como OBX con data type `TX` cuando OBR-4 = `ALARM`:
```
OBR||||ALARM|||20130103020458|||
OBX||TX|2^Yellow Alarm^MDIL-ALERT||SpO₂ baja, SpO₂ = 80 %||||||F
```

Mapeo de prioridades:
- `1` = Red Alarm (HIGH)
- `2` = Yellow Alarm (MEDIUM)
- `6` = Soft Inop (LOW)

## Arquitectura

### Diagrama de clases

Ver `docs/class_diagrams/efficia_module.puml`

### Diagrama de secuencia

Ver `docs/diagrams/sequence/phillips/seq_efficia_to_supervisor.puml`

### Dependencias

Agregar a `interop-lab/demo-devices/build.gradle`:

```groovy
implementation group: 'ca.uhn.hapi', name: 'hapi-base', version: '2.2'
implementation group: 'ca.uhn.hapi', name: 'hapi-structures-v24', version: '2.2'
```

> IMPORTANTE: NO agregar a `demo-apps/build.gradle` — las dependencias no se combinan entre módulos.

### Archivos a crear

| # | Archivo | Descripción |
|---|---------|-------------|
| 1 | `devices/hl7/Hl7Service.java` | Servicio general HAPI (Spring @Component) |
| 2 | `philips/efficia/EfficiaData.java` | POJO con enums AlarmType, AlarmPriority, ObservationStatus |
| 3 | `philips/efficia/EfficiaHL7Parser.java` | Parser de ORU^R01 específico del Efficia |
| 4 | `philips/efficia/Efficia.java` | Driver principal (extends AbstractConnectedDevice) |

### Archivos a modificar

| # | Archivo | Cambio |
|---|---------|--------|
| 1 | `demo-devices/build.gradle` | Agregar dependencias HAPI HL7 v2.4 |
| 2 | `DeviceFactory.java` | Agregar `EfficiaProvider` (ConnectionType.Network) |
| 3 | `HeadlessDeviceFactory.java` | Agregar `EfficiaProvider` (ConnectionType.Network) |
| 4 | SPI demo-devices | Agregar línea `DeviceFactory$EfficiaProvider` |
| 5 | SPI headless-adapter | Agregar línea `HeadlessDeviceFactory$EfficiaProvider` |

### Archivos a renombrar

| # | De | A |
|---|----|----|
| 1 | `SimEfficiaMonitor.java` | `SimulatedEfficia.java` |
| 2 | `EfficiaClinicalEngine.java` | `SimulatedEfficiaClinicalEngine.java` |
| 3 | `EfficiaMonitorProvider` (clases) | `SimulatedEfficiaProvider` |
| 4 | alias `"EfficiaMonitor"` | `"SimulatedEfficia"` |

## Implementación paso a paso

### Paso 1: Hl7Service.java

```java
package org.mdpnp.devices.hl7;

import ca.uhn.hl7v2.*;
import ca.uhn.hl7v2.app.*;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v24.message.*;
import ca.uhn.hl7v2.parser.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class Hl7Service {

    private HapiContext context;

    @PostConstruct
    public void init() {
        context = new DefaultHapiContext();
        // Configurar parser para HL7 v2.4
        Parser parser = context.getPipeParser();
        parser.getParserConfiguration().setValidating(false);
    }

    public SimpleServer createServer(int port, Application handler) throws HL7Exception {
        return new SimpleServer(context, port, handler);
    }

    public Message parse(String rawMessage) throws HL7Exception, EncodingNotSupportedException {
        return context.getPipeParser().parse(rawMessage);
    }

    public String encode(Message msg) throws HL7Exception {
        return context.getPipeParser().encode(msg);
    }

    public ORU_R01 castToORU(Message msg) {
        return (ORU_R01) msg;
    }

    public Message generateACK(Message msg) throws HL7Exception {
        return msg.generateACK();
    }

    @PreDestroy
    public void shutdown() {
        if (context != null) {
            context.close();
        }
    }
}
```

### Paso 2: EfficiaData.java

```java
package org.mdpnp.devices.philips.efficia;

public class EfficiaData {

    public enum AlarmType {
        NONE,
        YELLOW_ALARM,
        RED_ALARM,
        SOFT_INOP,
        ALARM_CLEAR
    }

    public enum AlarmPriority {
        HIGH(1),
        MEDIUM(2),
        LOW(6);

        private final int code;
        AlarmPriority(int code) { this.code = code; }
        public int getCode() { return code; }

        public static AlarmPriority fromCode(int code) {
            for (AlarmPriority p : values()) {
                if (p.code == code) return p;
            }
            return null;
        }
    }

    public enum ObservationStatus {
        FINAL("F"),
        DISCONNECTED("X"),
        UNKNOWN("");

        private final String hl7Code;
        ObservationStatus(String hl7Code) { this.hl7Code = hl7Code; }
        public String getHl7Code() { return hl7Code; }

        public static ObservationStatus fromHl7Code(String code) {
            if (code == null) return UNKNOWN;
            for (ObservationStatus s : values()) {
                if (s.hl7Code.equals(code)) return s;
            }
            return UNKNOWN;
        }
    }

    private Float heartRate;
    private Float spo2;
    private Float respRate;
    private Float perfusionIndex;
    private Float pulse;
    private Float stII;
    private Integer pvc;
    private AlarmType alarmType = AlarmType.NONE;
    private AlarmPriority alarmPriority;
    private String alarmText;
    private ObservationStatus status = ObservationStatus.UNKNOWN;
    private String timestamp;
    private String patientId;

    // Getters y setters
    public Float getHeartRate() { return heartRate; }
    public void setHeartRate(Float heartRate) { this.heartRate = heartRate; }
    public Float getSpo2() { return spo2; }
    public void setSpo2(Float spo2) { this.spo2 = spo2; }
    public Float getRespRate() { return respRate; }
    public void setRespRate(Float respRate) { this.respRate = respRate; }
    public Float getPerfusionIndex() { return perfusionIndex; }
    public void setPerfusionIndex(Float perfusionIndex) { this.perfusionIndex = perfusionIndex; }
    public Float getPulse() { return pulse; }
    public void setPulse(Float pulse) { this.pulse = pulse; }
    public Float getStII() { return stII; }
    public void setStII(Float stII) { this.stII = stII; }
    public Integer getPvc() { return pvc; }
    public void setPvc(Integer pvc) { this.pvc = pvc; }
    public AlarmType getAlarmType() { return alarmType; }
    public void setAlarmType(AlarmType alarmType) { this.alarmType = alarmType; }
    public AlarmPriority getAlarmPriority() { return alarmPriority; }
    public void setAlarmPriority(AlarmPriority alarmPriority) { this.alarmPriority = alarmPriority; }
    public String getAlarmText() { return alarmText; }
    public void setAlarmText(String alarmText) { this.alarmText = alarmText; }
    public ObservationStatus getStatus() { return status; }
    public void setStatus(ObservationStatus status) { this.status = status; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
}
```

### Paso 3: EfficiaHL7Parser.java

```java
package org.mdpnp.devices.philips.efficia;

import ca.uhn.hl7v2.model.v24.message.ORU_R01;
import ca.uhn.hl7v2.model.v24.segment.*;
import ca.uhn.hl7v2.model.v24.group.*;
import java.util.Map;

public class EfficiaHL7Parser {

    // Mapeo MDIL → campos de EfficiaData
    private static final Map<String, String> CODE_MAP = Map.of(
        "0002-4182", "heartRate",
        "0002-4bb8", "spo2",
        "0002-5000", "respRate",
        "0002-4bb0", "perfusionIndex",
        "0002-480a", "pulse",
        "0002-0302", "stII",
        "0002-4261", "pvc"
    );

    public EfficiaData parse(ORU_R01 message) {
        EfficiaData data = new EfficiaData();

        try {
            // 1. Extraer PID
            PID pid = message.getPATIENT_RESULT().getPID();
            data.setPatientId(pid.getPatientIdentifierList()[0].getIDNumber().getValue());

            // 2. Extraer OBR
            ORU_R01_ORDER_OBSERVATION order = message.getPATIENT_RESULT().getORDER_OBSERVATION();
            OBR obr = order.getOBR();
            String observationType = obr.getUniversalServiceIdentifier().getIdentifier().getValue();

            // 3. Determinar si es MONITOR o ALARM
            if ("ALARM".equals(observationType)) {
                parseAlarm(order, data);
            } else {
                parseMonitorData(order, data);
            }

        } catch (Exception e) {
            // Log error, retornar data parcial
        }

        return data;
    }

    private void parseMonitorData(ORU_R01_ORDER_OBSERVATION order, EfficiaData data) {
        try {
            ORU_R01_OBSERVATION[] observations = order.getOBSERVATIONAll();
            for (ORU_R01_OBSERVATION obs : observations) {
                OBX obx = obs.getOBX();
                String identifier = obx.getObservationIdentifier().getIdentifier().getValue();
                String value = obx.getObservationValue().getData().toString();
                String statusCode = obx.getObservationResultStatus().getValue();

                // Mapear status
                data.setStatus(EfficiaData.ObservationStatus.fromHl7Code(statusCode));

                // Si status es X (Disconnected), los valores vienen vacíos
                if (EfficiaData.ObservationStatus.DISCONNECTED == data.getStatus()) {
                    continue;
                }

                // Extraer código MDIL del identifier (formato: "0002-4182^HR^MDIL")
                String mdilCode = identifier.split("\\^")[0];

                // Parsear valor numérico
                if (value == null || value.isEmpty()) continue;
                float numValue = Float.parseFloat(value);

                // Mapear al campo correspondiente
                switch (mdilCode) {
                    case "0002-4182": data.setHeartRate(numValue); break;
                    case "0002-4bb8": data.setSpo2(numValue); break;
                    case "0002-5000": data.setRespRate(numValue); break;
                    case "0002-4bb0": data.setPerfusionIndex(numValue); break;
                    case "0002-480a": data.setPulse(numValue); break;
                    case "0002-0302": data.setStII(numValue); break;
                    case "0002-4261": data.setPvc((int) numValue); break;
                }
            }
        } catch (Exception e) {
            // Log error
        }
    }

    private void parseAlarm(ORU_R01_ORDER_OBSERVATION order, EfficiaData data) {
        try {
            ORU_R01_OBSERVATION[] observations = order.getOBSERVATIONAll();
            for (ORU_R01_OBSERVATION obs : observations) {
                OBX obx = obs.getOBX();
                String value = obx.getObservationValue().getData().toString();

                if (value != null && !value.isEmpty()) {
                    // Extraer tipo de alarma del texto
                    // Ej: "Yellow Alarm^MDIL-ALERT" → YELLOW_ALARM
                    // Ej: "SpO₂ baja, SpO₂ = 80 %" → texto de alarma
                    String[] parts = value.split("\\^");
                    if (parts.length > 0) {
                        String alarmText = parts[0].trim();
                        data.setAlarmText(alarmText);

                        if (alarmText.contains("Red")) {
                            data.setAlarmType(EfficiaData.AlarmType.RED_ALARM);
                            data.setAlarmPriority(EfficiaData.AlarmPriority.HIGH);
                        } else if (alarmText.contains("Yellow")) {
                            data.setAlarmType(EfficiaData.AlarmType.YELLOW_ALARM);
                            data.setAlarmPriority(EfficiaData.AlarmPriority.MEDIUM);
                        } else if (alarmText.contains("Inop")) {
                            data.setAlarmType(EfficiaData.AlarmType.SOFT_INOP);
                            data.setAlarmPriority(EfficiaData.AlarmPriority.LOW);
                        }
                    }
                } else {
                    // Valor vacío = ALARM_CLEAR
                    data.setAlarmType(EfficiaData.AlarmType.ALARM_CLEAR);
                }
            }
        } catch (Exception e) {
            // Log error
        }
    }
}
```

### Paso 4: Efficia.java (Driver principal)

```java
package org.mdpnp.devices.philips.efficia;

import ca.uhn.hl7v2.*;
import ca.uhn.hl7v2.app.*;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v24.message.ORU_R01;

import org.mdpnp.devices.connected.AbstractConnectedDevice;
import org.mdpnp.devices.hl7.Hl7Service;
import org.mdpnp.rosetta.types.*;

import com.rti.dds.subscription.Subscriber;
import com.rti.dds.publication.Publisher;
import org.mdpnp.devices.io.EventLoop;
import org.mdpnp.devices.DeviceClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Efficia extends AbstractConnectedDevice {

    private static final Logger log = LoggerFactory.getLogger(Efficia.class);

    private final Hl7Service hl7Service;
    private SimpleServer hl7Server;
    private final EfficiaHL7Parser parser = new EfficiaHL7Parser();

    // DDS InstanceHolders
    private InstanceHolder<ice.Numeric> heartRate;
    private InstanceHolder<ice.Numeric> spo2;
    private InstanceHolder<ice.Numeric> respRate;
    private InstanceHolder<ice.Numeric> pulse;
    private InstanceHolder<ice.Numeric> perfusionIndex;

    public Efficia(Subscriber subscriber, Publisher publisher, EventLoop eventLoop, Hl7Service hl7Service) {
        super(subscriber, publisher, eventLoop);
        this.hl7Service = hl7Service;
    }

    @Override
    public void init() {
        super.init();

        deviceIdentity.manufacturer = "Philips";
        deviceIdentity.model = "Efficia CM Series";
        AbstractSimulatedDevice.randomUDI(deviceIdentity);
        writeDeviceIdentity();

        heartRate = createNumericInstance(rosetta.MDC_ECG_HEART_RATE.VALUE, "bpm");
        spo2 = createNumericInstance(rosetta.MDC_PULS_OXIM_SAT_O2.VALUE, "%");
        respRate = createNumericInstance(rosetta.MDC_CO2_RESP_RATE.VALUE, "rpm");
        pulse = createNumericInstance(rosetta.MDC_PULS_OXIM_PULS_RATE.VALUE, "bpm");
        // TODO: perfusionIndex — crear cuando se defina el MDC code
    }

    @Override
    protected ice.ConnectionType getConnectionType() {
        return ice.ConnectionType.Network;
    }

    @Override
    public boolean connect(String address) {
        stateMachine.transitionWhenLegal(ice.ConnectionState.Connecting, 5000,
            "Iniciando servidor HL7 para Efficia");

        int port = Integer.parseInt(System.getProperty("efficia.real.hl7.port", "4202"));

        Application handler = new Application() {
            @Override
            public boolean canProcess(Message msg) {
                return true;
            }

            @Override
            public Message processMessage(Message msg) throws HL7Exception {
                handleHL7Message(msg);
                return msg.generateACK();
            }
        };

        try {
            hl7Server = hl7Service.createServer(port, handler);
            hl7Server.start();

            stateMachine.transitionWhenLegal(ice.ConnectionState.Connected, 5000,
                "Servidor HL7 escuchando en puerto " + port);

            log.info("Efficia driver started — MLLP server on port {}", port);
            return true;
        } catch (Exception e) {
            log.error("Failed to start HL7 server", e);
            stateMachine.transitionWhenLegal(ice.ConnectionState.Terminal, 5000,
                "Error al iniciar servidor: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void disconnect() {
        if (hl7Server != null) {
            hl7Server.stop();
            hl7Server = null;
        }
        stateMachine.transitionWhenLegal(ice.ConnectionState.Terminal, 5000,
            "Desconectado");
        log.info("Efficia driver stopped");
    }

    private void handleHL7Message(Message msg) {
        try {
            ORU_R01 oru = hl7Service.castToORU(msg);
            EfficiaData data = parser.parse(oru);

            DeviceClock.Reading now = getClockProvider().instant();

            // Publicar solo si el status es Final
            if (EfficiaData.ObservationStatus.FINAL == data.getStatus()) {
                if (data.getHeartRate() != null) {
                    numericSample(heartRate, data.getHeartRate(), now);
                }
                if (data.getSpo2() != null) {
                    numericSample(spo2, data.getSpo2(), now);
                }
                if (data.getRespRate() != null) {
                    numericSample(respRate, data.getRespRate(), now);
                }
                if (data.getPulse() != null) {
                    numericSample(pulse, data.getPulse(), now);
                }
                // TODO: perfusionIndex
            }

            // TODO: Alarmas — implementar cuando se requiera
            // if (data.getAlarmType() != EfficiaData.AlarmType.NONE
            //     && data.getAlarmType() != EfficiaData.AlarmType.ALARM_CLEAR) {
            //     writePatientAlert("Efficia", data.getAlarmText());
            // }

            // Si status es X (Disconnected), no publicamos — el monitor perdió contacto
            log.debug("Processed ORU: HR={}, SpO2={}, Resp={}, Pulse={}, Status={}",
                data.getHeartRate(), data.getSpo2(), data.getRespRate(),
                data.getPulse(), data.getStatus());

        } catch (Exception e) {
            log.error("Error processing HL7 message", e);
        }
    }
}
```

### Paso 5: Registro en DeviceFactory.java

Agregar inner class:

```java
public static class EfficiaProvider extends SpringLoadedDriver {
    @Override
    public DeviceType getDeviceType() {
        return new DeviceType(ice.ConnectionType.Network, "Philips",
            "Efficia CM Series", "Efficia", 1);
    }

    @Override
    public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
        EventLoop eventLoop = context.getBean("eventLoop", EventLoop.class);
        Subscriber subscriber = context.getBean("subscriber", Subscriber.class);
        Publisher publisher = context.getBean("publisher", Publisher.class);
        Hl7Service hl7Service = context.getBean(Hl7Service.class);
        return new Efficia(subscriber, publisher, eventLoop, hl7Service);
    }
}
```

Import: `import org.mdpnp.devices.hl7.Hl7Service;`

### Paso 6: Registro en HeadlessDeviceFactory.java

Agregar inner class idéntica al paso 5 pero dentro de `HeadlessDeviceFactory`.

### Paso 7: Archivos SPI

**demo-devices SPI** (`interop-lab/demo-devices/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider`):
Agregar línea:
```
org.mdpnp.apps.testapp.DeviceFactory$EfficiaProvider
```

**headless-adapter SPI** (`headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider`):
Agregar línea:
```
org.mdpnp.headless.HeadlessDeviceFactory$EfficiaProvider
```

## Renombrar Simulador

El simulador existente debe renombrarse para evitar confusión con el driver real:

| De | A |
|----|----|
| `SimEfficiaMonitor.java` | `SimulatedEfficia.java` |
| `EfficiaClinicalEngine.java` | `SimulatedEfficiaClinicalEngine.java` |
| `class SimEfficiaMonitor` | `class SimulatedEfficia` |
| `class EfficiaClinicalEngine` | `class SimulatedEfficiaClinicalEngine` |
| `EfficiaMonitorProvider` | `SimulatedEfficiaProvider` |
| alias `"EfficiaMonitor"` | `"SimulatedEfficia"` |

Ver prompt `docs/promts/efficia_rename_simulated.md` para los detalles del renombramiento.

## Testing

```bash
# Compilar
./gradlew :interop-lab:demo-devices:compileJava -x setupLocalDb

# Test unitario del parser (usando datos_efficia.txt)
./gradlew :interop-lab:demo-devices:test -x setupLocalDb --tests "*EfficiaHL7ParserTest"

# Ejecutar headless
./gradlew :headless-adapter:run -x setupLocalDb \
  --args="-domain 0 -device Efficia"

# Ejecutar GUI
./gradlew :interop-lab:demo-apps:run
# Seleccionar "Efficia" en el dropdown de dispositivos
```

## Uso

```bash
# Headless — el driver escucha en puerto 4202
./gradlew :headless-adapter:run -x setupLocalDb \
  --args="-domain 0 -device Efficia"

# Configurar puerto diferente
./gradlew :headless-adapter:run -x setupLocalDb \
  --args="-domain 0 -device Efficia" \
  -Defficia.real.hl7.port=5000

# El monitor Efficia real se conecta a <IP>:4202 por LAN
# Los datos se publican automáticamente en DDS
```
