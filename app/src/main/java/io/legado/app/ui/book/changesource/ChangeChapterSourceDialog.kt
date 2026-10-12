package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 单章/批量章节换源界面 (Compose)
 */
class ChangeChapterSourceDialog() : BaseDialogFragment() {

    constructor(
        name: String,
        author: String,
        chapterIndex: Int,
        chapterTitle: String,
        batchMode: Boolean = false,
    ) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
            putInt("chapterIndex", chapterIndex)
            putString("chapterTitle", chapterTitle)
            putBoolean("batchMode", batchMode)
        }
    }

    private val batchMode: Boolean get() = arguments?.getBoolean("batchMode") == true
    private val groups = mutableStateListOf<String>()
    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeChapterSourceViewModel by viewModels()
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            viewModel.startSearch()
        }

    private var searchFinishDialog: AlertDialog? = null
    private var targetBook: Book? = null
    private var tocLoading by mutableStateOf(false)
    private var contentLoading by mutableStateOf(false)
    private var batchCaching by mutableStateOf(false)

    private var searchItems by mutableStateOf<List<SearchBook>>(emptyList())
    private var tocItems by mutableStateOf<List<BookChapter>>(emptyList())
    private var selectedChapterIndices by mutableStateOf<Set<Int>>(emptySet())
    private var durChapterIndex by mutableIntStateOf(0)
    private var tocVisible by mutableStateOf(false)
    private var selectionEnabled by mutableStateOf(true)
    private var isSearching by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var currentTitle by mutableStateOf("")
    private var currentSubtitle by mutableStateOf<String?>(null)
    private var currentBookUrl by mutableStateOf<String?>(null)
    private var scoreRevision by mutableIntStateOf(0)
    private var isAutomationActiveState by mutableStateOf(false)
    private var hasOriginalChapterState by mutableStateOf(false)
    private var canSelectState by mutableStateOf(true)

    private var scrollSourceTargetIndex by mutableStateOf<Int?>(null)
    private var scrollSourceTrigger by mutableIntStateOf(0)
    private var scrollTocTargetIndex by mutableStateOf<Int?>(null)
    private var scrollTocTrigger by mutableIntStateOf(0)

    private var showWordCountFilterDialog by mutableStateOf(false)
    private var automationRangeDefault by mutableStateOf<IntRange?>(null)

    private var checkAuthorState by mutableStateOf(AppConfig.changeSourceCheckAuthor)
    private var loadInfoState by mutableStateOf(AppConfig.changeSourceLoadInfo)
    private var loadTocState by mutableStateOf(AppConfig.changeSourceLoadToc)
    private var loadWordCountState by mutableStateOf(AppConfig.changeSourceLoadWordCount)
    private var sortRespondTimeState by mutableStateOf(AppConfig.changeSourceSortRespondTime)
    private var wordCountFilterModeState by mutableIntStateOf(AppConfig.changeSourceWordCountFilterMode)
    private var selectedGroupState by mutableStateOf(AppConfig.searchGroup)

    private val selectedChapters: List<Pair<BookChapter, String?>>
        get() = selectedChapterSourceItems(tocItems, selectedChapterIndices)

    private val selectedPositions: List<Int>
        get() = tocItems.mapIndexedNotNull { position, chapter ->
            position.takeIf { chapter.index in selectedChapterIndices }
        }

    private val selectionCount: Int
        get() = selectedChapterIndices.size

    private val lastSelectedPosition: Int
        get() = tocItems.indexOfLast { it.index in selectedChapterIndices }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val context = LocalContext.current
                    val accent = remember(context) { Color(context.accentColor) }
                    val sourceListState = rememberLazyListState()
                    val tocListState = rememberLazyListState()
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(scrollSourceTrigger) {
                        val index = scrollSourceTargetIndex ?: return@LaunchedEffect
                        if (index in searchItems.indices) {
                            sourceListState.scrollToItem(index)
                        }
                    }

                    LaunchedEffect(scrollTocTrigger) {
                        val index = scrollTocTargetIndex ?: return@LaunchedEffect
                        if (tocItems.isNotEmpty()) {
                            tocListState.scrollToItem(index.coerceIn(0, tocItems.lastIndex))
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(R.color.background))
                    ) {
                        ChangeSourceTopBar(
                            title = currentTitle,
                            subtitle = currentSubtitle,
                            showNavigationIcon = false,
                            onBack = { dismissAllowingStateLoss() },
                            isSearching = isSearching,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { query ->
                                searchQuery = query
                                viewModel.screen(query)
                            },
                            onStartStopClick = { viewModel.startOrStopSearch() },
                            onSourceManageClick = { startActivity<BookSourceActivity>() },
                            showAutomation = batchMode,
                            isAutomationActive = isAutomationActiveState,
                            onAutomationClick = {
                                if (viewModel.isAutomationActive) {
                                    viewModel.stopAutomation()
                                } else {
                                    showAutomationRangeDialog()
                                }
                            },
                            checkAuthor = checkAuthorState,
                            onCheckAuthorChange = { checked ->
                                AppConfig.changeSourceCheckAuthor = checked
                                checkAuthorState = checked
                                viewModel.refresh()
                            },
                            loadInfo = loadInfoState,
                            onLoadInfoChange = { checked ->
                                AppConfig.changeSourceLoadInfo = checked
                                loadInfoState = checked
                            },
                            loadToc = loadTocState,
                            onLoadTocChange = { checked ->
                                AppConfig.changeSourceLoadToc = checked
                                loadTocState = checked
                            },
                            loadWordCount = loadWordCountState,
                            onLoadWordCountChange = { checked ->
                                AppConfig.changeSourceLoadWordCount = checked
                                syncChangeSourceResultOptions()
                                viewModel.onLoadWordCountChecked()
                            },
                            sortRespondTime = sortRespondTimeState,
                            onSortRespondTimeChange = { enabled ->
                                AppConfig.changeSourceSortRespondTime = enabled
                                syncChangeSourceResultOptions()
                                viewModel.onResultOptionsChanged(enabled)
                            },
                            wordCountFilterEnabled = wordCountFilterModeState != ChangeSourceResultOptions.FILTER_OFF,
                            onWordCountFilterClick = { showWordCountFilterDialog = true },
                            groups = groups,
                            selectedGroup = selectedGroupState,
                            onGroupSelect = { group ->
                                AppConfig.searchGroup = group
                                selectedGroupState = group
                                lifecycleScope.launch(IO) {
                                    viewModel.stopSearch()
                                    if (viewModel.refresh()) {
                                        viewModel.startSearch()
                                    }
                                }
                            }
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = sourceListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    itemsIndexed(
                                        items = searchItems,
                                        key = { _, item -> item.bookUrl }
                                    ) { _, item ->
                                        val isCurrent = currentBookUrl == item.bookUrl
                                        val score = scoreRevision.let { getBookScore(item) }
                                        ChangeSourceItemRow(
                                            item = item,
                                            isCurrent = isCurrent,
                                            showSelectedHighlight = false,
                                            score = score,
                                            loadWordCount = loadWordCountState,
                                            onClick = { openToc(item) },
                                            onScoreChange = { newScore ->
                                                setBookScore(item, newScore)
                                            },
                                            onTopSource = { topSource(item) },
                                            onBottomSource = { bottomSource(item) },
                                            onEditSource = { editSource(item) },
                                            onDisableSource = { disableSource(item) },
                                            onDeleteSource = { deleteSource(item) }
                                        )
                                    }
                                }

                                ChangeSourceBottomBar(
                                    statusText = callBack?.oldBook?.originName.orEmpty(),
                                    middleEllipsis = true,
                                    onDurClick = { scrollToDurSource() },
                                    onScrollTop = {
                                        if (searchItems.isNotEmpty()) {
                                            coroutineScope.launch {
                                                sourceListState.scrollToItem(0)
                                            }
                                        }
                                    },
                                    onScrollBottom = {
                                        if (searchItems.isNotEmpty()) {
                                            coroutineScope.launch {
                                                sourceListState.scrollToItem(searchItems.lastIndex)
                                            }
                                        }
                                    }
                                )
                            }

                            if (tocVisible) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(colorResource(R.color.background))
                                ) {
                                    val busy = tocLoading || batchCaching
                                    val hideEnabled = !(batchMode && (busy || isAutomationActiveState))
                                    Surface(
                                        shadowElevation = 4.dp,
                                        color = colorResource(R.color.background),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .clickable(enabled = hideEnabled) {
                                                    tocVisible = false
                                                    viewModel.clearToc()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_arrow_down),
                                                contentDescription = stringResource(R.string.close),
                                                tint = colorResource(R.color.primaryText)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    ) {
                                        LazyColumn(
                                            state = tocListState,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            itemsIndexed(
                                                items = tocItems,
                                                key = { index, chapter -> "${chapter.index}_$index" }
                                            ) { position, chapter ->
                                                val isDur = durChapterIndex == chapter.index
                                                val isSelected = batchMode && chapter.index in selectedChapterIndices
                                                val rowClickable = !batchMode || (!chapter.isVolume && selectionEnabled)
                                                val rowBg = if (chapter.isVolume) {
                                                    colorResource(R.color.btn_bg_press)
                                                } else {
                                                    Color.Transparent
                                                }
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(rowBg)
                                                        .then(
                                                            if (chapter.isVolume) {
                                                                Modifier.semantics { heading() }
                                                            } else {
                                                                Modifier
                                                            }
                                                        )
                                                        .clickable(enabled = rowClickable) {
                                                            if (batchMode) {
                                                                if (chapter.isVolume || !selectionEnabled) return@clickable
                                                                selectedChapterIndices = if (chapter.index in selectedChapterIndices) {
                                                                    selectedChapterIndices - chapter.index
                                                                } else {
                                                                    selectedChapterIndices + chapter.index
                                                                }
                                                                selectionChanged()
                                                            } else {
                                                                clickChapter(
                                                                    chapter,
                                                                    tocItems.getOrNull(position + 1)?.url
                                                                )
                                                            }
                                                        }
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = chapter.title,
                                                            color = if (isDur) accent else colorResource(R.color.primaryText),
                                                            fontSize = 14.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (!chapter.tag.isNullOrEmpty() && !chapter.isVolume) {
                                                            Text(
                                                                text = chapter.tag.orEmpty(),
                                                                color = colorResource(R.color.secondaryText),
                                                                fontSize = 12.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    if (!batchMode && isDur) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_check),
                                                            contentDescription = null,
                                                            tint = accent,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    } else if (batchMode && !chapter.isVolume) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Checkbox(
                                                            checked = isSelected,
                                                            onCheckedChange = null
                                                        )
                                                    }
                                                }
                                                HorizontalDivider(
                                                    thickness = 0.5.dp,
                                                    color = colorResource(R.color.divider)
                                                )
                                            }
                                        }

                                        if (tocLoading || contentLoading || batchCaching) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .align(Alignment.Center)
                                            )
                                        }
                                    }

                                    if (batchMode) {
                                        val busyActions = tocLoading || batchCaching
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(colorResource(R.color.background))
                                                .padding(horizontal = 4.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    if (viewModel.isAutomationActive) {
                                                        if (viewModel.skipAutomationChapter() &&
                                                            viewModel.automationState.value is ChapterSourceAutomationState.Finished
                                                        ) {
                                                            toastOnUi(R.string.chapter_source_finished)
                                                            dismissAllowingStateLoss()
                                                        }
                                                    } else {
                                                        viewModel.currentOriginalChapter?.let(::advanceOriginalChapter)
                                                    }
                                                },
                                                enabled = hasOriginalChapterState && !busyActions,
                                                border = BorderStroke(1.dp, accent),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                            ) {
                                                Text(stringResource(R.string.chapter_source_skip))
                                            }

                                            OutlinedButton(
                                                onClick = { cacheSelectedChapters() },
                                                enabled = hasOriginalChapterState &&
                                                        selectedChapterIndices.isNotEmpty() &&
                                                        !busyActions &&
                                                        canSelectState,
                                                border = BorderStroke(1.dp, accent),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                            ) {
                                                Text(stringResource(R.string.chapter_source_cache_next))
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.stopAutomation()
                                                    dismissAllowingStateLoss()
                                                },
                                                enabled = !batchCaching,
                                                border = BorderStroke(1.dp, accent),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                            ) {
                                                Text(stringResource(R.string.complete))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showWordCountFilterDialog) {
                        ChangeSourceWordCountFilterDialog(
                            onDismiss = { showWordCountFilterDialog = false },
                            onChanged = { reload ->
                                syncChangeSourceResultOptions()
                                viewModel.onResultOptionsChanged(reload)
                            }
                        )
                    }

                    automationRangeDefault?.let { range ->
                        var startText by remember(range) { mutableStateOf(range.first.toString()) }
                        var endText by remember(range) { mutableStateOf(range.last.toString()) }
                        AlertDialog(
                            onDismissRequest = { automationRangeDefault = null },
                            title = { Text(stringResource(R.string.chapter_source_automation)) },
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = startText,
                                        onValueChange = { startText = it },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("-")
                                    OutlinedTextField(
                                        value = endText,
                                        onValueChange = { endText = it },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val tocState = viewModel.tocState.value as? ChapterTocState.Success
                                        val originalBook = callBack?.oldBook
                                        val start = startText.trim().toIntOrNull()
                                        val end = endText.trim().toIntOrNull()
                                        automationRangeDefault = null
                                        if (tocState == null || originalBook == null || start == null || end == null ||
                                            !viewModel.startAutomation(
                                                originalBook,
                                                tocState.book,
                                                tocState.toc,
                                                start,
                                                end,
                                            )
                                        ) {
                                            toastOnUi(R.string.chapter_source_automation_invalid_range)
                                        }
                                    }
                                ) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { automationRangeDefault = null }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        currentBookUrl = oldBookUrl
        syncChangeSourceResultOptions()
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
        showTitle()
        initLiveData()
        initBatchMode()
        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner) {
            if (viewModel.isAutomationActive) {
                viewModel.stopAutomation()
                return@addCallback
            }
            if (batchCaching) {
                viewModel.cancelCacheContents()
                return@addCallback
            }
            if (batchMode && tocLoading) return@addCallback
            if (tocVisible) {
                tocVisible = false
                viewModel.clearToc()
                return@addCallback
            }
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        searchFinishDialog?.dismiss()
        searchFinishDialog = null
        super.onDestroyView()
    }

    private fun syncChangeSourceResultOptions() {
        checkAuthorState = AppConfig.changeSourceCheckAuthor
        loadInfoState = AppConfig.changeSourceLoadInfo
        loadTocState = AppConfig.changeSourceLoadToc
        loadWordCountState = AppConfig.changeSourceLoadWordCount
        sortRespondTimeState = AppConfig.changeSourceSortRespondTime
        wordCountFilterModeState = AppConfig.changeSourceWordCountFilterMode
        selectedGroupState = AppConfig.searchGroup
    }

    private fun showTitle() {
        currentTitle = viewModel.currentOriginalChapter?.title ?: viewModel.chapterTitle
        if (batchMode) {
            currentSubtitle = when (val state = viewModel.automationState.value) {
                is ChapterSourceAutomationState.Ready -> getString(
                    R.string.chapter_source_automation_progress,
                    state.position + 1,
                    state.total,
                )

                is ChapterSourceAutomationState.Caching -> getString(
                    R.string.chapter_source_automation_progress,
                    state.position + 1,
                    state.total,
                )

                is ChapterSourceAutomationState.Paused -> when (val reason = state.reason) {
                    ChapterSourceAutomationPause.Ambiguous -> getString(
                        R.string.chapter_source_automation_paused_ambiguous
                    )

                    ChapterSourceAutomationPause.Missing -> getString(
                        R.string.chapter_source_automation_paused_missing
                    )

                    is ChapterSourceAutomationPause.ContentError -> getString(
                        R.string.chapter_source_automation_paused_error,
                        reason.message,
                    )
                }

                is ChapterSourceAutomationState.Finished -> getString(
                    R.string.chapter_source_finished
                )

                else -> getString(
                    R.string.chapter_source_selected_count,
                    selectionCount,
                )
            }
        } else {
            currentSubtitle = null
        }
    }

    private fun initBatchMode() {
        if (!batchMode) return
        updateBatchActions()
        val oldBook = callBack?.oldBook ?: return
        viewModel.loadOriginalChapters(oldBook.bookUrl)
    }

    private fun initLiveData() {
        val owner = viewLifecycleOwner
        viewModel.searchStateData.observe(owner) {
            isSearching = it
        }
        viewModel.searchFinishData.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    showEmptySearchGroupDialog(event, owner)
                }
            }
        }
        viewModel.originalChaptersState.observe(owner, ::showOriginalChaptersState)
        viewModel.tocState.observe(owner, ::showTocState)
        viewModel.contentLoading.observe(owner) { loading ->
            contentLoading = loading
        }
        viewModel.contentResult.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    when (val result = event.take()) {
                        is ChapterContentResult.Success -> {
                            val callback = callBack ?: return@withStateAtLeast
                            callback.replaceContent(result.content)
                            dismissAllowingStateLoss()
                        }

                        is ChapterContentResult.Error -> {
                            tocVisible = false
                            viewModel.clearToc()
                            toastOnUi(result.message)
                        }

                        null -> Unit
                    }
                }
            }
        }
        viewModel.changeSourceResult.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    when (val result = event.take()) {
                        is SourceChangeResult.Success -> {
                            val callback = callBack ?: return@withStateAtLeast
                            val sourceViewModel = viewModel
                            val completion = SourceChangeCompletion(
                                result.deleteAfterChange,
                                sourceViewModel::del,
                            )
                            callback.changeTo(
                                result.source,
                                result.book,
                                result.toc,
                                completion::success,
                            )
                            if (result.dismissDialog) dismissAllowingStateLoss()
                        }

                        is SourceChangeResult.Error -> {
                            AppLog.put(
                                "自动换源失败\n${result.throwable.localizedMessage}",
                                result.throwable,
                                true,
                            )
                        }

                        null -> Unit
                    }
                }
            }
        }
        viewModel.batchCaching.observe(owner, ::updateBatchCaching)
        viewModel.automationState.observe(owner) { state ->
            showAutomationState(state)
            if (state is ChapterSourceAutomationState.Ready) {
                owner.lifecycleScope.launch {
                    owner.lifecycle.withStateAtLeast(RESUMED) {
                        if (viewModel.automationState.value == state) {
                            viewModel.runNextAutomationIfReady()
                        }
                    }
                }
            }
        }
        viewModel.batchCacheResult.observe(owner) { event ->
            owner.lifecycleScope.launch {
                owner.lifecycle.withStateAtLeast(RESUMED) {
                    when (val result = event.take()) {
                        is ChapterCacheResult.Success -> showCacheSuccess(result)
                        is ChapterCacheResult.Error -> toastOnUi(result.message)
                        null -> Unit
                    }
                }
            }
        }
        owner.lifecycleScope.launch {
            owner.lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.searchDataFlow.conflate().collect {
                searchItems = it
                delay(1000)
            }
        }
        owner.lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().conflate().collect {
                groups.clear()
                groups.addAll(it)
            }
        }
    }

    private fun showEmptySearchGroupDialog(
        event: PendingEvent<Boolean>,
        owner: LifecycleOwner,
    ) {
        if (event.peek() != true) {
            event.take()
            return
        }
        val searchGroup = AppConfig.searchGroup
        if (searchGroup.isEmpty()) {
            event.take()
            return
        }
        if (searchFinishDialog != null) return
        searchFinishDialog = context?.alert("搜索结果为空") {
            setMessage("${searchGroup}分组搜索结果为空,是否切换到全部分组")
            noButton { event.take() }
            yesButton {
                event.take()
                AppConfig.searchGroup = ""
                selectedGroupState = ""
                viewModel.startSearch()
                owner.lifecycleScope.launch {
                    syncChangeSourceResultOptions()
                }
            }
            onCancelled { event.take() }
            onDismiss { dialog ->
                if (searchFinishDialog === dialog) searchFinishDialog = null
            }
        }
    }

    private fun showOriginalChaptersState(state: OriginalChaptersState) {
        when (state) {
            OriginalChaptersState.Loading -> Unit
            is OriginalChaptersState.Success -> {
                when {
                    viewModel.isBatchFinished -> {
                        updateBatchActions()
                    }

                    viewModel.currentOriginalChapter == null -> {
                        toastOnUi(R.string.chapter_list_empty)
                        dismissAllowingStateLoss()
                    }

                    else -> {
                        showTitle()
                        updateBatchActions()
                    }
                }
            }

            is OriginalChaptersState.Error -> {
                toastOnUi(state.message)
                dismissAllowingStateLoss()
            }
        }
    }

    private fun scrollToDurSource() {
        searchItems.forEachIndexed { index, searchBook ->
            if (searchBook.bookUrl == oldBookUrl) {
                scrollSourceTargetIndex = index
                scrollSourceTrigger++
                return
            }
        }
    }

    fun openToc(searchBook: SearchBook) {
        if (viewModel.isAutomationActive || batchCaching || tocLoading) return
        viewModel.loadToc(searchBook.toBook())
    }

    private fun showTocState(state: ChapterTocState) {
        when (state) {
            ChapterTocState.Idle -> {
                targetBook = null
                updateTocLoading(false)
            }

            is ChapterTocState.Loading -> {
                targetBook = state.book
                clearSelection()
                tocItems = emptyList()
                tocVisible = true
                updateTocLoading(true)
            }

            is ChapterTocState.Success -> {
                targetBook = state.book
                tocVisible = true
                updateTocLoading(false)
                durChapterIndex = BookHelp.getDurChapter(
                    viewModel.chapterIndex,
                    viewModel.chapterTitle,
                    state.toc,
                    searchAllChapterNumbers = true,
                )
                tocItems = state.toc
                scrollTocTargetIndex = (durChapterIndex - 5).coerceAtLeast(0)
                scrollTocTrigger++
            }

            is ChapterTocState.Error -> {
                AppLog.put(
                    "单章换源获取目录出错\n${state.throwable.localizedMessage}",
                    state.throwable,
                    true,
                )
                updateTocLoading(false)
                tocVisible = false
                viewModel.clearToc()
            }
        }
    }

    val oldBookUrl: String?
        get() = callBack?.oldBook?.bookUrl

    fun topSource(searchBook: SearchBook) {
        viewModel.topSource(searchBook)
    }

    fun bottomSource(searchBook: SearchBook) {
        viewModel.bottomSource(searchBook)
    }

    fun editSource(searchBook: SearchBook) {
        editSourceResult.launch {
            putExtra("sourceUrl", searchBook.origin)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        viewModel.disableSource(searchBook)
    }

    fun deleteSource(searchBook: SearchBook) {
        if (oldBookUrl == searchBook.bookUrl) {
            viewModel.autoChangeSource(callBack?.oldBook?.type, searchBook)
        } else {
            viewModel.del(searchBook)
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        viewModel.setBookScore(searchBook, score)
        scoreRevision++
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return viewModel.getBookScore(searchBook)
    }

    fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?) {
        if (batchMode) return
        targetBook?.let {
            viewModel.loadContent(it, bookChapter, nextChapterUrl)
        }
    }

    fun selectionChanged() {
        viewLifecycleOwnerLiveData.value?.lifecycleScope?.launch {
            showTitle()
            updateBatchActions()
        }
    }

    private fun clearSelection() {
        if (selectedChapterIndices.isEmpty()) return
        selectedChapterIndices = emptySet()
        selectionChanged()
    }

    private fun selectPositions(positions: Collection<Int>) {
        val nextSelection = positions.mapNotNullTo(hashSetOf()) { position ->
            tocItems.getOrNull(position)?.takeUnless { it.isVolume }?.index
        }
        if (nextSelection == selectedChapterIndices) return
        selectedChapterIndices = nextSelection
        selectionChanged()
    }

    private fun cacheSelectedChapters() {
        if (selectionCount == 0 || tocLoading || batchCaching) return
        if (viewModel.isAutomationActive) {
            viewModel.cacheAutomationSelection(selectedPositions)
            return
        }
        val sourceBook = targetBook ?: return
        val originalBook = callBack?.oldBook ?: return
        val chapter = viewModel.currentOriginalChapter ?: return
        val lastPos = lastSelectedPosition
        viewModel.cacheContents(
            sourceBook,
            selectedChapters,
            originalBook,
            chapter,
            lastPos + 1,
        )
    }

    private fun showCacheSuccess(result: ChapterCacheResult.Success) {
        callBack?.contentCached(result.cachedChapterIndex)
        clearSelection()
        if (tocItems.isNotEmpty()) {
            scrollTocTargetIndex = result.targetPosition.coerceAtMost(tocItems.lastIndex)
            scrollTocTrigger++
        }
        result.automationSessionId?.let { sessionId ->
            if (!viewModel.acknowledgeAutomationCache(sessionId, result.cachedChapterIndex)) {
                showTitle()
                updateBatchActions()
                return
            }
            if (viewModel.automationState.value is ChapterSourceAutomationState.Finished) {
                toastOnUi(R.string.chapter_source_finished)
                dismissAllowingStateLoss()
            } else {
                showTitle()
                updateBatchActions()
            }
            return
        }
        if (result.nextChapter == null) {
            toastOnUi(R.string.chapter_source_finished)
            dismissAllowingStateLoss()
            return
        }
        showTitle()
        updateBatchActions()
    }

    private fun advanceOriginalChapter(chapter: BookChapter, targetPosition: Int? = null) {
        val nextChapter = viewModel.advanceOriginalChapter(chapter)
        clearSelection()
        if (targetPosition != null && tocItems.isNotEmpty()) {
            scrollTocTargetIndex = targetPosition.coerceAtMost(tocItems.lastIndex)
            scrollTocTrigger++
        }
        if (nextChapter == null) {
            toastOnUi(R.string.chapter_source_finished)
            dismissAllowingStateLoss()
            return
        }
        showTitle()
        updateBatchActions()
    }

    private fun updateTocLoading(loading: Boolean) {
        tocLoading = loading
        updateBatchActions()
    }

    private fun updateBatchCaching(caching: Boolean) {
        batchCaching = caching
        updateBatchActions()
    }

    private fun updateBatchActions() {
        if (!batchMode) return
        val hasOriginalChapter = viewModel.currentOriginalChapter != null
        val busy = tocLoading || batchCaching
        val automationState = viewModel.automationState.value
        val automationActive = viewModel.isAutomationActive
        val canSelect = !automationActive || automationState is ChapterSourceAutomationState.Paused
        hasOriginalChapterState = hasOriginalChapter
        isAutomationActiveState = automationActive
        canSelectState = canSelect
        selectionEnabled = !busy && canSelect
    }

    private fun showAutomationRangeDialog() {
        val tocState = viewModel.tocState.value as? ChapterTocState.Success
        if (tocState == null) {
            toastOnUi(R.string.chapter_source_automation_select_target)
            return
        }
        if (callBack?.oldBook == null) return
        val range = viewModel.automationRangeDefaults()
        if (range == null) {
            toastOnUi(R.string.chapter_list_empty)
            return
        }
        automationRangeDefault = range
    }

    private fun showAutomationState(state: ChapterSourceAutomationState) {
        isAutomationActiveState = viewModel.isAutomationActive
        when (state) {
            is ChapterSourceAutomationState.Caching -> {
                selectPositions(state.targetPositions)
            }

            is ChapterSourceAutomationState.Paused -> when (state.reason) {
                is ChapterSourceAutomationPause.ContentError -> {
                    selectPositions(state.targetPositions)
                }

                else -> {
                    clearSelection()
                    state.targetPositions.firstOrNull()?.let { pos ->
                        scrollTocTargetIndex = pos
                        scrollTocTrigger++
                    }
                }
            }

            is ChapterSourceAutomationState.Ready,
            is ChapterSourceAutomationState.Finished -> clearSelection()

            ChapterSourceAutomationState.Idle -> Unit
        }
        showTitle()
        updateBatchActions()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            currentBookUrl = oldBookUrl
        }
    }

    interface CallBack {
        val oldBook: Book?
        fun changeTo(
            source: BookSource,
            book: Book,
            toc: List<BookChapter>,
            onSuccess: () -> Unit,
        )
        fun replaceContent(content: String)
        fun contentCached(chapterIndex: Int)
    }

}
