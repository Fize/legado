package io.legado.app.ui.book.info

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.help.globalExecutor
import io.legado.app.ui.navigation.BookInfoRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookInfoEntryStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val previews = BookInfoPreviewStore(instrumentation.targetContext)
    private lateinit var database: AppDatabase
    private lateinit var repository: BookInfoNavigationRepository
    private val stores = mutableListOf<ViewModelStore>()
    private val route = BookInfoRoute("entry-book")
    private val book = Book(bookUrl = route.bookUrl, name = "Entry book", type = BookType.text)
    private val dao get() = database.bookInfoRequestDao
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java)
            .addLegadoMigrations().build()
        database.bookDao.insert(book)
        repository = BookInfoNavigationRepository(database)
    }
    @After fun cleanup() {
        main { stores.forEach(ViewModelStore::clear) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        previews.discard(previews.open(route.previewKey))
        database.close()
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!check() && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
        assertTrue("Entry condition was not reached", check())
    }
    private fun create(): BookInfoEntryViewModel {
        lateinit var model: BookInfoEntryViewModel
        main {
            val owner = ViewModelStore().also(stores::add)
            model = BookInfoEntryViewModel(repository, previews)
            owner.put("entry", model)
            model.initialize(route)
        }
        await { !model.state.value.loading && model.state.value.request != null }
        return model
    }
    private fun begin(model: BookInfoEntryViewModel, kind: BookInfoChildKind): BookInfoChildRequest {
        main { model.setResumed(true); model.begin(kind) }
        await { model.state.value.navigation?.kind == kind }
        return checkNotNull(model.state.value.navigation)
    }
    private fun background(model: BookInfoEntryViewModel, child: BookInfoChildRequest) {
        main { model.navigationHandled(child.id); model.setResumed(false) }
    }

    @Test fun bootstrapBindsTheStableRouteAndPreviewLeaseToOneOwner() {
        val model = create()
        assertEquals(route.requestId, model.state.value.request!!.id)
        assertEquals(route.previewKey, model.state.value.bootstrap!!.lease.key)
        assertEquals(route.previewKey, model.state.value.request!!.previewKey)
        assertEquals(route.bookUrl, model.state.value.bootstrap!!.bookUrl)
        assertEquals(model.state.value.request, dao.get(route.requestId))
    }

    @Test fun aRevokedPreviewCannotClaimTheDatabaseAfterANewerEntry() = runBlocking {
        val old = previews.open(route.previewKey)
        val newer = previews.open(route.previewKey)
        val request = checkNotNull(repository.claimPreview(route.requestId, route.bookUrl, newer, previews, "new"))
        assertNull(repository.claimPreview(route.requestId, route.bookUrl, old, previews, "old"))
        assertEquals(request, dao.get(route.requestId))
    }

    @Test fun anOldPreviewCannotRunItsClaimCallback() {
        val old = previews.open(route.previewKey)
        previews.open(route.previewKey)
        var called = false
        assertNull(previews.withCurrentLease(old) { called = true; true })
        assertFalse(called)
    }

    @Test fun theSameRequestCannotRebindItsPreviewKey() = runBlocking {
        val model = create()
        val otherKey = UUID.randomUUID().toString()
        val other = previews.open(otherKey)
        try {
            try {
                repository.claimPreview(route.requestId, route.bookUrl, other, previews, "other")
                fail("A request accepted another preview identity")
            } catch (_: IllegalArgumentException) { }
            assertEquals(model.state.value.request, dao.get(route.requestId))
            assertEquals(true, previews.withCurrentLease(model.state.value.bootstrap!!.lease) { true })
        } finally { previews.discard(other) }
    }

    @Test fun duplicateBeginCreatesOneChildAndOneNavigation() {
        val model = create()
        main { model.setResumed(true); repeat(3) { model.begin(BookInfoChildKind.Catalog) } }
        await { model.state.value.navigation != null }
        val child = checkNotNull(model.state.value.navigation)
        assertEquals(1L, dao.get(route.requestId)!!.revision)
        main { model.navigationHandled(child.id); model.begin(BookInfoChildKind.Edit) }
        assertNull(model.state.value.navigation)
        assertEquals(child.id, dao.activeChild(route.requestId)!!.id)
    }

    @Test fun aBackgroundCatalogResultWaitsUntilResumeThenQueuesItsReaderOnce() {
        val model = create()
        val catalog = begin(model, BookInfoChildKind.Catalog)
        background(model, catalog)
        dao.claimChild(catalog.id, book.bookUrl, catalog.kind, "catalog", 10)
        val position = BookInfoNavigationPosition(2, 3, true, highlightAnchorText = "anchor")
        assertTrue(dao.deliverCatalog(catalog.id, "catalog", book.bookUrl, position, 11))
        await { model.state.value.child?.phase == BookInfoChildPhase.Returned }
        assertEquals(1L, dao.get(route.requestId)!!.revision)
        main { model.setResumed(true) }
        await { model.state.value.navigation?.kind == BookInfoChildKind.Reader }
        val reader = checkNotNull(model.state.value.navigation)
        assertEquals(position, reader.position)
        assertEquals(2L, dao.get(route.requestId)!!.revision)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(catalog.id)!!.phase)
        main { model.navigationHandled(reader.id) }
        assertNull(model.state.value.navigation)
    }

    @Test fun aRestoredDisplayedChildDoesNotAutomaticallyReopen() {
        val first = create()
        val child = begin(first, BookInfoChildKind.Edit)
        background(first, child)
        dao.claimChild(child.id, book.bookUrl, child.kind, "editor", 10)
        val restored = create()
        main { restored.setResumed(true) }
        await { restored.state.value.child?.phase == BookInfoChildPhase.Displayed }
        assertNull(restored.state.value.navigation)
        assertEquals(child.id, dao.activeChild(route.requestId)!!.id)
    }

    @Test fun returningBeforeAChildClaimsItsRequestCancelsInsteadOfReopening() {
        val model = create()
        val child = begin(model, BookInfoChildKind.Catalog)
        background(model, child)
        main { model.setResumed(true) }
        await { model.state.value.refreshRevision == 1L }
        assertNull(model.state.value.child)
        assertNull(model.state.value.navigation)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
    }

    @Test fun anEditReturnRefreshesOnceAndIsNotReplayedOnTheNextResume() {
        val model = create()
        val child = begin(model, BookInfoChildKind.Edit)
        background(model, child)
        dao.claimChild(child.id, book.bookUrl, child.kind, "editor", 10)
        dao.deliverEdit(child.id, "editor", book.bookUrl, 11)
        await { model.state.value.child?.phase == BookInfoChildPhase.Returned }
        main { model.setResumed(true) }
        await { model.state.value.refresh?.childId == child.id }
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
        main { model.refreshApplied(child.id, book.bookUrl) }
        await { model.state.value.refreshRevision == 1L }
        main { model.setResumed(false); model.setResumed(true) }
        assertEquals(1L, model.state.value.refreshRevision)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
    }

    @Test fun aReaderReturnAdoptsItsCanonicalKeyBeforeAFreshEntryBootstraps() = runBlocking {
        val model = create()
        val child = begin(model, BookInfoChildKind.Reader)
        background(model, child)
        val readers = ReaderSessionRepository(dagger.Lazy { database })
        readers.claim(ReaderOpenRequest(child.id, book.bookUrl, returnToDetails = true), "reader")
        val changed = book.copy(bookUrl = "changed-source", name = "Changed book")
        database.readerSessionDao.replaceOwnedBook(child.id, "reader", 0, book.bookUrl,
            changed.bookUrl, 2, 3, 12) { database.bookDao.insert(changed) }
        readers.close(ReaderProgressOwner(child.id, "reader", 1))
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        await { model.state.value.child?.phase == BookInfoChildPhase.Returned }
        main { model.setResumed(true) }
        await { model.state.value.refresh?.bookUrl == changed.bookUrl }
        main { model.refreshApplied(child.id, changed.bookUrl) }
        await { model.state.value.refreshRevision == 1L }
        assertEquals(changed.bookUrl, model.state.value.request!!.bookUrl)
        assertEquals(changed.bookUrl, create().state.value.bootstrap!!.bookUrl)
    }

    @Test fun aPendingEditRefreshSurvivesAnotherViewModelBeforeAcknowledgement() {
        val first = create()
        val child = begin(first, BookInfoChildKind.Edit)
        background(first, child)
        dao.claimChild(child.id, book.bookUrl, child.kind, "editor", 10)
        dao.deliverEdit(child.id, "editor", book.bookUrl, 11)
        await { first.state.value.child?.phase == BookInfoChildPhase.Returned }
        main { first.setResumed(true) }
        await { first.state.value.refresh != null }
        val restored = create()
        main { restored.setResumed(true) }
        await { restored.state.value.refresh?.childId == child.id }
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
        assertEquals(1L, dao.get(route.requestId)!!.revision)
        main { restored.refreshApplied(child.id, "wrong-book") }
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
        main { restored.refreshApplied(child.id, book.bookUrl) }
        await { restored.state.value.refreshRevision == 1L }
        main { first.refreshApplied(child.id, book.bookUrl) }
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
        assertEquals(2L, dao.get(route.requestId)!!.revision)
    }

    @Test fun anUnacknowledgedReaderReturnBootstrapsItsCanonicalBookInTheNextOwner() = runBlocking {
        val first = create()
        val child = begin(first, BookInfoChildKind.Reader)
        background(first, child)
        val readers = ReaderSessionRepository(dagger.Lazy { database })
        readers.claim(ReaderOpenRequest(child.id, book.bookUrl, returnToDetails = true), "reader")
        val changed = book.copy(bookUrl = "pending-canonical-book", name = "Pending canonical book")
        database.readerSessionDao.replaceOwnedBook(child.id, "reader", 0, book.bookUrl,
            changed.bookUrl, 2, 3, 12) { database.bookDao.delete(book); database.bookDao.insert(changed) }
        readers.close(ReaderProgressOwner(child.id, "reader", 1))
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        await { first.state.value.child?.phase == BookInfoChildPhase.Returned }
        main { first.setResumed(true) }
        await { first.state.value.refresh?.bookUrl == changed.bookUrl }
        val restored = create()
        assertEquals(changed.bookUrl, restored.state.value.bootstrap!!.bookUrl)
        assertEquals(book.bookUrl, dao.get(route.requestId)!!.bookUrl)
        main { restored.setResumed(true) }
        await { restored.state.value.refresh?.bookUrl == changed.bookUrl }
        main { restored.refreshApplied(child.id, changed.bookUrl) }
        await { restored.state.value.refreshRevision == 1L }
        assertEquals(changed.bookUrl, dao.get(route.requestId)!!.bookUrl)
    }

    @Test fun clearingAnOldEntryCannotDeleteTheNewOwnersRequestOrPreview() {
        val old = create()
        val newer = create()
        await { old.state.value.closed }
        val newLease = newer.state.value.bootstrap!!.lease
        assertTrue(previews.save(newLease, 1, BookInfoPreview(book, emptyList(), null, true)))
        main { stores.first().clear() }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(newer.state.value.request, dao.get(route.requestId))
        assertNotNull(previews.peek(route.previewKey))
    }

    @Test fun clearingTheOwningEntryDisposesItsRequestAndPreview() {
        val model = create()
        assertTrue(previews.save(model.state.value.bootstrap!!.lease, 1, BookInfoPreview(book, emptyList(), null, true)))
        main { stores.single().clear() }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertNull(dao.get(route.requestId))
        assertNull(previews.peek(route.previewKey))
    }

    @Test fun aDeletedReaderResultClosesTheEntryAfterConsumption() = runBlocking {
        val model = create()
        val child = begin(model, BookInfoChildKind.Reader)
        background(model, child)
        val readers = ReaderSessionRepository(dagger.Lazy { database })
        readers.claim(ReaderOpenRequest(child.id, book.bookUrl, returnToDetails = true), "reader")
        database.bookDao.delete(book)
        readers.close(ReaderProgressOwner(child.id, "reader", 0))
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        await { model.state.value.child?.phase == BookInfoChildPhase.Returned }
        main { model.setResumed(true) }
        await { model.state.value.closed }
        assertTrue(dao.get(route.requestId)!!.deleted)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
        assertNull(model.state.value.navigation)
    }

    @Test fun aFailedBeginCanRetryAfterTheBookHasBeenSaved() {
        database.bookDao.delete(book)
        val model = create()
        main { model.setResumed(true); model.begin(BookInfoChildKind.Catalog) }
        await { model.state.value.failed && !model.state.value.busy }
        assertNull(dao.activeChild(route.requestId))
        database.bookDao.insert(book)
        main { model.retry() }
        await { model.state.value.navigation?.kind == BookInfoChildKind.Catalog }
        assertFalse(model.state.value.failed)
        assertEquals(1L, dao.get(route.requestId)!!.revision)
    }

    @Test fun aMissingReaderEntryPublishesItsDurableCanonicalKeyAfterQueuedProgress() = runBlocking {
        val model = create()
        val child = begin(model, BookInfoChildKind.Reader)
        background(model, child)
        val readers = ReaderSessionRepository(dagger.Lazy { database })
        readers.claim(ReaderOpenRequest(child.id, book.bookUrl, returnToDetails = true), "reader")
        val changed = book.copy(bookUrl = "orphan-reader-book", name = "Recovered reader book")
        database.readerSessionDao.replaceOwnedBook(child.id, "reader", 0, book.bookUrl,
            changed.bookUrl, 2, 3, 12) { database.bookDao.delete(book); database.bookDao.insert(changed) }
        globalExecutor.execute { database.readerSessionDao.saveProgress(child.id, "reader", 1,
            changed.bookUrl, changed.origin, 4, 7, "Last visible chapter", 30) }
        main { model.setChildEntries(emptySet()); model.setResumed(true) }
        await { model.state.value.refresh?.bookUrl == changed.bookUrl }
        assertNull(database.readerSessionDao.get(child.id))
        assertEquals(4, database.bookDao.getBook(changed.bookUrl)!!.durChapterIndex)
        assertEquals(7, database.bookDao.getBook(changed.bookUrl)!!.durChapterPos)
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
        main { model.refreshApplied(child.id, changed.bookUrl) }
        await { model.state.value.refreshRevision == 1L }
        assertEquals(changed.bookUrl, dao.get(route.requestId)!!.bookUrl)
    }

    @Test fun aReaderBookRemovedWhileItsRefreshIsPendingClosesTheDetail() = runBlocking {
        val model = create()
        val child = begin(model, BookInfoChildKind.Reader)
        background(model, child)
        val readers = ReaderSessionRepository(dagger.Lazy { database })
        readers.claim(ReaderOpenRequest(child.id, book.bookUrl, returnToDetails = true), "reader")
        readers.closeAndAwait(ReaderProgressOwner(child.id, "reader", 0))
        await { model.state.value.child?.phase == BookInfoChildPhase.Returned }
        main { model.setResumed(true) }
        await { model.state.value.refresh != null }
        database.bookDao.delete(book)
        main { model.refreshFailed(child.id) }
        await { model.state.value.closed }
        assertTrue(dao.get(route.requestId)!!.deleted)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(child.id)!!.phase)
    }
}
