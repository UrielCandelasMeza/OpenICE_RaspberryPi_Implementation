/*******************************************************************************
 * Copyright (c) 2024, MD PnP Program
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.mdpnp.devices.philips.efficia;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mdpnp.devices.DeviceClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clinical simulation engine for the {@link SimEfficiaMonitor}.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li><b>DDS publishing</b> — publishes each metric to the {@code ice.Numeric} topic every
 *       second so that the TimescalePersister can store them automatically.</li>
 *   <li><b>HL7 v2.4 / MLLP TCP server</b> — accepts external clients on port
 *       {@link SimEfficiaMonitor#HL7_PORT} and pushes an ORU^R01 message every 60 s as well as
 *       an MSH-only keep-alive at startup and every hour.</li>
 *   <li><b>UDP control listener</b> — listens on port {@link SimEfficiaMonitor#UDP_PORT} and
 *       interprets simple text commands to change the simulated patient state.</li>
 *   <li><b>Alarm handling</b> — writes patient alerts to DDS via
 *       {@code writePatientAlert()} when the state requests it.</li>
 * </ul>
 *
 * <h3>MDIL → OpenICE metric mapping</h3>
 * <pre>
 *   MDIL code   MDC metric                          Unit
 *   0002-4182   MDC_ECG_HEART_RATE                  bpm
 *   0002-4190   MDC_PULS_OXIM_SAT_O2                %
 *   0002-4184   MDC_CO2_RESP_RATE                   rpm
 *   0002-4186   MDC_PRESS_BLD_NONINV_SYS            mmHg
 *   0002-4187   MDC_PRESS_BLD_NONINV_DIA            mmHg
 *   0002-4188   MDC_TEMP_BLD                        Cel
 * </pre>
 */
class EfficiaClinicalEngine {

    private static final Logger log = LoggerFactory.getLogger(EfficiaClinicalEngine.class);

    // ── MLLP framing bytes ────────────────────────────────────────────────────
    private static final byte MLLP_SB = 0x0B;   // Start Block
    private static final byte MLLP_EB = 0x1C;   // End Block
    private static final byte MLLP_CR = 0x0D;   // Carriage Return

    // ── Jitter amplitude for "realistic" waveforms ────────────────────────────
    private static final int   HR_JITTER   = 3;
    private static final int   SPO2_JITTER = 1;
    private static final int   RESP_JITTER = 2;
    private static final float TEMP_JITTER = 0.1f;

    // ── References ────────────────────────────────────────────────────────────
    private final SimEfficiaMonitor device;
    private final ScheduledExecutorService executor;

    // ── State ─────────────────────────────────────────────────────────────────
    volatile boolean running;
    private final PatientState state = new PatientState();

    // ── Networking ────────────────────────────────────────────────────────────
    // Per the Efficia CM manual: in LAN/WLAN mode the monitor is the TCP CLIENT.
    // It connects outbound to a listener server. We keep a reference to that
    // single outbound connection so broadcastMllp can write to it.
    private volatile Socket hl7Connection;
    private DatagramSocket udpSocket;
    private final AtomicInteger msgCounter = new AtomicInteger(1);

    // ── Scheduled tasks ───────────────────────────────────────────────────────
    private ScheduledFuture<?> ddsTask;
    private ScheduledFuture<?> hl7Task;
    private ScheduledFuture<?> keepAliveTask;

    // ── Simulated patient state ────────────────────────────────────────────────
    static final class PatientState {
        volatile int   heartRate    = 72;
        volatile int   spo2         = 98;
        volatile int   respRate     = 14;
        volatile int   nibpSystolic = 120;
        volatile int   nibpDiastolic = 80;
        volatile float temperature  = 36.8f;
        volatile String alarmType;    // null = no active alarm
        volatile int    alarmPriority; // 1=High/Red, 2=Medium/Yellow, 6=Low/Soft Inop
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    EfficiaClinicalEngine(SimEfficiaMonitor device, ScheduledExecutorService executor) {
        this.device   = device;
        this.executor = executor;
    }

