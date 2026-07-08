#!/usr/bin/env python3
"""
Draeger Atlan A-350XL Receiver Client Script.
Connects to the simulated device via LAN (HL7/TCP) and Serial (Medibus).

Usage:
    python3 scripts/atlan_receiver.py --serial /dev/ttyUSB1 --host 127.0.0.1 --port 2575
"""

import argparse
import socket
import sys
import time
import threading

try:
    import serial
except ImportError:
    print("Error: 'pyserial' package is not installed. Install it with: pip install pyserial")
    sys.exit(1)

# MLLP delimiters
MLLP_SB = b'\x0b'
MLLP_EB = b'\x1c'
MLLP_CR = b'\x0d'

# Medibus delimiters
MEDIBUS_SOH = b'\x01'
MEDIBUS_ESC = b'\x1b'
MEDIBUS_CR = b'\x0d'


def log_raw_bytes(data):
    """Appends raw message bytes to 'datos_atlan.txt' with a header separator, converting carriage returns to newlines."""
    try:
        # Convert carriage returns to newlines to match datos_efficia.txt layout
        formatted_data = data.replace(b'\r', b'\n')
        with open("datos_atlan.txt", "ab") as f:
            f.write(b"--- Nuevo Registro ---\n")
            f.write(formatted_data)
            f.write(b"\n")
    except Exception as e:
        print(f"Failed to log raw bytes: {e}")

def calculate_medibus_checksum(cmd_code, payload=b""):
    """Calculates the 8-bit sum modulo 256 and returns a 2-character uppercase hex string."""
    total = cmd_code
    for b in payload:
        total = (total + b) & 0xff
    return f"{total:02X}".encode('ascii')


def make_medibus_frame(cmd_code, payload=b""):
    """Wraps a Medibus command into an ESC-framed packet with checksum."""
    checksum_bytes = calculate_medibus_checksum(cmd_code, payload)
    return MEDIBUS_ESC + bytes([cmd_code]) + payload + checksum_bytes + MEDIBUS_CR


def parse_medibus_response(frame):
    """Parses and prints a received SOH-framed response."""
    if len(frame) < 3:
        return
    
    cmd_echo = frame[0]
    payload = frame[1:-2]
    recv_checksum = frame[-2:]
    
    # Verify checksum
    total = cmd_echo
    for b in payload:
        total = (total + b) & 0xff
    calc_checksum = f"{total:02X}".encode('ascii')
    
    if calc_checksum != recv_checksum:
        print(f"[Medibus Warning] Checksum mismatch! Calc: {calc_checksum}, Recv: {recv_checksum}")
        return

    cmd_hex = f"0x{cmd_echo:02X}"
    print(f"\n--- Medibus Response for cmd {cmd_hex} ---")
    
    # Parse based on command
    if cmd_echo == 0x51:
        print("ICC (Initialization) successful.")
    elif cmd_echo == 0x52:
        try:
            payload_str = payload.decode('ascii', errors='ignore')
            dev_id = payload_str[0:4]
            # Extract name within apostrophes
            start = payload_str.find("'")
            end = payload_str.find("'", start + 1) if start != -1 else -1
            name = payload_str[start+1:end] if end != -1 else "Unknown"
            rev = payload_str[end+1:].strip() if end != -1 else ""
            print(f"Device Identification: ID={dev_id}, Name={name}, Revision={rev}")
        except Exception as e:
            print(f"Failed to parse device ID payload: {payload}, error={e}")
    elif cmd_echo == 0x24: # ReqMeasuredDataCP1
        # Each item is 6 bytes (2 code + 4 value)
        data_str = payload.decode('ascii', errors='ignore')
        print("Measured Data (CP1):")
        for i in range(0, len(data_str), 6):
            chunk = data_str[i:i+6]
            if len(chunk) < 6:
                break
            code = chunk[0:2]
            val = chunk[2:].strip()
            # Map typical codes
            metric = {
                "7D": "Peak Pressure (mbar)",
                "78": "PEEP (mbar)",
                "D6": "Respiratory Rate (rpm)",
                "B9": "Minute Volume (L/min)",
                "F0": "Inspired O2 (%)"
            }.get(code, f"Unknown code {code}")
            print(f"  - {metric}: {val}")
    elif cmd_echo == 0x29: # ReqDeviceSetting
        # Each item is 7 bytes (2 code + 5 value)
        data_str = payload.decode('ascii', errors='ignore')
        print("Device Settings:")
        for i in range(0, len(data_str), 7):
            chunk = data_str[i:i+7]
            if len(chunk) < 7:
                break
            code = chunk[0:2]
            val = chunk[2:].strip()
            setting = {
                "01": "Inspired O2 Target (%)",
                "04": "Tidal Volume Target (L)",
                "09": "SIMV Frequency (rpm)",
                "0B": "PEEP Target (mbar)"
            }.get(code, f"Unknown code {code}")
            print(f"  - {setting}: {val}")
    elif cmd_echo == 0x27: # ReqAlarmsCP1
        # Each active alarm is 15 bytes (1 priority + 2 code + 12 phrase)
        data_str = payload.decode('ascii', errors='ignore')
        if not data_str:
            print("No active alarms.")
        else:
            print("Active Alarms:")
            for i in range(0, len(data_str), 15):
                chunk = data_str[i:i+15]
                if len(chunk) < 15:
                    break
                priority = chunk[0]
                code = chunk[1:3]
                phrase = chunk[3:].strip()
                print(f"  - [{priority}] Code={code}: {phrase}")
    else:
        print(f"Raw Payload: {payload}")


