package org.mdpnp.devices.simulation.atlan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.simulation.GlobalSimulationObjectiveListener;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import ice.GlobalSimulationObjective;
import ice.Numeric;

/**
 * Simulated Draeger Atlan A-350XL anesthesia machine.
 * Supports Medibus slave communication via serial port, and HL7 MLLP over TCP on LAN (port 2575).
 *
 * @author Uriel Candelas
 */
public class SimDraegerAtlan extends AbstractSerialDevice implements GlobalSimulationObjectiveListener {

    private static final Logger log = LoggerFactory.getLogger(SimDraegerAtlan.class);

    private static final String HL7_HOST = System.getProperty("atlan.hl7.host", "localhost");
    private static final int    HL7_PORT  = Integer.getInteger("atlan.hl7.port", 2575);
    private static final int    SIM_TICK_SECONDS = 1;

    /**
     * Serial device path for the Medibus slave interface.
     * Set via JVM property: {@code -Datlan.serial.port=/dev/ttyS0}
     * <p>Leave blank (default) to disable serial output entirely.
     * <p>The port must be pre-configured with stty before starting:
     * <pre>  stty -F /dev/ttyS0 19200 parenb -parodd cs8 -cstopb raw</pre>
     */
    private static final String SERIAL_PORT = System.getProperty("atlan.serial.port", "");

    // DDS numeric instance holders
    private final InstanceHolder<Numeric> heartRateHolder;
    private final InstanceHolder<Numeric> spo2Holder;
    private final InstanceHolder<Numeric> respRateHolder;
    private final InstanceHolder<Numeric> airwayPressureHolder;
    private final InstanceHolder<Numeric> peepHolder;
    private final InstanceHolder<Numeric> tidalVolumeHolder;
    private final InstanceHolder<Numeric> minuteVolumeHolder;
    private final InstanceHolder<Numeric> inspiredO2Holder;

    // Simulated patient state
    private final PatientState state = new PatientState();
    private volatile boolean running;

    // Networking
    private volatile Socket hl7Connection;
    private final AtomicInteger msgCounter = new AtomicInteger(1);

    // Serial Medibus slave
    private volatile Thread   serialThread;
    private volatile InputStream  serialIn;
    private volatile OutputStream serialOut;

    // Scheduled tasks
    private ScheduledFuture<?> simulationTask;
    private ScheduledFuture<?> hl7BroadcastTask;

    public static class PatientState {
        public double heartRate = 72.0;
        public double spo2 = 98.0;
        public double respRate = 12.0;
        public double airwayPressure = 19.2;
        public double peep = 5.1;
        public double tidalVolume = 530.0;
        public double minuteVolume = 6.4;
        public double inspiredO2 = 35.0;
        public boolean alarmActive = false;
    }

    public SimDraegerAtlan(final Subscriber subscriber, final Publisher publisher, final EventLoop eventLoop) {
        super(subscriber, publisher, eventLoop);

        // Initialize unique device identifier before creating numeric instances
        AbstractSimulatedDevice.randomUDI(deviceIdentity);

        // Pre-allocate DDS Numeric instance holders
        heartRateHolder = createNumericInstance(rosetta.MDC_ECG_HEART_RATE.VALUE, "");
        spo2Holder = createNumericInstance(rosetta.MDC_PULS_OXIM_SAT_O2.VALUE, "");
        respRateHolder = createNumericInstance(rosetta.MDC_CO2_RESP_RATE.VALUE, "");
        airwayPressureHolder = createNumericInstance(rosetta.MDC_PRESS_AWAY.VALUE, "");
        peepHolder = createNumericInstance(rosetta.MDC_PRESS_AWAY.VALUE, "PEEP");
        tidalVolumeHolder = createNumericInstance(rosetta.MDC_VENT_VOL_TIDAL.VALUE, "");
        minuteVolumeHolder = createNumericInstance(rosetta.MDC_VENT_VOL_MINUTE_AWAY.VALUE, "");
        inspiredO2Holder = createNumericInstance(rosetta.MDC_AWAY_O2_INSP.VALUE, ""); // inspired agent/gas placeholder

        deviceIdentity.manufacturer = "Dr\u00E4ger";
        deviceIdentity.model = "Atlan A-350XL (Simulated)";
        writeDeviceIdentity();
    }

    @Override
    protected long getMaximumQuietTime(int idx) {
        // High timeout value since the master (client) controls query rate
        return 120000L; 
    }

    @Override
    public ice.ConnectionType getConnectionType() {
        return ice.ConnectionType.Simulated;
    }

    @Override
    protected void doInitCommands(int idx) throws IOException {
        // No init command needed for simulator
    }