    PatientState getState() {
        return state;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    void start() {
        running = true;

        // Start TCP MLLP server
        // Real Efficia = TCP client in LAN/WLAN mode
        startTcpClient();

        // Start UDP control listener
        startUdpListener();

        // Publish DDS numeric samples every 1 second
        ddsTask = executor.scheduleAtFixedRate(this::publishDdsSamples, 0, 1, TimeUnit.SECONDS);

        // Push HL7 ORU every 60 seconds
        hl7Task = executor.scheduleAtFixedRate(this::broadcastOru, 5, 60, TimeUnit.SECONDS);

        // Keep-alive: send MSH-only ORU at startup (after 1 s) and then every hour
        keepAliveTask = executor.scheduleAtFixedRate(this::sendKeepAlive, 1, 3600, TimeUnit.SECONDS);

        log.info("EfficiaClinicalEngine started");
    }

    void stop() {
        running = false;

        cancelQuietly(ddsTask);
        cancelQuietly(hl7Task);
        cancelQuietly(keepAliveTask);

        closeQuietly(hl7Connection);
        closeQuietly(udpSocket);

        log.info("EfficiaClinicalEngine stopped");
    }

    // ── DDS publishing ────────────────────────────────────────────────────────

    private void publishDdsSamples() {
        try {
            DeviceClock.Reading now = device.clockReading();

            int hr   = jitterInt(state.heartRate,    HR_JITTER);
            int sp   = jitterInt(state.spo2,         SPO2_JITTER);
            int rr   = jitterInt(state.respRate,      RESP_JITTER);
            int sys  = state.nibpSystolic;
            int dia  = state.nibpDiastolic;
            float t  = jitterFloat(state.temperature, TEMP_JITTER);

            device.publishNumericSample(device.heartRate,     hr,  now);
            device.publishNumericSample(device.spo2,          sp,  now);
            device.publishNumericSample(device.respRate,      rr,  now);
            device.publishNumericSample(device.nibpSystolic,  sys, now);
            device.publishNumericSample(device.nibpDiastolic, dia, now);
            device.publishNumericSample(device.temperature,   t,   now);

            // Publish alert if active
            if (state.alarmType != null) {
                String alertKey = "MDIL-ALERT_" + state.alarmType;
                String alertText = state.alarmType + " [priority=" + state.alarmPriority + "]";
                device.publishPatientAlert(alertKey, alertText);
            }

        } catch (Exception e) {
            log.error("Error publishing DDS samples", e);
        }
    }

    // ── HL7 ORU^R01 building and broadcasting ─────────────────────────────────

    /**
     * Builds a complete HL7 v2.4 ORU^R01 message using pipe-delimited plain text
     * (no HAPI library required — the Efficia format is a strict, predictable subset).
     */
    private String buildOruMessage() {
        String ts = timestamp();
        int    id = msgCounter.getAndIncrement();

        int hr   = jitterInt(state.heartRate,    HR_JITTER);
        int sp   = jitterInt(state.spo2,         SPO2_JITTER);
        int rr   = jitterInt(state.respRate,      RESP_JITTER);
        int sys  = state.nibpSystolic;
        int dia  = state.nibpDiastolic;
        float t  = jitterFloat(state.temperature, TEMP_JITTER);

        StringBuilder sb = new StringBuilder();

        // MSH
        sb.append("MSH|^~\\&|EfficiaSim|Philips|HostSystem|OpenICE|").append(ts)
          .append("||ORU^R01|MSG").append(String.format("%08d", id))
          .append("|P|2.4\r");

        // PID
        sb.append("PID|||PAT001^^^^MRN||Doe^John^^^^^L||19800101|M\r");

        // PV1
        sb.append("PV1|||ICU_BED_01\r");

        // ORC
        sb.append("ORC|RE\r");

        // OBR
        sb.append("OBR|1|||0000-0000^General^MDIL\r");

        // OBX rows — MDIL format: <partition>-<termcode>^<label>^MDIL
        int obx = 1;
        sb.append(obxRow(obx++, "NM", "0002-4182", "HR",   hr,  "0004-0aa0", "bpm"));
        sb.append(obxRow(obx++, "NM", "0002-4190", "SpO2", sp,  "0004-0aa0", "%"));
        sb.append(obxRow(obx++, "NM", "0002-4184", "RR",   rr,  "0004-0aa0", "rpm"));
        sb.append(obxRow(obx++, "NM", "0002-4186", "NBPs", sys, "0004-0aa0", "mmHg"));
        sb.append(obxRow(obx++, "NM", "0002-4187", "NBPd", dia, "0004-0aa0", "mmHg"));
        sb.append(obxRow(obx++, "NM", "0002-4188", "Temp", t,   "0004-0aa0", "Cel"));

        // Optional alarm OBX
        if (state.alarmType != null) {
            sb.append("OBX|").append(obx).append("|TX|MDIL-ALERT^Alert^MDIL||")
              .append(state.alarmType).append(" priority ").append(state.alarmPriority)
              .append("|||||||F\r");
        }

        return sb.toString();
    }

    private String obxRow(int seq, String type, String mdilCode, String label,
                           Number value, String unitCode, String unitLabel) {
        return "OBX|" + seq + "|" + type + "|" + mdilCode + "^" + label + "^MDIL||"
                + value + "|" + unitCode + "^" + unitLabel + "^MDIL|||||F\r";
    }

    /** Wraps a message string in MLLP framing bytes. */
    private byte[] mllpWrap(String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.US_ASCII);
        byte[] frame    = new byte[msgBytes.length + 3];
        frame[0] = MLLP_SB;
        System.arraycopy(msgBytes, 0, frame, 1, msgBytes.length);
        frame[frame.length - 2] = MLLP_EB;
        frame[frame.length - 1] = MLLP_CR;
        return frame;
    }

