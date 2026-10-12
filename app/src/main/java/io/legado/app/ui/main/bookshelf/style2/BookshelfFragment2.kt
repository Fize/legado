package io.legado.app.ui.main.bookshelf.style2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfBookGridItem
import io.legado.app.ui.main.bookshelf.BookshelfBookListItem
import io.legado.app.ui.main.bookshelf.BookshelfGroupGridItem
import io.legado.app.ui.main.bookshelf.BookshelfGroupListItem
import io.legado.app.ui.main.bookshelf.BookshelfTopBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.resolveBookshelfSpanCount
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面 (样式2 - 文件夹网格/列表)
 */
class BookshelfFragment2() : BaseBookshelfFragment() {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private var bookGroups: List<BookGroup> = emptyList()
    private val bookGroupsFlow = MutableStateFlow<List<BookGroup>>(emptyList())
    private var groupItems by mutableStateOf<List<BookshelfGroupItem>>(emptyList())
    private var booksFlowJob: Job? = null
    override var groupId by mutableLongStateOf(BookGroup.IdRoot)
    override var books by mutableStateOf<List<Book>>(emptyList())
    private var enableRefresh by mutableStateOf(true)
    override var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var refreshVersionState by mutableIntStateOf(0)
    private var scrollToTopNonce by mutableLongStateOf(0L)
    private var titleTextState by mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Bookshelf2Screen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        subscribeShelfHeaderRefresh()
        initBookGroupData()
        initBooksData()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Bookshelf2Screen() {
        val config = LocalConfiguration.current
        val spanCount = resolveBookshelfSpanCount(
            configuredLayout = bookshelfLayout,
            screenWidthDp = config.screenWidthDp,
        )
        @Suppress("UNUSED_VARIABLE")
        val refreshVer = refreshVersionState
        val currentGroupId = groupId
        val items = remember(currentGroupId, groupItems, books, refreshVer) {
            if (currentGroupId != BookGroup.IdRoot) {
                books
            } else {
                groupItems + books
            }
        }
        val showUnread = AppConfig.showUnread
        val showLastUpdateTime = AppConfig.showLastUpdateTime
        val showBooknameMode = AppConfig.showBookname
        val marginDp = (bookshelfMargin / 2).coerceAtLeast(2).dp

        val listState = rememberLazyListState()
        val gridState = rememberLazyGridState()

        LaunchedEffect(scrollToTopNonce) {
            if (scrollToTopNonce > 0L) {
                if (spanCount >= 2) {
                    if (AppConfig.isEInkMode) {
                        gridState.scrollToItem(0)
                    } else {
                        gridState.animateScrollToItem(0)
                    }
                } else {
                    if (AppConfig.isEInkMode) {
                        listState.scrollToItem(0)
                    } else {
                        listState.animateScrollToItem(0)
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            BookshelfTopBar(
                title = titleTextState.ifBlank { stringResource(R.string.bookshelf) },
                onMenuAction = ::handleBookshelfMenuAction,
                onBackClick = if (currentGroupId != BookGroup.IdRoot) ({ back() }) else null,
            )

            ShelfHeaderComposable()

            val bodyContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(currentGroupId, bookGroups) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                                onDragEnd = {
                                    if (totalDrag > 96f && adjacentBookshelfGroupId(bookGroups, currentGroupId, -1) != null) {
                                        switchBookGroup(-1)
                                    } else if (totalDrag < -96f && adjacentBookshelfGroupId(bookGroups, currentGroupId, 1) != null) {
                                        switchBookGroup(1)
                                    }
                                },
                            )
                        },
                ) {
                    if (items.isEmpty()) {
                        Text(
                            text = stringResource(R.string.bookshelf_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    } else if (spanCount >= 2) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(spanCount),
                            state = gridState,
                            contentPadding = PaddingValues(
                                horizontal = 8.dp + marginDp,
                                vertical = 8.dp + marginDp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(marginDp),
                            verticalArrangement = Arrangement.spacedBy(marginDp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = items,
                                key = { item ->
                                    when (item) {
                                        is BookshelfGroupItem -> "group_${item.group.groupId}"
                                        is Book -> "book_${item.name}_${item.author}_${item.bookUrl}"
                                        else -> item.hashCode()
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is BookshelfGroupItem -> {
                                        if (bookshelfLayout < 2) {
                                            BookshelfGroupListItem(
                                                item = item,
                                                compact = bookshelfLayout == 1,
                                                onClick = { onItemClick(item.group) },
                                                onLongClick = { onItemLongClick(item.group) },
                                            )
                                        } else {
                                            BookshelfGroupGridItem(
                                                item = item,
                                                showBooknameMode = showBooknameMode,
                                                onClick = { onItemClick(item.group) },
                                                onLongClick = { onItemLongClick(item.group) },
                                            )
                                        }
                                    }
                                    is Book -> {
                                        if (bookshelfLayout < 2) {
                                            BookshelfBookListItem(
                                                book = item,
                                                compact = bookshelfLayout == 1,
                                                isUpdating = isUpdate(item.bookUrl),
                                                showUnread = showUnread,
                                                showLastUpdateTime = showLastUpdateTime,
                                                timeAgoVersion = refreshVer,
                                                onClick = { onItemClick(item) },
                                                onLongClick = { onItemLongClick(item) },
                                            )
                                        } else {
                                            BookshelfBookGridItem(
                                                book = item,
                                                showBooknameMode = showBooknameMode,
                                                isUpdating = isUpdate(item.bookUrl),
                                                showUnread = showUnread,
                                                onClick = { onItemClick(item) },
                                                onLongClick = { onItemLongClick(item) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                horizontal = 8.dp,
                                vertical = 8.dp + marginDp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(marginDp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = items,
                                key = { item ->
                                    when (item) {
                                        is BookshelfGroupItem -> "group_${item.group.groupId}"
                                        is Book -> "book_${item.name}_${item.author}_${item.bookUrl}"
                                        else -> item.hashCode()
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is BookshelfGroupItem -> {
                                        BookshelfGroupListItem(
                                            item = item,
                                            compact = bookshelfLayout == 1,
                                            onClick = { onItemClick(item.group) },
                                            onLongClick = { onItemLongClick(item.group) },
                                        )
                                    }
                                    is Book -> {
                                        BookshelfBookListItem(
                                            book = item,
                                            compact = bookshelfLayout == 1,
                                            isUpdating = isUpdate(item.bookUrl),
                                            showUnread = showUnread,
                                            showLastUpdateTime = showLastUpdateTime,
                                            timeAgoVersion = refreshVer,
                                            onClick = { onItemClick(item) },
                                            onLongClick = { onItemLongClick(item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (enableRefresh && items.isNotEmpty()) {
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { activityViewModel.upToc(books, onlyUpdateRead) },
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                ) {
                    bodyContent()
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    bodyContent()
                }
            }
        }

        BookshelfDialogsComposable()
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            bookGroupsFlow.value = data
            updateTitleAndRefresh()
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun updateTitleAndRefresh() {
        if (!isAdded) return
        if (groupId == BookGroup.IdRoot) {
            titleTextState = getString(R.string.bookshelf)
            enableRefresh = true
        } else {
            bookGroups.firstOrNull { groupId == it.groupId }?.let {
                titleTextState = "${getString(R.string.bookshelf)}(${it.groupName})"
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
    }

    private fun initBooksData() {
        updateTitleAndRefresh()
        booksFlowJob?.cancel()
        val currentGroupId = groupId
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            val bookDao = appDb.bookDao
            val sortedBooksFlow = bookDao.flowByGroup(currentGroupId).map { currentBooks ->
                sortBooks(currentBooks, currentGroupId)
            }
            val booksFlow = if (currentGroupId == BookGroup.IdRoot) {
                val shelfDataFlow = combine(
                    sortedBooksFlow,
                    bookDao.flowBookshelfBooks(),
                ) { currentBooks, allBooks ->
                    currentBooks to allBooks
                }.flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME,
                    AppDatabase.BOOK_GROUP_TABLE_NAME,
                )
                combine(shelfDataFlow, bookGroupsFlow) { (currentBooks, allBooks), groups ->
                    currentBooks to buildBookshelfGroupItems(groups, allBooks)
                }
            } else {
                sortedBooksFlow.map { it to emptyList<BookshelfGroupItem>() }
                    .flowWithLifecycleAndDatabaseChangeFirst(
                        viewLifecycleOwner.lifecycle,
                        Lifecycle.State.RESUMED,
                        AppDatabase.BOOK_TABLE_NAME,
                    )
            }
            booksFlow.catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { (currentBooks, items) ->
                if (groupId != currentGroupId) return@collect
                books = currentBooks
                if (currentGroupId == BookGroup.IdRoot) {
                    groupItems = items
                }
                delay(100)
            }
        }
    }

    private fun switchBookGroup(offset: Int) {
        val targetGroupId = adjacentBookshelfGroupId(bookGroups, groupId, offset) ?: return
        groupId = targetGroupId
        initBooksData()
    }

    private fun sortBooks(books: List<Book>, groupId: Long): List<Book> {
        return when (AppConfig.getBookSortByGroupId(groupId)) {
            1 -> books.sortedByDescending { it.latestChapterTime }
            2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
            3 -> books.sortedBy { it.order }
            4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> books.sortedByDescending { it.durChapterTime }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    override fun gotoTop() {
        scrollToTopNonce++
    }

    fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
            }

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            groupItems.size + books.size
        } else {
            books.size
        }
    }

    fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return groupItems + books
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            refreshVersionState++
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            refreshVersionState++
        }
    }
}

internal fun adjacentBookshelfGroupId(
    groups: List<BookGroup>,
    currentGroupId: Long,
    offset: Int,
): Long? {
    val currentIndex = groups.indexOfFirst { it.groupId == currentGroupId }
    if (currentIndex < 0) return null
    return groups.getOrNull(currentIndex + offset)?.groupId
}
