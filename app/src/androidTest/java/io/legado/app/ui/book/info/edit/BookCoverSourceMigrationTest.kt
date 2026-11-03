package io.legado.app.ui.book.info.edit

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.CoverSourceRef
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookCoverSourceMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "cover-migration-${UUID.randomUUID()}"
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java, DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrading114PreservesCoversProgressAndMetadataThenRoundTripsProvenance() {
        helper.createDatabase(name, 114).use { db ->
            val values = ContentValues()
            db.query("PRAGMA table_info(books)").use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1 && cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase() == "TEXT") values.put(column, "") else values.put(column, 0L)
                    }
                }
            }
            values.put("bookUrl", "migration://book"); values.put("name", "Retained"); values.put("author", "Author")
            values.put("origin", "https://source.invalid"); values.put("customCoverUrl", "https://cdn.invalid/cover")
            values.put("persistedCoverUrl", "/retained.image"); values.put("durChapterIndex", 42); values.put("group", 17L)
            val entries = values.valueSet().map { it.key to it.value }
            db.execSQL("INSERT INTO books (${entries.joinToString { "`${it.first}`" }}) VALUES (${entries.joinToString { "?" }})", entries.map { it.second }.toTypedArray())
        }
        helper.runMigrationsAndValidate(name, 115, true, DatabaseMigrations.migration_114_115).close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addLegadoMigrations().build()
        try {
            val book = checkNotNull(db.bookDao.getBook("migration://book"))
            assertEquals("Retained", book.name); assertEquals("Author", book.author)
            assertEquals(42, book.durChapterIndex); assertEquals(17L, book.group)
            assertEquals("https://cdn.invalid/cover", book.customCoverUrl); assertEquals("/retained.image", book.persistedCoverUrl)
            assertNull(book.customCoverSource)
            book.customCoverSource = CoverSourceRef(checkNotNull(book.customCoverUrl), "https://chosen.invalid")
            db.bookDao.update(book)
            assertEquals(book.customCoverSource, db.bookDao.getBook(book.bookUrl)!!.customCoverSource)
        } finally { db.close() }
    }
}
