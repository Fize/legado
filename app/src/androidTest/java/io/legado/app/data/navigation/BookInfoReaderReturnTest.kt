package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.help.globalExecutor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookInfoReaderReturnTest {
    private lateinit var database: AppDatabase
    private lateinit var readers: ReaderSessionRepository
    private val book = Book(bookUrl = "book", name = "Book", type = BookType.text or BookType.notShelf)
    private val dao get() = database.bookInfoRequestDao
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).addLegadoMigrations().build()
        database.bookDao.insert(book)
        readers = ReaderSessionRepository(dagger.Lazy { database })
        dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "detail-owner", updatedAt = 1), "detail-owner")
        checkNotNull(dao.begin("detail", "detail-owner", 0, book.bookUrl, "reader", BookInfoChildKind.Reader, null, 2))
    }
    private fun flush() { globalExecutor.submit {}.get(10, TimeUnit.SECONDS) }
    @After fun cleanup() { flush(); database.close() }
    private fun open(token: String) = runBlocking {
        readers.claim(ReaderOpenRequest("reader", book.bookUrl, inBookshelf = false, returnToDetails = true), token)
    }
    private fun owner(token: String) = ReaderProgressOwner("reader", token, 0)

    @Test fun readerClaimBindsTheSameDurableEpochToItsReturnRequest() {
        open("reader-owner")
        assertEquals("reader-owner", dao.child("reader")!!.producerToken)
        assertEquals(BookInfoChildPhase.Displayed, dao.child("reader")!!.phase)
        assertTrue(database.readerSessionDao.ownsBook("reader", "reader-owner", 0, book.bookUrl))
    }

    private fun replacePendingPosition(position: BookInfoNavigationPosition) {
        check(dao.cancelRequested("detail", "detail-owner", 1, book.bookUrl, "reader", 3))
        checkNotNull(dao.consume("detail", "detail-owner", 1, book.bookUrl, "reader", 4))
        checkNotNull(dao.begin("detail", "detail-owner", 2, book.bookUrl, "positioned-reader",
            BookInfoChildKind.Reader, position, 5))
    }

    @Test fun durableCatalogPositionWinsOverDifferentRouteArguments() = runBlocking {
        replacePendingPosition(BookInfoNavigationPosition(4, 7, chapterChanged = true))
        val state = readers.claim(ReaderOpenRequest("positioned-reader", book.bookUrl,
            chapterIndex = 99, pageIndex = 88, returnToDetails = true), "reader-owner")
        assertEquals(4, state.chapterIndex)
        assertEquals(7, state.pageIndex)
        assertTrue(state.chapterChanged)
    }

    @Test fun absolutePdfPositionBecomesAPageWithinItsDurableChapter() = runBlocking {
        replacePendingPosition(BookInfoNavigationPosition(2, 0, chapterChanged = true, pdfPageIndex = 22))
        val state = readers.claim(ReaderOpenRequest("positioned-reader", book.bookUrl,
            returnToDetails = true), "reader-owner")
        assertEquals(2, state.chapterIndex)
        assertEquals(2, state.pageIndex)
    }

    @Test fun readerRecreationKeepsNewProgressInsteadOfReplayingTheCatalogPosition() = runBlocking {
        replacePendingPosition(BookInfoNavigationPosition(4, 7, chapterChanged = true))
        readers.claim(ReaderOpenRequest("positioned-reader", book.bookUrl, returnToDetails = true), "old")
        assertEquals(1, database.readerSessionDao.saveProgress("positioned-reader", "old", 0,
            book.bookUrl, book.origin, 5, 3, "Next chapter", 100))
        val restored = readers.claim(ReaderOpenRequest("positioned-reader", book.bookUrl,
            returnToDetails = true), "new")
        assertEquals(5, restored.chapterIndex)
        assertEquals(3, restored.pageIndex)
        assertEquals("new", dao.child("positioned-reader")!!.producerToken)
    }

    @Test fun closeFollowsQueuedProgressAndPublishesBeforeDeletingTheReaderProof() {
        open("reader-owner")
        globalExecutor.execute { database.readerSessionDao.saveProgress("reader", "reader-owner", 0,
            book.bookUrl, book.origin, 2, 3, "Visible chapter", 100) }
        readers.close(owner("reader-owner")); flush()
        assertEquals(2, database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(3, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertEquals(BookInfoChildPhase.Returned, dao.child("reader")!!.phase)
        assertEquals(book.bookUrl, dao.child("reader")!!.returnBookUrl)
        assertNull(database.readerSessionDao.get("reader"))
    }

    @Test fun aRecreatedReaderRevokesTheOldInstancesCloseAndKeepsThePendingResult() {
        open("old"); open("new")
        readers.close(owner("old")); flush()
        assertEquals(BookInfoChildPhase.Displayed, dao.child("reader")!!.phase)
        assertEquals("new", database.readerSessionDao.get("reader")!!.ownerToken)
        readers.close(owner("new")); flush()
        assertEquals(BookInfoChildPhase.Returned, dao.child("reader")!!.phase)
    }

    @Test fun sourceReplacementReturnsItsCanonicalKeyWithoutNameAuthorLookup() {
        open("reader-owner")
        val changed = book.copy(bookUrl = "changed-source", origin = "new-source", type = BookType.text)
        database.readerSessionDao.replaceOwnedBook("reader", "reader-owner", 0, book.bookUrl,
            changed.bookUrl, 1, 2, 10) { database.bookDao.delete(book); database.bookDao.insert(changed) }
        readers.close(owner("reader-owner")); flush()
        assertEquals(changed.bookUrl, dao.child("reader")!!.returnBookUrl)
        assertTrue(dao.child("reader")!!.readerInBookshelf)
        assertNotNull(dao.consume("detail", "detail-owner", 1, book.bookUrl, "reader", 20))
        assertEquals(changed.bookUrl, dao.get("detail")!!.bookUrl)
    }

    @Test fun missingParentRequestRollsBackReaderClaimAndBookOwnerHandoff() {
        database.readerSessionDao.claim(ReaderSessionState("existing", book.bookUrl, book.bookUrl, true, false, updatedAt = 1), "existing-owner")
        dao.close("detail", "detail-owner")
        assertThrows(IllegalStateException::class.java) { open("reader-owner") }
        assertNull(database.readerSessionDao.get("reader"))
        assertTrue(database.readerSessionDao.ownsBook("existing", "existing-owner", 0, book.bookUrl))
    }

    @Test fun cancelledChildCannotStealTheBookOwnerWhenItsOldRouteReopens() {
        database.readerSessionDao.claim(ReaderSessionState("existing", book.bookUrl, book.bookUrl, true, false, updatedAt = 1), "existing-owner")
        dao.cancelRequested("detail", "detail-owner", 1, book.bookUrl, "reader", 3)
        assertThrows(IllegalStateException::class.java) { open("reader-owner") }
        assertNull(database.readerSessionDao.get("reader"))
        assertTrue(database.readerSessionDao.ownsBook("existing", "existing-owner", 0, book.bookUrl))
    }

    @Test fun aCompletedReturnCannotBeReplayedByARestoredReaderRoute() {
        open("reader-owner"); readers.close(owner("reader-owner")); flush()
        assertThrows(IllegalStateException::class.java) { open("late-reader") }
        assertNull(database.readerSessionDao.get("reader"))
        assertEquals(BookInfoChildPhase.Returned, dao.child("reader")!!.phase)
    }

    @Test fun anUnrelatedStandaloneReaderStillDisposesNormally() = runBlocking {
        readers.claim(ReaderOpenRequest("standalone", book.bookUrl), "standalone-owner")
        readers.close(ReaderProgressOwner("standalone", "standalone-owner", 0)); flush()
        assertNull(database.readerSessionDao.get("standalone"))
        assertEquals(BookInfoChildPhase.Requested, dao.child("reader")!!.phase)
    }
}
