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

import ice.GlobalSimulationObjective;

import org.mdpnp.devices.simulation.AbstractSimulatedConnectedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

/**
 * Simulated Philips Efficia CM Series patient monitor.
 *
 * <p>This device driver emulates the Efficia CM Series behaviour:
 * <ul>
 *   <li>Generates simulated clinical data (HR, SpO2, NIBP, RR, Temp)</li>
 *   <li>Publishes metrics on DDS {@code ice.Numeric} topic every second</li>
 *   <li>Exposes an HL7 v2.4 / MLLP TCP server on port {@link #HL7_PORT} and
 *       pushes ORU^R01 messages to connected hosts every 60 seconds</li>
 *   <li>Listens for UDP control commands on port {@link #UDP_PORT}</li>
 * </ul>
 *
 * <p>To run in headless mode:
 * <pre>
 *   ./gradlew :headless-adapter:run --args="-domain 0 -device EfficiaMonitor"
 * </pre>
 *
 * <p>Supported UDP commands (send to 127.0.0.1:{@link #UDP_PORT}):
 * <ul>
 *   <li>{@code HR=<value>} — set heart rate</li>
 *   <li>{@code SPO2=<value>} — set SpO2 %</li>
 *   <li>{@code RESP=<value>} — set respiratory rate</li>
 *   <li>{@code TEMP=<value>} — set body temperature (°C)</li>
 *   <li>{@code NIBP=<sys>/<dia>} — trigger NIBP measurement with result</li>
 *   <li>{@code ALARM=<type>,<priority>} — trigger alarm (e.g. ASYSTOLE,1)</li>
 *   <li>{@code ALARM_CLEAR} — clear the active alarm</li>
 *   <li>{@code STATUS} — query current patient state (UDP reply)</li>
 * </ul>
 */
public class SimEfficiaMonitor extends AbstractSimulatedConnectedDevice {

    private static final Logger log = LoggerFactory.getLogger(SimEfficiaMonitor.class);

    /** Default HL7 v2.4 / MLLP TCP port used by real Efficia CM monitors. */
    public static final int    HL7_PORT = 2575;

    /**
     * Host where the HL7 listener server is running.
     * In LAN/WLAN mode the real Efficia monitor is the CLIENT — it connects
     * outbound to a configured host:port. Change this to the IP address of the
     * machine running {@link scripts.EfficiaHL7Listener} (or your real HL7 server).
     */
    public static final String HL7_HOST = "localhost";

    /** UDP port for receiving control/simulation commands. */
    public static final int UDP_PORT = 24106;

    /**
     * Serial device for RS-232 output.
     *
     * <p>Set to the UART device on the Raspberry Pi:
     * <ul>
     *   <li>{@code /dev/ttyAMA0} — native GPIO UART (pins 14 TX / 15 RX),
     *       requires {@code enable_uart=1} in {@code /boot/config.txt} and
     *       the serial console disabled ({@code raspi-config → Interface → Serial}).</li>
     *   <li>{@code /dev/ttyUSB0} — if using a USB-to-serial adapter instead.</li>
     * </ul>
     *
     * <p>The port must be configured before starting the engine:
     * <pre>  stty -F /dev/ttyAMA0 9600 cs8 -cstopb -parenb raw</pre>
     *
     * <p>Set to an empty string {@code ""} to disable serial output entirely.
     */
    public static final String SERIAL_PORT = "/dev/ttyS0";

    // ── DDS instance holders ──────────────────────────────────────────────────

    final InstanceHolder<ice.Numeric> heartRate;
    final InstanceHolder<ice.Numeric> spo2;
    final InstanceHolder<ice.Numeric> respRate;
    final InstanceHolder<ice.Numeric> nibpSystolic;
    final InstanceHolder<ice.Numeric> nibpDiastolic;
    final InstanceHolder<ice.Numeric> temperature;

    // ── Internal engine ───────────────────────────────────────────────────────

    private EfficiaClinicalEngine clinicalEngine;

    // ── Constructor ───────────────────────────────────────────────────────────