    @Override
    public boolean connect(String address) {
        if (!super.connect(address)) {
            return false;
        }

        running = true;

        // Start serial Medibus slave (if port is configured)
        startSerialMedibus();

        // Start HL7 MLLP outbound client
        startHl7Client();

        // Start internal simulation and DDS publishing
        simulationTask = executor.scheduleAtFixedRate(this::tickSimulation, 0, SIM_TICK_SECONDS, TimeUnit.SECONDS);

        // Start HL7 periodic trend broadcast (every 10 s for testing/simulation)
        hl7BroadcastTask = executor.scheduleAtFixedRate(this::broadcastHl7Trend, 5, 10, TimeUnit.SECONDS);

        log.info("Draeger Atlan A-350XL simulator started — serial={} hl7_host={} hl7_port={}",
                SERIAL_PORT.isBlank() ? "(disabled)" : SERIAL_PORT, HL7_HOST, HL7_PORT);
        return true;
    }

    @Override
    public void disconnect() {
        running = false;

        if (simulationTask != null) {
            simulationTask.cancel(true);
            simulationTask = null;
        }
        if (hl7BroadcastTask != null) {
            hl7BroadcastTask.cancel(true);
            hl7BroadcastTask = null;
        }

        // Stop serial thread
        Thread st = serialThread;
        serialThread = null;
        if (st != null) {
            st.interrupt();
        }
        closeQuietly(serialIn);
        closeQuietly(serialOut);
        serialIn  = null;
        serialOut = null;

        closeHl7Connection();
        super.disconnect();
    }

    // ── Serial Medibus slave ───────────────────────────────────────────────────
    //
    // Opens the serial device file directly (no RXTX needed) and runs the
    // existing Medibus slave logic (process()) on it. The port must be
    // configured with stty before the simulator starts:
    //   stty -F /dev/ttyS0 19200 parenb -parodd cs8 -cstopb raw

    private void startSerialMedibus() {
        if (SERIAL_PORT == null || SERIAL_PORT.isBlank()) {
            log.info("Medibus serial slave disabled — set -Datlan.serial.port=/dev/ttyS0 to enable");
            return;
        }
        File dev = new File(SERIAL_PORT);
        if (!dev.exists()) {
            log.warn("Serial port '{}' not found — Medibus serial slave disabled. "
                    + "Is the adapter connected and the path correct?", SERIAL_PORT);
            return;
        }
        serialThread = new Thread(() -> {
            try {
                serialIn  = new FileInputStream(dev);
                serialOut = new FileOutputStream(dev);
                log.info("Medibus serial slave opened on {}", SERIAL_PORT);
                // Reuse the existing slave loop
                process(0, serialIn, serialOut);
            } catch (IOException e) {
                if (running) {
                    log.error("Medibus serial error on '{}': {}", SERIAL_PORT, e.getMessage());
                }
            } finally {
                closeQuietly(serialIn);
                closeQuietly(serialOut);
            }
        }, "atlan-medibus-serial");
        serialThread.setDaemon(true);
        serialThread.start();
    }

    @Override
    protected void process(int idx, InputStream in, OutputStream out) throws IOException {
        log.info("Started Medibus slave processing loop");
        reportConnected("Medibus serial connection active");

        byte[] buffer = new byte[1024];
        int count = 0;
        boolean inFrame = false;
        int typeChar = -1;

        while (running) {
            int b = in.read();
            if (b < 0) {
                break; // EOF
            }
            if (b == 0x01 || b == 0x1B) { // SOH (0x01) or ESC (0x1B)
                inFrame = true;
                typeChar = b;
                count = 0;
                continue;
            }
            if (b == 0x0D) { // CR
                if (inFrame) {
                    inFrame = false;
                    try {
                        handleMedibusCommand(typeChar, buffer, count, out);
                    } catch (Exception e) {
                        log.error("Error processing Medibus command", e);
                    }
                }
                continue;
            }
            if (inFrame && count < buffer.length) {
                buffer[count++] = (byte) b;
            }
        }
    }

