"""Architecture, alignment, truncation, and missing-ABI counterexamples for release artifacts."""
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest
import zipfile

from check_android_native_pages import elf_errors, inspect_apk


def elf(alignment=16384, address=0, file_offset=0, machine=183):
    data = bytearray(120)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHI", data, 16, 3, machine, 1)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HHH", data, 52, 64, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, file_offset, address, 0, 120, 120, alignment)
    return data


def elf32(alignment=4096, address=0, file_offset=0, machine=40):
    data = bytearray(84)
    data[:7] = b"\x7fELF\x01\x01\x01"
    struct.pack_into("<HHI", data, 16, 3, machine, 1)
    struct.pack_into("<I", data, 28, 52)
    struct.pack_into("<HHH", data, 40, 52, 32, 1)
    struct.pack_into("<IIIIIIII", data, 52, 1, file_offset, address, 0, 84, 84, 5, alignment)
    return data


def write_apk(path, entries, stored_alignment=None):
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED if stored_alignment is None else zipfile.ZIP_STORED
            if stored_alignment:
                padding = (-(archive.fp.tell() + 30 + len(name.encode()))) % stored_alignment
                if padding:
                    if padding < 4:
                        padding += stored_alignment
                    info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
            archive.writestr(info, data)


class AndroidNativePagesTest(unittest.TestCase):
    def test_4k_load_alignment_is_rejected_for_64bit_even_in_a_compressed_apk(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / "bad.apk"
            write_apk(apk, [("lib/arm64-v8a/bad.so", elf(4096)), ("lib/armeabi-v7a/valid.so", elf32())])
            result = inspect_apk(apk)
            self.assertEqual(2, len(result["libraries"]))
            self.assertIn("4096", result["errors"][0])
            self.assertEqual([], result["libraries"][1]["errors"])

    def test_uncompressed_zip_alignment_is_independent_of_elf(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / "bad-zip.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/library.so", elf(machine=62))
            self.assertIn("ZIP data", inspect_apk(apk)["errors"][0])

    def test_valid_load_alignment_does_not_hide_an_incompatible_address(self):
        self.assertEqual([], elf_errors(elf()))
        self.assertIn("modulo", elf_errors(elf(address=4096))[0])
        self.assertIn("alignment", elf_errors(elf(alignment=24576))[0])

    def test_truncated_headers_and_tables_are_rejected(self):
        self.assertTrue(elf_errors(b""))
        self.assertTrue(elf_errors(elf()[:100]))
        self.assertTrue(elf_errors(elf32()[:60], "armeabi-v7a"))

    def test_aligned_wrong_machine_cannot_pass_by_its_directory_name(self):
        self.assertIn("machine 183", elf_errors(elf(), "x86_64")[0])
        self.assertIn("machine 62", elf_errors(elf(machine=62), "arm64-v8a")[0])
        self.assertIn("machine 3", elf_errors(elf32(machine=3), "armeabi-v7a")[0])

    def test_wrong_elf_class_for_abi_is_rejected(self):
        self.assertIn("32-bit", elf_errors(elf(), "armeabi-v7a")[0])
        self.assertIn("64-bit", elf_errors(elf32(), "arm64-v8a")[0])
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / "mislabeled.apk"
            write_apk(apk, [("lib/armeabi-v7a/library.so", elf())])
            library = inspect_apk(apk)["libraries"][0]
            self.assertEqual(64, library["elf_bits"])
            self.assertEqual(32, library["expected_elf_bits"])
            self.assertEqual(183, library["elf_machine"])
            self.assertEqual(40, library["expected_elf_machine"])

    def test_32bit_uses_4k_not_16k_alignment(self):
        self.assertEqual([], elf_errors(elf32(), "armeabi-v7a"))
        self.assertEqual([], elf_errors(elf32(machine=3), "x86"))
        self.assertIn("2048", elf_errors(elf32(alignment=2048), "armeabi-v7a")[0])
        self.assertIn("modulo 4096", elf_errors(elf32(address=2048), "armeabi-v7a")[0])

    def test_android_shared_object_endianness_type_and_version_are_checked(self):
        for position, value, message in [(5, 2, "little-endian"), (16, 2, "ET_DYN"), (6, 0, "version")]:
            data = elf()
            data[position] = value
            self.assertIn(message, elf_errors(data, "arm64-v8a")[0])

    def test_32bit_load_bounds_and_missing_load_segments_are_checked(self):
        data = elf32()
        struct.pack_into("<I", data, 52 + 16, 1000)
        self.assertTrue(any("bounds" in error for error in elf_errors(data, "armeabi-v7a")))
        data = elf32()
        struct.pack_into("<I", data, 52, 7)
        self.assertIn("no ELF LOAD", elf_errors(data, "armeabi-v7a")[0])

    def test_32bit_stored_zip_accepts_4k_and_rejects_unaligned_data(self):
        with tempfile.TemporaryDirectory() as root:
            valid = Path(root) / "valid.apk"
            write_apk(valid, [("lib/armeabi-v7a/library.so", elf32())], stored_alignment=4096)
            self.assertEqual([], inspect_apk(valid)["errors"])
            invalid = Path(root) / "invalid.apk"
            with zipfile.ZipFile(invalid, "w") as archive:
                archive.writestr("lib/armeabi-v7a/library.so", elf32())
            self.assertTrue(any("ZIP data" in e for e in inspect_apk(invalid)["errors"]))

    def test_unknown_native_abi_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            apk = Path(root) / "unknown.apk"
            write_apk(apk, [("lib/mips64/library.so", elf())])
            self.assertIn("unsupported native ABI", inspect_apk(apk)["errors"][0])

    def test_cli_requires_valid_libraries_for_every_required_abi(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            apks = []
            for abi, data in [("arm64-v8a", elf()), ("armeabi-v7a", elf32()), ("x86_64", elf(machine=62))]:
                apk = root / (abi + ".apk")
                write_apk(apk, [("lib/" + abi + "/library.so", data)])
                apks.append(str(apk))
            command = [sys.executable, "-B", str(Path(__file__).with_name("check_android_native_pages.py"))]
            flags = ["--json", str(root / "report.json"), "--require-abis", "arm64-v8a", "armeabi-v7a", "x86_64"]
            incomplete = subprocess.run(command + apks[:1] + flags, capture_output=True, text=True)
            self.assertEqual(1, incomplete.returncode)
            self.assertIn("Missing required native ABI: armeabi-v7a", incomplete.stdout)
            complete = subprocess.run(command + apks + flags, capture_output=True, text=True)
            self.assertEqual(0, complete.returncode, complete.stderr)
            self.assertEqual(3, len(json.loads((root / "report.json").read_text())))

    def test_cli_raw_library_mode_checks_the_selected_abi(self):
        with tempfile.TemporaryDirectory() as root:
            library = Path(root) / "libcronet.so"
            library.write_bytes(elf32())
            command = [sys.executable, "-B", str(Path(__file__).with_name("check_android_native_pages.py")), "--elf", str(library)]
            valid = subprocess.run(command + ["--abi", "armeabi-v7a"], capture_output=True, text=True)
            self.assertEqual(0, valid.returncode, valid.stderr)
            wrong = subprocess.run(command + ["--abi", "arm64-v8a"], capture_output=True, text=True)
            self.assertEqual(1, wrong.returncode)
            unspecified = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(2, unspecified.returncode)


if __name__ == "__main__":
    unittest.main()
