package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceViewLifecycleContractTest {

    @Test
    fun `search results stay in the view model across view recreation`() {
        val source = source("ChangeBookSourceViewModel.kt")

        assertTrue(source.contains("val searchItems: List<SearchBook> = emptyList()"))
        assertTrue(source.contains("private fun publishSearchResults()"))
        assertTrue(source.contains("searchItems = results"))
        assertTrue(source.contains("val uiState = _uiState.asStateFlow()"))
    }

    @Test
    fun `dialog collectors are cancelled with the current view`() {
        val book = source("ChangeBookSourceScreen.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")

        assertTrue(book.contains("collectAsStateWithLifecycle()"))
        assertFalse(book.contains("private fun initLiveData()"))
        assertTrue(chapter.contains("viewModel.chapterUiState.collectAsStateWithLifecycle("))
        assertTrue(chapter.contains("viewModel.uiState.collectAsStateWithLifecycle("))
        assertTrue(chapter.contains("minActiveState = RESUMED"))
        assertFalse(chapter.contains("private fun initLiveData()"))
    }

    @Test
    fun `progress replay is not discarded when a view restarts`() {
        val dialog = source("ChangeBookSourceScreen.kt")
        val viewModel = source("ChangeBookSourceViewModel.kt")

        assertTrue(dialog.contains("uiState.progressCount == 0"))
        assertTrue(dialog.contains("uiState.currentOriginName"))
        assertTrue(viewModel.contains("val progressCount: Int = 0"))
    }

    @Test
    fun `book source dialog locates the current source after first results`() {
        val viewModel = source("ChangeBookSourceViewModel.kt")
        val publish = viewModel.section("private fun publishSearchResults()", "private fun publishSearchResult")

        assertTrue(viewModel.contains("private var autoScrollCurrentSource = true"))
        assertTrue(publish.contains("results.indexOfFirst"))
        assertTrue(publish.contains("it.bookUrl == state.currentBookUrl"))
        assertTrue(publish.contains("autoScrollCurrentSource = false"))
    }

    @Test
    fun `current book source keeps a subtle background and check mark`() {
        val components = source("ChangeSourceComposeComponents.kt")
        assertTrue(components.contains("ColorUtils.withAlpha(context.accentColor, 0.1f)"))
        assertTrue(components.contains("R.drawable.ic_check"))
        assertTrue(components.contains("showSelectedHighlight && isCurrent"))
    }

    @Test
    fun `pending business results wait for the current host to resume`() {
        val book = source("ChangeBookSourceScreen.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")
        assertTrue(chapter.contains("minActiveState = RESUMED"))
        assertTrue(chapter.contains("consumeContentResult("))
        assertTrue(chapter.contains("consumeBatchCacheResult("))
        assertTrue(chapter.contains("consumeChangeSourceResult("))
        val bookDelivery = book.section(
            "LaunchedEffect(uiState.changeSourceResultRevision)",
            "Column(",
        )
        assertTrue(bookDelivery.contains("consumeChangeSourceResult"))
    }

    @Test
    fun `closing or replacing a toc cancels its pending content request`() {
        val viewModel = source("ChangeChapterSourceViewModel.kt")
        val loadToc = viewModel.section("fun loadToc", "fun clearToc")
        val clearToc = viewModel.section("fun clearToc", "private fun cancelContent")
        val cancelContent = viewModel.section("private fun cancelContent", "fun cacheContents")

        assertTrue(loadToc.contains("cancelContent()"))
        assertTrue(clearToc.contains("cancelContent()"))
        assertTrue(cancelContent.contains("contentTask?.cancel()"))
        assertTrue(cancelContent.contains("contentTask = null"))
        assertTrue(cancelContent.contains("it.copy(contentLoading = false)"))
    }

    @Test
    fun `deleting the current source waits for a successful replacement`() {
        val bookDialog = source("ChangeBookSourceDialog.kt")
        val bookScreen = source("ChangeBookSourceScreen.kt")
        val chapterDialog = source("ChangeChapterSourceDialog.kt")
        val viewModel = source("ChangeBookSourceViewModel.kt")

        assertTrue(bookScreen.contains("viewModel.autoChangeSource(oldBookType, item)"))
        assertTrue(bookScreen.contains("viewModel.del(item)"))
        assertTrue(bookDialog.contains("SourceChangeCompletion("))
        assertTrue(bookDialog.contains("completion::success"))
        val chapterDelete = chapterDialog.section("fun deleteSource", "fun setBookScore")
        assertTrue(chapterDelete.contains("viewModel.autoChangeSource("))
        assertTrue(chapterDelete.contains(", searchBook)"))
        assertTrue(chapterDialog.contains("SourceChangeCompletion("))
        assertTrue(chapterDialog.contains("completion::success"))

        val autoChange = viewModel.section("fun autoChangeSource", "fun setBookScore")
        val delete = viewModel.section("fun del", "fun autoChangeSource")
        val loading = bookScreen.substringAfter("if (uiState.changeSourceLoading)")
        assertTrue(delete.contains("Coroutine.async"))
        assertFalse(delete.contains("execute {"))
        assertTrue(autoChange.contains("deleteAfterChange: SearchBook"))
        assertTrue(autoChange.contains("it.origin != deleteAfterChange.origin"))
        assertTrue(autoChange.contains("deleteAfterChange = deleteAfterChange"))
        assertTrue(loading.contains("if (uiState.changeSourceCancelable)"))
        assertTrue(loading.contains("viewModel.cancelChangeSource()"))
    }

    @Test
    fun `old source deletion runs once only after migration success`() {
        val source = SearchBook(origin = "old")
        val deleted = mutableListOf<SearchBook>()
        val completion = SourceChangeCompletion(source, deleted::add)

        assertTrue(deleted.isEmpty())
        completion.success()
        completion.success()
        assertEquals(listOf(source), deleted)

        SourceChangeCompletion(null, deleted::add).success()
        assertEquals(listOf(source), deleted)
    }

    @Test
    fun `hosts acknowledge source changes only from successful migration callbacks`() {
        val viewModels = listOf(
            appSource("book/read/ReadBookViewModel.kt")
                .section("fun changeTo(", "/**\n     * 自动换源"),
            appSource("book/audio/AudioPlayViewModel.kt")
                .section("fun changeTo(", "fun removeFromBookshelf"),
        )
        viewModels.forEach { changeTo ->
            assertTrue(changeTo.contains("onSuccess: () -> Unit"))
            assertTrue(changeTo.contains(".onSuccess {\n            onSuccess()"))
            assertFalse(changeTo.contains(".onFinally {\n            onSuccess()"))
        }
        val mangaChange = appSource("book/manga/ReadMangaViewModel.kt").section("fun changeTo(", "fun openChapter")
        assertTrue(mangaChange.contains("changeSourceCoroutine = viewModelScope.launch"))
        assertTrue(mangaChange.contains("onSuccess: () -> Unit"))
        assertTrue(mangaChange.indexOf("sessionRepository.applySourceChange(book, toc)") < mangaChange.indexOf("onSuccess()"))
        assertTrue(mangaChange.contains("catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }"))
        assertFalse(mangaChange.contains(".onFinally"))

        val readActivity = appSource("book/read/ReadBookActivity.kt")
            .section("override fun changeTo(", "override fun replaceContent")
        val audioActivity = appSource("book/audio/AudioPlayActivity.kt")
            .section("override fun changeTo(", "override fun finish")
        val infoActivity = appSource("book/info/BookInfoActivity.kt")
            .section("override fun changeTo(", "override fun Content()")
        val mangaActivity = appSource("book/manga/MangaReaderDestination.kt")

        assertTrue(readActivity.contains("viewModel.changeTo(book, toc, onSuccess)"))
        assertTrue(audioActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(infoActivity.contains("viewModel.changeTo(source, book, toc, onSuccess)"))
        assertTrue(mangaActivity.contains("viewModel.changeTo(result.book, result.toc, completion::success)"))
        listOf(readActivity, audioActivity).forEach { changeTo ->
            assertTrue(
                changeTo.indexOf("appDb.bookDao.insert(book)") <
                    changeTo.indexOf("onSuccess()")
            )
        }
    }

    @Test
    fun `row delete confirmation is scoped to composable lifecycle`() {
        val components = source("ChangeSourceComposeComponents.kt")

        assertTrue(components.contains("var showDeleteConfirm by remember { mutableStateOf(false) }"))
        assertTrue(components.contains("if (showDeleteConfirm)"))
        assertTrue(components.contains("onDeleteSource()"))
    }

    @Test
    fun `view model owns asynchronous results instead of fragment callbacks`() {
        val bookViewModel = source("ChangeBookSourceViewModel.kt")
        val chapterViewModel = source("ChangeChapterSourceViewModel.kt")
        val bookDialog = source("ChangeBookSourceDialog.kt")
        val chapterDialog = source("ChangeChapterSourceDialog.kt")

        assertTrue(bookViewModel.contains("val uiState = _uiState.asStateFlow()"))
        assertTrue(bookViewModel.contains("showEmptyGroupDialog = searchBooks.isEmpty()"))
        assertTrue(bookViewModel.contains("changeSourceResultRevision = it.changeSourceResultRevision + 1"))
        assertFalse(bookViewModel.contains("MutableLiveData"))
        assertFalse(bookViewModel.contains("searchFinishCallback"))
        assertFalse(bookDialog.contains("viewModel.getToc(book,"))
        assertFalse(chapterDialog.contains("viewModel.getToc(book,"))
        assertFalse(chapterDialog.contains("viewModel.getContent("))
        assertFalse(chapterDialog.contains("cacheTask?.cancel()"))
        assertTrue(chapterViewModel.contains("val chapterUiState = _chapterUiState.asStateFlow()"))
        assertTrue(chapterViewModel.contains("contentResultRevision = state.contentResultRevision + 1"))
        assertTrue(chapterViewModel.contains("batchCacheResultRevision = state.batchCacheResultRevision + 1"))
        assertTrue(chapterViewModel.contains("it.copy(tocState = ChapterTocState.Success("))
        assertFalse(chapterViewModel.contains("MutableLiveData"))
        assertTrue(chapterViewModel.contains("withContext(NonCancellable)"))
        assertTrue(chapterViewModel.contains("if (cacheCommitStarted) return"))
    }

    @Test
    fun `per view dialogs and compositions are released`() {
        val book = source("ChangeBookSourceDialog.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")
        assertTrue(book.contains("DisposeOnViewTreeLifecycleDestroyed"))
        assertFalse(book.contains("searchFinishDialog"))
        assertFalse(book.contains("WaitDialog"))
        assertTrue(chapter.contains("DisposeOnViewTreeLifecycleDestroyed"))
        assertFalse(chapter.contains("searchFinishDialog"))
        assertFalse(chapter.contains("WaitDialog"))
        assertTrue(chapter.contains("addCallback(viewLifecycleOwner)"))
    }

    @Test
    fun `search prompt stays pending until the dialog finishes`() {
        val book = source("ChangeBookSourceScreen.kt")
        val chapter = source("ChangeChapterSourceDialog.kt")
        assertTrue(book.contains("if (uiState.showEmptyGroupDialog)"))
        assertTrue(book.contains("onDismissRequest = viewModel::dismissEmptyGroupDialog"))
        assertTrue(chapter.contains("if (sourceState.showEmptyGroupDialog)"))
        assertTrue(chapter.contains("onDismissRequest = viewModel::dismissEmptyGroupDialog"))
    }

    @Test
    fun `content errors clear the replayed toc state`() {
        val observer = source("ChangeChapterSourceDialog.kt").section(
            "LaunchedEffect(chapterState.contentResultRevision)",
            "LaunchedEffect(chapterState.batchCaching)",
        )
        val error = observer.section(
            "is ChapterContentResult.Error",
            "null -> Unit",
        )

        assertTrue(error.contains("tocVisible = false"))
        assertTrue(error.contains("viewModel.clearToc()"))
    }

    @Test
    fun `chapter automation advances only while the current view is resumed`() {
        val dialog = source("ChangeChapterSourceDialog.kt")
        val observer = dialog.section(
            "LaunchedEffect(chapterState.automationState)",
            "LaunchedEffect(scrollSourceTrigger)",
        )
        val viewModel = source("ChangeChapterSourceViewModel.kt")
        val runner = viewModel.section(
            "fun runNextAutomationIfReady()",
            "fun cacheAutomationSelection",
        )

        assertTrue(dialog.contains("minActiveState = RESUMED"))
        assertTrue(observer.contains("viewModel.runNextAutomationIfReady()"))
        assertFalse(runner.contains("delay("))
        assertFalse(runner.substringAfter("{").contains("runNextAutomationIfReady()"))
    }

    @Test
    fun `chapter automation freezes its target and rejects blank content`() {
        val dialog = source("ChangeChapterSourceDialog.kt")
        val openToc = dialog.section("fun openToc", "private fun showTocState")
        val viewModel = source("ChangeChapterSourceViewModel.kt")
        val loadToc = viewModel.section("fun loadToc", "fun clearToc")
        val cache = viewModel.section("private fun cacheContents", "fun automationRangeDefaults")
        val stop = viewModel.section("fun stopAutomation", "private fun cacheAutomationPositions")
        val renderState = dialog.section(
            "private fun showAutomationState",
            "override fun observeLiveBus()",
        )

        assertTrue(openToc.contains("viewModel.isAutomationActive"))
        assertTrue(openToc.contains("batchCaching"))
        assertTrue(loadToc.contains("isAutomationActive"))
        assertTrue(loadToc.contains("_chapterUiState.value.batchCaching"))
        assertTrue(cache.contains("mergedContent.isBlank()"))
        assertTrue(stop.contains("cacheCommitStarted"))
        assertTrue(stop.contains("requestStopAfterCurrent()"))
        assertTrue(renderState.contains("is ChapterSourceAutomationPause.ContentError"))
        assertTrue(renderState.contains("else ->"))
        assertTrue(renderState.contains("clearSelection()"))
    }

    private fun source(fileName: String): String {
        return appSource("book/changesource/$fileName")
    }

    private fun appSource(relativePath: String): String {
        return projectFile("src/main/java/io/legado/app/ui/$relativePath")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) {
            "Missing section $startMarker .. $endMarker"
        }
        return substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
