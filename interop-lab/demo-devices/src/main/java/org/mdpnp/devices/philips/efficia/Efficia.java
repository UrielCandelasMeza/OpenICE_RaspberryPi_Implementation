package org.mdpnp.devices.philips.efficia;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.app.Application;
import ca.uhn.hl7v2.app.SimpleServer;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v24.message.ORU_R01;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.mdpnp.devices.connected.AbstractConnectedDevice;
import org.mdpnp.devices.hl7.Hl7Service;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
// import org.mdpnp.devices.AbstractDevice.InstanceHolder;
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

    private volatile boolean deviceConnected = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Efficia-Timeout");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> timeoutFuture;
    private static final long TIMEOUT_SECONDS = 10;

    // DDS InstanceHolders
    private InstanceHolder<ice.Numeric> heartRate;
    private InstanceHolder<ice.Numeric> spo2;
    private InstanceHolder<ice.Numeric> respRate;
    private InstanceHolder<ice.Numeric> pulse;
    private InstanceHolder<ice.Numeric> perfusionIndex;
    private InstanceHolder<ice.Numeric> pvc;
    private InstanceHolder<ice.Numeric> stI;
    private InstanceHolder<ice.Numeric> stII;
    private InstanceHolder<ice.Numeric> stIII;
    private InstanceHolder<ice.Numeric> stAVR;
    private InstanceHolder<ice.Numeric> stAVL;
    private InstanceHolder<ice.Numeric> stAVF;
    private InstanceHolder<ice.Numeric> stV;
    private InstanceHolder<ice.Numeric> stMCL;

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
        perfusionIndex = createNumericInstance(rosetta.MDC_PULS_OXIM_PERF_REL.VALUE, "");
        pvc = createNumericInstance("Efficia_PVC", "/min");
        stI = createNumericInstance("Efficia_ST_I", "mm");
        stII = createNumericInstance("Efficia_ST_II", "mm");
        stIII = createNumericInstance("Efficia_ST_III", "mm");
        stAVR = createNumericInstance("Efficia_ST_aVR", "mm");
        stAVL = createNumericInstance("Efficia_ST_aVL", "mm");
        stAVF = createNumericInstance("Efficia_ST_aVF", "mm");
        stV = createNumericInstance("Efficia_ST_V", "mm");
        stMCL = createNumericInstance("Efficia_ST_MCL", "mm");
    }

    @Override
    protected String iconResourceName() {
        return "efficia.png";
    }

    @Override
    protected ice.ConnectionType getConnectionType() {
        return ice.ConnectionType.Network;
    }

    /**
     * @param address The IP address or connection string (not explicitly used here
     *                since
     *                the MLLP server port is configured via properties).
     * @return true if the MLLP server started successfully, false otherwise.
     */
    @Override
    public boolean connect(String address) {
        ice.ConnectionState state = getState();
        if (ice.ConnectionState.Connected.equals(state) || ice.ConnectionState.Connecting.equals(state)
                || ice.ConnectionState.Negotiating.equals(state)) {
            return true;
        }

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
                try {
                    return msg.generateACK();
                } catch (java.io.IOException e) {
                    throw new HL7Exception(e);
                }
            }
        };

        try {
            hl7Server = hl7Service.createServer(port, handler);
            hl7Server.start();

            stateMachine.transitionWhenLegal(ice.ConnectionState.Negotiating, 5000,
                    "Servidor HL7 escuchando en puerto " + port + " — Esperando Efficia...");

            log.info("Efficia driver started — MLLP server on port {}, waiting for device", port);
            return true;
        } catch (Exception e) {
            log.error("Failed to start HL7 server", e);
            if (hl7Server != null) {
                hl7Server.stop();
                hl7Server = null;
            }
            stateMachine.transitionWhenLegal(ice.ConnectionState.Terminal, 5000,
                    "Error al iniciar servidor: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void disconnect() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
        deviceConnected = false;
        if (hl7Server != null) {
            hl7Server.stop();
            hl7Server = null;
        }
        if (!ice.ConnectionState.Terminal.equals(getState())) {
            stateMachine.transitionWhenLegal(ice.ConnectionState.Terminal, 5000,
                    "Desconectado");
        }
        log.info("Efficia driver stopped");
    }

    private void resetTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        timeoutFuture = scheduler.schedule(this::onTimeout, TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void onTimeout() {
        if (deviceConnected) {
            deviceConnected = false;
            stateMachine.transitionWhenLegal(ice.ConnectionState.Negotiating, 5000,
                    "Efficia desconectado — esperando dispositivo...");
            log.info("Efficia device timed out — no messages for {}s", TIMEOUT_SECONDS);
        }
    }

    /**
     * Procesa un mensaje HL7 ORU^R01 recibido del Efficia.
     * Extrae los datos de observación y los publica como DDS Numeric.
     *
     * @param msg mensaje HAPI ya parseado por el framework MLLP
     */
    private void handleHL7Message(Message msg) {
        try {
            if (!deviceConnected) {
                deviceConnected = true;
                stateMachine.transitionWhenLegal(ice.ConnectionState.Connected, 5000,
                        "Efficia conectado — recibiendo datos");
                log.info("Efficia device connected — first HL7 message received");
            }
            resetTimeout();

            ORU_R01 oru = hl7Service.castToORU(msg);
            EfficiaData data = parser.parse(oru);

            DeviceClock.Reading now = getClockProvider().instant();

            // Publicar solo si el status es Final (F) — métricos con datos
            if (EfficiaData.ObservationStatus.FINAL == data.getStatus()) {
                publishOrFallback(heartRate, data.getHeartRate(), now);
                publishOrFallback(spo2, data.getSpo2(), now);
                publishOrFallback(respRate, data.getRespRate(), now);
                publishOrFallback(pulse, data.getPulse(), now);
                publishOrFallback(perfusionIndex, data.getPerfusionIndex(), now);
                publishOrFallback(pvc, data.getPvc() != null ? (float) data.getPvc() : null, now);
                publishOrFallback(stI, data.getStI(), now);
                publishOrFallback(stII, data.getStII(), now);
                publishOrFallback(stIII, data.getStIII(), now);
                publishOrFallback(stAVR, data.getStAVR(), now);
                publishOrFallback(stAVL, data.getStAVL(), now);
                publishOrFallback(stAVF, data.getStAVF(), now);
            }

            // Métricos desconectados (status X) — publicar con -?-
            publishDisconnected(stV, data, "0002-0343", now);
            publishDisconnected(stMCL, data, "0002-034b", now);

            log.debug("Processed ORU: HR={}, SpO2={}, Resp={}, Pulse={}, Status={}",
                    data.getHeartRate(), data.getSpo2(), data.getRespRate(),
                    data.getPulse(), data.getStatus());

        } catch (Exception e) {
            log.error("Error processing HL7 message", e);
        }
    }

    /**
     * Publishes a numeric sample. If the value is null, publishes Float.NaN (GUI
     * shows "-?").
     */
    private void publishOrFallback(InstanceHolder<ice.Numeric> holder, Float value, DeviceClock.Reading now) {
        numericSample(holder, value != null ? value : Float.NaN, now);
    }

    /**
     * Publishes a disconnected metric. If the metric was reported as disconnected
     * (status X),
     * publishes Float.NEGATIVE_INFINITY (GUI shows "-?-"). If the metric has a
     * value, publishes it.
     */
    private void publishDisconnected(InstanceHolder<ice.Numeric> holder, EfficiaData data,
            String mdilCode, DeviceClock.Reading now) {
        if (data.isDisconnected(mdilCode)) {
            numericSample(holder, Float.NEGATIVE_INFINITY, now);
        }
    }
}
