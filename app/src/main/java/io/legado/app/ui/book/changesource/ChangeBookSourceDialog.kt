package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 换源界面 (Compose)
 */
class ChangeBookSourceDialog() : BaseDialogFragment() {

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val groups = mutableStateListOf<String>()
    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeBookSourceViewModel by viewModels()
    private var waitDialog: WaitDialog? = null
    private var searchFinishDialog: AlertDialog? = null
    private var typeMismatchDialog: AlertDialog? = null
    private var autoScrollCurrentSource = true

    private var searchItems by mutableStateOf<List<SearchBook>>(emptyList())
    private var isSearching by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var bottomStatusText by mutableStateOf("")
    private var currentBookUrl by mutableStateOf<String?>(null)
    private var scoreRevision by mutableIntStateOf(0)
    private var scrollTargetIndex by mutableStateOf<Int?>(null)
    private var scrollTrigger by mutableIntStateOf(0)
    private var showWordCountFilterDialog by mutableStateOf(false)

    private var checkAuthorState by mutableStateOf(AppConfig.changeSourceCheckAuthor)
    private var loadInfoState by mutableStateOf(AppConfig.changeSourceLoadInfo)
    private var loadTocState by mutableStateOf(AppConfig.changeSourceLoadToc)
    private var loadWordCountState by mutableStateOf(AppConfig.changeSourceLoadWordCount)
    private var sortRespondTimeState by mutableStateOf(AppConfig.changeSourceSortRespondTime)
    private var wordCountFilterModeState by mutableIntStateOf(AppConfig.changeSourceWordCountFilterMode)
    private var selectedGroupState by mutableStateOf(AppConfig.searchGroup)

    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            val origin = it.data?.getStringExtra("origin") ?: return@registerForActivityResult
            viewModel.startSearch(origin)
        }

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
                    val listState = rememberLazyListState()
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(scrollTrigger) {
                        val index = scrollTargetIndex ?: return@LaunchedEffect
                        if (index in searchItems.indices) {
                            listState.scrollToItem(index)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(R.color.background))
                    ) {
                        ChangeSourceTopBar(
                            title = viewModel.name,
                            subtitle = viewModel.author,
                            showNavigationIcon = true,
                            onBack = { dismissAllowingStateLoss() },
                            isSearching = isSearching,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { query ->
                                searchQuery = query
                                viewModel.screen(query)
                            },
                            onStartStopClick = { viewModel.startOrStopSearch() },
                            onSourceManageClick = { startActivity<BookSourceActivity>() },
                            showRefreshList = true,
                            onRefreshListClick = { viewModel.startRefreshList() },
                            showClose = true,
                            onCloseClick = { dismissAllowingStateLoss() },
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

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(
                                items = searchItems,
                                key = { it.bookUrl }
                            ) { item ->
                                val isCurrent = currentBookUrl == item.bookUrl
                                val score = scoreRevision.let { getBookScore(item) }
                                ChangeSourceItemRow(
                                    item = item,
                                    isCurrent = isCurrent,
                                    showSelectedHighlight = true,
                                    score = score,
                                    loadWordCount = loadWordCountState,
                                    onClick = {
                                        if (item.bookUrl != oldBookUrl) {
                                            changeTo(item)
                                        }
                                    },
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
                            statusText = bottomStatusText,
                            onDurClick = { scrollToDurSource() },
                            onScrollTop = {
                                if (searchItems.isNotEmpty()) {
                                    coroutineScope.launch { listState.scrollToItem(0) }
                                }
                            },
                            onScrollBottom = {
                                if (searchItems.isNotEmpty()) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(searchItems.lastIndex)
                                    }
                                }
                            }
                        )
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
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        autoScrollCurrentSource = true
        currentBookUrl = oldBookUrl
        bottomStatusText = callBack?.oldBook?.originName.orEmpty()
        syncChangeSourceResultOptions()
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
        initLiveData()
    }

    override fun onDestroyView() {
        searchFinishDialog?.dismiss()
        searchFinishDialog = null
        typeMismatchDialog?.dismiss()
        typeMismatchDialog = null
        waitDialog?.dismiss()
        waitDialog = null
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
        viewModel.changeSourceLoading.observe(owner, ::showChangeSourceLoading)
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
                                "换源获取目录出错\n${result.throwable.localizedMessage}",
                                result.throwable,
                                true,
                            )
                        }

                        null -> Unit
                    }
                }
            }
        }
        owner.lifecycleScope.launch {
            owner.lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.searchDataFlow.conflate().collect {
                searchItems = it
                if (autoScrollCurrentSource && it.isNotEmpty()) {
                    if (scrollToDurSource()) autoScrollCurrentSource = false
                }
                delay(1000)
            }
        }

        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(STARTED) {
                viewModel.changeSourceProgress
                    .collect { (count, name) ->
                        bottomStatusText = if (count == 0 && name.isEmpty()) {
                            callBack?.oldBook?.originName.orEmpty()
                        } else {
                            getString(
                                R.string.change_source_progress,
                                searchItems.size,
                                count,
                                viewModel.totalSourceCount,
                                name
                            )
                        }
                        delay(500)
                    }
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
            cancelButton { event.take() }
            okButton {
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

    private fun showChangeSourceLoading(loading: Boolean) {
        if (!loading) {
            waitDialog?.dismiss()
            waitDialog = null
            return
        }
        if (waitDialog != null) return
        waitDialog = WaitDialog(requireContext()).also { dialog ->
            val cancelable = viewModel.changeSourceCancelable.value != false
            dialog.setText(R.string.load_toc)
            dialog.setCancelable(cancelable)
            if (cancelable) {
                dialog.setOnCancelListener {
                    viewModel.cancelChangeSource()
                }
            }
            dialog.show()
        }
    }

    private fun scrollToDurSource(): Boolean {
        searchItems.forEachIndexed { index, searchBook ->
            if (searchBook.bookUrl == oldBookUrl) {
                scrollTargetIndex = index
                scrollTrigger++
                return true
            }
        }
        return false
    }

    fun changeTo(searchBook: SearchBook) {
        val oldBookType = callBack?.oldBook?.type ?: 0
        if (searchBook.sameBookTypeLocal(oldBookType)) {
            changeSource(searchBook)
        } else {
            if (typeMismatchDialog != null) return
            typeMismatchDialog = alert(
                titleResource = R.string.book_type_different,
                messageResource = R.string.soure_change_source
            ) {
                okButton {
                    changeSource(searchBook)
                }
                cancelButton()
                onDismiss { dialog ->
                    if (typeMismatchDialog === dialog) typeMismatchDialog = null
                }
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

    private fun changeSource(searchBook: SearchBook) {
        val book = viewModel.bookMap[searchBook.primaryStr()] ?: searchBook.toBook()
        viewModel.changeSource(book)
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
    }

}
