package io.legado.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.ui.navigation.SearchRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SearchRepositoryTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val files = File(target.cacheDir, "search-test-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$id-$name", mode)
        override fun getNoBackupFilesDir() = files
    }
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val preferences = SearchPreferencesRepository(context)
    private val previews = BookInfoPreviewStore(context)
    private var fetch: suspend (BookSource, String, Int, Boolean) -> List<SearchBook> = { _, _, _, _ -> emptyList() }
    private val repository = SearchRepository(database, preferences, object : SearchSourceFetcher {
        override suspend fun search(source: BookSource, query: String, page: Int, precision: Boolean) = fetch(source, query, page, precision)
    }, previews)
    private val active = MutableStateFlow(true)

    @After fun close() { database.close(); files.deleteRecursively(); target.deleteSharedPreferences("$id-${context.packageName}_preferences") }
    private fun source(name: String, enabled: Boolean = true, group: String? = null) = BookSource(
        bookSourceUrl = "https://$name.invalid", bookSourceName = name, enabled = enabled, bookSourceGroup = group,
    ).also { database.bookSourceDao.insert(it) }
    private fun book(source: BookSource, name: String = "Query", url: String = "https://same.invalid/book") = SearchBook(
        bookUrl = url, name = name, author = "Author", origin = source.bookSourceUrl, originName = source.bookSourceName,
    )
    private suspend fun session() = repository.create("Query", SearchScopeSpec(""), false).session
    private suspend fun snapshot(id: String) = checkNotNull(repository.restore(id))

    @Test fun sameUrlVariantsRetainOriginAndPinnedPreviewIgnoresSharedCacheOverwrite() = runBlocking {
        val a = source("a"); val b = source("b")
        val bDone = CompletableDeferred<Unit>()
        fetch = { src, _, page, _ ->
            if (page > 1) emptyList() else {
                if (src.bookSourceUrl == a.bookSourceUrl) bDone.await()
                listOf(book(src).copy(intro = "From ${src.bookSourceName}")).also { if (src.bookSourceUrl == b.bookSourceUrl) bDone.complete(Unit) }
            }
        }
        // Sequential delivery controls commit order without delays or scheduler assumptions.
        val first = repository.create("Query", SearchScopeSpec.source(b.bookSourceName, b.bookSourceUrl), false).session
        repository.searchPage(first.id, false, active)
        val selected = snapshot(first.id).results.single()
        val request = repository.prepareSelection(first.id, selected.row.resultKey)
        database.searchBookDao.insert(book(a))
        val preview = checkNotNull(previews.peek(checkNotNull(request.previewKey)))
        assertEquals(b.bookSourceUrl, preview.sourceKey)
        assertEquals("From b", preview.book.intro)
        assertEquals(a.bookSourceUrl, database.searchBookDao.getSearchBook(preview.book.bookUrl)!!.origin)
        val multi = session()
        repository.searchPage(multi.id, false, active)
        val result = snapshot(multi.id).results.single()
        assertEquals(setOf(a.bookSourceUrl, b.bookSourceUrl), result.origins)
        assertEquals(2, result.variants.size)
    }

    @Test fun stopRejectsLateResultsWithoutWaitingForTheNetwork() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { _, _, _, _ -> entered.complete(Unit); release.await(); listOf(book(src)) }
        val initial = session()
        val job = launch { repository.searchPage(initial.id, false, active) }
        entered.await()
        withTimeout(2_000) { repository.stop(initial.id) }
        val stoppedGeneration = snapshot(initial.id).session.generation
        release.complete(Unit); job.join()
        val restored = snapshot(initial.id)
        assertEquals(SearchSessionStatus.Stopped, restored.session.status)
        assertEquals(stoppedGeneration, restored.session.generation)
        assertTrue(restored.results.isEmpty())
        assertEquals(0, restored.session.done)
    }

    @Test fun duplicatePageRequestsShareOnlyOnePageOwnerAndProgressIsExact() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        fetch = { _, _, _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await(); listOf(book(src)) }
        val initial = session()
        val first = launch { repository.searchPage(initial.id, false, active) }; entered.await()
        repository.searchPage(initial.id, false, active)
        release.complete(Unit); first.join()
        val frame = snapshot(initial.id)
        assertEquals(1, calls.get()); assertEquals(1, frame.session.done); assertEquals(1, frame.session.total)
        assertEquals(1, frame.session.completedPage); assertEquals(SearchSessionStatus.Ready, frame.session.status)
        assertEquals(1, frame.results.size)
    }

    @Test fun restoreInvalidatesRunningGenerationAndDoesNotReplayHttp() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        fetch = { _, _, _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await(); listOf(book(src)) }
        val initial = session(); val job = launch { repository.searchPage(initial.id, false, active) }; entered.await()
        val restored = snapshot(initial.id)
        assertEquals(SearchSessionStatus.Interrupted, restored.session.status)
        assertEquals(1, calls.get())
        release.complete(Unit); job.join()
        assertTrue(snapshot(initial.id).results.isEmpty())
        assertEquals(SearchSessionStatus.Interrupted, snapshot(initial.id).session.status)
    }

    @Test fun partialFailureRetriesSamePageAndKeepsSuccessfulResults() = runBlocking {
        val a = source("a"); val b = source("b"); val requests = mutableListOf<Int>()
        var fail = true
        fetch = { src, _, page, _ ->
            synchronized(requests) { requests += page }
            if (src.bookSourceUrl == b.bookSourceUrl && fail) error("offline")
            listOf(book(src, name = src.bookSourceName))
        }
        val initial = session(); repository.searchPage(initial.id, false, active)
        val partial = snapshot(initial.id)
        assertEquals(SearchSessionStatus.Partial, partial.session.status)
        assertEquals(2, partial.session.done); assertEquals(1, partial.session.failures)
        assertEquals("a", partial.results.single().row.name)
        fail = false; repository.searchPage(initial.id, true, active)
        val complete = snapshot(initial.id)
        assertEquals(SearchSessionStatus.Ready, complete.session.status)
        assertEquals(2, complete.results.size)
        assertTrue(requests.all { it == 1 })
        assertEquals(2, complete.session.done); assertEquals(0, complete.session.failures)
    }

    @Test fun editedSourceCannotCommitThePreviousCredentialResponse() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { _, _, _, _ -> entered.complete(Unit); release.await(); listOf(book(src)) }
        val initial = session(); val job = launch { repository.searchPage(initial.id, false, active) }; entered.await()
        database.bookSourceDao.update(src.copy(lastUpdateTime = src.lastUpdateTime + 1))
        release.complete(Unit); job.join()
        val frame = snapshot(initial.id)
        assertTrue(frame.results.isEmpty()); assertEquals(1, frame.session.failures)
        assertEquals(SearchSessionStatus.Error, frame.session.status)
    }

    @Test fun scopesUseEnabledGroupsButExplicitSingleSourceCanBeDisabled() = runBlocking {
        val a = source("a", group = "g"); val b = source("b", false, "g")
        assertEquals(listOf(a.bookSourceUrl), repository.resolveScope(SearchScopeSpec("g")).sources.map { it.bookSourceUrl })
        assertEquals(listOf(b.bookSourceUrl), repository.resolveScope(SearchScopeSpec.source("b", b.bookSourceUrl)).sources.map { it.bookSourceUrl })
        assertTrue(repository.resolveScope(SearchScopeSpec("missing")).scope.isAll)
    }

    @Test fun lateDraftWriteCannotRollBackTheNewerQueryOrDialogSelection() = runBlocking {
        val newer = draft(3).copy(query = "New", scopeDialogOpen = true, scopeSourceDraft = "b::https://b.invalid")
        repository.saveDraft(newer); repository.saveDraft(draft(2))
        assertEquals(newer, repository.draft(newer.id))
    }

    @Test fun peekDoesNotStealAnActivePreviewLease() {
        val key = UUID.randomUUID().toString(); val lease = previews.open(key)
        val first = BookInfoPreview(Book(bookUrl = "test://a", name = "First"), emptyList(), null, false)
        assertTrue(previews.save(lease, 1, first))
        assertEquals("First", previews.peek(key)!!.book.name)
        assertTrue(previews.save(lease, 2, first.copy(book = first.book.copy(name = "Second"))))
        assertEquals("Second", previews.peek(key)!!.book.name)
    }

    @Test fun cancelledPageIsPersistedAsInterruptedAndCanBeRetried() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>()
        fetch = { _, _, _, _ -> entered.complete(Unit); awaitCancellation() }
        val initial = session(); val job = launch { repository.searchPage(initial.id, false, active) }; entered.await()
        job.cancelAndJoin()
        assertEquals(SearchSessionStatus.Interrupted, database.searchSessionDao.get(initial.id)!!.status)
        fetch = { _, _, _, _ -> listOf(book(src)) }
        repository.searchPage(initial.id, true, active)
        assertEquals(SearchSessionStatus.Ready, snapshot(initial.id).session.status)
        assertEquals(1, snapshot(initial.id).session.completedPage)
    }

    @Test fun inactiveScreenDefersNewSourceRequestsUntilResumed() = runBlocking {
        val src = source("a"); val calls = AtomicInteger(); val initial = session()
        fetch = { _, _, _, _ -> calls.incrementAndGet(); listOf(book(src)) }
        active.value = false
        val job = launch { repository.searchPage(initial.id, false, active) }
        withTimeout(2_000) {
            while (database.searchSessionDao.get(initial.id)?.status != SearchSessionStatus.Running) delay(10)
        }
        assertEquals(0, calls.get())
        active.value = true; job.join()
        assertEquals(1, calls.get()); assertEquals(SearchSessionStatus.Ready, snapshot(initial.id).session.status)
    }

    @Test fun paginationRetainsSnapshotsAndEndsOnlyAfterAnEmptySourcePage() = runBlocking {
        val src = source("a"); val pages = arrayListOf<Int>()
        fetch = { _, _, page, _ -> pages += page; if (page == 1) listOf(book(src)) else emptyList() }
        val initial = session(); repository.searchPage(initial.id, false, active)
        assertTrue(snapshot(initial.id).session.hasMore)
        repository.searchPage(initial.id, false, active)
        val finished = snapshot(initial.id)
        assertEquals(listOf(1, 2), pages); assertEquals(2, finished.session.completedPage)
        assertFalse(finished.session.hasMore); assertEquals(1, finished.results.size)
    }

    @Test fun retryUpdatesVariantMetadataAndRetainsItsStableIdentity() = runBlocking {
        val src = source("a"); var intro = "Before"
        fetch = { _, _, _, _ -> listOf(book(src).copy(intro = intro)) }
        val initial = session(); repository.searchPage(initial.id, false, active)
        val previous = snapshot(initial.id).results.single().row
        intro = "After"; repository.searchPage(initial.id, true, active)
        val current = snapshot(initial.id).results.single().row
        assertEquals("After", current.intro); assertEquals(previous.resultKey, current.resultKey); assertEquals(previous.arrival, current.arrival)
    }

    @Test fun aNewViewModelRestoresPersistedResultsAndDialogDraftsWithoutReplayingHttp() = runBlocking {
        val src = source("a"); val calls = AtomicInteger(); val handle = SavedStateHandle(mapOf("searchDraftId" to id))
        fetch = { _, _, _, _ -> calls.incrementAndGet(); listOf(book(src)) }
        withModel(handle) { model ->
            waitFor { model.state.value.session?.status == SearchSessionStatus.Ready && model.state.value.results.size == 1 }
            main { model.showScope(); model.scopeMode(true); model.scopeQuery("narrow source"); model.scopeSource(SearchSourceOption(src.bookSourceUrl, src.bookSourceName, null, true)) }
            waitFor { repository.draft(id)?.scopeSourceQuery == "narrow source" }
        }
        val count = calls.get()
        withModel(SavedStateHandle(mapOf("searchDraftId" to id))) { model ->
            waitFor { model.state.value.results.size == 1 && model.state.value.draft?.scopeDialogOpen == true }
            val state = model.state.value
            assertEquals("narrow source", state.draft!!.scopeSourceQuery)
            assertEquals(src.bookSourceUrl, SearchScopeSpec(state.draft!!.scopeSourceDraft!!).sourceUrl)
            assertEquals("Query", state.draft!!.query)
            assertEquals(count, calls.get())
        }
        assertEquals(setOf("searchDraftId"), handle.keys())
    }

    @Test fun unconsumedDetailNavigationRestoresFromOnlyThePreviewKey() = runBlocking {
        val src = source("a"); val handle = SavedStateHandle(mapOf("searchDraftId" to id))
        fetch = { _, _, _, _ -> listOf(book(src)) }
        lateinit var pending: BookInfoOpenRequest
        withModel(handle) { model ->
            waitFor { model.state.value.results.size == 1 }
            main { model.openResult(model.state.value.results.single().row.resultKey) }
            waitFor { model.state.value.pendingOpen != null }
            pending = checkNotNull(model.state.value.pendingOpen)
        }
        val savedKey = checkNotNull(handle.get<String>("searchPendingPreviewKey"))
        assertEquals(pending.previewKey, savedKey)
        assertEquals(setOf("searchDraftId", "searchPendingPreviewKey"), handle.keys())
        withModel(SavedStateHandle(mapOf("searchDraftId" to id, "searchPendingPreviewKey" to savedKey))) { model ->
            waitFor { model.state.value.pendingOpen != null }
            assertEquals(pending, model.state.value.pendingOpen)
            main { model.consumeOpen(pending.id) }
            waitFor { model.state.value.pendingOpen == null }
        }
    }

    @Test fun changingPrecisionDuringSearchCancelsThePreviousSessionAndStartsOnlyTheNewMode() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        fetch = { _, _, _, precision ->
            calls.incrementAndGet()
            if (!precision) { entered.complete(Unit); awaitCancellation() }
            listOf(book(src))
        }
        withModel(SavedStateHandle(mapOf("searchDraftId" to id))) { model ->
            entered.await()
            waitFor { model.state.value.session?.status == SearchSessionStatus.Running }
            val old = checkNotNull(model.state.value.session).id
            main { model.setPrecision(true) }
            waitFor { model.state.value.session?.precision == true && model.state.value.session?.status == SearchSessionStatus.Ready && model.state.value.results.size == 1 }
            assertNotEquals(old, model.state.value.session!!.id)
            assertNotEquals(SearchSessionStatus.Running, database.searchSessionDao.get(old)!!.status)
            assertTrue(database.searchSessionDao.results(old).isEmpty())
            assertEquals(2, calls.get())
        }
    }

    @Test fun nonEmptyScopedSearchNeverShowsTheInitialEmptySessionPrompt() = runBlocking {
        val src = source("a", group = "selected")
        preferences.setScope(SearchScopeSpec("selected"))
        fetch = { _, _, _, _ -> listOf(book(src)) }
        withModel(SavedStateHandle(mapOf("searchDraftId" to id))) { model ->
            waitFor { model.state.value.results.size == 1 && model.state.value.session?.status == SearchSessionStatus.Ready }
            assertEquals("selected", model.state.value.session!!.scope)
            assertFalse(model.state.value.draft!!.emptyPromptOpen)
        }
    }

    @Test fun scopedEmptySearchPromptsOnlyAfterCompletionAndDismissalSurvivesRestore() = runBlocking {
        source("a", group = "selected"); preferences.setScope(SearchScopeSpec("selected"))
        withModel(SavedStateHandle(mapOf("searchDraftId" to id))) { model ->
            waitFor { model.state.value.draft?.emptyPromptOpen == true }
            assertEquals(1, model.state.value.session!!.completedPage)
            main { model.dismissEmptyPrompt() }
            waitFor { repository.draft(id)?.let { !it.emptyPromptOpen && it.emptyPromptSessionId != null } == true }
        }
        withModel(SavedStateHandle(mapOf("searchDraftId" to id))) { model ->
            waitFor { model.state.value.session != null }
            assertFalse(model.state.value.draft!!.emptyPromptOpen)
        }
    }

    @Test fun sessionCreatedBeforeProcessLossRestoresAsInterruptedWithoutStartingHttp() = runBlocking {
        source("a"); val calls = AtomicInteger()
        fetch = { _, _, _, _ -> calls.incrementAndGet(); emptyList() }
        val initial = session()
        val restored = snapshot(initial.id)
        assertEquals(SearchSessionStatus.Interrupted, restored.session.status)
        assertEquals(0, restored.session.requestedPage)
        assertEquals(0, calls.get())
    }

    @Test fun credentialEditWithTheSameTimestampRejectsThePreviousResponse() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { _, _, _, _ -> entered.complete(Unit); release.await(); listOf(book(src)) }
        val initial = session(); val job = launch { repository.searchPage(initial.id, false, active) }; entered.await()
        database.bookSourceDao.update(src.copy(header = "{\"Authorization\":\"New credential\"}"))
        release.complete(Unit); job.join()
        assertTrue(snapshot(initial.id).results.isEmpty())
        assertEquals(SearchSessionStatus.Error, snapshot(initial.id).session.status)
    }

    @Test fun identicalReplacementSourceCannotAcceptTheDeletedSourcesInFlightResponse() = runBlocking {
        val src = source("a"); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { _, _, _, _ -> entered.complete(Unit); release.await(); listOf(book(src)) }
        val initial = session(); val job = launch { repository.searchPage(initial.id, false, active) }; entered.await()
        database.bookSourceDao.delete(src); database.bookSourceDao.insert(src)
        release.complete(Unit); job.join()
        assertTrue(snapshot(initial.id).results.isEmpty())
        assertEquals(SearchSessionStatus.Error, snapshot(initial.id).session.status)
    }

    @Test fun finalRankingUsesExactKindContainsAndDistinctOriginCountsWithStableArrival() = runBlocking {
        val a = source("a"); val b = source("b")
        fetch = { src, _, _, _ -> if (src.bookSourceUrl == a.bookSourceUrl) listOf(
            book(src, "Unrelated", "https://a.invalid/unrelated"),
            book(src, "Query suffix", "https://a.invalid/contains"),
            book(src, "Kind match", "https://a.invalid/kind").copy(kind = "Query"),
            book(src, "Exact author", "https://a.invalid/exact").copy(author = "Query"),
            book(src, "Query", "https://a.invalid/query"),
        ) else listOf(book(src, "Query", "https://b.invalid/query")) }
        val initial = session(); repository.searchPage(initial.id, false, active)
        val result = snapshot(initial.id).results
        assertEquals(listOf("Query", "Exact author", "Kind match", "Query suffix", "Unrelated"), result.map { it.row.name })
        assertEquals(2, result.first().origins.size)
        assertEquals(6, database.searchSessionDao.results(initial.id).size)
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun withModel(handle: SavedStateHandle, block: suspend (SearchViewModel) -> Unit) {
        val owner = ViewModelStore(); lateinit var model: SearchViewModel
        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        main {
            model = SearchViewModel(handle, repository, preferences); owner.put("search", model)
            collector.launch { model.state.collect {} }
            model.setActive(true); model.initialize(SearchRoute("Query"))
        }
        try { block(model) } finally { main { owner.clear(); collector.cancel() } }
    }

    private fun draft(revision: Long) = SearchScreenDraft(id, revision, null, "Old", "", true, false, false, false, "", "", null,
        false, "", false, false, null, System.currentTimeMillis())
}