    private void handleMedibusCommand(int typeChar, byte[] buffer, int count, OutputStream out) throws IOException {
        if (typeChar != 0x1B) {
            // Medibus slave only responds to ESC command frames from master
            return;
        }
        if (count < 3) {
            return; // At least cmdCode (1) + checksum (2)
        }

        // Verify checksum
        int sum = 0;
        for (int i = 0; i < count - 2; i++) {
            sum = (sum + (buffer[i] & 0xFF)) & 0xFF;
        }
        String recvChecksum = new String(buffer, count - 2, 2);
        String calcChecksum = String.format("%02X", sum);
        if (!calcChecksum.equalsIgnoreCase(recvChecksum)) {
            log.warn("Medibus checksum mismatch: calculated={}, received={}", calcChecksum, recvChecksum);
            return;
        }

        byte cmd = buffer[0];
        log.trace("Received Medibus command: 0x{}", Integer.toHexString(cmd & 0xFF));

        if (cmd == 0x51) { // ICC
            sendMedibusResponse(out, cmd, null);
        } else if (cmd == 0x52) { // ReqDeviceId
            String payload = "8260'Atlan A-350XL' 01.00      ";
            sendMedibusResponse(out, cmd, payload.getBytes());
        } else if (cmd == 0x24) { // ReqMeasuredDataCP1
            String payload = String.format(Locale.US,
                "7D  %2.0f" +
                "78   %1.0f" +
                "D6  %2.0f" +
                "B9 %3.1f" +
                "F0  %2.0f",
                state.airwayPressure,
                state.peep,
                state.respRate,
                state.minuteVolume,
                state.inspiredO2
            );
            sendMedibusResponse(out, cmd, payload.getBytes());
        } else if (cmd == 0x29) { // ReqDeviceSetting
            String payload = String.format(Locale.US,
                "01  %2.0f " +
                "04%5.3f" +
                "09 %4.1f" +
                "0B  %3.1f",
                state.inspiredO2,
                state.tidalVolume / 1000.0,
                state.respRate,
                state.peep
            );
            sendMedibusResponse(out, cmd, payload.getBytes());
        } else if (cmd == 0x27) { // ReqAlarmsCP1
            if (state.alarmActive) {
                String payload = "210PAW HIGH    ";
                sendMedibusResponse(out, cmd, payload.getBytes());
            } else {
                sendMedibusResponse(out, cmd, null);
            }
        } else if (cmd == 0x25) { // ReqLowAlarmLimitsCP1
            String payload = "7D  10B9 4.0F0  18";
            sendMedibusResponse(out, cmd, payload.getBytes());
        } else if (cmd == 0x26) { // ReqHighAlarmLimitsCP1
            String payload = "7D  40B910.0F0  80";
            sendMedibusResponse(out, cmd, payload.getBytes());
        } else {
            sendMedibusResponse(out, cmd, null);
        }
    }

    private void sendMedibusResponse(OutputStream out, byte cmdCode, byte[] payload) throws IOException {
        out.write(0x01); // SOH
        out.write(cmdCode);
        int sum = cmdCode & 0xFF;
        if (payload != null) {
            out.write(payload);
            for (byte b : payload) {
                sum = (sum + b) & 0xFF;
            }
        }
        String hex = String.format("%02X", sum);
        out.write(hex.getBytes());
        out.write(0x0D); // CR
        out.flush();
    }

    private void tickSimulation() {
        try {
            // Apply slight jitter for realistic simulation
            state.airwayPressure = 18.0 + Math.sin(System.currentTimeMillis() / 2000.0) * 4.0;
            state.peep = 5.0 + Math.sin(System.currentTimeMillis() / 5000.0) * 0.5;
            state.heartRate = 72.0 + Math.sin(System.currentTimeMillis() / 10000.0) * 3.0;
            state.spo2 = 98.0 + (System.currentTimeMillis() % 120000 < 5000 ? -1.0 : 0.0);

            // DDS updates
            DeviceClock.Reading now = getClockProvider().instant();
            numericSample(heartRateHolder, (float) state.heartRate, now);
            numericSample(spo2Holder, (float) state.spo2, now);
            numericSample(respRateHolder, (float) state.respRate, now);
            numericSample(airwayPressureHolder, (float) state.airwayPressure, now);
            numericSample(peepHolder, (float) state.peep, now);
            numericSample(tidalVolumeHolder, (float) state.tidalVolume, now);
            numericSample(minuteVolumeHolder, (float) state.minuteVolume, now);
            numericSample(inspiredO2Holder, (float) state.inspiredO2, now);

            // Conditional alarm trigger if airway pressure exceeds high limit (e.g. 21.5 mbar)
            if (state.airwayPressure > 21.5) {
                state.alarmActive = true;
                writePatientAlert("MDIL-ALERT_10", "PAW HIGH (Pressure exceeds limit)");
            } else {
                state.alarmActive = false;
            }

        } catch (Exception e) {
            log.error("Error in simulation loop", e);
        }
    }

    // ── HL7/MLLP Server implementation ────────────────────────────────────────

