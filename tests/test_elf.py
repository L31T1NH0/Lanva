import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("validate_elf", ROOT / "scripts/validate-elf.py")
ELF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ELF)


def executable(machine=183, alignment=16384, interpreter=b"/system/bin/linker64\0"):
    data = bytearray(256)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", data, 16, 3, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 256, 256, alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 3, 4, 200, 200, 200, len(interpreter), len(interpreter), 1)
    data[200:200 + len(interpreter)] = interpreter
    return data


class ElfTest(unittest.TestCase):
    def test_accepts_android_and_rejects_wrong_platform_or_page_size(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "server"
            binary.write_bytes(executable())
            ELF.validate(binary)
            for data in (executable(machine=62), executable(alignment=4096),
                         executable(interpreter=b"/lib/ld-linux-aarch64.so.1\0"), b"not ELF", executable()[:150]):
                binary.write_bytes(data)
                with self.assertRaises(ValueError):
                    ELF.validate(binary)