    private void broadcastOru() {
        String msg = buildOruMessage();
        broadcastMllp(msg);
    }

    /** Sends an MSH-only ORU for keep-alive / clock sync. */
    private void sendKeepAlive() {
        String ts = timestamp();
        int    id = msgCounter.getAndIncrement();
        String ka = "MSH|^~\\&|EfficiaSim|Philips|HostSystem|OpenICE|" + ts
                  + "||ORU^R01|KA_" + id + "|P|2.4\r";
        broadcastMllp(ka);
        log.debug("HL7 keep-alive sent to {} clients", clientCount());
    }

    private void broadcastMllp(String message) {
        Socket conn = hl7Connection;
        if (conn == null || conn.isClosed()) {
            log.debug("No HL7 listener connected — skipping MLLP send");
            return;
        }
        byte[] frame = mllpWrap(message);
        try {
            OutputStream out = conn.getOutputStream();
            out.write(frame);
            out.flush();
        } catch (IOException e) {
            log.warn("HL7 listener disconnected: {}", e.getMessage());
            closeQuietly(conn);
            hl7Connection = null;
        }
    }

    private int clientCount() {
        return hl7Connection != null && !hl7Connection.isClosed() ? 1 : 0;
    }

    // ── TCP Client (MLLP) — Efficia is the CLIENT in LAN/WLAN mode ───────────
    //
    // Per the Efficia CM Series manual: in LAN/WLAN the monitor connects OUTBOUND
    // to a configured listener server (IP + port set on the monitor itself).
    // Our simulator replicates this behaviour: it attempts to connect to the
    // HL7 listener and retries every 5 s until the engine is stopped.

