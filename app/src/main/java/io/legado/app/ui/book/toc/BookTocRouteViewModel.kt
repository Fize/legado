package io.legado.app.ui.book.toc

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.BookTocContent
import io.legado.app.data.repository.BookTocRepository
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isVideo
import io.legado.app.model.localBook.PdfOutlineNode
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class BookTocTab { Chapters, Bookmarks, Highlights }

data class BookTocUiState(
    val book: Book? = null,
    val selectedTab: BookTocTab = BookTocTab.Chapters,
    val query: String = "",
    val chapterItems: List<TocListItem> = emptyList(),
    val displayTitles: Map<String, String> = emptyMap(),
    val pdfOutline: List<PdfOutlineRow> = emptyList(),
    val usesPdfOutline: Boolean = false,
    val reverseToc: Boolean = false,
    val tocExpanded: Boolean = true,
    val useReplaceTitles: Boolean = false,
    val countWords: Boolean = true,
    val splitLongChapters: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarkScrollIndex: Int = 0,
    val highlights: List<BookHighlight> = emptyList(),
    val highlightScrollIndex: Int = 0,
    val editingBookmark: Bookmark? = null,
    val editingHighlight: BookHighlight? = null,
    val txtTocRules: List<TxtTocRule> = emptyList(),
    val showTxtTocRulePicker: Boolean = false,
    val txtTocRuleQuery: String = "",
    val selectedTxtTocRuleName: String? = null,
    val message: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class BookTocSelection(
    val chapterIndex: Int,
    val chapterPosition: Int = 0,
    val chapterChanged: Boolean = false,
    val volumeIndex: Int? = null,
    val chapterInVolumeIndex: Int? = null,
    val highlightLayoutTitleLength: Int? = null,
    val highlightAnchorText: String? = null,
    val pdfPageIndex: Int? = null,
)

@HiltViewModel
class BookTocRouteViewModel @Inject constructor(
    private val repository: BookTocRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookTocUiState())
    val uiState: StateFlow<BookTocUiState> = _uiState.asStateFlow()
    private var content: BookTocContent? = null
    private val tocListState = TocListState()
    private var pdfOutlineState: PdfOutlineListState? = null
    private var bookmarksJob: Job? = null
    private var highlightsJob: Job? = null
    private var chapterSearchJob: Job? = null
    private var displayTitlesJob: Job? = null
    private var txtRulesJob: Job? = null
    private var requestedBookUrl: String? = null

    fun initialize(bookUrl: String, openTxtTocRules: Boolean = false) {
        if (requestedBookUrl == bookUrl && (uiState.value.loading || uiState.value.book != null)) return
        requestedBookUrl = bookUrl
        viewModelScope.launch {
            _uiState.value = BookTocUiState()
            runCatching { repository.load(bookUrl) }
                .onFailure { AppLog.put("目录页面加载失败", it) }
                .onSuccess { loaded ->
                    if (loaded == null) {
                        _uiState.update { it.copy(loading = false, error = "找不到这本书") }
                    } else {
                        content = loaded
                        val pdfNodes = loaded.pdfOutline
                        val hasPdfOutline = loaded.book.isPdf && pdfNodes.any { it.pageIndex != null }
                        if (hasPdfOutline) {
                            pdfOutlineState = PdfOutlineListState(pdfNodes, loaded.book.getTocExpanded())
                        } else {
                            tocListState.setFullChapters(
                                chapters = loaded.chapters,
                                reverseOrder = loaded.book.getReverseToc(),
                                reverseDisplay = !loaded.book.isPdf && !loaded.book.isEpub && loaded.book.getReverseTocDisplay(),
                                defaultExpanded = loaded.book.getTocExpanded(),
                                currentChapterIndex = loaded.book.durChapterIndex,
                                epubToc = loaded.epubOutline,
                            )
                        }
                        _uiState.update {
                            it.copy(
                                book = loaded.book,
                                loading = false,
                                usesPdfOutline = hasPdfOutline,
                                reverseToc = if (loaded.book.isPdf || loaded.book.isEpub) {
                                    loaded.book.getReverseToc()
                                } else loaded.book.getReverseTocDisplay(),
                                tocExpanded = loaded.book.getTocExpanded(),
                                useReplaceTitles = repository.useReplaceInToc(),
                                countWords = repository.countWordsInToc(),
                                splitLongChapters = loaded.book.getSplitLongChapter(),
                            )
                        }
                        refreshVisibleItems()
                        observeBookmarks()
                        observeHighlights()
                        if (loaded.book.isLocalTxt) {
                            if (txtRulesJob == null) observeTxtTocRules(openTxtTocRules)
                            else if (openTxtTocRules) openTxtTocRulePicker()
                        }
                    }
                }
        }
    }

    fun selectTab(tab: BookTocTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun editBookmark(bookmark: Bookmark) = _uiState.update { it.copy(editingBookmark = bookmark) }

    fun editHighlight(highlight: BookHighlight) = _uiState.update { it.copy(editingHighlight = highlight) }

    fun dismissEditors() = _uiState.update { it.copy(editingBookmark = null, editingHighlight = null) }

    fun saveBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            runCatching { repository.saveBookmark(bookmark) }
                .onFailure { AppLog.put("保存书签失败", it) }
            dismissEditors()
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            runCatching { repository.deleteBookmark(bookmark) }
                .onFailure { AppLog.put("删除书签失败", it) }
            dismissEditors()
        }
    }

    fun saveHighlight(highlight: BookHighlight) {
        viewModelScope.launch {
            runCatching { repository.saveHighlight(highlight) }
                .onFailure { AppLog.put("保存标注失败", it) }
            dismissEditors()
        }
    }

    fun deleteHighlight(highlight: BookHighlight) {
        viewModelScope.launch {
            runCatching { repository.deleteHighlight(highlight) }
                .onFailure { AppLog.put("删除标注失败", it) }
            dismissEditors()
        }
    }

    fun exportBookmarks(uri: Uri, markdown: Boolean) {
        val book = content?.book ?: return
        viewModelScope.launch {
            runCatching { repository.exportBookmarks(book, uri, markdown) }
                .onSuccess { _uiState.update { it.copy(message = "书签导出成功") } }
                .onFailure {
                    AppLog.put("书签导出失败", it)
                    _uiState.update { state -> state.copy(message = "书签导出失败：${it.localizedMessage.orEmpty()}") }
                }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun openTxtTocRulePicker() {
        val book = content?.book?.takeIf { it.isLocalTxt } ?: return
        val current = uiState.value.txtTocRules.firstOrNull {
            book.tocUrl == it.rule + io.legado.app.model.localBook.TextFile.spaceChars + it.replacement
        }?.name.orEmpty()
        _uiState.update {
            it.copy(showTxtTocRulePicker = true, selectedTxtTocRuleName = current, txtTocRuleQuery = "")
        }
    }

    fun dismissTxtTocRulePicker() = _uiState.update { it.copy(showTxtTocRulePicker = false) }

    fun setTxtTocRuleQuery(query: String) = _uiState.update { it.copy(txtTocRuleQuery = query) }

    fun selectTxtTocRule(name: String) = _uiState.update { it.copy(selectedTxtTocRuleName = name) }

    fun setTxtTocRuleEnabled(rule: TxtTocRule, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setTxtTocRuleEnabled(rule, enabled) }
                .onFailure { AppLog.put("更新 TXT 目录规则状态失败", it) }
        }
    }

    fun applySelectedTxtTocRule() {
        val loaded = content ?: return
        val rule = uiState.value.txtTocRules.firstOrNull { it.name == uiState.value.selectedTxtTocRuleName } ?: return
        viewModelScope.launch {
            runCatching { repository.applyTxtTocRule(loaded.book, rule) }
                .onFailure {
                    AppLog.put("应用 TXT 目录规则失败", it)
                    _uiState.update { state -> state.copy(message = "应用目录规则失败：${it.localizedMessage.orEmpty()}") }
                }
                .onSuccess {
                    dismissTxtTocRulePicker()
                    requestedBookUrl = null
                    initialize(loaded.book.bookUrl)
                }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        refreshVisibleItems()
        observeBookmarks()
        observeHighlights()
    }

    fun toggleVolume(index: Int) {
        if (tocListState.toggleVolume(index)) refreshVisibleItems()
    }

    fun togglePdfOutline(id: Int) {
        pdfOutlineState?.toggle(id)
        refreshVisibleItems()
    }

    fun toggleReverseToc() {
        val loaded = content ?: return
        val reversed = !uiState.value.reverseToc
        viewModelScope.launch {
            runCatching { repository.setReverseToc(loaded.book, reversed) }
                .onFailure { AppLog.put("保存目录顺序失败", it) }
                .onSuccess {
                    _uiState.update { it.copy(reverseToc = reversed) }
                    if (uiState.value.usesPdfOutline) {
                        refreshVisibleItems()
                    } else {
                        tocListState.setFullChapters(
                            chapters = loaded.chapters,
                            reverseOrder = loaded.book.getReverseToc(),
                            reverseDisplay = !loaded.book.isEpub && loaded.book.getReverseTocDisplay(),
                            resetCollapse = true,
                            defaultExpanded = uiState.value.tocExpanded,
                            currentChapterIndex = loaded.book.durChapterIndex,
                            epubToc = loaded.epubOutline,
                        )
                        refreshVisibleItems()
                    }
                }
        }
    }

    fun toggleTocExpanded() {
        val loaded = content ?: return
        val expanded = !uiState.value.tocExpanded
        viewModelScope.launch {
            runCatching { repository.setTocExpanded(loaded.book, expanded) }
                .onFailure { AppLog.put("保存目录展开设置失败", it) }
                .onSuccess {
                    _uiState.update { it.copy(tocExpanded = expanded) }
                    if (pdfOutlineState != null) {
                        pdfOutlineState = PdfOutlineListState(loaded.pdfOutline, expanded)
                    } else {
                        tocListState.setFullChapters(
                            chapters = loaded.chapters,
                            reverseOrder = loaded.book.getReverseToc(),
                            reverseDisplay = !loaded.book.isPdf && !loaded.book.isEpub && loaded.book.getReverseTocDisplay(),
                            resetCollapse = true,
                            defaultExpanded = expanded,
                            currentChapterIndex = loaded.book.durChapterIndex,
                            epubToc = loaded.epubOutline,
                        )
                    }
                    refreshVisibleItems()
                }
        }
    }

    fun toggleUseReplaceTitles() {
        val enabled = !uiState.value.useReplaceTitles
        repository.setUseReplaceInToc(enabled)
        _uiState.update { it.copy(useReplaceTitles = enabled) }
        refreshVisibleItems()
    }

    fun toggleCountWords() {
        val enabled = !uiState.value.countWords
        repository.setCountWordsInToc(enabled)
        _uiState.update { it.copy(countWords = enabled) }
    }

    fun toggleSplitLongChapters() {
        val loaded = content ?: return
        if (!loaded.book.isLocalTxt) return
        val enabled = !uiState.value.splitLongChapters
        viewModelScope.launch {
            runCatching { repository.setSplitLongChapter(loaded.book, enabled) }
                .onFailure {
                    AppLog.put("切换长章节拆分失败", it)
                    _uiState.update { state -> state.copy(message = "更新目录失败：${it.localizedMessage.orEmpty()}") }
                }
                .onSuccess {
                    requestedBookUrl = null
                    initialize(loaded.book.bookUrl)
                }
        }
    }

    fun selectChapter(chapter: BookChapter): BookTocSelection? {
        val book = content?.book ?: return null
        if (book.isVideo) {
            val volumes = content?.chapters.orEmpty().filter { it.isVolume }
            var volumeIndex = 0
            var chapterInVolumeIndex = chapter.index
            if (volumes.isNotEmpty()) {
                for ((reverseIndex, volume) in volumes.reversed().withIndex()) {
                    if (volume.index < chapter.index) {
                        chapterInVolumeIndex = chapter.index - volume.index - 1
                        volumeIndex = volumes.size - reverseIndex - 1
                        break
                    } else if (volume.index == chapter.index) {
                        volumeIndex = volumes.size - reverseIndex - 1
                        chapterInVolumeIndex = 0
                        break
                    }
                }
            }
            return BookTocSelection(
                chapterIndex = chapter.index,
                chapterChanged = chapter.index != book.durChapterIndex,
                volumeIndex = volumeIndex,
                chapterInVolumeIndex = chapterInVolumeIndex,
            )
        }
        return BookTocSelection(chapter.index, chapterChanged = chapter.index != book.durChapterIndex)
    }

    fun selectPdfOutline(node: PdfOutlineNode): BookTocSelection? {
        val book = content?.book ?: return null
        val page = node.pageIndex ?: return null
        val index = page / io.legado.app.model.localBook.PdfFile.PAGE_SIZE
        if (index !in 0 until book.totalChapterNum) return null
        return BookTocSelection(
            chapterIndex = index,
            chapterChanged = index != book.durChapterIndex,
            pdfPageIndex = page,
        )
    }

    fun selectBookmark(bookmark: Bookmark): BookTocSelection = BookTocSelection(
        chapterIndex = bookmark.chapterIndex,
        chapterPosition = bookmark.chapterPos,
        chapterChanged = bookmark.chapterIndex != content?.book?.durChapterIndex,
    )

    fun selectHighlight(highlight: BookHighlight): BookTocSelection? {
        val book = content?.book ?: return null
        if (!repository.supportsHighlightPosition(book)) return null
        val chapterIndex = resolveHighlightChapterIndex(highlight, runCatching {
            chapterIndexByUrl
        }.getOrDefault(emptyMap())) ?: return null
        return BookTocSelection(
            chapterIndex = chapterIndex,
            chapterPosition = highlight.chapterPos,
            chapterChanged = chapterIndex != book.durChapterIndex,
            highlightLayoutTitleLength = highlight.layoutTitleLength,
            highlightAnchorText = highlight.bookText.takeIf {
                highlight.chapterPosEnd - highlight.chapterPos == it.length
            }.orEmpty(),
        )
    }

    private var chapterIndexByUrl: Map<String, Int> = emptyMap()

    private fun refreshVisibleItems() {
        val loaded = content ?: return
        val query = uiState.value.query
        val pdfState = pdfOutlineState
        if (pdfState != null) {
            _uiState.update {
                it.copy(
                    pdfOutline = pdfState.items(query.takeIf(String::isNotBlank), loaded.book.getReverseToc()),
                    displayTitles = emptyMap(),
                )
            }
            return
        }
        if (query.isBlank()) {
            publishChapterItems(tocListState.showNormal(loaded.book.durChapterIndex))
            return
        }
        chapterSearchJob?.cancel()
        chapterSearchJob = viewModelScope.launch {
            val matches = if (!loaded.epubOutline.isNullOrEmpty()) {
                tocListState.searchIndexes(query)
            } else {
                repository.searchChapterIndexes(loaded.book, query)
            }
            publishChapterItems(tocListState.showSearch(matches, loaded.book.durChapterIndex))
        }
    }

    private fun publishChapterItems(items: List<TocListItem>) {
        val book = content?.book ?: return
        _uiState.update { it.copy(chapterItems = items, displayTitles = emptyMap()) }
        displayTitlesJob?.cancel()
        displayTitlesJob = viewModelScope.launch {
            val titles = runCatching { repository.displayTitles(book, items, uiState.value.useReplaceTitles) }
                .getOrElse { error ->
                    AppLog.put("计算目录显示标题失败", error)
                    items.associate { it.key to it.chapter.title }
                }
            _uiState.update { state -> state.copy(displayTitles = titles) }
        }
    }

    private fun observeBookmarks() {
        val book = content?.book ?: return
        bookmarksJob?.cancel()
        bookmarksJob = viewModelScope.launch {
            repository.observeBookmarks(book, uiState.value.query)
                .catch { AppLog.put("目录页面读取书签失败", it); emit(emptyList()) }
                .collect { bookmarks ->
                    val target = bookmarks.indexOfLast { it.chapterIndex < book.durChapterIndex }.coerceAtLeast(0)
                    _uiState.update { state -> state.copy(bookmarks = bookmarks, bookmarkScrollIndex = target) }
                }
        }
    }

    private fun observeTxtTocRules(openPickerOnFirstEmission: Boolean = false) {
        txtRulesJob = viewModelScope.launch {
            var shouldOpenPicker = openPickerOnFirstEmission
            repository.observeTxtTocRules()
                .catch { AppLog.put("目录页面读取 TXT 规则失败", it); emit(emptyList()) }
                .collect { rules ->
                    _uiState.update { it.copy(txtTocRules = rules) }
                    if (shouldOpenPicker) {
                        shouldOpenPicker = false
                        openTxtTocRulePicker()
                    }
                }
        }
    }

    private fun observeHighlights() {
        val book = content?.book ?: return
        if (!repository.supportsHighlightPosition(book)) {
            _uiState.update { it.copy(highlights = emptyList()) }
            return
        }
        highlightsJob?.cancel()
        highlightsJob = viewModelScope.launch {
            repository.observeHighlights(book, uiState.value.query)
                .catch { AppLog.put("目录页面读取标注失败", it); emit(emptyList()) }
                .collect { highlights ->
                    chapterIndexByUrl = withContext(IO) { repository.chapterIndexes(book.bookUrl) }
                    val ordered = highlights.sortedWith(
                        compareBy<BookHighlight>(
                            { resolveHighlightChapterIndex(it, chapterIndexByUrl) ?: Int.MAX_VALUE },
                            ::highlightBodyPosition,
                            BookHighlight::time,
                        )
                    )
                    val target = ordered.indexOfLast {
                        resolveHighlightChapterIndex(it, chapterIndexByUrl)
                            ?.let { chapterIndex -> chapterIndex < book.durChapterIndex } == true
                    }.coerceAtLeast(0)
                    _uiState.update { state -> state.copy(highlights = ordered, highlightScrollIndex = target) }
                }
        }
    }
}