def run_serial_client(port_name):
    """Runs a Medibus master loop query on the serial port using a robust read timeout loop."""
    print(f"Starting Medibus serial client on port {port_name}...")
    try:
        ser = serial.Serial(
            port=port_name,
            baudrate=19200,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_EVEN,
            stopbits=serial.STOPBITS_ONE,
            timeout=0.1  # Short timeout for responsive multiplexing
        )
    except Exception as e:
        print(f"Failed to open serial port {port_name}: {e}")
        return

    # Initialize connection (ICC)
    print("Sending ICC...")
    ser.write(make_medibus_frame(0x51))
    
    commands = [
        0x52,  # ReqDeviceId
        0x24,  # ReqMeasuredDataCP1
        0x29,  # ReqDeviceSetting
        0x27   # ReqAlarmsCP1
    ]
    cmd_idx = 0
    last_query_time = time.time()

    buffer = bytearray()
    in_frame = False

    while True:
        # Read 1 byte (blocks up to 0.1s)
        b = ser.read(1)
        if b:
            if b == MEDIBUS_SOH:
                in_frame = True
                buffer.clear()
            elif b == MEDIBUS_CR:
                if in_frame:
                    in_frame = False
                    full_frame = MEDIBUS_SOH + buffer + MEDIBUS_CR
                    log_raw_bytes(full_frame)
                    parse_medibus_response(buffer)
            elif in_frame:
                buffer.extend(b)
        
        # Periodic query check: if 2 seconds have passed since last query, send next command
        now = time.time()
        if now - last_query_time >= 2.0:
            target_cmd = commands[cmd_idx]
            cmd_idx = (cmd_idx + 1) % len(commands)
            try:
                ser.write(make_medibus_frame(target_cmd))
            except Exception as e:
                print(f"Serial write error: {e}")
                break
            last_query_time = now


def handle_hl7_mllp_message(msg_str, sock):
    """Parses trend metrics and sends HL7 ACK."""
    segments = msg_str.split('\r')
    msh_fields = segments[0].split('|') if len(segments) > 0 else []
    
    if len(msh_fields) < 10:
        return
        
    msg_ctrl_id = msh_fields[9]
    print(f"\n--- HL7 Message Received (CtrlID: {msg_ctrl_id}) ---")
    
    # Process OBX segments
    for seg in segments:
        if seg.startswith("OBX"):
            fields = seg.split('|')
            if len(fields) >= 7:
                metric_name_comp = fields[3].split('^')
                metric_label = metric_name_comp[1] if len(metric_name_comp) > 1 else fields[3]
                value = fields[5]
                units_comp = fields[6].split('^')
                unit_label = units_comp[1] if len(units_comp) > 1 else fields[6]
                
                print(f"  * {metric_label}: {value} {unit_label}")

    # Build MLLP ACK response
    ts = time.strftime("%Y%m%d%H%M%S")
    ack = (
        f"MSH|^~\\&|AtlanReceiver|Draeger|||{ts}||ACK^^ACK ALL|{msg_ctrl_id}|P|2.4\r"
        f"MSA|AA|{msg_ctrl_id}\r"
    )
    frame = MLLP_SB + ack.encode('ascii') + MLLP_EB + MLLP_CR
    try:
        sock.sendall(frame)
    except Exception as e:
        print(f"Error sending HL7 MLLP ACK: {e}")


def run_hl7_server(port):
    """Starts a TCP Server for HL7/MLLP messages and parses incoming trend data."""
    print(f"Starting HL7 LAN Server on port {port}. Waiting for Atlan simulator to connect...")
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server_sock.bind(('0.0.0.0', port))
        server_sock.listen(5)
    except Exception as e:
        print(f"Failed to bind HL7 server to port {port}: {e}")
        return

    while True:
        try:
            client_sock, client_addr = server_sock.accept()
            print(f"Atlan simulator connected from {client_addr}!")
            
            buffer = bytearray()
            while True:
                data = client_sock.recv(1024)
                if not data:
                    print("Atlan simulator connection closed.")
                    break
                buffer.extend(data)
                
                while True:
                    start_idx = buffer.find(MLLP_SB)
                    if start_idx == -1:
                        buffer.clear()
                        break
                    
                    end_idx = buffer.find(MLLP_EB + MLLP_CR, start_idx)
                    if end_idx == -1:
                        break
                        
                    msg_bytes = buffer[start_idx + 1: end_idx]
                    log_raw_bytes(msg_bytes)
                    msg_str = msg_bytes.decode('ascii', errors='ignore')
                    
                    print("\n--- Mensaje HL7 Recibido ---")
                    print(msg_str.replace('\r', '\n'))
                    
                    handle_hl7_mllp_message(msg_str, client_sock)
                    
                    buffer = buffer[end_idx + 2:]
                    
        except Exception as e:
            print(f"HL7 server connection exception: {e}")


def main():
    parser = argparse.ArgumentParser(description="Draeger Atlan A-350XL Medibus and HL7 client/server.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--serial", help="Serial port identifier (e.g. /dev/ttyUSB1 or COM4) to run Medibus client")
    group.add_argument("--lan", action="store_true", help="Start the HL7 LAN Server")
    parser.add_argument("--port", type=int, default=2575, help="HL7 server port to listen on (default: 2575)")
    args = parser.parse_args()

    try:
        if args.lan:
            run_hl7_server(args.port)
        elif args.serial:
            run_serial_client(args.serial)
    except KeyboardInterrupt:
        print("\nExiting Atlan Receiver.")
        sys.exit(0)


if __name__ == '__main__':
    main()