    private void startHl7Client() {
        Thread clientThread = new Thread(() -> {
            while (running) {
                try {
                    log.info("HL7: connecting to receiver at {}:{}", HL7_HOST, HL7_PORT);
                    Socket conn = new Socket(HL7_HOST, HL7_PORT);
                    hl7Connection = conn;
                    log.info("HL7: connected to receiver successfully");

                    InputStream in = conn.getInputStream();
                    byte[] buf = new byte[1024];
                    while (running) {
                        int r = in.read(buf);
                        if (r < 0) {
                            break; // disconnected
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        log.warn("HL7: could not reach receiver ({}), retrying in 5 s", e.getMessage());
                    }
                } finally {
                    closeHl7Connection();
                }
                if (running) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "atlan-hl7-client");
        clientThread.setDaemon(true);
        clientThread.start();
    }

    private void closeHl7Connection() {
        Socket old = hl7Connection;
        hl7Connection = null;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {}
            log.info("HL7 connection closed");
        }
    }

    private void broadcastHl7Trend() {
        Socket conn = hl7Connection;
        if (conn == null || conn.isClosed()) {
            log.debug("No HL7 receiver connected — skipping LAN send");
            return;
        }
        String message = buildHl7Message();
        byte[] frame = wrapMllp(message);
        try {
            OutputStream out = conn.getOutputStream();
            out.write(frame);
            out.flush();
        } catch (IOException e) {
            log.warn("Failed to write to HL7 receiver: {}", e.getMessage());
            closeHl7Connection();
        }
    }

    private String buildHl7Message() {
        String ts = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date());
        int id = msgCounter.getAndIncrement();

        StringBuilder sb = new StringBuilder();
        // MSH
        sb.append("MSH|^~\\&|AtlanSim|Draeger|HostSystem|OpenICE|").append(ts)
          .append("||ORU^R01|MSG").append(String.format("%08d", id))
          .append("|P|2.4\r");
        // PID
        sb.append("PID|||PAT002^^^^MRN||Anesthesia^Patient^^^^^L||19700101|M\r");
        // PV1
        sb.append("PV1|||OR_ROOM_01\r");
        // ORC
        sb.append("ORC|RE\r");
        // OBR
        sb.append("OBR|1|||0000-0000^General^MDIL\r");

        // OBX values
        int seq = 1;
        sb.append(obxRow(seq++, "NM", "0002-4182", "HR", state.heartRate, "0004-0aa0", "bpm"));
        sb.append(obxRow(seq++, "NM", "0002-4190", "SpO2", state.spo2, "0004-0aa0", "%"));
        sb.append(obxRow(seq++, "NM", "0002-4184", "RR", state.respRate, "0004-0aa0", "rpm"));
        sb.append(obxRow(seq++, "NM", "0002-4186", "PAW", state.airwayPressure, "0004-0aa0", "mbar"));
        sb.append(obxRow(seq++, "NM", "0002-4188", "PEEP", state.peep, "0004-0aa0", "mbar"));
        sb.append(obxRow(seq++, "NM", "0002-4192", "VT", state.tidalVolume, "0004-0aa0", "mL"));
        sb.append(obxRow(seq++, "NM", "0002-4194", "MV", state.minuteVolume, "0004-0aa0", "L"));
        sb.append(obxRow(seq++, "NM", "0002-4196", "FiO2", state.inspiredO2, "0004-0aa0", "%"));

        if (state.alarmActive) {
            sb.append("OBX|").append(seq).append("|TX|MDIL-ALERT^Alert^MDIL||PAW HIGH priority 2||||||F\r");
        }

        return sb.toString();
    }

    private String obxRow(int seq, String type, String mdilCode, String label, double value, String unitCode, String unitLabel) {
        return "OBX|" + seq + "|" + type + "|" + mdilCode + "^" + label + "^MDIL||"
                + String.format(Locale.US, "%.1f", value) + "|" + unitCode + "^" + unitLabel + "^MDIL|||||F\r";
    }

    private byte[] wrapMllp(String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[msgBytes.length + 3];
        frame[0] = 0x0B; // SB
        System.arraycopy(msgBytes, 0, frame, 1, msgBytes.length);
        frame[frame.length - 2] = 0x1C; // EB
        frame[frame.length - 1] = 0x0D; // CR
        return frame;
    }

    @Override
    public void simulatedNumeric(GlobalSimulationObjective obj) {
        if (obj == null) return;
        try {
            if (rosetta.MDC_ECG_HEART_RATE.VALUE.equals(obj.metric_id)) {
                state.heartRate = obj.value;
            } else if (rosetta.MDC_PULS_OXIM_SAT_O2.VALUE.equals(obj.metric_id)) {
                state.spo2 = obj.value;
            } else if (rosetta.MDC_CO2_RESP_RATE.VALUE.equals(obj.metric_id)) {
                state.respRate = (int) Math.round(obj.value);
            } else if (rosetta.MDC_VENT_VOL_TIDAL.VALUE.equals(obj.metric_id)) {
                state.tidalVolume = obj.value;
            }
        } catch (Exception e) {
            log.warn("Failed to apply simulation objective", e);
        }
    }

    @Override
    protected String iconResourceName() {
        return "anesthesia.png";
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }
}
