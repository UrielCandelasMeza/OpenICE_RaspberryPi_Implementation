#!/usr/bin/env python3
"""
Simulador Masimo Radical 7 — envía datos ASCII por puerto serie hardware (Raspberry Pi Tx/Rx).

El script corre en una Raspberry Pi y escribe los datos capturados del Radical 7
por el pin Tx (GPIO 14) del puerto UART.  Un PC con un adaptador USB-TTL conectado
a Rx/Tx de la RPi recibe los datos y OpenICE los lee como si fuera el dispositivo real.

Cableado:
    RPi (simulador)          Adaptador USB-TTL (PC)
    ─────────────            ──────────────────────
    Tx  (GPIO 14)  ────────  RX
    Rx  (GPIO 15)  ────────  TX
    GND            ────────  GND

Uso en la Raspberry Pi:
    # Configurar el UART a 19200 baudios
    sudo stty -F /dev/ttyAMA0 19200 raw

    # Ejecutar el simulador (requiere sudo si /dev/ttyAMA0 no tiene permisos)
    sudo python3 scripts/simula_massimo.py

    # O especificar el puerto manualmente:
    sudo python3 scripts/simula_massimo.py --port /dev/ttyS0

Puertos serie típicos en Raspberry Pi:
    /dev/ttyAMA0   — UART principal (GPIO 14/15) — default
    /dev/ttyS0     — mini-UART (GPIO 14/15, si se configura)
    /dev/serial0   — symlink al UART activo

Requiere: python3, pyserial (ya incluido en scripts/venv/).
"""

import re
import sys
import time
import signal
import argparse
from pathlib import Path

try:
    import serial
except ImportError:
    print("Error: pyserial no encontrado. Usa: scripts/venv/bin/python3 scripts/simula_massimo.py")
    sys.exit(1)


DATA_DIR = Path(__file__).resolve().parent

LINE_RE = re.compile(
    r"^(\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2})\s+SN=\S+\s+CHAN=\S+\s+sysALARM=\S+"
)

TIMESTAMP_RE = re.compile(r"^\d{2}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}")


def load_messages(data_file: str) -> list[str]:
    """Lee un archivo de datos y retorna las líneas de mensajes válidas."""
    filepath = DATA_DIR / data_file
    if not filepath.exists():
        print(f"Error: archivo no encontrado: {filepath}")
        sys.exit(1)

    raw = filepath.read_bytes()
    text = raw.decode("utf-8", errors="replace")

    # Limpiar bytes nulos y separadores "--- Nuevo Mensaje ---"
    text = text.replace("\x00", "\n")
    text = re.sub(r"---\s*Nuevo\s+Mensaje\s*---", "", text)

    lines = []
    for line in text.splitlines():
        line = line.strip()
        if LINE_RE.match(line):
            lines.append(line)

    return lines


def update_timestamp(line: str, base_time: float, index: int) -> str:
    """Reemplaza solo el timestamp del mensaje con tiempo relativo al inicio del envío."""
    t = time.localtime(base_time + index)
    new_ts = time.strftime("%m/%d/%y %H:%M:%S", t)
    return TIMESTAMP_RE.sub(new_ts, line, count=1)


def main():
    parser = argparse.ArgumentParser(
        description="Simulador Masimo Radical 7 — Raspberry Pi UART Tx/Rx"
    )
    parser.add_argument(
        "--port", default="/dev/ttyAMA0",
        help="Puerto serie hardware (default: /dev/ttyAMA0 — UART GPIO)"
    )
    parser.add_argument(
        "--file", default="datos_massimo.txt",
        help="Archivo de datos a transmitir (default: datos_massimo.txt)"
    )
    parser.add_argument(
        "--interval", type=float, default=1.0,
        help="Segundos entre cada mensaje (default: 1.0)"
    )
    parser.add_argument(
        "--repeat", action="store_true",
        help="Repetir los mensajes en bucle infinito"
    )
    parser.add_argument(
        "--baudrate", type=int, default=19200,
        help="Baud rate del UART (default: 19200)"
    )
    args = parser.parse_args()

    messages = load_messages(args.file)
    if not messages:
        print(f"Error: no se encontraron mensajes válidos en {args.file}")
        sys.exit(1)

    print(f"Cargados {len(messages)} mensajes de {args.file}")
    print(f"Puerto: {args.port} @ {args.baudrate} baudios")
    print()

    # Configurar el puerto con stty si es /dev/ttyAMA0 o /dev/ttyS0
    if args.port in ("/dev/ttyAMA0", "/dev/ttyS0", "/dev/serial0"):
        import subprocess
        stty_cmd = f"stty -F {args.port} {args.baudrate} raw -echo -echoe -echok"
        print(f"Configurando UART: {stty_cmd}")
        try:
            subprocess.run(stty_cmd.split(), check=True, capture_output=True)
        except subprocess.CalledProcessError as e:
            print(f"Advertencia: stty falló ({e}). Asegúrate de usar sudo.")
            print(f"  Ejecuta manualmente: sudo {stty_cmd}")
        except FileNotFoundError:
            print("Advertencia: stty no encontrado (¿estás en Windows? skip)")
        print()

    # Abrir el puerto serie
    try:
        ser = serial.Serial(
            port=args.port,
            baudrate=args.baudrate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=1,
        )
    except PermissionError:
        print(f"Error de permisos al abrir {args.port}")
        print(f"  Intenta con sudo, o ejecuta: sudo chmod 666 {args.port}")
        sys.exit(1)
    except Exception as e:
        print(f"Error abriendo {args.port}: {e}")
        sys.exit(1)

    print(f"Simulador listo — transmitiendo por {args.port} Tx → PC Rx")
    print("Presiona Ctrl+C para detener\n")

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
                ser.write(payload)
                ser.flush()
                msg_count += 1
                sys.stdout.write(f"\r  Enviados: {msg_count} mensajes  ")
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