    public SimEfficiaMonitor(final Subscriber subscriber,
                             final Publisher publisher,
                             final EventLoop eventLoop) {
        super(subscriber, publisher, eventLoop);

        // Pre-allocate DDS numeric instance holders
        heartRate     = createNumericInstance(rosetta.MDC_ECG_HEART_RATE.VALUE,       "");
        spo2          = createNumericInstance(rosetta.MDC_PULS_OXIM_SAT_O2.VALUE,     "");
        respRate      = createNumericInstance(rosetta.MDC_CO2_RESP_RATE.VALUE,        "");
        nibpSystolic  = createNumericInstance(rosetta.MDC_PRESS_BLD_NONINV_SYS.VALUE, "");
        nibpDiastolic = createNumericInstance(rosetta.MDC_PRESS_BLD_NONINV_DIA.VALUE, "");
        temperature   = createNumericInstance(rosetta.MDC_TEMP_BLD.VALUE,             "");

        deviceIdentity.manufacturer = "Philips";
        deviceIdentity.model        = "Efficia CM Series (Simulated)";
        writeDeviceIdentity();
    }

    // ── AbstractSimulatedConnectedDevice lifecycle ────────────────────────────

    @Override
    public boolean connect(String address) {
        if (!super.connect(address)) {
            return false;
        }

        clinicalEngine = new EfficiaClinicalEngine(this, executor);
        clinicalEngine.start();

        log.info("Efficia CM Series simulator started — HL7/MLLP port {}, UDP control port {}",
                HL7_PORT, UDP_PORT);
        return true;
    }

    @Override
    public void disconnect() {
        if (clinicalEngine != null) {
            clinicalEngine.stop();
            clinicalEngine = null;
        }
        super.disconnect();
    }

    // ── GlobalSimulationObjectiveListener ────────────────────────────────────

    @Override
    public void simulatedNumeric(GlobalSimulationObjective obj) {
        if (obj == null || clinicalEngine == null) {
            return;
        }
        EfficiaClinicalEngine.PatientState state = clinicalEngine.getState();
        try {
            int intVal  = (int) Math.round(obj.value);
            float fVal  = obj.value;

            if (rosetta.MDC_ECG_HEART_RATE.VALUE.equals(obj.metric_id)) {
                state.heartRate = intVal;
            } else if (rosetta.MDC_PULS_OXIM_SAT_O2.VALUE.equals(obj.metric_id)) {
                state.spo2 = intVal;
            } else if (rosetta.MDC_CO2_RESP_RATE.VALUE.equals(obj.metric_id)) {
                state.respRate = intVal;
            } else if (rosetta.MDC_PRESS_BLD_NONINV_SYS.VALUE.equals(obj.metric_id)) {
                state.nibpSystolic = intVal;
            } else if (rosetta.MDC_PRESS_BLD_NONINV_DIA.VALUE.equals(obj.metric_id)) {
                state.nibpDiastolic = intVal;
            } else if (rosetta.MDC_TEMP_BLD.VALUE.equals(obj.metric_id)) {
                state.temperature = fVal;
            }
        } catch (Exception e) {
            log.warn("Error applying simulation objective", e);
        }
    }

    /**
     * Package-private bridge so that {@link EfficiaClinicalEngine} (a helper class in the
     * same package, but not a subclass of {@code AbstractDevice}) can publish patient alerts.
     * {@code writePatientAlert} is {@code protected} in {@code AbstractDevice} and therefore
     * not directly callable by a non-subclass in a different package.
     */
    void publishPatientAlert(String key, String value) {
        writePatientAlert(key, value);
    }

    /**
     * Package-private bridge so that {@link EfficiaClinicalEngine} can obtain a
     * {@link org.mdpnp.devices.DeviceClock.Reading} without needing access to the
     * {@code protected} {@code getClockProvider()} method on {@code AbstractDevice}.
     */
    org.mdpnp.devices.DeviceClock.Reading clockReading() {
        return getClockProvider().instant();
    }

    /**
     * Package-private bridge so that {@link EfficiaClinicalEngine} can publish a numeric
     * sample without needing access to the {@code protected} {@code numericSample()} method
     * on {@code AbstractSimulatedConnectedDevice}.
     */
    void publishNumericSample(InstanceHolder<ice.Numeric> holder, float value,
                              org.mdpnp.devices.DeviceClock.Reading time) {
        numericSample(holder, value, time);
    }

    @Override
    protected String iconResourceName() {
        return "patient_monitor.png";
    }
}
