package org.mdpnp.devices.philips.efficia;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.app.Application;
import ca.uhn.hl7v2.app.SimpleServer;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v24.message.ORU_R01;

import org.mdpnp.devices.connected.AbstractConnectedDevice;
import org.mdpnp.devices.hl7.Hl7Service;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rosetta.types.InstanceHolder;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.devices.DeviceClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

/**
 * Real Philips Efficia CM Series device driver.
 *
 * <p>
 * Este driver actúa como servidor MLLP (TCP) escuchando las conexiones
 * del monitor Efficia por LAN. Recibe mensajes HL7 v2.4 ORU^R01, los parsea
 * y publica los datos como DDS Numeric en OpenICE.
 * </p>
 *
 * <h3>Flujo de datos</h3>
 * 
 * <pre>
 *   Efficia (TCP client) → [MLLP:4202] → Hl7Service → EfficiaHL7Parser → DDS Numeric
 * </pre>
 *
 * <h3>Uso headless</h3>
 * 
 * <pre>
 *   ./gradlew :headless-adapter:run --args="-domain 0 -device Efficia"
 * </pre>
 *
 * <h3>Uso con puerto personalizado</h3>
 * 
 * <pre>
 *   -Defficia.real.hl7.port=5000
 * </pre>
 * 
 * @author Uriel Candelas
 */
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
    // TODO: perfusionIndex — crear cuando se defina el MDC code adecuado
    // private InstanceHolder<ice.Numeric> perfusionIndex;

    /**
     * Constructor del driver real Efficia.
     *
     * @param subscriber DDS subscriber
     * @param publisher  DDS publisher
     * @param eventLoop  event loop de DDS
     * @param hl7Service servicio HL7/HAPI inyectado por Spring
     */
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
        // TODO: perfusionIndex
    }

    @Override
    protected ice.ConnectionType getConnectionType() {
        return ice.ConnectionType.Network;
    }

    /**
     * @param address The IP address or connection string (not explicitly used here since
     *                the MLLP server port is configured via properties).
     * @return true if the MLLP server started successfully, false otherwise.
     */
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

    /**
     * Procesa un mensaje HL7 ORU^R01 recibido del Efficia.
     * Extrae los datos de observación y los publica como DDS Numeric.
     *
     * @param msg mensaje HAPI ya parseado por el framework MLLP
     */
    private void handleHL7Message(Message msg) {
        try {
            ORU_R01 oru = hl7Service.castToORU(msg);
            EfficiaData data = parser.parse(oru);

            DeviceClock.Reading now = getClockProvider().instant();

            // Publicar solo si el status es Final (F)
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
            // && data.getAlarmType() != EfficiaData.AlarmType.ALARM_CLEAR) {
            // writePatientAlert("Efficia", data.getAlarmText());
            // }

            log.debug("Processed ORU: HR={}, SpO2={}, Resp={}, Pulse={}, Status={}",
                    data.getHeartRate(), data.getSpo2(), data.getRespRate(),
                    data.getPulse(), data.getStatus());

        } catch (Exception e) {
            log.error("Error processing HL7 message", e);
        }
    }
}
