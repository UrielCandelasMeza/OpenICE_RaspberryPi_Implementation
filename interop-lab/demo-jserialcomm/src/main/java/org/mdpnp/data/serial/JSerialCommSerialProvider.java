package org.mdpnp.data.serial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialSocket;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Serial provider backed by jSerialComm — an actively-maintained library
 * (v2.11.4, LGPL-3 / Apache-2) with bundled native JNI code for Linux x86,
 * x86_64, ARM, ARM64.  Replaces the abandoned PureJavaComm (2017) while
 * preserving the exact same {@link SerialProvider}/{@link SerialSocket} SPI
 * that all 22 device drivers consume.
 */
public class JSerialCommSerialProvider implements SerialProvider {

    // ── Default serial settings ────────────────────────────────────────────

    private static class DefaultSerialSettings {
        private final int baud;
        private final SerialSocket.DataBits dataBits;
        private final SerialSocket.Parity parity;
        private final SerialSocket.StopBits stopBits;
        private final SerialSocket.FlowControl flowControl;

        DefaultSerialSettings(int baud, SerialSocket.DataBits dataBits, SerialSocket.Parity parity,
                SerialSocket.StopBits stopBits, SerialSocket.FlowControl flowControl) {
            this.baud = baud;
            this.dataBits = dataBits;
            this.parity = parity;
            this.stopBits = stopBits;
            this.flowControl = flowControl;
        }

        void configurePort(SerialSocket socket) {
            socket.setSerialParams(baud, dataBits, parity, stopBits, flowControl);
        }
    }

    private DefaultSerialSettings defaultSettings =
            new DefaultSerialSettings(9600, SerialSocket.DataBits.Eight,
                    SerialSocket.Parity.None, SerialSocket.StopBits.One,
                    SerialSocket.FlowControl.None);

    // ── SerialSocket implementation ────────────────────────────────────────

    private static class SocketImpl implements SerialSocket {
        private final SerialPort serialPort;
        private final String portIdentifier;

        SocketImpl(SerialPort serialPort, String portIdentifier) {
            this.serialPort = serialPort;
            this.portIdentifier = portIdentifier;
        }

        @Override
        public String getPortIdentifier() {
            return portIdentifier;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return serialPort.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return serialPort.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            serialPort.closePort();
        }

        @Override
        public void setSerialParams(int baud, DataBits dataBits, Parity parity,
                StopBits stopBits, FlowControl flowControl) {
            int db;
            switch (dataBits) {
                case Seven: db = 7; break;
                default:    db = 8; break;
            }
            int sb;
            switch (stopBits) {
                case Two:            sb = SerialPort.TWO_STOP_BITS;             break;
                case OneAndOneHalf:  sb = SerialPort.ONE_POINT_FIVE_STOP_BITS;  break;
                default:             sb = SerialPort.ONE_STOP_BIT;              break;
            }
            int p;
            switch (parity) {
                case Even: p = SerialPort.EVEN_PARITY; break;
                case Odd:  p = SerialPort.ODD_PARITY;  break;
                default:   p = SerialPort.NO_PARITY;   break;
            }
            int fc;
            switch (flowControl) {
                case Hardware: fc = SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED; break;
                case Software: fc = SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED; break;
                default:       fc = SerialPort.FLOW_CONTROL_DISABLED; break;
            }
            if (!serialPort.setComPortParameters(baud, db, sb, p)) {
                throw new RuntimeException("setComPortParams failed for " + portIdentifier);
            }
            if (!serialPort.setFlowControl(fc)) {
                throw new RuntimeException("setFlowControl failed for " + portIdentifier);
            }
        }
    }

    // ── SerialProvider implementation ──────────────────────────────────────

    @Override
    public List<String> getPortNames() {
        List<String> list = new ArrayList<>();
        for (SerialPort sp : SerialPort.getCommPorts()) {
            list.add(sp.getSystemPortName());
        }
        Collections.sort(list);
        return list;
    }

    @Override
    public SerialSocket connect(String portIdentifier, long timeout) throws IOException {
        for (SerialPort sp : SerialPort.getCommPorts()) {
            if (sp.getSystemPortName().equals(portIdentifier)) {
                sp.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
                if (!sp.openPort()) {
                    throw new IOException("Failed to open port " + portIdentifier);
                }
                SocketImpl socket = new SocketImpl(sp, portIdentifier);
                doConfigurePort(socket);
                return socket;
            }
        }
        throw new IOException("Unknown port: " + portIdentifier);
    }

    @Override
    public void cancelConnect() {
        // jSerialComm connect() is synchronous; nothing to cancel
    }

    @Override
    public void setDefaultSerialSettings(int baudrate, SerialSocket.DataBits dataBits,
            SerialSocket.Parity parity, SerialSocket.StopBits stopBits) {
        setDefaultSerialSettings(baudrate, dataBits, parity, stopBits, SerialSocket.FlowControl.None);
    }

    @Override
    public void setDefaultSerialSettings(int baudrate, SerialSocket.DataBits dataBits,
            SerialSocket.Parity parity, SerialSocket.StopBits stopBits,
            SerialSocket.FlowControl flowControl) {
        this.defaultSettings = new DefaultSerialSettings(baudrate, dataBits, parity, stopBits, flowControl);
    }

    protected void doConfigurePort(SerialSocket socket) {
        defaultSettings.configurePort(socket);
    }

    @Override
    public SerialProvider duplicate() {
        JSerialCommSerialProvider dup = new JSerialCommSerialProvider();
        dup.defaultSettings = this.defaultSettings;
        return dup;
    }
}
