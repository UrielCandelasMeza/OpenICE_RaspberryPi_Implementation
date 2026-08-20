import sys
import argparse

hex = {
    "0": 0,
    "1": 1,
    "2": 2,
    "3": 3,
    "4": 4,
    "5": 5,
    "6": 6,
    "7": 7,
    "8": 8,
    "9": 9,
    "A": 10,
    "B": 11,
    "C": 12,
    "D": 13,
    "E": 14,
    "F": 15,

}

def to_ascii(hex_string: str):

    upper = hex_string.upper()
    parts = upper.split(" ")

    for part in parts:

        sum = 0
        total_len = len(part)
        exp = 1
        chars = list(part)

        for char in chars:
            translate = hex[char]
            sum += translate * 16 ** (total_len - exp)
            exp += 1

        ascii_result = ""
        ascii_result += chr(sum)
        print(ascii_result)



def main():
    parser = argparse.ArgumentParser(description="Simulador Masimo Radical 7")
    parser.add_argument("--ascii", help="Convierte HEX a ASCII")
    parser.add_argument("--hex", help="Convierte ASCII a HEX")

    args = parser.parse_args()

    if not args.ascii and not args.hex:
        print("Ingrese a que sistema va a covertir: --ascii o --hex")
        sys.exit(1)

    if args.ascii:
        to_ascii(args.ascii)




if __name__ == "__main__":
    main()
