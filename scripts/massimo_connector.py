#!/usr/bin/env python3
"""
Before use execute in pc: stty -F /dev/ttyUSB0 19200 -parenb cs8 -cstopb raw
After use execute in pc: stty -F /dev/ttyUSB0 sane

Before use execute in raspberry: stty -F /dev/ttyS0 19200 -parenb cs8 -cstopb raw
After use execute in raspberry: stty -F /dev/ttyS0 sane

"""
import time
import serial

PORT = '/dev/ttyUSB0'         # Adjust to match your system's port assignment
BAUDRATE = 9600       # Match the baud rate of your transmitting hardware
TIMEOUT = 20           # Seconds to wait for data before moving on (prevents blocking)

try:
    # 2. Establish the connection using a context manager (auto-closes on exit)
    with serial.Serial(PORT, BAUDRATE, timeout=TIMEOUT) as ser:
        print(f"Connected to {PORT} successfully. Reading data...")

        # Flush buffers to avoid reading old, stale data
        ser.reset_input_buffer()

        # 3. Continuous reading loop
        while True:
            # Check if there is data waiting in the hardware buffer
            if ser.in_waiting > 0:
                # Read a line up to the '\n' character (returns bytes)
                raw_data = ser.readline()

                # Decode the raw bytes into a human-readable UTF-8 string
                decoded_text = raw_data.decode('utf-8').strip()

                try:
                    with open("test.txt", "ab") as file:
                        file.write(b"--- Nuevo Mensaje ---")
                        file.write(decoded_text.encode('utf-8'))
                        file.write(b"\n")
                except Exception as e:
                    print(f"Failed to log: {e}")


                print(f"Received: {decoded_text}")

            time.sleep(0.01) # Small delay to limit CPU overhead

except serial.SerialException as e:
    print(f"Serial Error: Could not open port {PORT}. {e}")
except KeyboardInterrupt:
    print("\nProgram stopped by user.")
