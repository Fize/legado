package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.PlatformReaderReturnRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformReaderReturnTest {
    private lateinit var database: AppDatabase
    private lateinit var returns: PlatformReaderReturnRepository
    private val book = Book(bookUrl = "original", name = "Original", type = BookType.text)
    private val dao get() = database.bookInfoRequestDao
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).addLegadoMigrations().build()
        returns = PlatformReaderReturnRepository(database)
        database.bookDao.insert(book)
        dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "detail-owner", updatedAt = 1), "detail-owner")
        checkNotNull(dao.begin("detail", "detail-owner", 0, book.bookUrl, "reader", BookInfoChildKind.Reader,
            BookInfoNavigationPosition(2, 80, true, pdfPageIndex = 22), 2))
    }
    @After fun cleanup() { database.close() }

    @Test fun nativeReturnIsConsumedByTheOriginalDetailWithoutAComposeSession() {
        val owner = returns.claim("reader", book.bookUrl, "native")
        assertEquals(22, owner.position!!.pdfPageIndex)
        assertNull(database.readerSessionDao.get("reader"))
        assertTrue(returns.finish(owner))
        val result = dao.consume("detail", "detail-owner", 1, book.bookUrl, "reader", 10)!!
        assertEquals(book.bookUrl, result.returnBookUrl)
        assertTrue(result.readerInBookshelf)
        assertFalse(result.readerDeleted)
    }

    @Test fun processTakeoverKeepsPendingPositionUntilItsActualApplicationIsAcknowledged() {
        val old = returns.claim("reader", book.bookUrl, "old")
        val recreated = returns.claim("reader", book.bookUrl, "recreated")
        assertEquals(old.position, recreated.position)
        assertFalse(returns.acknowledgePosition(old))
        assertTrue(returns.acknowledgePosition(recreated))
        assertNull(returns.claim("reader", book.bookUrl, "latest").position)
        assertFalse(returns.finish(recreated))
    }

    @Test fun sourceChangeAndItsReturnKeyCommitTogetherAndSurviveAProcessTakeover() {
        val owner = returns.claim("reader", book.bookUrl, "old")
        val replacement = book.copy(bookUrl = "replacement", origin = "new-source")
        returns.replaceSource(owner, book.bookUrl, replacement.bookUrl) {
            database.bookDao.delete(book); database.bookDao.insert(replacement)
        }
        val restored = returns.claim("reader", book.bookUrl, "restored")
        assertEquals(replacement.bookUrl, restored.returnBookUrl)
        assertFalse(returns.finish(owner))
        assertTrue(returns.finish(restored))
        dao.consume("detail", "detail-owner", 1, book.bookUrl, "reader", 10)
        assertEquals(replacement.bookUrl, dao.get("detail")!!.bookUrl)
    }

    @Test fun failedCanonicalKeyPublicationRollsBackTheSourceReplacement() {
        val owner = returns.claim("reader", book.bookUrl, "native")
        assertThrows(IllegalStateException::class.java) {
            returns.replaceSource(owner, book.bookUrl, "missing-replacement") { database.bookDao.delete(book) }
        }
        assertNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(book.bookUrl, dao.child("reader")!!.returnBookUrl)
    }

    @Test fun cancelledAndClosedRequestsCannotBeReopenedOrMoveAnUnrelatedBook() {
        val owner = returns.claim("reader", book.bookUrl, "native")
        assertTrue(dao.cancelChild(owner.id, owner.producerToken, 10))
        assertThrows(IllegalStateException::class.java) { returns.claim("reader", book.bookUrl, "late") }
        assertThrows(IllegalStateException::class.java) {
            returns.replaceSource(owner, book.bookUrl, "other") { fail("Must reject before mutating books") }
        }
        assertFalse(returns.finish(owner))
        dao.close("detail", "detail-owner")
        assertThrows(IllegalStateException::class.java) { returns.claim("reader", book.bookUrl, "late") }
    }

    @Test fun anExistingComposeReaderCannotBeTakenOverThroughThePlatformAdapter() {
        database.readerSessionDao.claim(ReaderSessionState("reader", book.bookUrl, book.bookUrl,
            true, false, updatedAt = 3), "compose")
        assertThrows(IllegalStateException::class.java) { returns.claim("reader", book.bookUrl, "native") }
        assertEquals("compose", database.readerSessionDao.get("reader")!!.ownerToken)
    }

    @Test fun removedPreviewReportsDeletionInsteadOfInventingASameNamedReplacement() {
        val owner = returns.claim("reader", book.bookUrl, "native")
        database.bookDao.delete(book)
        database.bookDao.insert(book.copy(bookUrl = "unrelated"))
        assertTrue(returns.finish(owner))
        val result = dao.child("reader")!!
        assertTrue(result.readerDeleted)
        assertFalse(result.readerInBookshelf)
        assertEquals(book.bookUrl, result.returnBookUrl)
    }
}
