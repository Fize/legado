#!/usr/bin/env python3
"""Check the cache/export Proto Lite field contract in actual APK DEX definitions.

R8 may omit unchanged fields from mapping.txt. Read the DEX class data, rather
than accepting metadata strings or field references as proof. This is a static
gate for this app-owned message, not a substitute for release DataStore tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MESSAGE = "io.legado.app.data.preferences.proto.CacheExportSettingsProto$CacheExportSettings"
SOURCE = ROOT / "app/src/main/java/io/legado/app/data/preferences/proto/CacheExportSettingsProto.java"


def defined_fields(data, descriptor):
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Not a supported DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def leb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid DEX ULEB128")

    string_count, string_offset = u32(56), u32(60)
    type_count, type_offset = u32(64), u32(68)
    field_count, field_offset = u32(80), u32(84)
    class_count, class_offset = u32(96), u32(100)

    def string(index):
        if index >= string_count:
            raise ValueError("Invalid DEX string index")
        _, offset = leb(u32(string_offset + index * 4))
        return data[offset:data.index(0, offset)].decode("utf-8", errors="replace")

    def type_name(index):
        if index >= type_count:
            raise ValueError("Invalid DEX type index")
        return string(u32(type_offset + index * 4))

    for index in range(class_count):
        offset = class_offset + index * 32
        class_index = u32(offset)
        if type_name(class_index) != descriptor:
            continue
        position = u32(offset + 24)
        if position == 0:
            return set()
        static, position = leb(position)
        instance, position = leb(position)
        _, position = leb(position)
        _, position = leb(position)
        result = set()
        for count in (static, instance):
            field_index = 0
            for _ in range(count):
                difference, position = leb(position)
                field_index += difference
                _, position = leb(position)
                if field_index >= field_count:
                    raise ValueError("Invalid DEX field index")
                declaring_class = struct.unpack_from("<H", data, field_offset + field_index * 8)[0]
                if declaring_class != class_index:
                    raise ValueError("Defined field belongs to another class")
                result.add(string(u32(field_offset + field_index * 8 + 4)))
        return result
    return None


def inspect_apk(apk, mapping, source=SOURCE):
    source_bytes = source.read_bytes()
    expected = set(re.findall(r"^\s*private\s+(?!static\b)[^;\n=]+?\s+(\w+_)\s*[;=]", source_bytes.decode(), re.M))
    if not expected:
        raise ValueError("No generated Proto instance fields found")
    prefix = MESSAGE + " -> "
    matches = [line[len(prefix):-1] for line in mapping.read_text().splitlines() if line.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError("Expected one generated-message class in R8 mapping")
    descriptor = "L" + matches[0].replace(".", "/") + ";"
    report = {"apk": str(apk), "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "message": MESSAGE, "actual_class": descriptor,
              "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
              "mapping_sha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
              "required_fields": sorted(expected), "errors": []}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            fields = defined_fields(archive.read(name), descriptor)
            if fields is None:
                continue
            report.update(dex=name, defined_fields=sorted(fields))
            report["errors"] = ["Missing original Proto field: " + field for field in sorted(expected - fields)]
            return report
    report["errors"].append("Generated Proto message class absent from DEX")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    reports = []
    for apk in args.apks:
        try:
            report = inspect_apk(apk, args.mapping)
        except (OSError, ValueError, IndexError, struct.error, zipfile.BadZipFile) as error:
            report = {"apk": str(apk), "errors": [str(error)]}
        reports.append(report)
        print(f"{apk}: {len(report.get('required_fields', []))} required Proto fields; {len(report['errors'])} errors")
        for error in report["errors"]:
            print("  " + error)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(reports, indent=2) + "\n")
    return 1 if any(report["errors"] for report in reports) else 0


if __name__ == "__main__":
    raise SystemExit(main())
