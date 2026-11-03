package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderDetailRequestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val databaseName = "reader-detail-$id"
    private lateinit var database: AppDatabase
    private lateinit var navigation: BookInfoNavigationRepository
    private val previews = BookInfoPreviewStore(context)
    private val leases = mutableListOf<BookInfoPreviewLease>()
    private val book = Book(bookUrl = "https://reader-detail-$id.invalid/book", origin = "source-$id", originName = "Source",
        name = "Reader detail $id", author = "Author $id", type = BookType.image, durChapterIndex = 2, durChapterPos = 3)
    private val sessionId = "reader-$id"
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).addLegadoMigrations().build()
    @Before fun setup() {
        database = open(); navigation = BookInfoNavigationRepository(database)
        database.bookDao.insert(book)
        database.readerSessionDao.claim(ReaderSessionState(sessionId, book.bookUrl, book.bookUrl, true, false, updatedAt = 1), "reader")
    }
    @After fun cleanup() {
        leases.forEach { previews.discard(it) }
        database.close(); context.deleteDatabase(databaseName)
    }
    private fun begin(): ReaderDetailRequest = checkNotNull(database.readerDetailDao.begin(sessionId, "reader", 0, id, 2, 3, 2))
    private suspend fun claim(token: String = "detail"): BookInfoRequest {
        val lease = previews.open(id).also { leases += it }
        return checkNotNull(navigation.claimPreview(id, book.bookUrl, lease, previews, token, true))
    }

    @Test fun enteringDetailsRevokesProgressUntilItsExactResultIsConsumed() = runBlocking {
        val request = begin(); val parent = claim()
        assertEquals(1L, request.revision)
        assertFalse(database.readerSessionDao.activate(sessionId, "reader"))
        assertEquals(0, database.readerSessionDao.saveProgress(sessionId, "reader", request.revision,
            book.bookUrl, book.origin, 9, 99, "late", 10))
        assertTrue(navigation.close(parent.navigationOwner()))
        val returned = checkNotNull(database.readerDetailDao.apply(sessionId, "reader", 11))
        assertEquals(ReaderDetailPhase.Consumed, returned.request.phase)
        assertEquals(2L, returned.session.revision)
        assertEquals(3, returned.session.pageIndex)
        assertTrue(database.readerSessionDao.ownsBook(sessionId, "reader", returned.session.revision, book.bookUrl))
        assertNull(database.readerDetailDao.apply(sessionId, "reader", 12))
    }
    @Test fun sourceReplacementReturnsTheCommittedCanonicalKeyToTheOriginalSession() = runBlocking {
        begin(); val parent = claim()
        val changed = book.copy(bookUrl = "${book.bookUrl}/changed", origin = "changed-source", durChapterIndex = 4, durChapterPos = 7)
        val updated = navigation.replaceBook(parent.navigationOwner(), changed.bookUrl) {
            database.bookDao.delete(book); database.bookDao.insert(changed)
        }
        assertEquals(changed.bookUrl, database.readerDetailDao.lease(id)!!.bookUrl)
        navigation.close(updated.navigationOwner())
        val result = checkNotNull(database.readerDetailDao.apply(sessionId, "reader", 20))
        assertFalse(result.session.deleted)
        assertEquals(book.bookUrl, result.session.initialBookUrl)
        assertEquals(changed.bookUrl, result.session.bookUrl)
        assertEquals(4, result.session.chapterIndex); assertEquals(7, result.session.pageIndex)
        assertEquals(0, database.readerSessionDao.saveProgress(sessionId, "reader", 1, book.bookUrl, book.origin, 8, 88, null, 30))
    }
    @Test fun deletionIsDurableAndNeverAdoptsAReimportAtTheSameUrl() = runBlocking {
        begin(); val parent = claim()
        database.runInTransaction {
            database.bookDao.delete(book)
            database.bookInfoDeleteDao.publishDeleted(book.bookUrl, 10)
            database.readerDetailDao.publishDeleted(book.bookUrl, 10)
        }
        assertTrue(database.readerDetailDao.get(id)!!.deleted)
        database.bookDao.insert(book.copy(name = "Reimport $id", durChapterPos = 99))
        navigation.close(parent.navigationOwner())
        val result = checkNotNull(database.readerDetailDao.apply(sessionId, "reader", 20))
        assertTrue(result.session.deleted)
        assertFalse(database.readerSessionDao.activate(sessionId, "reader"))
        assertEquals(99, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun replacingTheResultRowBeforeConsumptionDoesNotInheritItsLease() = runBlocking {
        begin(); val parent = claim(); navigation.close(parent.navigationOwner())
        database.bookDao.delete(book); database.bookDao.insert(book.copy(name = "Replacement $id", durChapterPos = 77))
        val result = checkNotNull(database.readerDetailDao.apply(sessionId, "reader", 20))
        assertTrue(result.session.deleted)
        assertEquals(77, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun aReplacedProducerCannotPublishOrModifyTheNewDetailEntry() = runBlocking {
        begin(); val old = claim(); val current = claim("new-detail")
        assertFalse(navigation.close(old.navigationOwner()))
        try { BookInfoRepository(database).applyEdit(book.bookUrl, BookInfoEdit.CanUpdate(false), old.navigationOwner()); fail("Stale producer wrote the book") }
        catch (_: IllegalStateException) { }
        assertTrue(navigation.close(current.navigationOwner()))
        assertNotNull(database.readerDetailDao.apply(sessionId, "reader", 20))
    }
    @Test fun closingTheReaderInvalidatesTheDetailAndItsOrdinaryWrites() = runBlocking {
        begin(); val parent = claim()
        database.readerSessionDao.close(sessionId, "reader")
        assertNull(database.readerDetailDao.get(id))
        try { BookInfoRepository(database).applyEdit(book.bookUrl, BookInfoEdit.CanUpdate(false), parent.navigationOwner()); fail("Orphan producer wrote the book") }
        catch (_: IllegalStateException) { }
        assertTrue(database.bookDao.getBook(book.bookUrl)!!.canUpdate)
    }
    @Test fun aMissingReaderOrReimportedRowCannotClaimTheQueuedDetail() = runBlocking {
        begin(); database.bookDao.delete(book); database.bookDao.insert(book)
        val lease = previews.open(id).also { leases += it }
        assertNull(navigation.claimPreview(id, book.bookUrl, lease, previews, "detail", true))
        assertNull(database.bookInfoRequestDao.get(id))
    }
    @Test fun plainEntryCannotClaimAnExistingReaderBoundRequest() = runBlocking {
        begin(); claim()
        val lease = previews.open(id).also { leases += it }
        try { navigation.claimPreview(id, book.bookUrl, lease, previews, "plain", false); fail("Plain route stole reader detail") }
        catch (_: IllegalArgumentException) { }
        assertEquals("detail", database.bookInfoRequestDao.get(id)!!.ownerToken)
    }
    @Test fun cancellingBeforeNavigationReactivatesTheReaderWithoutProducingAResult() {
        begin()
        assertTrue(database.readerDetailDao.cancelRequested(sessionId, "reader", id, 10))
        assertTrue(database.readerSessionDao.activate(sessionId, "reader"))
        assertNull(database.readerDetailDao.active(sessionId))
        assertNull(database.readerDetailDao.claim(id, book.bookUrl, "late", 11))
    }
    @Test fun anotherConsumerCannotConsumeTheOriginalReadersResult() = runBlocking {
        begin(); val parent = claim(); navigation.close(parent.navigationOwner())
        assertNull(database.readerDetailDao.apply(sessionId, "other-token", 11))
        assertEquals(ReaderDetailPhase.Returned, database.readerDetailDao.get(id)!!.phase)
        assertNotNull(database.readerDetailDao.apply(sessionId, "reader", 12))
    }
    @Test fun returnedCanonicalKeyIsAppliedBeforeARecreatedReaderLoadsItsBook() = runBlocking {
        begin(); val parent = claim()
        val changed = book.copy(bookUrl = "${book.bookUrl}/changed", origin = "changed-source")
        val updated = navigation.replaceBook(parent.navigationOwner(), changed.bookUrl) {
            database.bookDao.delete(book); database.bookDao.insert(changed)
        }
        navigation.close(updated.navigationOwner())
        database.close(); database = open()
        val sessions = ReaderSessionRepository(dagger.Lazy { database })
        val restored = sessions.claim(ReaderOpenRequest(sessionId, book.bookUrl), "recreated")
        assertEquals(changed.bookUrl, restored.bookUrl)
        assertTrue(database.readerSessionDao.ownsBook(sessionId, "recreated", restored.revision, changed.bookUrl))
        assertEquals(ReaderDetailPhase.Consumed, database.readerDetailDao.get(id)!!.phase)
    }
    @Test fun anUnshelvedPrivatePreviewCanReturnWithoutResurrectingTheOldSource() = runBlocking {
        database.bookDao.update(book.copy(type = BookType.image or BookType.notShelf))
        begin(); val parent = claim()
        val original = checkNotNull(database.bookDao.getBook(book.bookUrl))
        val changed = original.copy(bookUrl = "${book.bookUrl}/preview", origin = "preview-source")
        val lease = leases.last()
        val preview = BookInfoPreview(changed, listOf(BookChapter(bookUrl = changed.bookUrl, url = "chapter", title = "New", index = 0)),
            changed.origin, false)
        val updated = navigation.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        assertFalse(database.bookDao.has(changed.bookUrl))
        navigation.prepareReaderReturn(updated.navigationOwner(), changed, preview.chapters)
        navigation.close(updated.navigationOwner())
        val returned = checkNotNull(database.readerDetailDao.apply(sessionId, "reader", 20))
        assertEquals(changed.bookUrl, returned.session.bookUrl); assertFalse(returned.session.inBookshelf)
        assertFalse(database.bookDao.has(original.bookUrl))
        assertEquals(1, database.bookChapterDao.getChapterList(changed.bookUrl).size)
    }

    @Test fun anOrphanedUnshelvedDetailRecoversItsJournalBeforeReturning() = runBlocking {
        database.bookDao.update(book.copy(type = BookType.image or BookType.notShelf))
        begin(); val parent = claim()
        val original = checkNotNull(database.bookDao.getBook(book.bookUrl))
        val changed = original.copy(bookUrl = "${book.bookUrl}/orphan", origin = "orphan-source")
        val preview = BookInfoPreview(changed, listOf(BookChapter(bookUrl = changed.bookUrl, url = "chapter", title = "Recovered", index = 0)), changed.origin, false)
        navigation.replacePreview(parent.navigationOwner(), leases.last(), previews, 1, preview, original)
        val sessions = ReaderSessionRepository(dagger.Lazy { database }, dagger.Lazy { navigation }, dagger.Lazy { previews })
        sessions.recoverDetail(ReaderProgressOwner(sessionId, "reader", 1), id)
        val applied = checkNotNull(sessions.applyDetail(ReaderProgressOwner(sessionId, "reader", 1)))
        assertEquals(changed.bookUrl, applied.session.bookUrl)
        assertFalse(applied.session.deleted); assertFalse(applied.session.inBookshelf)
        assertNull(database.bookInfoRequestDao.get(id))
        assertEquals("Recovered", database.bookChapterDao.getChapterList(changed.bookUrl).single().title)
    }

    @Test fun catalogSelectionReturnsToTheExistingReaderInsteadOfOpeningAnotherSession() = runBlocking {
        begin(); val parent = claim()
        val child = checkNotNull(navigation.begin(parent.navigationOwner(), BookInfoChildKind.Catalog))
        val producer = checkNotNull(navigation.claimChild(child.id, book.bookUrl, child.kind, "catalog"))
        val selected = BookInfoNavigationPosition(5, 8, true)
        assertTrue(navigation.deliverCatalog(producer, selected))
        val current = checkNotNull(navigation.get(parent.id))
        assertTrue(navigation.close(current.navigationOwner()))
        val applied = checkNotNull(database.readerDetailDao.apply(sessionId, "reader", 20))
        assertEquals(5, applied.session.chapterIndex); assertEquals(8, applied.session.pageIndex)
        assertEquals(sessionId, applied.session.id)
    }
}
