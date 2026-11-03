package io.legado.app.data.repository

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.ui.book.info.BookInfoOpenViewModel
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoOpenRepositoryTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val files = File(target.cacheDir, "detail-open-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = files }
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val previews = BookInfoPreviewStore(context)
    private val repository = BookInfoOpenRepository(database, previews)
    private val owners = mutableListOf<ViewModelStore>()
    private fun book(url: String = "https://selected-$id.invalid/book", origin: String = "source-a") =
        Book(bookUrl = url, name = "Selected $id", author = "Author $id", origin = origin, originName = origin)
    private fun key() = UUID.randomUUID().toString()
    @Before fun setup() {
        database.bookSourceDao.insert(BookSource("source-a", "Selected source"), BookSource("wrong-source", "Overwritten source"))
    }
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.forEach { it.clear() } }
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        database.close(); files.deleteRecursively()
    }

    @Test fun clickedResultDoesNotReadAnOverwrittenSharedSearchCacheOrInsertALibraryRow() = runBlocking {
        val selected = book()
        database.searchBookDao.insert(selected.toSearchBook().copy(origin = "wrong-source"))
        val request = repository.selected(repository.snapshot(selected), key())
        val restored = previews.peek(request.previewKey)!!
        assertEquals("source-a", restored.book.origin)
        assertEquals(request.id, request.previewKey)
        assertFalse(restored.inBookshelf)
        assertFalse(database.bookDao.has(selected.bookUrl))
    }

    @Test fun shelfSelectionUsesExactUrlWhenTwoBooksHaveTheSameName() = runBlocking {
        val first = book(); val second = book(first.bookUrl + "/other", "source-b").copy(author = "Other author $id")
        database.bookDao.insert(first, second)
        assertTrue(database.bookDao.has(first.bookUrl))
        assertTrue(database.bookDao.has(second.bookUrl))
        val request = repository.shelf(second.bookUrl, key())
        assertEquals(second.bookUrl, request.bookUrl)
        assertEquals("source-b", previews.peek(request.previewKey)!!.book.origin)
    }

    @Test fun selectedResultAdoptsTheExistingShelfCanonicalBookAndChapters() = runBlocking {
        val shelf = book(); database.bookDao.insert(shelf)
        database.bookChapterDao.insert(BookChapter(bookUrl = shelf.bookUrl, url = "chapter", title = "Saved chapter"))
        val candidate = book(shelf.bookUrl + "/candidate", "candidate-source")
        val request = repository.selected(repository.snapshot(candidate), key())
        val restored = previews.peek(request.previewKey)!!
        assertEquals(shelf.bookUrl, request.bookUrl)
        assertTrue(restored.inBookshelf)
        assertEquals("Saved chapter", restored.chapters.single().title)
    }

    @Test fun temporaryRowsDoNotOverrideTheSelectedUnshelvedSource() = runBlocking {
        val selected = book()
        database.bookDao.insert(selected.copy(origin = "temporary", type = BookType.notShelf))
        val request = repository.selected(repository.snapshot(selected), key())
        assertEquals("source-a", previews.peek(request.previewKey)!!.book.origin)
        assertFalse(previews.peek(request.previewKey)!!.inBookshelf)
    }

    @Test fun detachedSelectionPreservesParserInputsAndRuntimeVariables() = runBlocking {
        val original = book().apply {
            infoHtml = "<h1>Selected info</h1>"; tocHtml = "<a>Selected TOC</a>"
            downloadUrls = listOf("https://selected.invalid/archive")
            variableMap["custom"] = "selected-variable"
            readConfig = Book.ReadConfig(manualReplaceRuleIds = listOf(7L))
        }
        val snapshot = repository.snapshot(original)
        original.infoHtml = "Wrong info"; original.variableMap["custom"] = "wrong-variable"
        original.readConfig!!.manualReplaceRuleIds = listOf(8L)
        val request = repository.selected(snapshot, key())
        val restored = previews.peek(request.previewKey)!!
        assertEquals("<h1>Selected info</h1>", restored.infoHtml)
        assertEquals("<a>Selected TOC</a>", restored.tocHtml)
        assertEquals(listOf("https://selected.invalid/archive"), restored.downloadUrls)
        assertEquals("selected-variable", restored.book.getCustomVariable())
        assertEquals(listOf(7L), restored.book.readConfig!!.manualReplaceRuleIds)
    }

    @Test fun anOldOpeningOwnerCannotDiscardANewerRestoredSelection() = runBlocking {
        val request = repository.selected(book(), key())
        val old = repository.retain(request.id)
        val current = repository.retain(request.id)
        repository.release(old.lease)
        assertEquals(request, repository.pending(request.id))
        repository.release(current.lease)
        assertNull(repository.pending(request.id))
    }

    @Test fun reopeningPendingSelectionKeepsTheSameRequestIdentity() = runBlocking {
        val request = repository.selected(book(), key())
        assertEquals(request, BookInfoOpenRepository(database, BookInfoPreviewStore(context)).pending(request.id))
    }

    @Test fun unopenedSelectionCanBeDiscardedButClaimedDetailKeepsItsPreview() = runBlocking {
        val unopened = repository.selected(book(), key())
        repository.release(repository.retain(unopened.id).lease)
        assertNull(repository.pending(unopened.id))
        val claimed = repository.selected(book(), key())
        val opener = repository.retain(claimed.id)
        BookInfoNavigationRepository(database).claimPreview(claimed.id, claimed.bookUrl, previews.open(claimed.previewKey), previews, "detail")
        repository.release(opener.lease)
        assertEquals(claimed, repository.pending(claimed.id))
    }

    private suspend fun model(handle: SavedStateHandle = SavedStateHandle()): BookInfoOpenViewModel = withContext(Dispatchers.Main) {
        BookInfoOpenViewModel(handle, repository).also { model -> owners += ViewModelStore().also { it.put("open", model) } }
    }
    private suspend fun awaitPending(model: BookInfoOpenViewModel) = withTimeout(5_000) {
        while (model.state.value.pending == null) { check(model.state.value.error == null) { model.state.value.error.orEmpty() }; delay(10) }
        model.state.value.pending!!
    }

    @Test fun aRecreatedViewModelRestoresOnlyThePreviewKeyAndOriginalRequest() = runBlocking {
        val handle = SavedStateHandle(); val first = model(handle)
        withContext(Dispatchers.Main) { first.openSelected(book()) }
        val request = awaitPending(first)
        val saved = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any>(it) } }
        assertEquals(setOf("bookInfoSelectedPreviewKey"), saved.keys)
        val second = model(SavedStateHandle(saved))
        assertEquals(request, awaitPending(second))
    }

    @Test fun repeatedOpenCannotReplaceAnUnconsumedSelection() = runBlocking {
        val model = model()
        withContext(Dispatchers.Main) { model.openSelected(book()) }
        val request = awaitPending(model)
        withContext(Dispatchers.Main) { model.openSelected(book(origin = "wrong-source")) }
        assertEquals(request, model.state.value.pending)
        assertEquals("source-a", previews.peek(request.previewKey)!!.book.origin)
    }

    @Test fun restoredMissingPreviewIsAnErrorWithoutAdoptingTheSharedSearchCache() = runBlocking {
        val key = key(); database.searchBookDao.insert(book().toSearchBook())
        val model = model(SavedStateHandle(mapOf("bookInfoSelectedPreviewKey" to key)))
        withTimeout(5_000) { while (model.state.value.error == null) delay(10) }
        assertNull(model.state.value.pending)
        assertNotNull(model.state.value.error)
        assertNull(repository.pending(key))
    }
    @Test fun clearingAnOldViewModelKeepsTheNewViewModelsRestoredSelection() = runBlocking {
        val handle = SavedStateHandle(); val first = model(handle)
        withContext(Dispatchers.Main) { first.openSelected(book()) }
        val request = awaitPending(first)
        val saved = withContext(Dispatchers.Main) { handle.keys().associateWith { handle.get<Any>(it) } }
        val second = model(SavedStateHandle(saved))
        assertEquals(request, awaitPending(second))
        withContext(Dispatchers.Main) { owners.first().clear() }
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(request, repository.pending(request.id))
    }

    @Test fun abandoningAnUnconsumedOpeningReleasesItsPrivatePreview() = runBlocking {
        val model = model()
        withContext(Dispatchers.Main) { model.openSelected(book()) }
        val request = awaitPending(model)
        withContext(Dispatchers.Main) { owners.single().clear() }
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(repository.pending(request.id))
    }

    @Test fun anIgnoredClickWhilePreparingCannotChangeTheFailedRequestsRetryTarget() = runBlocking {
        val model = model()
        withContext(Dispatchers.Main) {
            model.openShelf("https://missing-$id.invalid/book")
            model.openSelected(book())
        }
        withTimeout(5_000) { while (model.state.value.error == null) delay(10) }
        withContext(Dispatchers.Main) { model.retry() }
        withTimeout(5_000) { while (model.state.value.error == null && model.state.value.pending == null) delay(10) }
        assertNull(model.state.value.pending)
        assertNotNull(model.state.value.error)
    }

}
