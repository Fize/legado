package io.legado.app.data.navigation

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoRequestMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "detail-request-migration-${UUID.randomUUID()}"
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java,
        DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addLegadoMigrations().build()
    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val database = open()
        try { block(database) } finally { database.close() }
    }
    private val book = Book(bookUrl = "retained-book", name = "Retained library book", type = BookType.text or BookType.notShelf)
    private val position = BookInfoNavigationPosition(2, 17, true, 1, 2, 8, "retained anchor", 22)

    private fun insertLegacyBook(db: SupportSQLiteDatabase) {
        val values = ContentValues()
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
        values.put("bookUrl", book.bookUrl); values.put("name", book.name)
        values.put("durChapterIndex", 3); values.put("durChapterPos", 9)
        val entries = values.valueSet().map { it.key to it.value }
        db.execSQL("INSERT INTO books (${entries.joinToString { "`${it.first}`" }}) VALUES (${entries.joinToString { "?" }})",
            entries.map { it.second }.toTypedArray())
    }

    @Test fun upgrading124AddsReaderDetailOwnershipWithoutChangingExistingSessionsOrPreviews() {
        helper.createDatabase(name, 124).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO reader_sessions(id,initialBookUrl,bookUrl,inBookshelf,chapterChanged,chapterIndex,pageIndex,revision,positionTime,ownerToken,updatedAt) VALUES('reader',?,?,1,0,3,9,2,100,'reader-owner',100)", arrayOf(book.bookUrl, book.bookUrl))
            db.execSQL("INSERT INTO book_info_requests(id,initialBookUrl,bookUrl,ownerToken,revision,deleted,updatedAt,previewKey,previewCommitRevision) VALUES('detail',?,?,'detail-owner',7,0,100,'preview',5)", arrayOf(book.bookUrl, book.bookUrl))
        }
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            val session = checkNotNull(db.readerSessionDao.get("reader"))
            assertFalse(session.deleted); assertEquals(2L, session.revision); assertEquals(9, session.pageIndex)
            val detail = checkNotNull(db.bookInfoRequestDao.get("detail"))
            assertFalse(detail.returnToReader); assertEquals(5L, detail.previewCommitRevision)
            assertNull(db.readerDetailDao.active(session.id))
            assertEquals(3, db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
    }

    @Test fun upgrading123AddsUploadCommandsWithoutChangingBooksOrDeleteIntents() {
        helper.createDatabase(name, 123).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO book_info_delete_commands(id,requestId,bookUrl,snapshot,target,deleteOriginal,deleteRemote,status,phase,executionToken,callbackClaimed,error,updatedAt,installationId) VALUES('delete','old',?,'snapshot',NULL,0,0,'Queued','Accepted','',0,NULL,1,'install')", arrayOf(book.bookUrl))
        }
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            assertEquals(3, db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals("snapshot", db.bookInfoDeleteDao.get("delete")!!.snapshot)
            assertEquals(BookInfoDeleteStatus.Queued, db.bookInfoDeleteDao.get("delete")!!.status)
            assertTrue(db.bookInfoUploadDao.pending().isEmpty())
            val command = BookInfoUploadCommand("upload", "old", book.bookUrl, "source", "origin", "book.txt", "book.txt",
                UUID.randomUUID().toString(), "digest", 3, "target", false, "install", updatedAt = 2)
            db.bookInfoUploadDao.insert(command)
            db.bookInfoUploadDao.insertLease(BookInfoUploadLease(book.bookUrl, command.id))
            db.bookDao.delete(checkNotNull(db.bookDao.getBook(book.bookUrl)))
            assertNull(db.bookInfoUploadDao.lease(book.bookUrl))
            assertNotNull(db.bookInfoUploadDao.get(command.id))
        }
    }

    @Test fun upgrading118PreservesBooksReadingOwnershipAndPendingResults() {
        helper.createDatabase(name, 118).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO search_keywords(word,usage,lastUseTime) VALUES('Retained keyword',7,1234)")
            db.execSQL("INSERT INTO reader_sessions(id,initialBookUrl,bookUrl,inBookshelf,chapterChanged,chapterIndex,pageIndex,revision,positionTime,ownerToken,updatedAt) VALUES('reader',?,?,1,0,3,9,1,100,'reader-owner',100)", arrayOf(book.bookUrl, book.bookUrl))
            db.execSQL("INSERT INTO reader_book_owners(bookUrl,sessionId,ownerToken) VALUES(?,'reader','reader-owner')", arrayOf(book.bookUrl))
            db.execSQL("INSERT INTO reader_catalog_requests(id,sessionId,bookUrl,revision,chapterIndex,pageIndex,consumed) VALUES('reader-toc','reader',?,1,4,1,0)", arrayOf(book.bookUrl))
        }
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            val saved = checkNotNull(db.bookDao.getBook(book.bookUrl))
            assertEquals(book.name, saved.name); assertEquals(3, saved.durChapterIndex); assertEquals(9, saved.durChapterPos)
            assertEquals(7, db.searchKeywordDao.get("Retained keyword")!!.usage)
            assertTrue(db.readerSessionDao.ownsBook("reader", "reader-owner", 1, book.bookUrl))
            assertEquals(4, db.readerSessionDao.applyCatalog("reader", "reader-owner", 200)!!.chapterIndex)
            val owner = db.bookInfoRequestDao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
            val child = checkNotNull(db.bookInfoRequestDao.begin(owner.id, owner.ownerToken, owner.revision, owner.bookUrl,
                "catalog", BookInfoChildKind.Catalog, null, 2))
            db.bookInfoRequestDao.claimChild(child.id, book.bookUrl, child.kind, "catalog-owner", 3)
            assertTrue(db.bookInfoRequestDao.deliverCatalog(child.id, "catalog-owner", book.bookUrl, position, 4))
        }
        withDatabase { db ->
            val dao = db.bookInfoRequestDao
            val restored = dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "new-owner", updatedAt = 5), "new-owner")
            assertEquals(position, dao.activeChild(restored.id)!!.position)
            val reader = checkNotNull(dao.continueCatalog(restored.id, restored.ownerToken, restored.revision, restored.bookUrl,
                "catalog", "next-reader", 6))
            assertEquals(position, reader.position)
            assertNull(dao.continueCatalog(restored.id, restored.ownerToken, restored.revision, restored.bookUrl,
                "catalog", "duplicate-reader", 7))
            assertEquals(1234L, db.searchKeywordDao.get("Retained keyword")!!.lastUseTime)
            assertTrue(db.readerSessionDao.ownsBook("reader", "reader-owner", 2, book.bookUrl))
        }
    }

    @Test fun readerHandoffAndChangedCanonicalKeySurviveMultipleDatabaseReopens() {
        withDatabase { db ->
            db.bookDao.insert(book)
            val dao = db.bookInfoRequestDao
            dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
            dao.begin("detail", "owner", 0, book.bookUrl, "catalog", BookInfoChildKind.Catalog, null, 2)
            dao.claimChild("catalog", book.bookUrl, BookInfoChildKind.Catalog, "catalog-owner", 3)
            dao.deliverCatalog("catalog", "catalog-owner", book.bookUrl, position, 4)
            dao.continueCatalog("detail", "owner", 1, book.bookUrl, "catalog", "reader", 5)
        }
        val changed = book.copy(bookUrl = "changed-source", origin = "another-source", type = BookType.text)
        withDatabase { db ->
            val dao = db.bookInfoRequestDao
            val reader = checkNotNull(dao.activeChild("detail"))
            assertEquals("reader", reader.id); assertEquals(position, reader.position)
            assertEquals(BookInfoChildPhase.Requested, reader.phase)
            db.readerSessionDao.claim(ReaderSessionState(reader.id, book.bookUrl, book.bookUrl, false, false, updatedAt = 6), "reader-owner")
            dao.claimChild(reader.id, book.bookUrl, reader.kind, "reader-owner", 6)
            db.readerSessionDao.replaceOwnedBook(reader.id, "reader-owner", 0, book.bookUrl,
                changed.bookUrl, 2, 2, 7) { db.bookDao.delete(book); db.bookDao.insert(changed) }
            assertTrue(dao.deliverReader(reader.id, "reader-owner", book.bookUrl, changed.bookUrl, 7))
        }
        withDatabase { db ->
            val dao = db.bookInfoRequestDao
            val parent = dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "new-owner", updatedAt = 8), "new-owner")
            assertTrue(dao.consume(parent.id, parent.ownerToken, parent.revision, parent.bookUrl, "reader", 9)!!.readerInBookshelf)
            assertEquals(changed.bookUrl, dao.get(parent.id)!!.bookUrl)
        }
        withDatabase { db ->
            val dao = db.bookInfoRequestDao
            val parent = dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "final-owner", updatedAt = 10), "final-owner")
            assertEquals(changed.bookUrl, parent.bookUrl)
            assertNull(dao.activeChild(parent.id))
            assertNull(dao.consume(parent.id, parent.ownerToken, parent.revision, parent.bookUrl, "reader", 11))
            assertEquals(changed.bookUrl, db.bookDao.getBook(changed.bookUrl)!!.bookUrl)
        }
    }

    @Test fun pendingResultsKeepTheirParentForeignKeyAndSurviveBookDeletion() {
        helper.createDatabase(name, 118).close()
        withDatabase { db ->
            db.bookDao.insert(book)
            val dao = db.bookInfoRequestDao
            dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
            dao.begin("detail", "owner", 0, book.bookUrl, "reader", BookInfoChildKind.Reader, null, 2)
            db.readerSessionDao.claim(ReaderSessionState("reader", book.bookUrl, book.bookUrl, false, false, updatedAt = 3), "reader-owner")
            dao.claimChild("reader", book.bookUrl, BookInfoChildKind.Reader, "reader-owner", 3)
            db.bookDao.delete(book)
            assertNotNull(dao.child("reader"))
            assertTrue(dao.deliverReader("reader", "reader-owner", book.bookUrl, book.bookUrl, 4))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_list(book_info_child_requests)").use { cursor ->
                assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
                assertEquals("book_info_requests", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
        }
        withDatabase { db ->
            val dao = db.bookInfoRequestDao
            assertTrue(dao.consume("detail", "owner", 1, book.bookUrl, "reader", 5)!!.readerDeleted)
            assertTrue(dao.get("detail")!!.deleted)
            assertEquals(1, dao.close("detail", "owner"))
            assertNull(dao.child("reader"))
        }
    }

    @Test fun upgrading119RetainsThePendingChildAndBindsItsPreviewOnce() {
        helper.createDatabase(name, 119).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO book_info_requests(id,initialBookUrl,bookUrl,ownerToken,revision,deleted,updatedAt) VALUES('detail',?,?,'old',1,0,10)",
                arrayOf(book.bookUrl, book.bookUrl))
            db.execSQL("INSERT INTO book_info_child_requests(id,requestId,bookUrl,revision,kind,phase,producerToken,readerDeleted,readerInBookshelf,updatedAt) VALUES('catalog','detail',?,1,'Catalog','Displayed','catalog-owner',0,0,11)",
                arrayOf(book.bookUrl))
        }
        val previewKey = UUID.randomUUID().toString()
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            val dao = db.bookInfoRequestDao
            val legacy = checkNotNull(dao.get("detail"))
            assertNull(legacy.previewKey)
            val claimed = dao.claim(legacy.copy(previewKey = previewKey), "new")
            assertEquals(previewKey, claimed.previewKey)
            assertEquals(1L, claimed.revision)
            assertEquals(BookInfoChildPhase.Displayed, dao.activeChild("detail")!!.phase)
            assertEquals("catalog-owner", dao.child("catalog")!!.producerToken)
            assertThrows(IllegalArgumentException::class.java) { dao.claim(legacy, "old") }
            assertEquals("new", dao.get("detail")!!.ownerToken)
        }
        withDatabase { db ->
            assertEquals(previewKey, db.bookInfoRequestDao.get("detail")!!.previewKey)
            assertNotNull(db.bookInfoRequestDao.activeChild("detail"))
            assertEquals(3, db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
    }
    @Test fun upgrading120PreservesPreviewIdentityAndAddsAnIndependentCommitReceipt() {
        val previewKey = UUID.randomUUID().toString()
        helper.createDatabase(name, 120).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO book_info_requests(id,initialBookUrl,bookUrl,ownerToken,revision,deleted,updatedAt,previewKey) VALUES('detail',?,?,'owner',1,0,10,?)",
                arrayOf(book.bookUrl, book.bookUrl, previewKey))
            db.execSQL("INSERT INTO book_info_child_requests(id,requestId,bookUrl,revision,kind,phase,producerToken,readerDeleted,readerInBookshelf,updatedAt) VALUES('catalog','detail',?,1,'Catalog','Displayed','catalog-owner',0,0,11)",
                arrayOf(book.bookUrl))
        }
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            val dao = db.bookInfoRequestDao
            val parent = checkNotNull(dao.get("detail"))
            assertEquals(previewKey, parent.previewKey)
            assertNull(parent.previewCommitRevision)
            assertEquals(BookInfoChildPhase.Displayed, dao.activeChild("detail")!!.phase)
            assertTrue(dao.cancelChild("catalog", "catalog-owner", 12))
            assertNotNull(dao.consume("detail", "owner", 1, book.bookUrl, "catalog", 13))
            val committed = dao.replacePreview("detail", "owner", 2, book.bookUrl, book.bookUrl, 14) { }
            assertEquals(3L, committed.previewCommitRevision)
            assertEquals(3, db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
        withDatabase { db ->
            assertEquals(3L, db.bookInfoRequestDao.get("detail")!!.previewCommitRevision)
            assertEquals(previewKey, db.bookInfoRequestDao.get("detail")!!.previewKey)
        }
    }

    @Test fun upgrading121RetainsThePreviewReceiptAndAddsDurableDeletionLeases() {
        helper.createDatabase(name, 121).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO book_info_requests(id,initialBookUrl,bookUrl,ownerToken,revision,deleted,updatedAt,previewCommitRevision) VALUES('detail',?,?,'owner',4,0,10,4)",
                arrayOf(book.bookUrl, book.bookUrl))
        }
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            assertEquals(4L, db.bookInfoRequestDao.get("detail")!!.previewCommitRevision)
            val command = io.legado.app.data.entities.BookInfoDeleteCommand("delete", "detail", book.bookUrl,
                io.legado.app.utils.GSON.toJson(book), null, false, false, updatedAt = 11)
            db.bookInfoDeleteDao.insert(command)
            db.bookInfoDeleteDao.insertLease(io.legado.app.data.entities.BookInfoDeletionLease(book.bookUrl, command.id))
            assertEquals(3, db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
        withDatabase { db ->
            assertEquals("delete", db.bookInfoDeleteDao.lease(book.bookUrl)!!.commandId)
            db.bookDao.delete(checkNotNull(db.bookDao.getBook(book.bookUrl)))
            assertNull(db.bookInfoDeleteDao.lease(book.bookUrl))
            assertNotNull(db.bookInfoDeleteDao.get("delete"))
        }
    }

    @Test fun upgrading122DoesNotAuthorizeReplayingUnboundDestructiveCommands() = kotlinx.coroutines.runBlocking {
        helper.createDatabase(name, 122).use { db ->
            insertLegacyBook(db)
            db.execSQL("INSERT INTO book_info_delete_commands(id,requestId,bookUrl,snapshot,deleteOriginal,deleteRemote,status,phase,executionToken,callbackClaimed,updatedAt) VALUES('delete','detail',?,?,0,0,'Queued','Accepted','',0,11)",
                arrayOf(book.bookUrl, io.legado.app.utils.GSON.toJson(book)))
            db.execSQL("INSERT INTO book_info_deletion_leases(bookUrl,commandId) VALUES(?,'delete')", arrayOf(book.bookUrl))
        }
        withDatabase { db ->
            assertEquals(132, db.openHelper.writableDatabase.version)
            assertNull(db.bookInfoDeleteDao.get("delete")!!.installationId)
            kotlinx.coroutines.runBlocking { io.legado.app.data.repository.BookInfoDeleteRepository(db).pending() }
            assertEquals(io.legado.app.data.entities.BookInfoDeleteStatus.Cancelled, db.bookInfoDeleteDao.get("delete")!!.status)
            assertNull(db.bookInfoDeleteDao.lease(book.bookUrl))
            assertEquals(3, db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
    }

}
