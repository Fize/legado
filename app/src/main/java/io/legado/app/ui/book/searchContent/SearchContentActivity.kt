package io.legado.app.ui.book.searchContent

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全文搜索界面 (Compose)
 */
class SearchContentActivity : BaseComposeActivity() {

    val viewModel by viewModels<SearchContentViewModel>()
    private val searchResults = mutableStateListOf<SearchResult>()
    private var searchResultCounts by mutableIntStateOf(0)
    private var queryText by mutableStateOf("")
    private var isSearching by mutableStateOf(false)
    private var durChapterIndex by mutableIntStateOf(0)
    private var initialScrollIndex by mutableStateOf<Int?>(null)
    private var requestSearchFocus by mutableStateOf(false)
    private var replaceEnabledState by mutableStateOf(SearchContentViewModel.replaceEnabled)
    private var regexReplaceState by mutableStateOf(SearchContentViewModel.regexReplace)

    private var searchJob: Job? = null
    private var initJob: Job? = null

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val textColor = colorResource(R.color.primaryText)
        val accentColor = remember(context) { Color(context.accentColor) }
        val bbg = remember(context) { context.bottomBackground }
        val btc = remember(context, bbg) {
            Color(context.getPrimaryTextColor(ColorUtils.isColorLight(bbg)))
        }
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        var menuExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(initialScrollIndex) {
            val pos = initialScrollIndex ?: return@LaunchedEffect
            if (pos in searchResults.indices) {
                listState.scrollToItem(pos)
            }
        }

        LaunchedEffect(requestSearchFocus) {
            if (requestSearchFocus) {
                runCatching { focusRequester.requestFocus() }
                requestSearchFocus = false
            }
        }

        Scaffold(
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        },
                        title = {
                            OutlinedTextField(
                                value = queryText,
                                onValueChange = { queryText = it },
                                placeholder = { Text(stringResource(R.string.search)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        startContentSearch(queryText.trim())
                                    }
                                ),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (queryText.isNotEmpty()) {
                                            IconButton(onClick = { queryText = "" }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.clear)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                focusManager.clearFocus()
                                                startContentSearch(queryText.trim())
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = stringResource(R.string.search)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                            )
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.more_menu)
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.replace)) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = replaceEnabledState,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            val next = !SearchContentViewModel.replaceEnabled
                                            SearchContentViewModel.replaceEnabled = next
                                            replaceEnabledState = next
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.regex)) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = regexReplaceState,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            val next = !SearchContentViewModel.regexReplace
                                            SearchContentViewModel.regexReplace = next
                                            regexReplaceState = next
                                        }
                                    )
                                }
                            }
                        }
                    )
                    if (isSearching) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            },
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(bbg))
                        .navigationBarsPadding()
                        .height(36.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.search_content_size)}: $searchResultCounts",
                        color = btc,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { requestSearchFocus = true }
                            .padding(horizontal = 10.dp)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    IconButton(
                        onClick = {
                            if (searchResults.isNotEmpty()) {
                                coroutineScope.launch { listState.scrollToItem(0) }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_drop_up),
                            contentDescription = stringResource(R.string.go_to_top),
                            tint = btc
                        )
                    }
                    IconButton(
                        onClick = {
                            if (searchResults.isNotEmpty()) {
                                coroutineScope.launch {
                                    listState.scrollToItem(searchResults.lastIndex)
                                }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_drop_down),
                            contentDescription = stringResource(R.string.go_to_bottom),
                            tint = btc
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = searchResults,
                        key = { index, _ -> index }
                    ) { index, item ->
                        SearchContentItemCard(
                            item = item,
                            isDur = durChapterIndex == item.chapterIndex,
                            textColor = textColor,
                            accentColor = accentColor,
                            onClick = {
                                if (item.query.isNotBlank()) {
                                    openSearchResult(item, index)
                                }
                            }
                        )
                    }
                }

                if (isSearching) {
                    SmallFloatingActionButton(
                        onClick = { searchJob?.cancel() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop_black_24dp),
                            contentDescription = stringResource(R.string.stop)
                        )
                    }
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val searchResultList = IntentData.get<List<SearchResult>>("searchResultList")
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        requestSearchFocus = noSearchResult
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        searchResultCounts = list.size
        searchResults.clear()
        searchResults.addAll(list)
        initialScrollIndex = position
    }

    private fun initBook(submit: Boolean = true) {
        searchResultCounts = viewModel.searchResultCounts
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                queryText = searchWord
                if (submit) {
                    startContentSearch(searchWord.trim())
                }
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch {
            withContext(IO) {
                viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.add(chapter.getFileName())
                }
            }
        }
    }

    fun startContentSearch(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        searchResults.clear()
        viewModel.searchResultList.clear()
        viewModel.searchResultCounts = 0
        searchResultCounts = 0
        viewModel.lastQuery = query
        isSearching = true
        searchJob = lifecycleScope.launch(IO) {
            try {
                initJob?.join()
                kotlin.runCatching {
                    appDb.bookChapterDao.getChapterList(viewModel.bookUrl).forEach { bookChapter ->
                        ensureActive()
                        val results = if (
                            isLocalBook ||
                            viewModel.cacheChapterNames.contains(bookChapter.getFileName())
                        ) {
                            viewModel.searchChapter(query, bookChapter)
                        } else {
                            return@forEach
                        }
                        ensureActive()
                        if (results.isNotEmpty()) {
                            viewModel.searchResultList.addAll(results)
                            withContext(Main) {
                                searchResultCounts = viewModel.searchResultCounts
                                searchResults.addAll(results)
                            }
                        }
                    }
                    if (viewModel.searchResultCounts == 0) {
                        val noResult = SearchResult(
                            resultText = getString(R.string.search_content_empty)
                        )
                        withContext(Main) {
                            searchResults.add(noResult)
                        }
                    }
                }.onFailure {
                    AppLog.put("全文搜索出错\n${it.localizedMessage}", it)
                }
            } finally {
                withContext(Main) {
                    isSearching = false
                }
            }
        }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    fun openSearchResult(searchResult: SearchResult, index: Int) {
        searchJob?.cancel()
        postEvent(EventBus.SEARCH_RESULT, viewModel.searchResultList as List<SearchResult>)
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        IntentData.put("searchResultList$key", viewModel.searchResultList)
        searchData.putExtra("key", key)
        searchData.putExtra("index", index)
        setResult(RESULT_OK, searchData)
        finish()
    }
}

@Composable
internal fun SearchContentItemCard(
    item: SearchResult,
    isDur: Boolean,
    textColor: Color,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colorResource(R.color.background_card),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = item.toAnnotatedString(textColor, accentColor),
            fontSize = 14.sp,
            fontWeight = if (isDur) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}