    private void startTcpClient() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    log.info("HL7/MLLP: connecting to listener {}:{}",
                            SimEfficiaMonitor.HL7_HOST, SimEfficiaMonitor.HL7_PORT);
                    Socket conn = new Socket(SimEfficiaMonitor.HL7_HOST,
                                            SimEfficiaMonitor.HL7_PORT);
                    hl7Connection = conn;
                    log.info("HL7/MLLP: connected to listener");
                    // Announce ourselves with a keep-alive right after connect
                    executor.submit(this::sendKeepAlive);
                    // Block until the listener closes the connection
                    conn.getInputStream().transferTo(OutputStream.nullOutputStream());
                    log.info("HL7/MLLP: listener closed connection, will retry");
                } catch (IOException e) {
                    if (running) {
                        log.warn("HL7/MLLP: could not reach listener ({}), retrying in 5 s",
                                e.getMessage());
                    }
                } finally {
                    Socket old = hl7Connection;
                    hl7Connection = null;
                    closeQuietly(old);
                }
                if (running) {
                    try { Thread.sleep(5_000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "efficia-tcp-client");
        t.setDaemon(true);
        t.start();
    }

    // ── UDP Listener (Control) ────────────────────────────────────────────────

    private void startUdpListener() {
        Thread t = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(SimEfficiaMonitor.UDP_PORT);
                log.info("UDP control listener on port {}", SimEfficiaMonitor.UDP_PORT);
                byte[] buf = new byte[512];
                while (running) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        udpSocket.receive(packet);
                        String cmd = new String(packet.getData(), 0,
                                packet.getLength(), StandardCharsets.UTF_8).trim();
                        String reply = processCommand(cmd);
                        if (reply != null) {
                            byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                            DatagramPacket response = new DatagramPacket(
                                    replyBytes, replyBytes.length,
                                    packet.getAddress(), packet.getPort());
                            udpSocket.send(response);
                        }
                    } catch (SocketException se) {
                        if (running) log.error("UDP receive error", se);
                    }
                }
            } catch (IOException e) {
                if (running) log.error("Failed to start UDP listener on port {}",
                        SimEfficiaMonitor.UDP_PORT, e);
            }
        }, "efficia-udp");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Processes a UDP control command and returns an optional reply string.
     *
     * <p>Supported commands:
     * <ul>
     *   <li>{@code HR=<n>}</li>
     *   <li>{@code SPO2=<n>}</li>
     *   <li>{@code RESP=<n>}</li>
     *   <li>{@code TEMP=<n>}</li>
     *   <li>{@code NIBP=<sys>/<dia>}</li>
     *   <li>{@code ALARM=<type>,<priority>}</li>
     *   <li>{@code ALARM_CLEAR}</li>
     *   <li>{@code STATUS}</li>
     * </ul>
     */
    private String processCommand(String cmd) {
        try {
            if (cmd.startsWith("HR=")) {
                state.heartRate = Integer.parseInt(cmd.substring(3));
                log.info("CMD: HR set to {}", state.heartRate);

            } else if (cmd.startsWith("SPO2=")) {
                state.spo2 = Integer.parseInt(cmd.substring(5));
                log.info("CMD: SpO2 set to {}", state.spo2);

            } else if (cmd.startsWith("RESP=")) {
                state.respRate = Integer.parseInt(cmd.substring(5));
                log.info("CMD: RespRate set to {}", state.respRate);

            } else if (cmd.startsWith("TEMP=")) {
                state.temperature = Float.parseFloat(cmd.substring(5));
                log.info("CMD: Temp set to {}", state.temperature);

            } else if (cmd.startsWith("NIBP=")) {
                String[] parts = cmd.substring(5).split("/");
                state.nibpSystolic  = Integer.parseInt(parts[0].trim());
                state.nibpDiastolic = Integer.parseInt(parts[1].trim());
                log.info("CMD: NIBP set to {}/{}", state.nibpSystolic, state.nibpDiastolic);
                // Trigger immediate HL7 ORU for the completed NIBP measurement
                executor.submit(this::broadcastOru);

            } else if (cmd.startsWith("ALARM=")) {
                String[] parts = cmd.substring(6).split(",");
                state.alarmType     = parts[0].trim().toUpperCase(Locale.ROOT);
                state.alarmPriority = Integer.parseInt(parts[1].trim());
                log.info("CMD: Alarm triggered — type={} priority={}", state.alarmType, state.alarmPriority);
                // Publish aperiodic ORU immediately for the alarm
                executor.submit(this::broadcastOru);

            } else if (cmd.equals("ALARM_CLEAR")) {
                String key = state.alarmType != null ? "MDIL-ALERT_" + state.alarmType : null;
                state.alarmType = null;
                state.alarmPriority = 0;
                if (key != null) {
                    device.publishPatientAlert(key, null);
                }
                log.info("CMD: Alarm cleared");

            } else if (cmd.equals("STATUS")) {
                return String.format(Locale.ROOT,
                        "HR=%d SpO2=%d RR=%d NIBP=%d/%d Temp=%.1f Alarm=%s/%d",
                        state.heartRate, state.spo2, state.respRate,
                        state.nibpSystolic, state.nibpDiastolic,
                        state.temperature,
                        state.alarmType != null ? state.alarmType : "NONE",
                        state.alarmPriority);

            } else {
                log.warn("Unknown UDP command: '{}'", cmd);
            }
        } catch (Exception e) {
            log.warn("Error processing command '{}': {}", cmd, e.getMessage());
        }
        return null;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static int jitterInt(int base, int amplitude) {
        return base + (int) (Math.random() * (amplitude * 2 + 1)) - amplitude;
    }

    private static float jitterFloat(float base, float amplitude) {
        return base + (float) (Math.random() * amplitude * 2) - amplitude;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    private static void cancelQuietly(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
