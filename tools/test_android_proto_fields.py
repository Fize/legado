import struct
import tempfile
from pathlib import Path
import unittest
import zipfile

from check_android_proto_fields import MESSAGE, defined_fields, inspect_apk


def leb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def fixture_dex(names, descriptor="Lx/y;", instance=None, static=()):
    strings = [descriptor, "I"] + names
    so = 112
    to = so + len(strings) * 4
    fo = to + 8
    co = fo + len(names) * 8
    data = bytearray(co + 32)
    data[:8] = b"dex\n035\0"
    for offset, value in [(56, len(strings)), (60, so), (64, 2), (68, to),
                          (80, len(names)), (84, fo), (96, 1), (100, co)]:
        struct.pack_into("<I", data, offset, value)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, so + index * 4, len(data))
        data += leb(len(value)) + value.encode() + b"\0"
    struct.pack_into("<II", data, to, 0, 1)
    for index in range(len(names)):
        struct.pack_into("<HHI", data, fo + index * 8, 0, 1, index + 2)
    struct.pack_into("<I", data, co + 24, len(data))
    instance = list(range(len(names))) if instance is None else instance
    data += leb(len(static)) + leb(len(instance)) + b"\0\0"
    for indexes in (static, instance):
        previous = 0
        for index in indexes:
            data += leb(index - previous) + leb(2)
            previous = index
    return bytes(data)


class ProtoFieldGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "Message.java"
        self.source.write_text("private int schemaVersion_;\nprivate String charset_;\nprivate int format_;\n")
        self.mapping = self.root / "mapping.txt"
        # Kept fields have no individual mapping lines in real R8 output.
        self.mapping.write_text(MESSAGE + " -> x.y:\n")

    def apk(self, *dexes):
        apk = self.root / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for index, data in enumerate(dexes):
                archive.writestr("classes" + (str(index + 1) if index else "") + ".dex", data)
        return apk

    def inspect(self, *dexes):
        return inspect_apk(self.apk(*dexes), self.mapping, self.source)

    def test_renamed_class_with_original_fields_passes_without_field_mapping_lines(self):
        report = self.inspect(fixture_dex(["schemaVersion_", "charset_", "format_"]))
        self.assertEqual([], report["errors"])

    def test_metadata_strings_and_references_do_not_replace_definitions(self):
        data = fixture_dex(["schemaVersion_", "charset_", "format_", "a", "b", "c"], instance=[3, 4, 5])
        self.assertIn(b"schemaVersion_", data)
        self.assertEqual(3, len(self.inspect(data)["errors"]))

    def test_searches_secondary_dex(self):
        report = self.inspect(fixture_dex(["other_"], "Lother;"), fixture_dex(["schemaVersion_", "charset_", "format_"]))
        self.assertEqual([], report["errors"])
        self.assertEqual("classes2.dex", report["dex"])

    def test_missing_message_fails(self):
        self.assertTrue(self.inspect(fixture_dex(["other_"], "Lother;"))["errors"])

    def test_field_index_deltas_restart_for_static_and_instance_fields(self):
        data = fixture_dex(["a", "b", "c", "d", "e"], static=[1, 4], instance=[0, 2])
        self.assertEqual({"a", "b", "c", "e"}, defined_fields(data, "Lx/y;"))

    def test_truncated_dex_is_rejected(self):
        with self.assertRaises((ValueError, IndexError, struct.error)):
            defined_fields(fixture_dex(["a"])[:70], "Lx/y;")

    def test_unrecognized_generated_source_does_not_pass_vacuously(self):
        self.source.write_text("class Message {}")
        with self.assertRaisesRegex(ValueError, "No generated"):
            self.inspect(fixture_dex(["a"]))

    def test_generic_instance_field_is_required_too(self):
        with self.source.open("a") as output:
            output.write("private java.util.Map<String, Integer> options_;\n")
        report = self.inspect(fixture_dex(["schemaVersion_", "charset_", "format_"]))
        self.assertEqual(["Missing original Proto field: options_"], report["errors"])


if __name__ == "__main__":
    unittest.main()
