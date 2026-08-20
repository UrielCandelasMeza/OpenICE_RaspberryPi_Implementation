/*******************************************************************************
 * Copyright (c) 2014, MD PnP Program
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.mdpnp.devices.masimo.radical;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.serial.AbstractSerialDevice;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket;
import org.mdpnp.devices.simulation.AbstractSimulatedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

/**
 * @author Jeff Plourde
 *
 */
public class DemoRadical7 extends AbstractSerialDevice {
    private static final Logger log = LoggerFactory.getLogger(DemoRadical7.class);

    private InstanceHolder<ice.Numeric> pulseUpdate;
    private InstanceHolder<ice.Numeric> spo2Update;
    private InstanceHolder<ice.Numeric> piUpdate;
    private InstanceHolder<ice.Numeric> sphbUpdate;
    private InstanceHolder<ice.Numeric> spocUpdate;
    private InstanceHolder<ice.Numeric> pviUpdate;
    private InstanceHolder<ice.Numeric> desatUpdate;
    private InstanceHolder<ice.Numeric> eegPsiUpdate;
    private InstanceHolder<ice.Numeric> eegEmgUpdate;
    private InstanceHolder<ice.Numeric> eegSeflUpdate;
    private InstanceHolder<ice.Numeric> eegSefrUpdate;

    private static final String MANUFACTURER_NAME = "Masimo";
    private static final String MODEL_NAME = "Radical-7";
    private static final int DEFAULT_BAUD_RATE = 9600;

    private final int baudRate;

    private class MyMasimoRadical7 extends MasimoRadical7 {

        public MyMasimoRadical7() throws NoSuchFieldException, SecurityException, IOException {
            super();
        }

        @Override
        public void firePulseOximeter() {
            super.firePulseOximeter();
            reportConnected("message received");

            DeviceClock.Reading sampleTime = super.instant();

            pulseUpdate = numericSample(pulseUpdate, getHeartRate(),
                    rosetta.MDC_PULS_OXIM_PULS_RATE.VALUE, "",
                    rosetta.MDC_DIM_BEAT_PER_MIN.VALUE,
                    sampleTime);
            spo2Update = numericSample(spo2Update, getSpO2(),
                    rosetta.MDC_PULS_OXIM_SAT_O2.VALUE, "",
                    rosetta.MDC_DIM_PERCENT.VALUE,
                    sampleTime);

            if (getPerfusionIndex() != null) {
                piUpdate = numericSample(piUpdate, getPerfusionIndex(),
                        rosetta.MDC_PULS_OXIM_PERF_REL.VALUE, "",
                        rosetta.MDC_DIM_DIMLESS.VALUE,
                        sampleTime);
            }
            if (getsphb() != null) {
                sphbUpdate = numericSample(sphbUpdate, getsphb(),
                        "Masimo_SPHB", "SpHb",
                        "g/dL",
                        sampleTime);
            }
            if (getspoc() != null) {
                spocUpdate = numericSample(spocUpdate, getspoc(),
                        "Masimo_SPOC", "SpOC",
                        rosetta.MDC_DIM_PERCENT.VALUE,
                        sampleTime);
            }
            if (getPlethVariabilityIndex() != null) {
                pviUpdate = numericSample(pviUpdate, getPlethVariabilityIndex(),
                        "Masimo_PVI", "PVI",
                        rosetta.MDC_DIM_DIMLESS.VALUE,
                        sampleTime);
            }
            if (getDesat() != null) {
                desatUpdate = numericSample(desatUpdate, getDesat(),
                        "Masimo_DESAT", "DESAT",
                        rosetta.MDC_DIM_DIMLESS.VALUE,
                        sampleTime);
            }
            if (getEegPSI() != null) {
                eegPsiUpdate = numericSample(eegPsiUpdate, getEegPSI(),
                        "Masimo_eegPSI", "EEG PSI",
                        rosetta.MDC_DIM_DIMLESS.VALUE,
                        sampleTime);
            }
            if (getEegEMG() != null) {
                eegEmgUpdate = numericSample(eegEmgUpdate, getEegEMG(),
                        "Masimo_eegEMG", "EEG EMG",
                        rosetta.MDC_DIM_PERCENT.VALUE,
                        sampleTime);
            }
            if (getEegSEFL() != null) {
                eegSeflUpdate = numericSample(eegSeflUpdate, getEegSEFL(),
                        "Masimo_eegSEFL", "EEG SEF Left",
                        "Hz",
                        sampleTime);
            }
            if (getEegSEFR() != null) {
                eegSefrUpdate = numericSample(eegSefrUpdate, getEegSEFR(),
                        "Masimo_eegSEFR", "EEG SEF Right",
                        "Hz",
                        sampleTime);
            }

            String guid = getUniqueId();
            if (guid != null && !guid.equals(deviceIdentity.serial_number)) {
                deviceIdentity.serial_number = guid;
                writeDeviceIdentity();
            }
            if (getAlarm() != null && !"".equals(getAlarm())) {
                writeDeviceAlert(getAlarm());
            } else {
                writeDeviceAlert("");
            }
        }
    }

    private final MyMasimoRadical7 fieldDelegate;

    @Override
    protected void process(int idx, InputStream inputStream, OutputStream outputStream) throws IOException {
        fieldDelegate.setInputStream(inputStream);
        fieldDelegate.run();
    }

    @Override
    protected long getMaximumQuietTime(int idx) {
        return 1100L;
    }

    @Override
    protected void doInitCommands(int idx) throws IOException {
    }

    @Override
    public SerialProvider getSerialProvider(int idx) {
        SerialProvider serialProvider = super.getSerialProvider(idx);
        serialProvider.setDefaultSerialSettings(baudRate, SerialSocket.DataBits.Eight, SerialSocket.Parity.None,
                SerialSocket.StopBits.One);
        return serialProvider;
    }

    public DemoRadical7(final Subscriber subscriber, final Publisher publisher, EventLoop eventLoop)
            throws NoSuchFieldException, SecurityException, IOException {
        this(subscriber, publisher, eventLoop, resolveBaudRate());
    }

    public DemoRadical7(final Subscriber subscriber, final Publisher publisher, EventLoop eventLoop, int baudRate)
            throws NoSuchFieldException, SecurityException, IOException {
        super(subscriber, publisher, eventLoop);
        this.baudRate = baudRate;
        AbstractSimulatedDevice.randomUDI(deviceIdentity);
        deviceIdentity.manufacturer = MANUFACTURER_NAME;
        deviceIdentity.model = MODEL_NAME;
        writeDeviceIdentity();

        this.fieldDelegate = new MyMasimoRadical7();
        log.info("Masimo Radical-7 configured with baud rate: {}", baudRate);
    }

    private static int resolveBaudRate() {
        String prop = System.getProperty("mdpnp.serial.baudrate");
        if (prop != null) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return DEFAULT_BAUD_RATE;
    }

    @Override
    protected String iconResourceName() {
        return "radical7.png";
    }

}
