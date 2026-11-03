package io.legado.app.ui.book.manga

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.ReaderSessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderSessionMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "reader-migration-${UUID.randomUUID()}"
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java,
        DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addLegadoMigrations().build()

    @Test fun upgrading116RetainsExistingDataAndRestoresTheChangedSourceAndPendingCatalogAfterReopen() {
        helper.createDatabase(name, 116).use { db ->
            db.execSQL("INSERT INTO search_keywords(word,usage,lastUseTime) VALUES('Retained',7,1234)")
        }
        val initial = ReaderSessionState("reader", "initial-book", "initial-book", true, false, updatedAt = 1)
        val first = open()
        try {
            val db = first
            assertEquals(132, db.openHelper.writableDatabase.version)
            assertEquals(7, db.searchKeywordDao.get("Retained")!!.usage)
            db.bookDao.insert(io.legado.app.data.entities.Book(bookUrl = "initial-book", name = "Initial"),
                io.legado.app.data.entities.Book(bookUrl = "changed-source", name = "Changed"))
            db.readerSessionDao.claim(initial, "old")
            db.readerSessionDao.moveBook("reader", "old", "changed-source", 2, 3, 10)
            db.readerSessionDao.beginCatalog("reader", "old", "catalog", 2, 3, 20)
            assertTrue(db.readerSessionDao.deliverCatalog("catalog", "changed-source", 4, 1))
        } finally { first.close() }
        val second = open()
        try {
            val db = second
            val restored = db.readerSessionDao.claim(initial, "new")
            assertEquals("changed-source", restored.bookUrl)
            assertEquals(2, restored.chapterIndex)
            val result = checkNotNull(db.readerSessionDao.applyCatalog("reader", "new", 30))
            assertEquals(4, result.chapterIndex); assertEquals(1, result.pageIndex)
            assertNull(db.readerSessionDao.applyCatalog("reader", "new", 40))
            assertFalse(db.readerSessionDao.deliverCatalog("catalog", "changed-source", 0, 0))
            assertEquals(1234L, db.searchKeywordDao.get("Retained")!!.lastUseTime)
        } finally { second.close() }
    }
    @Test fun upgradingAnEarlier117DevelopmentShapePreservesValidSessionsAndRevokesOrphans() {
        helper.createDatabase(name, 117).use { db ->
            // Reproduce the development shape actually installed before the deletion FKs were added.
            db.execSQL("DROP TABLE reader_book_owners")
            db.execSQL("CREATE TABLE reader_book_owners (bookUrl TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, ownerToken TEXT NOT NULL)")
            db.execSQL("DROP TABLE reader_catalog_requests")
            db.execSQL("CREATE TABLE reader_catalog_requests (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, bookUrl TEXT NOT NULL, revision INTEGER NOT NULL, chapterIndex INTEGER, pageIndex INTEGER, consumed INTEGER NOT NULL)")
            val values = android.content.ContentValues()
            db.query("PRAGMA table_info(books)").use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1 && cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) {
                        when (cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase()) {
                            "TEXT" -> values.put(column, "")
                            "REAL" -> values.put(column, 0.0)
                            "BLOB" -> values.put(column, ByteArray(0))
                            else -> values.put(column, 0L)
                        }
                    }
                }
            }
            values.put("bookUrl", "retained-book"); values.put("name", "Retained library book")
            val entries = values.valueSet().map { it.key to it.value }
            db.execSQL("INSERT INTO books (${entries.joinToString { "`${it.first}`" }}) VALUES (${entries.joinToString { "?" }})", entries.map { it.second }.toTypedArray())
            db.execSQL("INSERT INTO search_keywords(word,usage,lastUseTime) VALUES('Retained',7,1234)")
            listOf("retained-book", "deleted-book").forEachIndexed { index, url ->
                db.execSQL("INSERT INTO reader_sessions(id,initialBookUrl,bookUrl,inBookshelf,chapterChanged,chapterIndex,pageIndex,revision,positionTime,ownerToken,updatedAt) VALUES(?,?,?,1,0,2,3,1,100,?,100)", arrayOf("reader-$index", url, url, "owner-$index"))
                db.execSQL("INSERT INTO reader_book_owners(bookUrl,sessionId,ownerToken) VALUES(?,?,?)", arrayOf(url, "reader-$index", "owner-$index"))
                db.execSQL("INSERT INTO reader_catalog_requests(id,sessionId,bookUrl,revision,chapterIndex,pageIndex,consumed) VALUES(?,?,?,1,4,1,0)", arrayOf("toc-$index", "reader-$index", url))
            }
            db.execSQL("UPDATE room_master_table SET identity_hash = '3747c60ab161937be11db747aa009642' WHERE id = 42")
        }
        val database = open()
        try {
            val dao = database.readerSessionDao
            assertEquals(132, database.openHelper.writableDatabase.version)
            assertEquals("Retained library book", database.bookDao.getBook("retained-book")!!.name)
            assertEquals(7, database.searchKeywordDao.get("Retained")!!.usage)
            assertTrue(dao.ownsBook("reader-0", "owner-0", 1, "retained-book"))
            assertEquals(4, checkNotNull(dao.applyCatalog("reader-0", "owner-0", 200)).chapterIndex)
            assertFalse(dao.ownsBook("reader-1", "owner-1", 1, "deleted-book"))
            assertNull(dao.catalog("toc-1"))
            database.bookDao.delete(checkNotNull(database.bookDao.getBook("retained-book")))
            assertFalse(dao.ownsBook("reader-0", "owner-0", 2, "retained-book"))
            assertNull(dao.catalog("toc-0"))
        } finally { database.close() }
    }

}
