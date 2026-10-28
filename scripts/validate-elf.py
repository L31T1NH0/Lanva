#!/usr/bin/env python3
"""Reject host binaries and binaries incompatible with Android's 16 KB pages."""
from pathlib import Path
import struct
import sys


def validate(binary):
    data = Path(binary).read_bytes()
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("Esperado um ELF 64-bit little-endian.")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != 3 or machine != 183:
        raise ValueError("Esperado um executável PIE para ARM64.")
    table = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size != 56 or count == 0 or table + count * entry_size > len(data):
        raise ValueError("Tabela de segmentos ELF inválida.")
    interpreter = None
    loads = 0
    for index in range(count):
        kind, flags, offset, virtual, physical, size, memory, alignment = struct.unpack_from("<IIQQQQQQ", data, table + index * entry_size)
        if offset + size > len(data):
            raise ValueError("Segmento ELF truncado.")
        if kind == 3:
            interpreter = data[offset:offset + size].rstrip(b"\0")
        if kind == 1:
            loads += 1
            if alignment < 16384 or virtual % 16384 != offset % 16384:
                raise ValueError("Segmento incompatível com páginas de 16 KB.")
    if not loads or interpreter != b"/system/bin/linker64":
        raise ValueError("Executável deve usar /system/bin/linker64 do Android.")


if __name__ == "__main__":
    try:
        validate(sys.argv[1])
    except (IndexError, ValueError, OSError) as error:
        sys.exit(f"Binário Oxide inválido: {error}")
    print("ELF Android ARM64 PIE, alinhado para páginas de 16 KB.")

