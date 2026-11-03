#!/usr/bin/env python3
"""Compare fixture-owned Android data before and after an in-place APK upgrade."""

import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ElementTree


TABLES = {
    "books": ["bookUrl"],
    "book_groups": ["groupId"],
    "book_sources": ["bookSourceUrl"],
    "bookmarks": ["time"],
    "highlights": ["time"],
    "readRecord": ["bookName", "author"],
}


class ComparisonError(ValueError):
    pass


def _stable_bytes(value):
    if isinstance(value, bytes):
        return {"bytes_sha256": hashlib.sha256(value).hexdigest()}
    return value


def _row_hash(values):
    normalized = [_stable_bytes(value) for value in values]
    data = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(data.encode()).hexdigest()


def _read_table(database, table, key_columns):
    conn = sqlite3.connect(database)
    try:
        columns = [
            row[1]
            for row in conn.execute(f'PRAGMA table_info("{table}")')
        ]
        if not columns:
            raise ComparisonError(f"Missing table: {table}")
        missing_keys = [column for column in key_columns if column not in columns]
        if missing_keys:
            raise ComparisonError(f"{table} is missing key columns: {missing_keys}")
        selected = ", ".join(f'"{column}"' for column in columns)
        rows = conn.execute(f'SELECT {selected} FROM "{table}"').fetchall()
        key_indexes = [columns.index(column) for column in key_columns]
        by_key = {}
        for row in rows:
            key = tuple(row[index] for index in key_indexes)
            if key in by_key:
                raise ComparisonError(f"Duplicate key in {table}: {key!r}")
            by_key[key] = {
                column: value
                for column, value in zip(columns, row)
            }
        return columns, by_key
    finally:
        conn.close()


def _read_version(database):
    conn = sqlite3.connect(database)
    try:
        return conn.execute("PRAGMA user_version").fetchone()[0]
    finally:
        conn.close()


def compare_databases(before, after, expected_before=None, expected_after=None):
    report = {
        "before_version": _read_version(before),
        "after_version": _read_version(after),
        "tables": {},
        "preserved": True,
        "errors": [],
    }
    if expected_before is not None and report["before_version"] != expected_before:
        report["errors"].append(
            f"Expected schema {expected_before}, found {report['before_version']}"
        )
    if expected_after is not None and report["after_version"] != expected_after:
        report["errors"].append(
            f"Expected schema {expected_after}, found {report['after_version']}"
        )

    for table, key_columns in TABLES.items():
        before_columns, before_rows = _read_table(before, table, key_columns)
        after_columns, after_rows = _read_table(after, table, key_columns)
        common_columns = [column for column in before_columns if column in after_columns]
        missing_columns = [
            column for column in before_columns if column not in after_columns
        ]
        missing_keys = sorted(set(before_rows) - set(after_rows), key=repr)
        added_keys = sorted(set(after_rows) - set(before_rows), key=repr)
        changed_rows = []
        for key in sorted(set(before_rows) & set(after_rows), key=repr):
            changed_fields = [
                column
                for column in common_columns
                if _stable_bytes(before_rows[key][column])
                != _stable_bytes(after_rows[key][column])
            ]
            if changed_fields:
                changed_rows.append(
                    {
                        "key": key,
                        "fields": changed_fields,
                        "before_sha256": _row_hash(
                            [before_rows[key][column] for column in common_columns]
                        ),
                        "after_sha256": _row_hash(
                            [after_rows[key][column] for column in common_columns]
                        ),
                    }
                )
        table_report = {
            "rows_before": len(before_rows),
            "rows_after": len(after_rows),
            "common_columns": common_columns,
            "added_after_columns": [
                column for column in after_columns if column not in before_columns
            ],
            "missing_after_columns": missing_columns,
            "missing_keys": missing_keys,
            "added_keys": added_keys,
            "changed_rows": changed_rows,
        }
        table_report["preserved"] = not (
            missing_columns
            or missing_keys
            or added_keys
            or changed_rows
            or len(before_rows) != len(after_rows)
        )
        report["tables"][table] = table_report
        if not table_report["preserved"]:
            report["errors"].append(f"Data was not preserved in {table}")

    report["preserved"] = not report["errors"]
    return report


def _preference_value(element):
    tag = element.tag
    if tag == "set":
        return (
            "set",
            sorted(
                child.text or ""
                for child in element
                if child.tag == "string"
            ),
        )
    if tag == "boolean":
        return tag, (element.attrib.get("value") or "").lower() == "true"
    if tag == "int":
        return tag, int(element.attrib.get("value") or 0)
    if tag == "long":
        return tag, int(element.attrib.get("value") or 0)
    if tag == "float":
        return tag, float(element.attrib.get("value") or 0)
    return tag, element.text or ""


def read_preferences(path):
    root = ElementTree.parse(path).getroot()
    if root.tag != "map":
        raise ComparisonError(f"Preference XML must have a map root: {path}")
    result = {}
    for element in root:
        name = element.attrib.get("name")
        if not name:
            raise ComparisonError(f"Preference entry has no name: {path}")
        if name in result:
            raise ComparisonError(f"Duplicate preference: {name}")
        result[name] = _preference_value(element)
    return result


def _preference_hash(value):
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(encoded.encode()).hexdigest()


def compare_preferences(before, after):
    before_values = read_preferences(before)
    after_values = read_preferences(after)
    changed = []
    missing = []
    for key, value in before_values.items():
        if key not in after_values:
            missing.append(key)
        elif value != after_values[key]:
            changed.append(
                {
                    "key": key,
                    "before_type": value[0],
                    "after_type": after_values[key][0],
                    "before_sha256": _preference_hash(value),
                    "after_sha256": _preference_hash(after_values[key]),
                }
            )
    return {
        "before_keys": len(before_values),
        "after_keys": len(after_values),
        "missing": sorted(missing),
        "changed": changed,
        "preserved": not missing and not changed,
    }


def build_report(before_db, after_db, before_preferences, after_preferences):
    report = compare_databases(
        before_db,
        after_db,
        expected_before=113,
        expected_after=132,
    )
    report["preferences"] = compare_preferences(
        before_preferences,
        after_preferences,
    )
    if not report["preferences"]["preserved"]:
        report["errors"].append("Preferences were not preserved")
    report["preserved"] = not report["errors"]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-db", required=True, type=Path)
    parser.add_argument("--after-db", required=True, type=Path)
    parser.add_argument("--before-preferences", required=True, type=Path)
    parser.add_argument("--after-preferences", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    report = build_report(
        args.before_db,
        args.after_db,
        args.before_preferences,
        args.after_preferences,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    if not report["preserved"]:
        raise SystemExit(
            "Upgrade data changed: " + "; ".join(report["errors"])
        )
    print(
        "PASS: schema "
        f"{report['before_version']} -> {report['after_version']}; "
        f"{sum(item['rows_after'] for item in report['tables'].values())} "
        "entity rows and "
        f"{report['preferences']['before_keys']} preferences preserved"
    )


if __name__ == "__main__":
    main()
