package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.BookInfoNavigationRepository
import io.legado.app.data.repository.navigationOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookInfoRequestStoreTest {
    private lateinit var database: AppDatabase
    private val dao get() = database.bookInfoRequestDao
    private val book = Book(bookUrl = "detail-book", origin = "source", name = "Detail", type = BookType.text or BookType.notShelf)
    private val replacement = book.copy(bookUrl = "replacement-book", origin = "replacement-source", name = "Replacement")
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).addLegadoMigrations().build()
        database.bookDao.insert(book, replacement)
        assertNotNull(database.bookDao.getBook(book.bookUrl))
        assertNotNull(database.bookDao.getBook(replacement.bookUrl))
    }
    @After fun close() { database.close() }
    private fun request(id: String = "detail", token: String = "owner") =
        dao.claim(BookInfoRequest(id, book.bookUrl, book.bookUrl, token, updatedAt = 1), token)
    private fun begin(kind: BookInfoChildKind = BookInfoChildKind.Catalog, id: String = "detail", child: String = "child"): BookInfoChildRequest {
        val parent = checkNotNull(dao.get(id))
        return checkNotNull(dao.begin(id, parent.ownerToken, parent.revision, parent.bookUrl, child, kind, null, 2))
    }
    private fun claim(child: BookInfoChildRequest, token: String = "producer"): BookInfoChildRequest {
        if (child.kind == BookInfoChildKind.Reader) database.readerSessionDao.claim(
            ReaderSessionState(child.id, child.bookUrl, child.bookUrl, false, false, updatedAt = 3), token)
        return checkNotNull(dao.claimChild(child.id, child.bookUrl, child.kind, token, 3))
    }
    private fun consume(id: String = "detail", child: String = "child"): BookInfoChildRequest? {
        val parent = checkNotNull(dao.get(id))
        return dao.consume(id, parent.ownerToken, parent.revision, parent.bookUrl, child, 10)
    }

    @Test fun previewIdentityDoesNotRequireALibraryBook() {
        database.bookDao.delete(book)
        val owner = request()
        assertEquals(book.bookUrl, owner.bookUrl)
        assertNull(dao.begin(owner.id, owner.ownerToken, owner.revision, owner.bookUrl, "child", BookInfoChildKind.Catalog, null, 2))
        assertEquals(0L, dao.get(owner.id)!!.revision)
    }

    @Test fun detailClaimDoesNotTakeTheReadersProgressOwnership() {
        database.readerSessionDao.claim(ReaderSessionState("reader", book.bookUrl, book.bookUrl, false, false, updatedAt = 1), "reader-owner")
        request(); begin(); claim(dao.child("child")!!)
        assertTrue(database.readerSessionDao.ownsBook("reader", "reader-owner", 0, book.bookUrl))
    }

    @Test fun anotherBookCannotReuseTheSameRequestIdentity() {
        request()
        assertThrows(IllegalArgumentException::class.java) {
            dao.claim(BookInfoRequest("detail", replacement.bookUrl, replacement.bookUrl, "other", updatedAt = 2), "other")
        }
        assertEquals(book.bookUrl, dao.get("detail")!!.bookUrl)
        assertEquals("owner", dao.get("detail")!!.ownerToken)
    }

    @Test fun aNewEntryOwnerRejectsTheOldOwnersLateNavigationAndDisposal() {
        val old = request(); request(token = "new")
        assertNull(dao.begin(old.id, old.ownerToken, 0, old.bookUrl, "old-child", BookInfoChildKind.Catalog, null, 100))
        assertEquals(0, dao.close(old.id, old.ownerToken))
        assertNotNull(dao.get(old.id))
    }

    @Test fun duplicateClicksUsingTheSameRevisionCannotCreateTwoActiveChildren() {
        val parent = request(); val child = begin()
        assertNull(dao.begin(parent.id, parent.ownerToken, parent.revision, parent.bookUrl, "duplicate", BookInfoChildKind.Catalog, null, 3))
        assertEquals(child, dao.activeChild(parent.id))
        assertNull(dao.child("duplicate"))
    }

    @Test fun wrongBookAndKindCannotClaimAChild() {
        request(); begin()
        assertNull(dao.claimChild("child", replacement.bookUrl, BookInfoChildKind.Catalog, "bad", 3))
        assertNull(dao.claimChild("child", book.bookUrl, BookInfoChildKind.Reader, "bad", 3))
        assertEquals(BookInfoChildPhase.Requested, dao.child("child")!!.phase)
    }

    @Test fun anUnclaimedOrOldProducerCannotPublishOrCancel() {
        request(); val child = begin()
        assertFalse(dao.deliverCatalog(child.id, "", book.bookUrl, BookInfoNavigationPosition(1), 3))
        assertFalse(dao.cancelChild(child.id, "", 3))
        claim(child, "old"); claim(child, "new")
        assertFalse(dao.deliverCatalog(child.id, "old", book.bookUrl, BookInfoNavigationPosition(1), 100))
        assertFalse(dao.cancelChild(child.id, "old", 100))
        assertTrue(dao.deliverCatalog(child.id, "new", book.bookUrl, BookInfoNavigationPosition(2), 4))
    }

    @Test fun catalogResultSurvivesEntryOwnerRecreationAndIsPublishedOnlyOnce() {
        request(); val child = claim(begin())
        request(token = "new-owner")
        val position = BookInfoNavigationPosition(4, 17, true, 2, 3, 8, "selected anchor", 42)
        assertTrue(dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, position, 4))
        assertFalse(dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, BookInfoNavigationPosition(0), 5))
        assertEquals(position, dao.activeChild("detail")!!.position)
    }

    @Test fun catalogSelectionAndItsReaderRequestCommitTogether() {
        request(); val child = claim(begin())
        val position = BookInfoNavigationPosition(4, 17, true, 2, 3, 8, "anchor", 42)
        dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, position, 4)
        val parent = dao.get("detail")!!
        val reader = checkNotNull(dao.continueCatalog(parent.id, parent.ownerToken, parent.revision, parent.bookUrl, child.id, "reader", 5))
        assertEquals(position, reader.position)
        assertEquals(BookInfoChildKind.Reader, reader.kind)
        assertEquals(BookInfoChildPhase.Requested, reader.phase)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
        assertEquals(reader, dao.activeChild(parent.id))
        assertEquals(2L, dao.get(parent.id)!!.revision)
        assertNull(dao.continueCatalog(parent.id, parent.ownerToken, parent.revision, parent.bookUrl, child.id, "another", 6))
    }

    @Test fun failedReaderInsertionDoesNotConsumeTheCatalogResult() {
        request(); val child = claim(begin())
        dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, BookInfoNavigationPosition(1), 4)
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            dao.continueCatalog("detail", "owner", 1, book.bookUrl, child.id, child.id, 5)
        }
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
        assertEquals(1L, dao.get("detail")!!.revision)
    }

    @Test fun ordinaryConsumptionCannotDropAnUnprocessedCatalogSelection() {
        request(); val child = claim(begin())
        dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, BookInfoNavigationPosition(1), 4)
        assertThrows(IllegalArgumentException::class.java) { consume() }
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
    }

    @Test fun invalidCatalogPositionsNeverPublish() {
        request(); val child = claim(begin())
        for (position in listOf(BookInfoNavigationPosition(-1), BookInfoNavigationPosition(1, -1), BookInfoNavigationPosition(1, pdfPageIndex = -1))) {
            assertFalse(dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, position, 4))
        }
        assertEquals(BookInfoChildPhase.Displayed, dao.child(child.id)!!.phase)
    }

    @Test fun parentCancellationIsLimitedToItsOwnUndisplayedChild() {
        request(); val child = begin()
        assertFalse(dao.cancelRequested("detail", "bad", 1, book.bookUrl, child.id, 4))
        claim(child)
        assertFalse(dao.cancelRequested("detail", "owner", 1, book.bookUrl, child.id, 4))
        assertTrue(dao.cancelChild(child.id, "producer", 4))
        assertEquals(BookInfoChildPhase.Cancelled, consume()!!.phase)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
        assertNull(consume())
    }

    @Test fun pendingNavigationCanBeCancelledWithoutOpeningTheChild() {
        request(); begin()
        assertTrue(dao.cancelRequested("detail", "owner", 1, book.bookUrl, "child", 4))
        assertNotNull(consume())
        assertNull(dao.activeChild("detail"))
    }

    @Test fun editResultRequiresItsExactProducerAndIsConsumedOnce() {
        request(); val child = claim(begin(BookInfoChildKind.Edit))
        assertFalse(dao.deliverEdit(child.id, "bad", book.bookUrl, 4))
        assertFalse(dao.deliverEdit(child.id, child.producerToken, replacement.bookUrl, 4))
        assertTrue(dao.deliverEdit(child.id, child.producerToken, book.bookUrl, 4))
        assertEquals(BookInfoChildKind.Edit, consume()!!.kind)
        assertNull(consume())
        assertFalse(dao.deliverEdit(child.id, child.producerToken, book.bookUrl, 5))
    }

    @Test fun aReadersSourceChangeSurvivesRemovalOfTheInitialBook() {
        request(); val child = claim(begin(BookInfoChildKind.Reader))
        database.readerSessionDao.replaceOwnedBook(child.id, child.producerToken, 0, book.bookUrl,
            replacement.bookUrl, 2, 3, 4) { database.bookDao.delete(book) }
        assertNotNull(dao.child(child.id))
        assertTrue(dao.deliverReader(child.id, child.producerToken, book.bookUrl, replacement.bookUrl, 5))
        assertEquals(replacement.bookUrl, consume()!!.returnBookUrl)
        assertEquals(replacement.bookUrl, dao.get("detail")!!.bookUrl)
        val restored = request(token = "recreated")
        assertEquals(replacement.bookUrl, restored.bookUrl)
        assertFalse(restored.deleted)
    }

    @Test fun aReaderDeletionRemainsTerminalAfterRecreation() {
        request(); val child = claim(begin(BookInfoChildKind.Reader))
        database.bookDao.delete(book)
        assertTrue(dao.deliverReader(child.id, child.producerToken, book.bookUrl, book.bookUrl, 5))
        assertTrue(consume()!!.readerDeleted)
        assertTrue(request(token = "restored").deleted)
        assertNull(dao.begin("detail", "restored", 2, book.bookUrl, "another", BookInfoChildKind.Reader, null, 10))
    }

    @Test fun readerMembershipUsesTheCurrentPersistedBook() {
        request(); val child = claim(begin(BookInfoChildKind.Reader))
        database.bookDao.update(book.copy(type = BookType.text))
        assertTrue(dao.deliverReader(child.id, child.producerToken, book.bookUrl, book.bookUrl, 5))
        assertTrue(consume()!!.readerInBookshelf)
    }

    @Test fun aChildProducerCannotInventTheReadersCanonicalBookKey() {
        request(); val child = claim(begin(BookInfoChildKind.Reader))
        assertFalse(dao.deliverReader(child.id, child.producerToken, book.bookUrl, replacement.bookUrl, 5))
        assertNotNull(database.readerSessionDao.moveBook(child.id, child.producerToken, replacement.bookUrl, 1, 0, 6))
        assertTrue(dao.deliverReader(child.id, child.producerToken, book.bookUrl, replacement.bookUrl, 7))
    }

    @Test fun aReaderEpochChangeRevokesReturnPublicationBeforeTheChildReclaims() {
        request(); val child = claim(begin(BookInfoChildKind.Reader), "old")
        database.readerSessionDao.claim(ReaderSessionState(child.id, book.bookUrl, book.bookUrl, false, false, updatedAt = 4), "new")
        assertFalse(dao.deliverReader(child.id, "old", book.bookUrl, book.bookUrl, 5))
        dao.claimChild(child.id, book.bookUrl, child.kind, "new", 6)
        assertTrue(dao.deliverReader(child.id, "new", book.bookUrl, book.bookUrl, 7))
    }

    @Test fun childOwnershipAloneCannotPublishWithoutAReaderSession() {
        request(); val child = begin(BookInfoChildKind.Reader)
        dao.claimChild(child.id, book.bookUrl, child.kind, "producer", 3)
        assertFalse(dao.deliverReader(child.id, "producer", book.bookUrl, book.bookUrl, 4))
    }

    @Test fun bookDeletionBetweenReaderDeliveryAndConsumptionClosesTheDetail() {
        request(); val child = claim(begin(BookInfoChildKind.Reader))
        assertTrue(dao.deliverReader(child.id, child.producerToken, book.bookUrl, book.bookUrl, 5))
        database.bookDao.delete(book)
        assertNotNull(consume())
        assertTrue(dao.get("detail")!!.deleted)
    }

    @Test fun sourceReplacementInvalidatesOlderChildCallbacks() = runBlocking {
        request(); val child = claim(begin())
        val updated = dao.replaceBook("detail", "owner", 1, book.bookUrl, replacement.bookUrl, 5) { database.bookDao.delete(book) }
        assertEquals(replacement.bookUrl, updated.bookUrl)
        assertFalse(dao.deliverCatalog(child.id, child.producerToken, book.bookUrl, BookInfoNavigationPosition(1), 6))
        assertNull(dao.observeCurrentChild(child.id).first())
        assertNull(dao.activeChild("detail"))
    }

    @Test fun aStaleReplacementCannotRunItsBookMutation() {
        request(); request(token = "new")
        var executed = false
        assertThrows(IllegalStateException::class.java) {
            dao.replaceBook("detail", "owner", 0, book.bookUrl, replacement.bookUrl, 5) { executed = true }
        }
        assertFalse(executed)
    }

    @Test fun failedSourceReplacementRollsBackBookAndRequestChanges() {
        request()
        assertThrows(IllegalStateException::class.java) {
            dao.replaceBook("detail", "owner", 0, book.bookUrl, "missing", 5) { database.bookDao.delete(book) }
        }
        assertNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(book.bookUrl, dao.get("detail")!!.bookUrl)
        assertEquals(0L, dao.get("detail")!!.revision)
    }

    @Test fun closingAnOwnerCascadesOnlyItsOwnChildrenAndProtectsOtherPreviews() {
        request(); request("other"); begin()
        assertTrue(dao.anotherRequestUsesBook(book.bookUrl, "detail"))
        assertEquals(1, dao.close("detail", "owner"))
        assertNull(dao.child("child"))
        assertNotNull(dao.get("other"))
        assertFalse(dao.anotherRequestUsesBook(book.bookUrl, "other"))
    }

    @Test fun repositoryReturnsTheCommittedParentOwnerAlongsideItsResult() = runBlocking {
        val repository = BookInfoNavigationRepository(database)
        val parent = repository.claim("detail", book.bookUrl, book.bookUrl, "owner")
        val child = checkNotNull(repository.begin(parent.navigationOwner(), BookInfoChildKind.Edit))
        val producer = checkNotNull(repository.claimChild(child.id, book.bookUrl, child.kind))
        assertTrue(repository.deliverEdit(producer))
        val current = checkNotNull(repository.get(parent.id))
        val accepted = checkNotNull(repository.consume(current.navigationOwner(), child.id))
        assertEquals(current.revision + 1, accepted.request.revision)
        assertEquals(child.id, accepted.result.id)
        assertTrue(repository.close(accepted.request.navigationOwner()))
    }
}
