package io.legado.app.ui.book.info

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookInfoPreview
import io.legado.app.data.repository.BookInfoPreviewStore
import io.legado.app.data.repository.BookInfoRepository
import io.legado.app.help.book.BookHelp
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoPreviewRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = BookInfoPreviewStore(context)
    private val repository = BookInfoRepository(appDb)
    private val id = UUID.randomUUID().toString()
    private val keys = linkedSetOf<String>()
    private val books = arrayListOf<Book>()
    private val sources = arrayListOf<BookSource>()
    private val cover get() = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}"

    @After fun cleanUp() {
        keys.forEach { store.discard(store.open(it)) }
        books.forEach { appDb.bookChapterDao.delByBook(it.bookUrl); appDb.bookDao.delete(it); BookHelp.clearCache(it) }
        sources.forEach { appDb.bookSourceDao.delete(it) }
    }

    @Test fun parserInputsAndIndependentChapterVariablesRoundTripOutsideTheLibrary() {
        val key = key()
        val first = store.open(key)
        val book = Book(bookUrl = "preview://$id", name = "Preview", readConfig = Book.ReadConfig(manualReplaceRuleIds = listOf(7L)))
        book.infoHtml = "<h1>Info $id</h1>"
        book.tocHtml = "<a>TOC $id</a>"
        book.downloadUrls = listOf("https://example.invalid/$id.epub")
        book.variableMap["custom"] = "Runtime book variable"
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Preview chapter").apply { variableMap["runtime"] = "chapter value" }
        assertTrue(store.save(first, 1, BookInfoPreview(book, listOf(chapter), "source", false)))
        val restored = checkNotNull(store.open(key).restored)
        assertEquals(book.bookUrl, restored.book.bookUrl)
        assertEquals(book.infoHtml, restored.infoHtml)
        assertEquals(book.tocHtml, restored.tocHtml)
        assertEquals(book.downloadUrls, restored.downloadUrls)
        assertEquals("Runtime book variable", restored.book.getCustomVariable())
        assertEquals("chapter value", restored.chapters.single().variableMap["runtime"])
        assertEquals(listOf(7L), restored.book.readConfig!!.manualReplaceRuleIds)
        assertFalse(appDb.bookDao.has(book.bookUrl))
    }

    @Test fun lateWritesAndDiscardFromAnOldOwnerCannotOverwriteTheNewOwner() {
        val key = key()
        val first = store.open(key)
        assertTrue(store.save(first, 1, preview("First")))
        val second = store.open(key)
        assertEquals("First", second.restored!!.book.name)
        assertTrue(store.save(second, 2, preview("Second")))
        assertFalse(store.save(second, 1, preview("Out of order")))
        assertFalse(store.save(first, 999, preview("Late old owner")))
        assertFalse(store.discard(first))
        assertEquals("Second", store.open(key).restored!!.book.name)
    }

    @Test fun savedKeysCannotTraverseOutsideThePreviewDirectory() {
        val invalid = "../escaped-$id"
        assertTrue(runCatching { store.open(invalid) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(context.noBackupFilesDir, "escaped-$id.json").exists())
    }

    @Test fun aDifferentViewModelRestoresAnUnsavedSourceChangeAndItsChaptersFromDisk() {
        val original = Book(bookUrl = "original://$id", name = "Original $id", author = "Author", origin = "missing",
            type = BookType.text or BookType.notShelf, tocUrl = "original://$id/toc", coverUrl = cover)
        books.add(original); appDb.bookDao.insert(original)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = original.bookUrl, url = "original-chapter", title = "Original"))
        val source = BookSource("https://preview-$id.invalid", "Preview source $id")
        sources.add(source); appDb.bookSourceDao.insert(source)
        val changed = Book(bookUrl = "${source.bookSourceUrl}/book", name = "Changed $id", author = "Author",
            origin = source.bookSourceUrl, tocUrl = "${source.bookSourceUrl}/toc", coverUrl = cover).apply {
            infoHtml = "<html>Cached changed info $id</html>"
            tocHtml = "<html>Cached changed toc $id</html>"
            downloadUrls = listOf("${source.bookSourceUrl}/download.epub")
        }
        val chapters = (1..2).map { BookChapter(bookUrl = changed.bookUrl, url = "chapter-$it", title = "Changed $it", index = it - 1) }
        val saved = SavedStateHandle()
        withModel(saved) { model ->
            main { model.initData(original.bookUrl) }
            await { model.uiState.value.book?.bookUrl == original.bookUrl && model.uiState.value.chapters.size == 1 }
            main { model.changeTo(source, changed, chapters) }
            await { model.uiState.value.book?.bookUrl == changed.bookUrl && model.uiState.value.chapters.size == 2 && model.uiState.value.recoveryReady }
            assertFalse(model.uiState.value.inBookshelf)
            assertFalse(appDb.bookDao.has(changed.bookUrl))
        }
        val restored = copyState(saved)
        withModel(restored) { model ->
            main { model.initData(original.bookUrl) }
            await { model.uiState.value.book?.bookUrl == changed.bookUrl && model.uiState.value.recoveryReady }
            assertEquals(chapters.map { it.title }, model.uiState.value.chapters.map { it.title })
            assertEquals(changed.infoHtml, model.getBook(false)!!.infoHtml)
            assertEquals(changed.tocHtml, model.getBook(false)!!.tocHtml)
            assertEquals(changed.downloadUrls, model.getBook(false)!!.downloadUrls)
            assertEquals(source.bookSourceUrl, model.uiState.value.source!!.bookSourceUrl)
            assertFalse(model.uiState.value.inBookshelf)
            assertFalse(appDb.bookDao.has(changed.bookUrl))
            assertTrue(saved.keys().all { saved.get<Any?>(it) is String || saved.get<Any?>(it) is Boolean })
        }
    }

    @Test fun theCurrentLibraryRecordAndSourceTakePrecedenceOverAnOldCachedSnapshot() {
        val key = key()
        val source = BookSource("current-source://$id", "Current source")
        sources.add(source); appDb.bookSourceDao.insert(source)
        val current = Book(bookUrl = "library://$id", name = "Current library metadata", origin = source.bookSourceUrl,
            tocUrl = "library://$id/toc", coverUrl = cover)
        books.add(current); appDb.bookDao.insert(current)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = current.bookUrl, url = "current-chapter", title = "Current library chapter"))
        val lease = store.open(key)
        val stale = current.copy(name = "Stale metadata", origin = "old-source")
        assertTrue(store.save(lease, 1, BookInfoPreview(stale, listOf(BookChapter(bookUrl = current.bookUrl, url = "stale", title = "Stale chapter")),
            "old-source", false, infoHtml = "Stale parser body")))
        withModel(SavedStateHandle(mapOf("previewKey" to key))) { model ->
            main { model.initData("") }
            await { model.uiState.value.recoveryReady }
            assertEquals(current.name, model.uiState.value.book!!.name)
            assertEquals(listOf("Current library chapter"), model.uiState.value.chapters.map { it.title })
            assertEquals(source.bookSourceUrl, model.uiState.value.source!!.bookSourceUrl)
            assertNull(model.getBook(false)!!.infoHtml)
            assertTrue(model.uiState.value.inBookshelf)
        }
    }

    @Test fun aCorruptPreviewFallsBackToTheExistingBookWithoutDeletingLibraryData() {
        val key = key()
        File(context.noBackupFilesDir, "book-info-previews/$key.json").apply { parentFile!!.mkdirs(); writeText("{invalid json") }
        val current = Book(bookUrl = "fallback://$id", name = "Fallback $id", origin = "missing", tocUrl = "fallback://$id/toc", coverUrl = cover)
        books.add(current); appDb.bookDao.insert(current)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = current.bookUrl, url = "chapter", title = "Retained chapter"))
        withModel(SavedStateHandle(mapOf("previewKey" to key))) { model ->
            main { model.initData(current.bookUrl) }
            await { model.uiState.value.book?.bookUrl == current.bookUrl && model.uiState.value.recoveryReady }
            assertEquals(listOf("Retained chapter"), model.uiState.value.chapters.map { it.title })
            assertTrue(appDb.bookDao.has(current.bookUrl))
        }
    }

    @Test fun anEntryUsesItsAlreadyClaimedPreviewLeaseWithoutOpeningANewEpoch() {
        val key = key()
        val candidate = Book(bookUrl = "entry-preview://$id", name = "Entry preview $id", origin = "missing",
            type = BookType.text or BookType.notShelf, tocUrl = "entry-preview://$id/toc", coverUrl = cover)
        val first = store.open(key)
        assertTrue(store.save(first, 1, BookInfoPreview(candidate,
            listOf(BookChapter(bookUrl = candidate.bookUrl, url = "chapter", title = "Preview chapter")), null, false)))
        val claimed = store.open(key)
        val route = io.legado.app.ui.navigation.BookInfoRoute(candidate.bookUrl, previewKey = key)
        withModel(SavedStateHandle(mapOf("bookUrl" to "stale-saved-key"))) { model ->
            main { model.initializeEntry(route, BookInfoEntryBootstrap(claimed, candidate.bookUrl)) }
            await { model.uiState.value.book?.bookUrl == candidate.bookUrl && model.uiState.value.recoveryReady }
            assertEquals(listOf("Preview chapter"), model.uiState.value.chapters.map { it.title })
            assertEquals(true, store.withCurrentLease(claimed) { true })
            assertFalse(appDb.bookDao.has(candidate.bookUrl))
        }
    }

    @Test fun theDurableEntryKeyWinsOverBothSavedStateAndAnOlderPreview() {
        val key = key()
        val old = Book(bookUrl = "old-entry://$id", name = "Old $id", author = "Author", origin = "missing",
            tocUrl = "old-entry://$id/toc", coverUrl = cover)
        val current = old.copy(bookUrl = "current-entry://$id", name = "Current $id", tocUrl = "current-entry://$id/toc")
        books.add(current); appDb.bookDao.insert(current)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = current.bookUrl, url = "current-chapter", title = "Current chapter"))
        val first = store.open(key)
        assertTrue(store.save(first, 1, BookInfoPreview(old, emptyList(), null, false)))
        val claimed = store.open(key)
        val route = io.legado.app.ui.navigation.BookInfoRoute(old.bookUrl, previewKey = key)
        withModel(SavedStateHandle(mapOf("bookUrl" to old.bookUrl, "name" to old.name, "author" to old.author))) { model ->
            main { model.initializeEntry(route, BookInfoEntryBootstrap(claimed, current.bookUrl)) }
            await { model.uiState.value.book?.bookUrl == current.bookUrl && model.uiState.value.recoveryReady }
            assertEquals(current.name, model.uiState.value.book!!.name)
            assertEquals(listOf("Current chapter"), model.uiState.value.chapters.map { it.title })
        }
    }

    @Test fun aReturnedCanonicalKeyRefreshesTheExistingViewModelByUrl() {
        val key = key()
        val original = Book(bookUrl = "before-return://$id", name = "Before return $id", origin = "missing",
            tocUrl = "before-return://$id/toc", coverUrl = cover)
        val returned = original.copy(bookUrl = "after-return://$id", name = "After return $id", tocUrl = "after-return://$id/toc")
        books.addAll(listOf(original, returned)); appDb.bookDao.insert(original, returned)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = original.bookUrl, url = "before", title = "Before chapter"),
            BookChapter(bookUrl = returned.bookUrl, url = "after", title = "After chapter"))
        val claimed = store.open(key)
        val route = io.legado.app.ui.navigation.BookInfoRoute(original.bookUrl, previewKey = key)
        withModel(SavedStateHandle()) { model ->
            main { model.initializeEntry(route, BookInfoEntryBootstrap(claimed, original.bookUrl)) }
            await { model.uiState.value.book?.bookUrl == original.bookUrl && model.uiState.value.recoveryReady }
            main { model.refreshEntryBook(returned.bookUrl) }
            await { model.uiState.value.book?.bookUrl == returned.bookUrl && model.uiState.value.recoveryReady }
            assertEquals(returned.name, model.uiState.value.book!!.name)
            assertEquals(listOf("After chapter"), model.uiState.value.chapters.map { it.title })
            assertEquals(returned.bookUrl, store.peek(key)!!.book.bookUrl)
        }
    }

    private fun preview(name: String) = BookInfoPreview(Book(bookUrl = "preview://$id", name = name), emptyList(), null, false)
    private fun key() = UUID.randomUUID().toString().also(keys::add)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun copyState(saved: SavedStateHandle): SavedStateHandle {
        lateinit var result: SavedStateHandle
        main { result = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }) }
        return result
    }
    private fun withModel(saved: SavedStateHandle, block: (BookInfoViewModel) -> Unit) {
        val owner = ViewModelStore()
        lateinit var model: BookInfoViewModel
        main { model = BookInfoViewModel(repository, saved, store, io.legado.app.data.repository.BookGroupRepository(appDb, context), io.legado.app.data.repository.BookInfoLibraryRepository(appDb), io.legado.app.data.repository.CoverSearchRepository(appDb), io.legado.app.data.repository.BookIntroRepository(), io.legado.app.data.repository.BookInfoNavigationRepository(appDb)); owner.put("details", model) }
        try { block(model) } finally {
            main { owner.clear(); saved.get<String>("previewKey")?.let(keys::add) }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Preview state did not reach the expected state", condition())
        instrumentation.waitForIdleSync()
    }
}
