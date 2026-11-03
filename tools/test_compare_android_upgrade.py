import sqlite3
import tempfile
import unittest
from pathlib import Path

from compare_android_upgrade import (
    ComparisonError,
    compare_databases,
    compare_preferences,
)


def create_database(path, version, add_new_column=False, position=1536):
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE books (
            bookUrl TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            durChapterPos INTEGER NOT NULL
        );
        CREATE TABLE book_groups (
            groupId INTEGER PRIMARY KEY,
            groupName TEXT NOT NULL
        );
        CREATE TABLE book_sources (
            bookSourceUrl TEXT PRIMARY KEY,
            bookSourceName TEXT NOT NULL
        );
        CREATE TABLE bookmarks (
            time INTEGER PRIMARY KEY,
            chapterPos INTEGER NOT NULL
        );
        CREATE TABLE highlights (
            time INTEGER PRIMARY KEY,
            note TEXT NOT NULL
        );
        CREATE TABLE readRecord (
            bookName TEXT NOT NULL,
            author TEXT NOT NULL,
            readTime INTEGER NOT NULL
        );
        """
    )
    if add_new_column:
        conn.execute("ALTER TABLE book_groups ADD COLUMN generation TEXT")
    conn.execute(
        "INSERT INTO books VALUES (?, ?, ?)",
        ("book://one", "Book", position),
    )
    conn.execute(
        "INSERT INTO book_groups(groupId, groupName) VALUES (1, 'Group')"
    )
    conn.execute(
        "INSERT INTO book_sources VALUES ('source://one', 'Source')"
    )
    conn.execute("INSERT INTO bookmarks VALUES (7, 31)")
    conn.execute("INSERT INTO highlights VALUES (8, 'note')")
    conn.execute(
        "INSERT INTO readRecord VALUES ('Book', 'Author', 9)"
    )
    conn.execute(f"PRAGMA user_version={version}")
    conn.commit()
    conn.close()


def preferences(path, value="old"):
    path.write_text(
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>"
        f"<string name='themeMode'>{value}</string>"
        "<boolean name='syncBookProgress' value='true' />"
        "<set name='menu'>"
        "<string>copy</string><string>search</string>"
        "</set>"
        "</map>\n"
    )


class UpgradeComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.before_db = self.root / "before.db"
        self.after_db = self.root / "after.db"
        create_database(self.before_db, 113)
        create_database(self.after_db, 132, add_new_column=True)
        self.before_prefs = self.root / "before.xml"
        self.after_prefs = self.root / "after.xml"
        preferences(self.before_prefs)
        preferences(self.after_prefs)

    def tearDown(self):
        self.temp.cleanup()

    def test_common_entity_fields_survive_new_schema_columns(self):
        report = compare_databases(
            self.before_db,
            self.after_db,
            expected_before=113,
            expected_after=132,
        )
        self.assertTrue(report["preserved"])
        self.assertEqual(
            ["generation"],
            report["tables"]["book_groups"]["added_after_columns"],
        )

    def test_changed_reading_position_is_rejected(self):
        self.after_db.unlink()
        create_database(self.after_db, 132, add_new_column=True, position=1600)
        report = compare_databases(
            self.before_db,
            self.after_db,
            expected_before=113,
            expected_after=132,
        )
        self.assertFalse(report["preserved"])
        self.assertEqual(
            ["durChapterPos"],
            report["tables"]["books"]["changed_rows"][0]["fields"],
        )

    def test_dropped_legacy_column_is_rejected(self):
        conn = sqlite3.connect(self.after_db)
        conn.execute("ALTER TABLE book_sources RENAME TO old_sources")
        conn.execute(
            "CREATE TABLE book_sources (bookSourceUrl TEXT PRIMARY KEY)"
        )
        conn.execute(
            "INSERT INTO book_sources VALUES ('source://one')"
        )
        conn.commit()
        conn.close()
        report = compare_databases(
            self.before_db,
            self.after_db,
            expected_before=113,
            expected_after=132,
        )
        self.assertFalse(report["preserved"])
        self.assertEqual(
            ["bookSourceName"],
            report["tables"]["book_sources"]["missing_after_columns"],
        )

    def test_preference_change_is_rejected(self):
        preferences(self.after_prefs, value="new")
        report = compare_preferences(self.before_prefs, self.after_prefs)
        self.assertFalse(report["preserved"])
        self.assertEqual("themeMode", report["changed"][0]["key"])
        self.assertNotIn("old", str(report))

    def test_malformed_preference_root_is_rejected(self):
        self.after_prefs.write_text("<preferences/>")
        with self.assertRaises(ComparisonError):
            compare_preferences(self.before_prefs, self.after_prefs)


if __name__ == "__main__":
    unittest.main()
