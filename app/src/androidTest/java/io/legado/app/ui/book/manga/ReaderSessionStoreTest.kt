package io.legado.app.ui.book.manga

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReaderSessionState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSessionStoreTest {
    private lateinit var database: AppDatabase
    private val dao get() = database.readerSessionDao
    private val book = Book(bookUrl = "https://reader.invalid/book", origin = "https://reader.invalid",
        name = "Owned", author = "Author", totalChapterNum = 6)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).addLegadoMigrations().build()
        database.bookDao.insert(book)
    }
    @After fun tearDown() { database.close() }

    private fun request(id: String = "reader") = ReaderSessionState(id, book.bookUrl, book.bookUrl,
        true, false, updatedAt = 1)
    private fun save(token: String, revision: Long = 0, chapter: Int = 1, time: Long = 100, id: String = "reader") =
        dao.saveProgress(id, token, revision, book.bookUrl, book.origin, chapter, 2, "Chapter", time)

    @Test fun aNewOwnerRejectsThePreviousInstancesLateWriteEvenWithANewerTimestamp() {
        dao.claim(request(), "old")
        assertEquals(1, save("old"))
        dao.claim(request(), "new")
        assertEquals(0, save("old", chapter = 5, time = 1000))
        assertEquals(1, dao.get("reader")!!.chapterIndex)
        assertEquals(1, save("new", chapter = 2, time = 200))
        assertEquals(2, database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun separateReaderEntriesKeepTheirPositionsButOnlyTheActiveOwnerWritesTheBook() {
        dao.claim(request(), "first")
        assertEquals(1, save("first"))
        dao.claim(request("another"), "second")
        assertEquals(0, save("first", chapter = 4, time = 300))
        assertEquals(4, dao.get("reader")!!.chapterIndex)
        assertEquals(1, database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(1, save("second", chapter = 2, time = 200, id = "another"))
        assertTrue(dao.activate("reader", "first"))
        assertEquals(1, save("first", chapter = 4, time = 400))
    }

    @Test fun theSameEntryRestoresItsLatestBookAndPositionInsteadOfReplayingLaunchArguments() {
        dao.claim(request(), "first")
        val changed = book.copy(bookUrl = "https://new.invalid/book", origin = "https://new.invalid")
        val moved = dao.replaceOwnedBook("reader", "first", 0, book.bookUrl, changed.bookUrl, 3, 4, 10) {
            database.bookDao.delete(book)
            database.bookDao.insert(changed)
        }
        val restored = dao.claim(request(), "replacement")
        assertEquals(changed.bookUrl, restored.bookUrl)
        assertEquals(3, restored.chapterIndex); assertEquals(4, restored.pageIndex)
        assertEquals(moved.revision, restored.revision)
        assertFalse(dao.ownsBook("reader", "first", moved.revision, changed.bookUrl))
        assertTrue(dao.ownsBook("reader", "replacement", moved.revision, changed.bookUrl))
    }

    @Test fun aRequestIdCannotBeReboundToAnUnrelatedInitialBook() {
        dao.claim(request(), "first")
        try { dao.claim(request().copy(initialBookUrl = "other"), "attacker"); fail("Identity must be immutable") }
        catch (_: IllegalArgumentException) { }
        assertEquals("first", dao.get("reader")!!.ownerToken)
    }

    @Test fun queuedWritesBeforeNavigationCannotOverwriteTheChosenPosition() {
        dao.claim(request(), "owner")
        val catalog = checkNotNull(dao.beginCatalog("reader", "owner", "toc", 1, 2, 10))
        assertEquals(0, save("owner", revision = 0, chapter = 5, time = 1000))
        assertTrue(dao.deliverCatalog("toc", book.bookUrl, 3, 1))
        val chosen = checkNotNull(dao.applyCatalog("reader", "owner", 20))
        assertTrue(chosen.revision > catalog.revision)
        assertEquals(3, chosen.chapterIndex); assertEquals(1, chosen.pageIndex)
        assertEquals(0, save("owner", revision = catalog.revision, chapter = 4, time = 2000))
        assertNull(dao.applyCatalog("reader", "owner", 30))
    }

    @Test fun aDeliveredCatalogChoiceSurvivesANewOwnerAndIsAppliedExactlyOnce() {
        dao.claim(request(), "old")
        dao.beginCatalog("reader", "old", "toc", 1, 2, 10)
        assertTrue(dao.deliverCatalog("toc", book.bookUrl, 4, 0))
        dao.claim(request(), "new")
        assertNull(dao.applyCatalog("reader", "old", 20))
        assertEquals(4, checkNotNull(dao.applyCatalog("reader", "new", 20)).chapterIndex)
        assertNull(dao.applyCatalog("reader", "new", 30))
        assertFalse(dao.deliverCatalog("toc", book.bookUrl, 2, 0))
    }

    @Test fun sourceChangesAndNewCatalogRequestsRejectOldReturns() {
        dao.claim(request(), "owner")
        dao.beginCatalog("reader", "owner", "first", 1, 2, 10)
        dao.beginCatalog("reader", "owner", "second", 1, 2, 20)
        assertFalse(dao.deliverCatalog("first", book.bookUrl, 4, 0))
        assertFalse(dao.deliverCatalog("second", "other-book", 4, 0))
        assertFalse(dao.deliverCatalog("second", book.bookUrl, -1, 0))
        database.bookDao.insert(book.copy(bookUrl = "new-source", name = "Another source"))
        dao.moveBook("reader", "owner", "new-source", 1, 2, 30)
        assertFalse(dao.deliverCatalog("second", book.bookUrl, 4, 0))
    }

    @Test fun staleInstancesCannotReacquireOwnershipThroughNavigationOrSourceReplacement() {
        dao.claim(request(), "old")
        dao.claim(request(), "new")
        assertNull(dao.moveBook("reader", "old", "other", 1, 0, 10))
        assertNull(dao.beginCatalog("reader", "old", "toc", 1, 0, 10))
        assertFalse(dao.activate("reader", "old"))
        assertTrue(dao.ownsBook("reader", "new", 0, book.bookUrl))
    }

    @Test fun outOfOrderWritesDoNotRollBackTheDurableEntryPosition() {
        dao.claim(request(), "owner")
        assertEquals(1, save("owner", chapter = 3, time = 300))
        assertEquals(0, save("owner", chapter = 1, time = 100))
        assertEquals(3, dao.get("reader")!!.chapterIndex)
        assertEquals(3, database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun aDifferentSourceAtTheSameBookUrlRejectsBothBookAndEntryProgress() {
        dao.claim(request(), "owner")
        database.bookDao.insert(book.copy(origin = "new-source"))
        assertEquals(0, save("owner", chapter = 3))
        assertEquals(-1, dao.get("reader")!!.chapterIndex)
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun theCurrentDurableOwnerCanSaveAfterClockRollbackWithoutReducingTheBookTimestamp() {
        database.bookDao.insert(book.copy(durChapterTime = 10000))
        dao.claim(request(), "owner")
        assertEquals(1, save("owner", chapter = 3, time = 100))
        val saved = checkNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(3, saved.durChapterIndex)
        assertEquals(10000L, saved.durChapterTime)
    }

    @Test fun deletingAndRecreatingTheSameBookKeyRevokesTheOldWriter() {
        dao.claim(request(), "owner")
        database.bookDao.delete(book)
        database.bookDao.insert(book.copy(name = "Recreated"))
        assertEquals(0, save("owner", chapter = 4, time = 1000))
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertFalse(dao.ownsBook("reader", "owner", 0, book.bookUrl))
    }

    @Test fun sourceReplacementAndItsRequestIdentityCommitTogetherDespiteDeleteCascades() {
        dao.claim(request(), "owner")
        val changed = book.copy(bookUrl = "new-source", origin = "new-origin")
        val updated = dao.replaceOwnedBook("reader", "owner", 0, book.bookUrl, changed.bookUrl, 2, 1, 10) {
            database.bookDao.delete(book)
            database.bookDao.insert(changed)
        }
        assertEquals(changed.bookUrl, updated.bookUrl)
        assertTrue(dao.ownsBook("reader", "owner", updated.revision, changed.bookUrl))
        assertNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(0, save("owner", revision = 0, chapter = 4, time = 1000))
    }

    @Test fun failureWhileReplacingTheSourceRollsBackTheBookRequestAndOwner() {
        dao.claim(request(), "owner")
        try {
            dao.replaceOwnedBook("reader", "owner", 0, book.bookUrl, "new-source", 2, 1, 10) {
                database.bookDao.delete(book)
                error("Fixture transaction failure")
            }
            fail("Replacement must roll back")
        } catch (_: IllegalStateException) { }
        assertNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(book.bookUrl, dao.get("reader")!!.bookUrl)
        assertTrue(dao.ownsBook("reader", "owner", 0, book.bookUrl))
    }

    @Test fun metadataUpdatesKeepTheOwnerAndCannotBeOverwrittenByAProgressSnapshot() {
        dao.claim(request(), "owner")
        database.bookDao.update(book.copy(name = "Edited", author = "Edited author", group = 32L))
        assertEquals(1, save("owner", chapter = 3, time = 100))
        val saved = checkNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals("Edited", saved.name); assertEquals("Edited author", saved.author)
        assertEquals(32L, saved.group); assertEquals(3, saved.durChapterIndex)
        assertTrue(dao.ownsBook("reader", "owner", 0, book.bookUrl))
    }

    @Test fun deletingTheBookAlsoInvalidatesPendingCatalogResultsForItsOldIncarnation() {
        dao.claim(request(), "owner")
        dao.beginCatalog("reader", "owner", "toc", 1, 0, 10)
        assertTrue(dao.deliverCatalog("toc", book.bookUrl, 2, 0))
        database.bookDao.delete(book)
        database.bookDao.insert(book)
        assertNull(dao.catalog("toc"))
        dao.claim(request(), "new")
        assertNull(dao.applyCatalog("reader", "new", 20))
    }

    @Test fun closingAnOldInstanceCannotDeleteItsReplacementAndClosingTheOwnerCleansUpResults() {
        dao.claim(request(), "old")
        dao.beginCatalog("reader", "old", "toc", 1, 0, 10)
        dao.claim(request(), "new")
        assertEquals(0, dao.close("reader", "old"))
        assertNotNull(dao.get("reader")); assertNotNull(dao.catalog("toc"))
        assertEquals(1, dao.close("reader", "new"))
        assertNull(dao.get("reader")); assertNull(dao.catalog("toc"))
        assertFalse(dao.ownsBook("reader", "new", 1, book.bookUrl))
    }

    @Test fun aDeletedBookIsNeverRecreatedByItsReaderOwner() {
        dao.claim(request(), "owner")
        database.bookDao.delete(book)
        assertEquals(0, save("owner"))
        assertNull(database.bookDao.getBook(book.bookUrl))
    }
}
