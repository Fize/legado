#!/usr/bin/env python3
"""Validate Android native ABI identity and ELF/ZIP page alignment.

64-bit libraries require 16 KB alignment; 32-bit libraries use 4 KB alignment.
This checks artifacts, not execution on a device. Raw dynamic libraries can be
checked with --elf FILE --abi ABI. Uses only the Python standard library.
"""

import argparse
import json
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16384
# ELF class, e_machine, required page alignment. Android ABIs are little-endian.
ABI_FORMATS = {
    "arm64-v8a": (2, 183, 16384),
    "armeabi-v7a": (1, 40, 4096),
    "x86_64": (2, 62, 16384),
    "x86": (1, 3, 4096),
}
ABIS = set(ABI_FORMATS)


def elf_errors(data, abi=None):
    # Preserve the raw 64-bit/16KB API used by the real Cronet runtime probe.
    elf_class, machine, page_size = ABI_FORMATS[abi] if abi else (2, None, PAGE_SIZE)
    wide = elf_class == 2
    header_size = 64 if wide else 52
    if len(data) < header_size or data[:5] != b"\x7fELF" + bytes([elf_class]) or data[5] not in (1, 2):
        return [f"not a valid {64 if wide else 32}-bit ELF header"]
    endian = "<" if data[5] == 1 else ">"
    if abi:
        if data[5] != 1:
            return [f"{abi} requires little-endian ELF"]
        actual_machine = struct.unpack_from("<H", data, 18)[0]
        if actual_machine != machine:
            return [f"ELF machine {actual_machine} does not match {abi} (expected {machine})"]
        if struct.unpack_from("<H", data, 16)[0] != 3:
            return ["native library is not an ELF shared object (ET_DYN)"]
        if data[6] != 1 or struct.unpack_from("<I", data, 20)[0] != 1:
            return ["invalid ELF version"]
    offset = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
    stride, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
    if offset < header_size or stride < (56 if wide else 32) or not count or offset + stride * count > len(data):
        return ["invalid ELF program header table"]
    errors = []
    loads = 0
    for index in range(count):
        fields = struct.unpack_from(endian + ("IIQQQQQQ" if wide else "IIIIIIII"), data, offset + stride * index)
        if wide:
            kind, _, file_offset, address, _, file_size, memory_size, alignment = fields
        else:
            kind, file_offset, address, _, file_size, memory_size, _, alignment = fields
        if kind != 1:  # PT_LOAD
            continue
        loads += 1
        if alignment < page_size or alignment & (alignment - 1):
            errors.append(f"LOAD {index} alignment {alignment} is not a power of two >= {page_size}")
        if (address - file_offset) % page_size:
            errors.append(f"LOAD {index} file offset and virtual address differ modulo {page_size}")
        if abi and (file_size > memory_size or (file_size and file_offset + file_size > len(data))):
            errors.append(f"LOAD {index} file or memory bounds are invalid")
    if not loads:
        errors.append("no ELF LOAD segments")
    return errors


def elf_identity(data):
    bits = {1: 32, 2: 64}.get(data[4]) if len(data) > 4 and data[:4] == b"\x7fELF" else None
    machine = struct.unpack_from(("<" if data[5] == 1 else ">") + "H", data, 18)[0] if len(data) >= 20 and data[:4] == b"\x7fELF" and data[5] in (1, 2) else None
    return {"elf_bits": bits, "elf_machine": machine}


def inspect_apk(path):
    result = {"apk": str(path), "libraries": [], "errors": []}
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            abi = parts[1]
            if abi not in ABIS:
                result["errors"].append(f"{entry.filename}: unsupported native ABI {abi}")
                continue
            data = archive.read(entry)
            errors = elf_errors(data, abi)
            page_size = ABI_FORMATS[abi][2]
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                if len(header) != 30 or header[:4] != b"PK\x03\x04":
                    errors.append("invalid ZIP local header")
                else:
                    name_length, extra_length = struct.unpack_from("<HH", header, 26)
                    data_offset = entry.header_offset + 30 + name_length + extra_length
                    if data_offset % page_size:
                        errors.append(f"uncompressed ZIP data starts at {data_offset}, not aligned to {page_size}")
            result["libraries"].append({"name": entry.filename, "abi": abi,
                **elf_identity(data), "expected_elf_bits": 64 if ABI_FORMATS[abi][0] == 2 else 32,
                "expected_elf_machine": ABI_FORMATS[abi][1], "required_alignment_bytes": page_size, "errors": errors})
            result["errors"].extend(f"{entry.filename}: {error}" for error in errors)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="*", type=Path)
    parser.add_argument("--elf", type=Path, help="Inspect a raw dynamic library instead of APKs")
    parser.add_argument("--abi", choices=sorted(ABIS), help="Required with --elf")
    parser.add_argument("--require-abis", nargs="+", choices=sorted(ABIS), help="Fail if the input APK set is missing a native ABI")
    parser.add_argument("--json", type=Path, help="Save machine readable results")
    args = parser.parse_args()
    if args.elf:
        if args.apks or not args.abi or args.require_abis:
            parser.error("--elf requires --abi and cannot be combined with APKs or --require-abis")
        try:
            data = args.elf.read_bytes()
            errors = elf_errors(data, args.abi)
        except OSError as error:
            data = b""
            errors = [str(error)]
        results = [{"elf": str(args.elf), "abi": args.abi, **elf_identity(data),
            "expected_elf_bits": 64 if ABI_FORMATS[args.abi][0] == 2 else 32,
            "expected_elf_machine": ABI_FORMATS[args.abi][1], "required_alignment_bytes": ABI_FORMATS[args.abi][2], "errors": errors}]
        print(f"{args.elf}: {args.abi}; {len(errors)} errors")
    else:
        if not args.apks or args.abi:
            parser.error("Provide APK files, or --elf FILE --abi ABI")
        results = []
        for apk in args.apks:
            try:
                result = inspect_apk(apk)
            except (OSError, zipfile.BadZipFile, RuntimeError, struct.error) as error:
                result = {"apk": str(apk), "libraries": [], "errors": [str(error)]}
            results.append(result)
            print(f"{apk}: {len(result['libraries'])} native libraries; {len(result['errors'])} errors")
        found = {lib["abi"] for result in results for lib in result["libraries"] if not lib["errors"]}
        missing = set(args.require_abis or []) - found
        if missing:
            results.append({"apk": "<required-abi-set>", "libraries": [],
                "errors": [f"Missing required native ABI: {abi}" for abi in sorted(missing)]})
    for result in results:
        for error in result["errors"]:
            print(f"  {error}")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    return 1 if any(result["errors"] for result in results) else 0


if __name__ == "__main__":
    sys.exit(main())
