package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.explore.SearchBookGridItem
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.resolveBookListSpanCount
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class SearchActivity :
    VMBaseComposeActivity<SearchViewModel>(),
    SearchScopeDialog.Callback {

    override val viewModel by viewModels<SearchViewModel>()

    private var groupsState by mutableStateOf<List<String>>(emptyList())
    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var isManualStopSearch by mutableStateOf(false)

    private var searchQueryState by mutableStateOf("")
    private var showInputHelpState by mutableStateOf(true)
    private var isSearchingState by mutableStateOf(false)
    private var hasMoreState by mutableStateOf(false)
    private var searchBooksState by mutableStateOf<List<SearchBook>>(emptyList())
    private var bookshelfMatchesState by mutableStateOf<List<Book>>(emptyList())
    private var historyKeysState by mutableStateOf<List<SearchKeyword>>(emptyList())
    private var searchDoneState by mutableIntStateOf(0)
    private var searchTotalState by mutableIntStateOf(0)
    private var precisionSearchState by mutableStateOf(false)
    private var showReadRecordState by mutableStateOf(true)
    private var adapterRefreshVersion by mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        precisionSearchState = getPrefBoolean(PreferKey.precisionSearch)
        showReadRecordState = AppConfig.showSearchReadRecord
        initData()
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val configuration = LocalConfiguration.current
        val focusManager = LocalFocusManager.current
        val spanCount = remember(configuration.screenWidthDp) {
            resolveBookListSpanCount(configuration.screenWidthDp)
        }
        val gridState = rememberLazyGridState()
        var overflowExpanded by remember { mutableStateOf(false) }

        val isAtBottom by remember {
            derivedStateOf {
                val layoutInfo = gridState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisible >= totalItems - 1
            }
        }
        LaunchedEffect(isAtBottom, searchBooksState.size, isSearchingState) {
            if (isAtBottom && searchBooksState.isNotEmpty() && !showInputHelpState) {
                scrollToBottom()
            }
        }

        val showFab = isSearchingState || (!isManualStopSearch && hasMoreState && !showInputHelpState)

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = searchQueryState,
                            onValueChange = { newText ->
                                searchQueryState = newText
                                viewModel.stop()
                                upHistory(newText.trim())
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search_book_key),
                                    maxLines = 1,
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    submitSearch(searchQueryState)
                                },
                            ),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (searchQueryState.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                searchQueryState = ""
                                                viewModel.stop()
                                                upHistory("")
                                                showInputHelpState = true
                                            },
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            submitSearch(searchQueryState)
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = stringResource(R.string.search),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .onFocusChanged { focusState ->
                                    if (viewModel.isSearchLiveData.value == true ||
                                        (!focusState.isFocused && searchBooksState.isNotEmpty() && searchQueryState.isNotBlank())
                                    ) {
                                        visibleInputHelp(false)
                                    } else if (focusState.isFocused) {
                                        visibleInputHelp(true)
                                    }
                                },
                        )
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.precision_search)) },
                                    trailingIcon = {
                                        Checkbox(checked = precisionSearchState, onCheckedChange = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        val next = !getPrefBoolean(PreferKey.precisionSearch)
                                        putPrefBoolean(PreferKey.precisionSearch, next)
                                        precisionSearchState = next
                                        if (searchQueryState.isNotBlank()) {
                                            submitSearch(searchQueryState)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_search_read_record)) },
                                    trailingIcon = {
                                        Checkbox(checked = showReadRecordState, onCheckedChange = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        AppConfig.showSearchReadRecord = !AppConfig.showSearchReadRecord
                                        showReadRecordState = AppConfig.showSearchReadRecord
                                        viewModel.upAdapterLiveData.postValue("hasReadRecord")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.search_result_filter)) },
                                    onClick = {
                                        overflowExpanded = false
                                        showSearchResultFilterDialog()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.search_scope)) },
                                    onClick = {
                                        overflowExpanded = false
                                        alertSearchScope()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.book_source_manage)) },
                                    onClick = {
                                        overflowExpanded = false
                                        startActivity<BookSourceActivity>()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log)) },
                                    onClick = {
                                        overflowExpanded = false
                                        showDialogFragment(AppLogDialog())
                                    },
                                )

                                HorizontalDivider()
                                val searchScopeNames = viewModel.searchScope.displayNames
                                if (viewModel.searchScope.isSource() && searchScopeNames.isNotEmpty()) {
                                    val firstScope = searchScopeNames.first()
                                    DropdownMenuItem(
                                        text = { Text(firstScope) },
                                        trailingIcon = {
                                            Checkbox(checked = true, onCheckedChange = null)
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            viewModel.searchScope.remove(firstScope)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.all_source)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = searchScopeNames.isEmpty(),
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        viewModel.searchScope.update("")
                                    },
                                )
                                groupsState.forEach { group ->
                                    val isScopeChecked = searchScopeNames.contains(group)
                                    DropdownMenuItem(
                                        text = { Text(group) },
                                        trailingIcon = {
                                            Checkbox(checked = isScopeChecked, onCheckedChange = null)
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            if (isScopeChecked) {
                                                viewModel.searchScope.remove(group)
                                            } else {
                                                viewModel.searchScope.update(group)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (showFab) {
                    FloatingActionButton(
                        onClick = {
                            if (viewModel.isSearchLiveData.value == true) {
                                isManualStopSearch = true
                                viewModel.stop()
                            } else {
                                viewModel.search("")
                            }
                        },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isSearchingState) R.drawable.ic_stop_black_24dp else R.drawable.ic_play_24dp,
                            ),
                            contentDescription = null,
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isSearchingState) {
                        LinearProgressIndicator(
                            progress = {
                                if (searchTotalState > 0) {
                                    (searchDoneState.toFloat() / searchTotalState).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (showInputHelpState) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (searchQueryState.isNotBlank() && bookshelfMatchesState.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.bookshelf),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    bookshelfMatchesState.forEach { book ->
                                        Surface(
                                            modifier = Modifier.clickable { showBookInfo(book) },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ) {
                                            Text(
                                                text = book.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.searchHistory),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (historyKeysState.isNotEmpty()) {
                                    TextButton(onClick = ::alertClearHistory) {
                                        Text(text = stringResource(R.string.clear))
                                    }
                                }
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                historyKeysState.forEach { item ->
                                    Surface(
                                        modifier = Modifier.combinedClickable(
                                            onClick = { searchHistory(item.word) },
                                            onLongClick = { deleteHistory(item) },
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Text(
                                            text = item.word,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(spanCount),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = searchBooksState,
                                key = { index, item -> "${item.name}_${item.author}_$index" },
                            ) { _, item ->
                                val inShelf = remember(item.name, item.author, adapterRefreshVersion) {
                                    isInBookshelf(item)
                                }
                                val readRecord = remember(item.name, item.author, showReadRecordState, adapterRefreshVersion) {
                                    !inShelf && hasReadRecord(item)
                                }
                                SearchBookGridItem(
                                    book = item,
                                    isInBookshelf = inShelf,
                                    hasReadRecord = readRecord,
                                    originCount = item.origins.size,
                                    onClick = { showBookInfo(item.name, item.author, item.bookUrl) },
                                )
                            }
                        }
                    }
                }

                if (isSearchingState) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 12.dp),
                    ) {
                        Text(
                            text = "$searchDoneState/$searchTotalState",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    private fun submitSearch(query: String) {
        val searchKey = query.trim()
        if (searchKey.isEmpty()) return
        isManualStopSearch = false
        viewModel.saveSearchKey(searchKey)
        viewModel.searchKey = ""
        viewModel.search(searchKey)
        visibleInputHelp(false)
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            if (!showInputHelpState && searchQueryState.isNotBlank()) {
                submitSearch(searchQueryState)
            }
        }
        viewModel.isSearchLiveData.observe(this) { searching ->
            isSearchingState = searching
            hasMoreState = viewModel.hasMore
        }
        viewModel.searchBookLiveData.observe(this) {
            searchBooksState = filterSearchResults(
                it,
                getPrefString(PreferKey.searchResultFilter).orEmpty(),
            )
            hasMoreState = viewModel.hasMore
        }
        viewModel.searchProgressLiveData.observe(this) { (searched, total) ->
            searchDoneState = searched
            searchTotalState = total
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().collect {
                groupsState = it
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.resume()
                try {
                    awaitCancellation()
                } finally {
                    viewModel.pause()
                }
            }
        }
        visibleInputHelp(true)
    }

    /**
     * 处理传入数据
     */
    private fun receiptIntent(intent: Intent? = null) {
        val searchScope = intent?.getStringExtra("searchScope")
        searchScope?.let {
            viewModel.searchScope.update(searchScope, postValue = false, save = false)
        }
        val key = intent?.getStringExtra("key")
        if (key.isNullOrBlank()) {
            visibleInputHelp(true)
        } else {
            searchQueryState = key
            submitSearch(key)
        }
    }

    /**
     * 滚动到底部事件
     */
    private fun scrollToBottom() {
        if (isManualStopSearch) {
            return
        }
        if (viewModel.isSearchLiveData.value == false
            && viewModel.searchKey.isNotEmpty()
            && viewModel.hasMore
        ) {
            viewModel.search("")
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        if (visible) {
            upHistory(searchQueryState)
        }
        showInputHelpState = visible
    }

    /**
     * 更新搜索历史
     */
    private fun upHistory(key: String? = null) {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            if (key.isNullOrBlank()) {
                bookshelfMatchesState = emptyList()
            } else {
                appDb.bookDao.flowSearch(key).conflate().collect {
                    bookshelfMatchesState = it
                }
            }
        }
        historyFlowJob?.cancel()
        historyFlowJob = lifecycleScope.launch {
            when {
                key.isNullOrBlank() -> appDb.searchKeywordDao.flowByTime()
                else -> appDb.searchKeywordDao.flowSearch(key)
            }.catch {
                AppLog.put("搜索界面获取搜索历史数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                historyKeysState = it
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            adapterRefreshVersion++
        }
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            alert("搜索结果为空") {
                val precisionSearch = appCtx.getPrefBoolean(PreferKey.precisionSearch)
                val displayScope = viewModel.searchScope.display
                if (precisionSearch) {
                    setMessage("${displayScope}分组搜索结果为空，是否关闭精准搜索？")
                    yesButton {
                        appCtx.putPrefBoolean(PreferKey.precisionSearch, false)
                        precisionSearchState = false
                        viewModel.searchKey = ""
                        viewModel.search(searchQueryState)
                    }
                } else {
                    setMessage("${displayScope}分组搜索结果为空，是否切换到全部分组？")
                    yesButton {
                        viewModel.searchScope.update("")
                    }
                }
                noButton()
            }
        }
    }

    /**
     * 显示书籍详情
     */
    fun showBookInfo(name: String, author: String, bookUrl: String) {
        startActivity<BookInfoActivity> {
            putExtra("name", name)
            putExtra("author", author)
            putExtra("bookUrl", bookUrl)
        }
    }

    /**
     * 是否已经加入书架
     */
    fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /**
     * 是否有阅读记录
     */
    fun hasReadRecord(book: SearchBook): Boolean {
        return viewModel.hasReadRecord(book)
    }

    /**
     * 显示书籍详情
     */
    fun showBookInfo(book: Book) {
        showBookInfo(book.name, book.author, book.bookUrl)
    }

    /**
     * 点击历史关键字
     */
    fun searchHistory(key: String) {
        lifecycleScope.launch {
            searchQueryState = key
            when {
                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } -> {
                    submitSearch(key)
                }

                else -> {
                    upHistory(key)
                }
            }
        }
    }

    /**
     * 删除搜索记录
     */
    fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
    }

    override fun onSearchScopeOk(searchScope: SearchScope) {
        viewModel.searchScope.update(searchScope.toString())
    }

    private fun alertSearchScope() {
        showDialogFragment<SearchScopeDialog>()
    }

    private fun showSearchResultFilterDialog() {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.search_result_filter_hint)
            editView.setSingleLine(false)
            editView.minLines = 4
            editView.maxLines = 8
            editView.setText(getPrefString(PreferKey.searchResultFilter).orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(R.string.search_result_filter) {
            customView { alertBinding.root }
            okButton {
                val filter = alertBinding.editView.text?.toString().orEmpty().trim()
                putPrefString(PreferKey.searchResultFilter, filter)
                searchBooksState = filterSearchResults(
                    viewModel.searchBookLiveData.value.orEmpty(),
                    filter,
                )
            }
            cancelButton()
        }
    }

    private fun alertClearHistory() {
        alert(R.string.draw) {
            setMessage(R.string.sure_clear_search_history)
            yesButton {
                viewModel.clearHistory()
            }
            noButton()
        }
    }

    companion object {

        fun start(context: Context, key: String?, searchScope: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

        fun start(context: Context, source: BookSource, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

        fun start(context: Context, source: BookSourcePart, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

    }
}

internal fun filterSearchResults(
    books: List<SearchBook>,
    rawWords: String,
): List<SearchBook> {
    if (rawWords.isBlank()) return books
    val words = rawWords.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    return books.filterNot { book ->
        words.any { word ->
            book.name.contains(word, ignoreCase = true) ||
                    book.author.contains(word, ignoreCase = true) ||
                    book.kind?.contains(word, ignoreCase = true) == true
        }
    }
}
