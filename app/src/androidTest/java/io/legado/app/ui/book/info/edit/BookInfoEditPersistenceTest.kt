package io.legado.app.ui.book.info.edit

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.CoverSourceRef
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoEditPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = BookInfoEditDraftStore(context)
    private val repository = BookInfoEditRepository(appDb)
    private val books = arrayListOf<Book>()
    private val keys = arrayListOf<String>()
    private val owners = arrayListOf<ViewModelStore>()
    @After fun cleanup() {
        owners.forEach { main { it.clear() } }
        books.forEach { book -> appDb.bookHighlightDao.getByBook(book.bookUrl).forEach { appDb.bookHighlightDao.delete(it) }; appDb.bookDao.delete(book); BookHelp.clearCache(book) }
        keys.forEach { File(context.noBackupFilesDir, "book-info-edit-drafts/$it.json").delete() }
    }
    private fun book() = Book(bookUrl = "edit-persistence://${UUID.randomUUID()}", name = "Name ${UUID.randomUUID()}", author = "Author", origin = "https://original.invalid").also { books.add(it); appDb.bookDao.insert(it) }
    private fun key() = UUID.randomUUID().toString().also(keys::add)
    private fun snapshot(revision: Long, text: String) = BookInfoEditDraftSnapshot("book", revision, BookInfoEditDraft(bookIntro = text), setOf(BookInfoEditField.Intro))
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) { val end = SystemClock.elapsedRealtime() + 15000; while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out" }; SystemClock.sleep(20) } }
    private fun model(saved: SavedStateHandle): BookInfoEditViewModel {
        lateinit var vm: BookInfoEditViewModel
        main { vm = BookInfoEditViewModel(repository, saved, CoverSearchRepository(appDb), CoverFileRepository(context), store)
            owners.add(ViewModelStore().apply { put("edit", vm) }) }
        return vm
    }
    @Test fun leasesRejectOldWritesReorderingAndResurrectionAfterDiscard() {
        val key = key(); val old = store.open(key)
        assertTrue(store.save(old, snapshot(1, "first")))
        val current = store.open(key)
        assertEquals("first", current.restored!!.draft.bookIntro)
        assertTrue(store.save(current, snapshot(3, "latest")))
        assertFalse(store.save(current, snapshot(2, "late")))
        assertFalse(store.save(old, snapshot(99, "old")))
        assertFalse(store.discard(old))
        assertTrue(store.discard(current))
        val reopened = store.open(key)
        assertNull(reopened.restored)
        assertFalse(store.save(old, snapshot(100, "resurrected")))
        assertFalse(store.save(current, snapshot(100, "resurrected")))
        assertTrue(store.save(reopened, snapshot(1, "new")))
    }
    @Test fun invalidKeysCannotEscapeDraftDirectory() {
        assertTrue(runCatching { store.open("../escape") }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun editsPatchFreshRowsAndSynchronizeHighlightLabels() = runBlocking {
        val original = book()
        val highlight = BookHighlight(bookUrl = original.bookUrl, bookName = original.name, bookAuthor = original.author)
        appDb.bookHighlightDao.insert(highlight)
        val concurrent = original.copy(durChapterIndex = 7, durChapterPos = 23, group = 8, customCoverUrl = "https://cdn.invalid/new", customCoverSource = CoverSourceRef("https://cdn.invalid/new", "https://source.invalid"))
        appDb.bookDao.update(concurrent)
        val saved = repository.saveDraft(original.bookUrl, BookInfoEditDraft(bookName = "Renamed ${UUID.randomUUID()}"), setOf(BookInfoEditField.Name))
        assertEquals(7, saved.durChapterIndex); assertEquals(23, saved.durChapterPos); assertEquals(8L, saved.group)
        assertEquals(concurrent.customCoverSource, saved.customCoverSource)
        assertEquals(saved.name, appDb.bookHighlightDao.getByBook(saved.bookUrl).single().bookName)
        assertEquals(original.author, appDb.bookHighlightDao.getByBook(saved.bookUrl).single().bookAuthor)
    }
    @Test fun conflictingIdentityRollsBackMetadataAndHighlights() = runBlocking {
        val original = book(); val other = book()
        appDb.bookHighlightDao.insert(BookHighlight(bookUrl = original.bookUrl, bookName = original.name, bookAuthor = original.author))
        assertTrue(runCatching { repository.saveDraft(original.bookUrl, BookInfoEditDraft(bookName = other.name, bookAuthor = other.author), setOf(BookInfoEditField.Name, BookInfoEditField.Author)) }.isFailure)
        assertEquals(original.name, appDb.bookDao.getBook(original.bookUrl)!!.name)
        assertEquals(original.name, appDb.bookHighlightDao.getByBook(original.bookUrl).single().bookName)
    }
    @Test fun coverAuthRoundTripsAndGuardsConcurrentPersistence() = runBlocking {
        val original = book(); val url = "https://cdn.invalid/cover.png"
        val saved = repository.saveDraft(original.bookUrl, BookInfoEditDraft(coverUrl = url, coverSource = "https://source-b.invalid"), setOf(BookInfoEditField.Cover))
        assertEquals("https://source-b.invalid", appDb.bookDao.getBook(original.bookUrl)!!.getCoverSourceOrigin())
        val stale = CoverSourceRef(url, "https://source-a.invalid")
        assertEquals(0, appDb.bookDao.updatePersistedCoverUrlIfUnchanged(saved.bookUrl, saved.origin, saved.coverUrl, url, null, "/stale", stale))
        assertEquals(1, appDb.bookDao.updatePersistedCoverUrlIfUnchanged(saved.bookUrl, saved.origin, saved.coverUrl, url, null, "/fresh", saved.customCoverSource))
        assertEquals(0, appDb.bookDao.clearCoverOverridesIfUnchanged(saved.bookUrl, url, "/fresh", stale))
        assertEquals(1, appDb.bookDao.clearCoverOverridesIfUnchanged(saved.bookUrl, url, "/fresh", saved.customCoverSource))
        assertNull(appDb.bookDao.getBook(saved.bookUrl)!!.customCoverSource)
    }
    @Test fun largeDraftRestoresInANewViewModelWithoutBinderPayload() {
        val book = book(); val saved = SavedStateHandle(); val first = model(saved)
        main { first.loadBook(book.bookUrl) }; await { first.book != null }
        val text = "Draft text ".repeat(120000)
        main { first.updateDraft { it.copy(bookIntro = text) } }
        await { first.uiState.value.recoveryReady }
        assertNull(saved.get<String>("edit.intro"))
        val state = saved.keys().associateWith { saved.get<Any?>(it) }
        assertTrue(state.values.filterIsInstance<String>().sumOf { it.length } < 10000)
        keys.add(checkNotNull(saved["edit.draftKey"]))
        main { owners.first().clear() }
        val second = model(SavedStateHandle(state))
        main { second.loadBook(book.bookUrl) }; await { second.book != null }
        assertEquals(text, second.uiState.value.draft.bookIntro)
        assertEquals(setOf(BookInfoEditField.Intro), second.uiState.value.dirty)
        assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customIntro)
        main { second.save() }; await { second.uiState.value.saved }
        assertEquals(text, appDb.bookDao.getBook(book.bookUrl)!!.customIntro)
    }
    @Test fun localImageIsCopiedAndOnlyCommittedAfterSave() {
        val book = book(); val saved = SavedStateHandle(); val vm = model(saved)
        main { vm.loadBook(book.bookUrl) }; await { vm.book != null }
        keys.add(checkNotNull(saved["edit.draftKey"]))
        val input = File(context.cacheDir, "cover-${UUID.randomUUID()}.png")
        android.graphics.Bitmap.createBitmap(12, 20, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            input.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        var copied: File? = null
        try {
            main { vm.importCover(android.net.Uri.fromFile(input)) }
            await { !vm.uiState.value.busy && BookInfoEditField.Cover in vm.uiState.value.dirty }
            copied = File(vm.uiState.value.draft.coverUrl)
            assertTrue(copied.isFile); assertNull(vm.uiState.value.draft.coverSource)
            assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
            input.delete(); assertTrue(copied.isFile)
            main { vm.save() }; await { vm.uiState.value.saved }
            assertEquals(copied.path, appDb.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
        } finally { input.delete(); copied?.delete() }
    }
    @Test fun invalidImageRetainsTheCurrentDraftAndCanBeRetried() {
        val book = book(); val saved = SavedStateHandle(); val vm = model(saved)
        main { vm.loadBook(book.bookUrl) }; await { vm.book != null }
        keys.add(checkNotNull(saved["edit.draftKey"]))
        main { vm.updateDraft { it.copy(coverUrl = "https://manual.invalid/cover") } }
        val input = File(context.cacheDir, "invalid-${UUID.randomUUID()}.png").apply { writeText("not an image") }
        try {
            main { vm.importCover(android.net.Uri.fromFile(input)) }
            await { !vm.uiState.value.busy && vm.uiState.value.error != null }
            assertEquals("https://manual.invalid/cover", vm.uiState.value.draft.coverUrl)
            assertFalse(saved.contains("edit.coverImport"))
            assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
        } finally { input.delete() }
    }
    @Test fun missingLargeDraftReportsRecoveryFailureWithoutOverwritingBook() {
        val book = book(); val key = key()
        val saved = SavedStateHandle(mapOf("edit.target" to book.bookUrl, "edit.draftKey" to key, "edit.dirty" to arrayListOf("Intro"), "edit.largeFields" to arrayListOf("Intro")))
        val vm = model(saved); main { vm.loadBook(book.bookUrl) }; await { vm.book != null }
        assertEquals(BookInfoEditError.Draft, vm.uiState.value.error)
        assertFalse(BookInfoEditField.Intro in vm.uiState.value.dirty)
        main { vm.save() }; await { vm.uiState.value.saved }
        assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customIntro)
    }
}
