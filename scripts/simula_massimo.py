#!/usr/bin/env python3
import os
import re
import sys
import time
import signal
import argparse
from pathlib import Path

try:
    import serial
except ImportError:
    print("Error: pyserial no encontrado.")
    sys.exit(1)

DATA_DIR = Path(__file__).resolve().parent

LINE_RE = re.compile(
    r"^(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})\s+SN=\S+\s+CHAN=\S+\s+sysALARM=\S+"
)
TIMESTAMP_RE = re.compile(r"^\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}")

def load_messages(data_file: str) -> list[str]:
    filepath = DATA_DIR / data_file
    if not filepath.exists():
        print(f"Error: archivo no encontrado: {filepath}")
        sys.exit(1)

    raw = filepath.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    text = text.replace("\x00", "\n")
    text = re.sub(r"---\s*Nuevo\s+Mensaje\s*---", "", text)

    lines = []
    for line in text.splitlines():
        line = line.strip()
        if LINE_RE.match(line):
            lines.append(line)
    return lines

def update_timestamp(line: str, base_time: float, index: int) -> str:
    t = time.localtime(base_time + index)
    new_ts = time.strftime("%m/%d/%y %H:%M:%S", t)
    return TIMESTAMP_RE.sub(new_ts, line, count=1)

def main():
    parser = argparse.ArgumentParser(description="Simulador Masimo Radical 7")
    parser.add_argument("--port", default="/dev/ttyS0", help="Puerto serie (default: /dev/ttyS0)")
    parser.add_argument("--file", default="datos_massimo.txt", help="Archivo de datos")
    parser.add_argument("--interval", type=float, default=1.0, help="Segundos entre mensajes")
    parser.add_argument("--repeat", action="store_true", help="Repetir en bucle")
    parser.add_argument("--baudrate", type=int, default=9600, help="Baud rate (default: 9600)")
    args = parser.parse_args()

    messages = load_messages(args.file)
    if not messages:
        print(f"Error: no se encontraron mensajes válidos en {args.file}")
        sys.exit(1)

    try:
        # Inicialización pura con pyserial, control de flujo deshabilitado
        ser = serial.Serial(
            port=args.port,
            baudrate=args.baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=1,
            xonxoff=False,
            rtscts=False,
            dsrdtr=False
        )
        
        # CRÍTICO para cables RS232 a USB: Afirmar señales para que el cable comience a escuchar
        ser.dtr = True
        ser.rts = True
        
    except PermissionError:
        print(f"Error de permisos: sudo chmod 666 {args.port} o ejecuta con sudo")
        sys.exit(1)
    except Exception as e:
        print(f"Error abriendo {args.port}: {e}")
        sys.exit(1)

    ser.reset_input_buffer()
    ser.reset_output_buffer()

    running = True

    def on_sigint(sig, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, on_sigint)
    signal.signal(signal.SIGTERM, on_sigint)

    base_time = time.time()
    msg_count = 0

    try:
        while running:
            for msg in messages:
                if not running:
                    break
                line = update_timestamp(msg, base_time, msg_count)
                payload = line.encode("ascii", errors="replace") + b"\r\n"

                bytes_written = ser.write(payload)
                ser.flush()

                msg_count += 1
                sys.stdout.write(f"\r  TX [{bytes_written}B]: {msg_count} mensajes  ")
                sys.stdout.flush()
                time.sleep(args.interval)

            if not args.repeat:
                break
    except serial.SerialException as e:
        print(f"\nError de serial: {e}")
    finally:
        ser.close()
        print(f"\nDetenido. Total enviados: {msg_count} mensajes")

if __name__ == "__main__":
    main()