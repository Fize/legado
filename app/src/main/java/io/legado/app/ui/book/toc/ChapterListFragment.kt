package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceBook
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.EpubTocNode
import io.legado.app.model.localBook.PdfFile
import io.legado.app.model.localBook.PdfOutline
import io.legado.app.model.localBook.PdfOutlineNode
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ChapterListFragment : VMBaseFragment<TocViewModel>(),
    TocViewModel.ChapterListCallBack {

    override val viewModel by activityViewModels<TocViewModel>()

    private val tocListState = TocListState()
    private var pdfOutlineState: PdfOutlineListState? = null
    private var pdfOutlineLoading = false
    private var epubToc: List<EpubTocNode>? = null
    private var epubTocLoading = false

    val cacheFileNames = hashSetOf<String>()
    val audioCacheKeys = hashSetOf<AudioCacheKey>()

    @Volatile
    private var displayTitleMap = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var upDisplayTitleJob: Coroutine<*>? = null
    private var released = false

    private var durChapterIndex by mutableIntStateOf(0)
    private var currentChapterInfoText by mutableStateOf("")
    private var chapterItemsState by mutableStateOf<List<TocListItem>>(emptyList())
    private var pdfOutlineRowsState by mutableStateOf<List<PdfOutlineRow>>(emptyList())
    private var showPdfOutlineMode by mutableStateOf(false)
    private var cacheRenderVersion by mutableIntStateOf(0)
    private var scrollRequestIndex by mutableIntStateOf(-1)
    private var scrollRequestNonce by mutableLongStateOf(0L)
    private var currentFirstVisiblePosition = 0

    private var chapterList: List<BookChapter> = emptyList()
    private var currentSearchKey: String? = null
    private var chapterListJob: Job? = null
    private var cacheFileJob: Job? = null
    private var audioCacheStateReady = false
    private val pendingAudioCacheChanges = linkedMapOf<AudioCacheKey, Boolean>()
    private var pendingScrollItemKey: String? = null
    private var pendingChapterScroll: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ChapterListScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        released = false
        viewModel.chapterListCallBack = this@ChapterListFragment
        viewModel.bookData.observe(viewLifecycleOwner) {
            initBook(it)
        }
    }

    override fun onDestroyView() {
        chapterListJob?.cancel()
        cacheFileJob?.cancel()
        pdfOutlineLoading = false
        epubTocLoading = false
        pendingScrollItemKey = null
        pendingChapterScroll = null
        released = true
        upDisplayTitleJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        viewModel.chapterListCallBack = clearCallbackIfOwned(
            viewModel.chapterListCallBack,
            this,
        )
        super.onDestroyView()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        chapterListJob?.cancel()
        cacheFileJob?.cancel()
        pdfOutlineState = null
        pdfOutlineLoading = book.isPdf
        epubToc = null
        epubTocLoading = book.isEpub
        showPdfOutlineMode = false
        durChapterIndex = book.durChapterIndex
        currentChapterInfoText =
            "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
        cacheFileNames.clear()
        audioCacheKeys.clear()
        audioCacheStateReady = !book.isAudio
        pendingAudioCacheChanges.clear()
        tocListState.clear()
        chapterList = emptyList()
        clearDisplayTitle()
        setChapterItems(emptyList())
        val normalizedSearchKey = viewModel.searchKey?.takeIf { it.isNotBlank() }
        currentSearchKey = normalizedSearchKey
        pendingScrollItemKey = null
        pendingChapterScroll = null
        chapterListJob = viewLifecycleOwner.lifecycleScope.launch {
            if (book.isEpub) {
                epubToc = try {
                    withContext(IO) { EpubFile.getToc(book) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("读取 EPUB 目录失败", e)
                    null
                }
                epubTocLoading = false
            }
            if (book.isPdf) {
                val outline = try {
                    withContext(IO) { PdfOutline.read(book) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("读取 PDF 目录失败", e)
                    emptyList()
                }
                pdfOutlineLoading = false
                if (outline.any { it.pageIndex != null }) {
                    pdfOutlineState = PdfOutlineListState(outline, book.getTocExpanded())
                    showPdfOutlineMode = true
                    showPdfOutline()
                    return@launch
                }
            }
            val chapters = queryChapterList(book)
            chapterList = chapters
            tocListState.setFullChapters(
                chapters = chapters,
                reverseOrder = book.getReverseToc(),
                reverseDisplay = !book.isPdf && !book.isEpub && book.getReverseTocDisplay(),
                resetCollapse = true,
                defaultExpanded = book.getTocExpanded(),
                currentChapterIndex = durChapterIndex,
                epubToc = epubToc,
            )
            val searchKey = currentSearchKey
            setChapterItems(
                if (searchKey == null) {
                    tocListState.showNormal(durChapterIndex)
                } else {
                    tocListState.showSearch(
                        searchResultIndexes = queryChapterIndexes(book, searchKey),
                        currentChapterIndex = durChapterIndex,
                    )
                }
            )
        }
        cacheFileJob = viewLifecycleOwner.lifecycleScope.launch {
            if (book.isPdf) return@launch
            if (book.isAudio) {
                var treeUri = AppConfig.audioCacheTreeUri
                var cachedKeys: Set<AudioCacheKey>
                while (true) {
                    cachedKeys = withContext(IO) {
                        runCatching {
                            AudioCacheManager.listCachedChapterKeys(
                                treeUri,
                                book.bookUrl,
                            )
                        }.getOrDefault(emptySet())
                    }
                    if (viewModel.bookData.value?.bookUrl != book.bookUrl) return@launch
                    val currentTreeUri = AppConfig.audioCacheTreeUri
                    if (treeUri == currentTreeUri) break
                    pendingAudioCacheChanges.clear()
                    treeUri = currentTreeUri
                }
                audioCacheKeys.addAll(cachedKeys)
                pendingAudioCacheChanges.forEach { (key, cached) ->
                    if (cached) audioCacheKeys.add(key)
                    else audioCacheKeys.remove(key)
                }
                pendingAudioCacheChanges.clear()
                audioCacheStateReady = true
            } else {
                cacheFileNames.addAll(withContext(IO) { BookHelp.getChapterFiles(book) })
            }
            cacheRenderVersion++
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (viewModel.bookData.value?.isAudio != true && book.bookUrl == bookUrl) {
                    cacheFileNames.add(chapter.getFileName())
                    notifyVisibleChapterChanged(chapter.index)
                }
            }
        }
        observeEvent<AudioCacheStateChanged>(EventBus.AUDIO_CACHE_CHANGED) { event ->
            val currentBook = viewModel.bookData.value ?: return@observeEvent
            if (!currentBook.isAudio || currentBook.bookUrl != event.bookUrl) return@observeEvent
            if (event.treeUri != AppConfig.audioCacheTreeUri) return@observeEvent
            if (!audioCacheStateReady) {
                pendingAudioCacheChanges[event.key] = event.cached
            } else {
                if (event.cached) audioCacheKeys.add(event.key)
                else audioCacheKeys.remove(event.key)
                val position = findVisiblePositionByAudioCacheKey(event.key)
                if (position >= 0) cacheRenderVersion++
            }
        }
    }

    override fun upChapterList(
        searchKey: String?,
        resetCollapse: Boolean,
        replaceAll: Boolean,
    ) {
        currentSearchKey = searchKey?.takeIf { it.isNotBlank() }
        // Keep parsing the document when the user searches before the outline is ready.
        if (pdfOutlineLoading || epubTocLoading) return
        pdfOutlineState?.let { state ->
            if (resetCollapse) state.setExpanded(book?.getTocExpanded() != false)
            showPdfOutline()
            return
        }
        chapterListJob?.cancel()
        if (replaceAll) clearDisplayTitle()
        chapterListJob = viewLifecycleOwner.lifecycleScope.launch {
            val normalizedSearchKey = searchKey?.takeIf { it.isNotBlank() }
            currentSearchKey = normalizedSearchKey
            pendingScrollItemKey = null
            pendingChapterScroll = null
            val currentBook = book ?: return@launch
            val reverseOrder = currentBook.getReverseToc()
            if (normalizedSearchKey == null) {
                if (resetCollapse || !tocListState.hasFullChapters()) {
                    val chapters = queryChapterList(currentBook)
                    chapterList = chapters
                    tocListState.setFullChapters(
                        chapters = chapters,
                        reverseOrder = reverseOrder,
                        reverseDisplay = !currentBook.isPdf && !currentBook.isEpub && currentBook.getReverseTocDisplay(),
                        resetCollapse = resetCollapse,
                        defaultExpanded = currentBook.getTocExpanded(),
                        currentChapterIndex = durChapterIndex,
                        epubToc = epubToc,
                    )
                }
                submitChapterItems(
                    tocListState.showNormal(durChapterIndex),
                    replaceAll,
                )
            } else {
                delay(150)
                if (resetCollapse || !tocListState.hasFullChapters()) {
                    val chapters = queryChapterList(currentBook)
                    chapterList = chapters
                    tocListState.setFullChapters(
                        chapters = chapters,
                        reverseOrder = reverseOrder,
                        reverseDisplay = !currentBook.isPdf && !currentBook.isEpub && currentBook.getReverseTocDisplay(),
                        resetCollapse = resetCollapse,
                        defaultExpanded = currentBook.getTocExpanded(),
                        currentChapterIndex = durChapterIndex,
                        epubToc = epubToc,
                    )
                }
                submitChapterItems(
                    tocListState.showSearch(
                        searchResultIndexes = queryChapterIndexes(
                            currentBook,
                            normalizedSearchKey,
                        ),
                        currentChapterIndex = durChapterIndex,
                    ),
                    replaceAll,
                )
            }
        }
    }

    private fun submitChapterItems(items: List<TocListItem>, replaceAll: Boolean) {
        if (replaceAll) {
            setChapterItemsNoDiff(items)
        } else {
            setChapterItems(items)
        }
    }

    private fun setChapterItems(items: List<TocListItem>) {
        chapterItemsState = items
        onListChanged()
        onItemsUpdated()
    }

    private fun setChapterItemsNoDiff(items: List<TocListItem>) {
        chapterItemsState = items
        cacheRenderVersion++
        onListChanged()
        onItemsUpdated()
    }

    private suspend fun queryChapterList(book: Book): List<BookChapter> {
        return withContext(IO) {
            val end = book.simulatedTotalChapterNum() - 1
            appDb.bookChapterDao.getChapterList(book.bookUrl, 0, end).let { chapters ->
                if (book.isPdf && book.getReverseToc()) chapters.asReversed() else chapters
            }
        }
    }

    private suspend fun queryChapterIndexes(book: Book, searchKey: String): List<Int> {
        if (!epubToc.isNullOrEmpty()) return tocListState.searchIndexes(searchKey)
        return withContext(IO) {
            val end = book.simulatedTotalChapterNum() - 1
            appDb.bookChapterDao.searchIndexes(book.bookUrl, searchKey, 0, end)
        }
    }

    private fun notifyVisibleChapterChanged(chapterIndex: Int) {
        val position = findVisiblePositionByChapterIndex(chapterIndex)
        if (position >= 0) {
            cacheRenderVersion++
        }
    }

    private fun requestScrollToPosition(position: Int) {
        if (position >= 0) {
            scrollRequestIndex = position
            scrollRequestNonce++
        }
    }

    private fun scrollToChapterIndex(chapterIndex: Int, expandVolume: Boolean) {
        pdfOutlineState?.let { state ->
            if (expandVolume) state.setExpanded(true)
            showPdfOutline()
            val rows = state.items(currentSearchKey, book?.getReverseToc() == true)
            val closest = rows.withIndex().filter { it.value.node.pageIndex != null }
                .minByOrNull { kotlin.math.abs(it.value.node.pageIndex!! - chapterIndex * PdfFile.PAGE_SIZE) }
            requestScrollToPosition(closest?.index ?: 0)
            return
        }
        if (expandVolume && currentSearchKey == null &&
            tocListState.expandVolumeContainingChapter(chapterIndex)
        ) {
            pendingChapterScroll = chapterIndex
            setChapterItems(tocListState.showNormal(durChapterIndex))
            return
        }
        scrollToResolvedChapterPosition(chapterIndex)
    }

    private fun scrollToResolvedChapterPosition(chapterIndex: Int) {
        val position = tocListState.findFallbackVisiblePositionForChapterIndex(chapterIndex)
        if (position >= 0) {
            requestScrollToPosition(position)
            upDisplayTitles(position)
        }
    }

    private fun onListChanged() {
        if (pdfOutlineState != null) return
        if (pendingScrollItemKey != null || pendingChapterScroll != null) return
        viewLifecycleOwner.lifecycleScope.launch(Main) {
            if (pdfOutlineState != null) return@launch
            val scrollPosition = if (currentSearchKey == null) {
                tocListState.findFallbackVisiblePositionForChapterIndex(durChapterIndex)
                    .coerceAtLeast(0)
            } else {
                0
            }
            requestScrollToPosition(scrollPosition)
            upDisplayTitles(scrollPosition)
        }
    }

    private fun onVolumeToggled(volumeIndex: Int) {
        if (currentSearchKey != null) return
        val firstVisibleItem = chapterItemsState.getOrNull(currentFirstVisiblePosition)
        pendingScrollItemKey = if (
            firstVisibleItem != null &&
            tocListState.isDescendantOf(firstVisibleItem.chapter.index, volumeIndex) &&
            !tocListState.isVolumeCollapsed(volumeIndex)
        ) {
            "volume:$volumeIndex"
        } else {
            firstVisibleItem?.key
        }
        if (tocListState.toggleVolume(volumeIndex)) {
            setChapterItems(tocListState.showNormal(durChapterIndex))
        } else {
            pendingScrollItemKey = null
        }
    }

    private fun onItemsUpdated() {
        if (pdfOutlineState != null) return
        pendingChapterScroll?.let { chapterIndex ->
            pendingChapterScroll = null
            scrollToResolvedChapterPosition(chapterIndex)
            return
        }
        val anchorKey = pendingScrollItemKey ?: return
        pendingScrollItemKey = null
        val position = findVisiblePositionByItemKey(anchorKey)
        if (position >= 0) {
            requestScrollToPosition(position)
            upDisplayTitles(position)
        } else {
            upDisplayTitles(currentFirstVisiblePosition)
        }
    }

    override fun clearDisplayTitle() {
        upDisplayTitleJob?.cancel()
        displayTitleMap = ConcurrentHashMap()
        cacheRenderVersion++
        upDisplayTitles(currentFirstVisiblePosition)
    }

    fun upDisplayTitles(startIndex: Int) {
        if (released) return
        upDisplayTitleJob?.cancel()
        val displayTitleMap = displayTitleMap
        upDisplayTitleJob = Coroutine.async(scope) {
            val book = book ?: return@async
            val items = chapterItemsState
            if (items.isEmpty()) return@async
            val safeStartIndex = startIndex.coerceIn(0, items.lastIndex)
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val replaceBook = book.toReplaceBook()
            val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
            launch {
                for (i in safeStartIndex until items.size) {
                    updateDisplayTitle(
                        items, i, replaceRules, useReplace, replaceBook, displayTitleMap
                    )
                }
            }
            launch {
                for (i in safeStartIndex - 1 downTo 0) {
                    updateDisplayTitle(
                        items, i, replaceRules, useReplace, replaceBook, displayTitleMap
                    )
                }
            }
        }
    }

    private suspend fun updateDisplayTitle(
        items: List<TocListItem>,
        index: Int,
        replaceRules: List<ReplaceRule>,
        useReplace: Boolean,
        replaceBook: ReplaceBook,
        displayTitleMap: ConcurrentHashMap<String, String>,
    ) {
        val item = items[index]
        val chapter = item.readingChapter?.let { reading ->
            if (reading.index == item.chapter.index) item.chapter
            else item.chapter.copy(index = reading.index)
        } ?: item.chapter
        if (displayTitleMap[item.key] != null) return
        currentCoroutineContext().ensureActive()
        val displayTitle = chapter.getDisplayTitle(
            replaceRules,
            useReplace,
            replaceBook = replaceBook,
        )
        currentCoroutineContext().ensureActive()
        displayTitleMap[item.key] = displayTitle
        handler.post {
            if (displayTitleMap === this.displayTitleMap &&
                !released && chapterItemsState.getOrNull(index)?.key == item.key
            ) {
                cacheRenderVersion++
            }
        }
    }

    private fun getDisplayTitle(item: TocListItem): String {
        return displayTitleMap[item.key] ?: item.chapter.title
    }

    fun findVisiblePositionByChapterIndex(chapterIndex: Int): Int {
        return chapterItemsState.indexOfFirst {
            it is TocListItem.Chapter && it.readingChapter?.index == chapterIndex
        }
    }

    fun findVisiblePositionByAudioCacheKey(key: AudioCacheKey): Int {
        return chapterItemsState.indexOfFirst {
            it is TocListItem.Chapter && AudioCacheKey.from(it.chapter) == key
        }
    }

    fun findVisiblePositionByItemKey(key: String): Int {
        return chapterItemsState.indexOfFirst { it.key == key }
    }

    override fun upAdapter() {
        if (pdfOutlineState != null) showPdfOutline()
        else cacheRenderVersion++
    }

    private fun showPdfOutline() {
        pdfOutlineState?.let { state ->
            pdfOutlineRowsState = state.items(currentSearchKey, book?.getReverseToc() == true)
        }
    }

    private fun openPdfOutline(node: PdfOutlineNode) {
        val page = node.pageIndex ?: return
        val currentBook = book ?: return
        // The outline is navigation only; retain the persisted ten-page reading segments.
        val index = page / PdfFile.PAGE_SIZE
        if (index !in 0 until currentBook.totalChapterNum) return
        activity?.run {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra("index", index)
                    .putExtra(TocActivityResult.EXTRA_PDF_PAGE_INDEX, page)
                    .putExtra("chapterChanged", index != durChapterIndex)
            )
            finish()
        }
    }

    val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    val book: Book?
        get() = viewModel.bookData.value

    val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    val isAudioBook: Boolean
        get() = viewModel.bookData.value?.isAudio == true

    val isAudioCacheStateReady: Boolean
        get() = audioCacheStateReady

    fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            if (book?.isVideo == true) {
                val volumes = chapterList.filterTo(arrayListOf()) { it.isVolume }
                var chapterInVolumeIndex = 0
                var durVolumeIndex = 0
                if (volumes.isNotEmpty()) {
                    for ((index, volume) in volumes.reversed().withIndex()) {
                        val chapterIndex = bookChapter.index
                        if (volume.index < chapterIndex) {
                            chapterInVolumeIndex = chapterIndex - volume.index - 1
                            durVolumeIndex = volumes.size - index - 1
                            break
                        } else if (volume.index == chapterIndex) {
                            durVolumeIndex = volumes.size - index - 1
                            break
                        }
                    }
                } else {
                    chapterInVolumeIndex = bookChapter.index
                }
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra("index", bookChapter.index)
                        .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
                        .putExtra("durVolumeIndex", durVolumeIndex)
                        .putExtra("chapterInVolumeIndex", chapterInVolumeIndex),
                )
                finish()
                return@run
            }
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex),
            )
            finish()
        }
    }

    private fun volumeSummary(item: TocListItem.Volume): String {
        val context = requireContext()
        val summary = if (item.matchedCount == null) {
            context.getString(R.string.all_chapter_num, item.chapterCount)
        } else if (item.matchedSelf && item.matchedCount == 0) {
            context.getString(R.string.toc_volume_title_match)
        } else {
            context.getString(
                R.string.toc_search_match_count,
                item.matchedCount,
                item.chapterCount,
            )
        }
        return item.chapter.tag
            ?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.toc_volume_tag_summary, it, summary) }
            ?: summary
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ChapterListScreen() {
        val listState = rememberLazyListState()
        val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
        LaunchedEffect(firstVisible) {
            currentFirstVisiblePosition = firstVisible
        }
        LaunchedEffect(scrollRequestNonce) {
            if (scrollRequestNonce > 0L && scrollRequestIndex >= 0) {
                val totalCount = if (showPdfOutlineMode) pdfOutlineRowsState.size else chapterItemsState.size
                if (totalCount > 0) {
                    listState.scrollToItem(scrollRequestIndex.coerceIn(0, totalCount - 1))
                }
            }
        }

        // Read cacheRenderVersion so Compose recomposes when display titles or cache states update
        val version = cacheRenderVersion

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (showPdfOutlineMode) {
                    itemsIndexed(
                        items = pdfOutlineRowsState,
                        key = { _, item -> item.node.id },
                    ) { _, item ->
                        PdfOutlineRowItem(
                            item = item,
                            onOpen = ::openPdfOutline,
                            onToggle = { id ->
                                pdfOutlineState?.toggle(id)
                                showPdfOutline()
                            },
                        )
                        HorizontalDivider()
                    }
                } else {
                    itemsIndexed(
                        items = chapterItemsState,
                        key = { _, item -> item.key },
                    ) { _, item ->
                        val displayTitle = remember(item.key, item.chapter.title, version) {
                            getDisplayTitle(item)
                        }
                        val isVolume = item is TocListItem.Volume
                        val isCurrentChapter = durChapterIndex == item.readingChapter?.index
                        val isCached = remember(item.key, version, isLocalBook, isAudioBook, isAudioCacheStateReady) {
                            isLocalBook || isVolume ||
                                if (isAudioBook) {
                                    !isAudioCacheStateReady || audioCacheKeys.contains(AudioCacheKey.from(item.chapter))
                                } else {
                                    cacheFileNames.contains(item.chapter.getFileName())
                                }
                        }
                        TocChapterRowItem(
                            item = item,
                            displayTitle = displayTitle,
                            isCurrentChapter = isCurrentChapter,
                            isCached = isCached,
                        )
                        HorizontalDivider()
                    }
                }
            }

            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentChapterInfoText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                scrollToChapterIndex(durChapterIndex, expandVolume = true)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    IconButton(
                        onClick = { requestScrollToPosition(0) },
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.go_to_top),
                        )
                    }
                    IconButton(
                        onClick = {
                            val count = if (showPdfOutlineMode) pdfOutlineRowsState.size else chapterItemsState.size
                            if (count > 0) {
                                requestScrollToPosition(count - 1)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.go_to_bottom),
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TocChapterRowItem(
        item: TocListItem,
        displayTitle: String,
        isCurrentChapter: Boolean,
        isCached: Boolean,
    ) {
        val chapter = item.chapter
        val isVolume = item is TocListItem.Volume
        val isCurrentGroup = isCurrentChapter || (item is TocListItem.Volume && item.containsCurrentChapter)
        val startPadding = (12 + item.depth * 10).dp

        val bgColor = if (isVolume) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .combinedClickable(
                    onClick = {
                        val readingChapter = item.readingChapter
                        if (readingChapter != null) openChapter(readingChapter)
                        else if (item is TocListItem.Volume && item.canToggle) onVolumeToggled(item.chapter.index)
                    },
                    onLongClick = {
                        requireContext().longToastOnUi(displayTitle)
                    },
                )
                .padding(start = startPadding, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isVolume || isCurrentGroup) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrentGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                val subMeta = buildList {
                    if (item is TocListItem.Volume) {
                        add(volumeSummary(item))
                    } else {
                        if (!chapter.tag.isNullOrEmpty()) add(chapter.tag!!)
                        if (AppConfig.tocCountWords && !chapter.wordCount.isNullOrEmpty()) add(chapter.wordCount!!)
                    }
                }
                if (subMeta.isNotEmpty()) {
                    Text(
                        text = subMeta.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (chapter.isVip && !chapter.isPay && !isVolume) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (item is TocListItem.Volume) {
                if (item.canToggle) {
                    IconButton(
                        onClick = { onVolumeToggled(item.chapter.index) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (item.collapsed) {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = stringResource(
                                if (item.collapsed) R.string.toc_expand_volume else R.string.toc_collapse_volume
                            ),
                        )
                    }
                }
            } else {
                if (isCurrentChapter) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else if (!isCached) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PdfOutlineRowItem(
        item: PdfOutlineRow,
        onOpen: (PdfOutlineNode) -> Unit,
        onToggle: (Int) -> Unit,
    ) {
        val node = item.node
        val titleText = node.title.ifBlank { stringResource(R.string.pdf_outline_untitled) }
        val startPadding = (12 + minOf(node.depth, 8) * 10).dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (node.pageIndex != null) onOpen(node)
                        else if (item.canToggle) onToggle(node.id)
                    },
                    onLongClick = {
                        requireContext().longToastOnUi(titleText)
                    },
                )
                .padding(start = startPadding, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                node.pageIndex?.let { pageIdx ->
                    Text(
                        text = stringResource(R.string.pdf_outline_page, pageIdx + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.canToggle) {
                IconButton(
                    onClick = { onToggle(node.id) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (item.collapsed) {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = stringResource(
                            if (item.collapsed) R.string.pdf_outline_expand else R.string.pdf_outline_collapse
                        ),
                    )
                }
            }
        }
    }
}
