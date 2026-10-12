package io.legado.app.ui.main.bookshelf.style1.books

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.BookshelfBookGridItem
import io.legado.app.ui.main.bookshelf.BookshelfBookListItem
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.resolveBookshelfSpanCount
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架书籍列表子界面
 */
class BooksFragment() : BaseFragment(0) {

    constructor(position: Int, group: BookGroup) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putInt("bookSort", group.getRealBookSort())
        bundle.putBoolean("enableRefresh", group.enableRefresh)
        bundle.putBoolean("onlyUpdateRead", group.onlyUpdateRead)
        arguments = bundle
    }

    private val activityViewModel by activityViewModels<MainViewModel>()
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefreshState by mutableStateOf(true)
    private var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }

    private var booksState by mutableStateOf<List<Book>>(emptyList())
    private var refreshVersionState by mutableIntStateOf(0)
    private var timeAgoVersionState by mutableIntStateOf(0)
    private var scrollToTopNonce by mutableLongStateOf(0L)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    BooksScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            bookSort = it.getInt("bookSort", 0)
            enableRefreshState = it.getBoolean("enableRefresh", true)
            onlyUpdateRead = it.getBoolean("onlyUpdateRead", false)
        }
        startLastUpdateTimeJob()
        upRecyclerData()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BooksScreen() {
        val config = LocalConfiguration.current
        val spanCount = resolveBookshelfSpanCount(
            configuredLayout = bookshelfLayout,
            screenWidthDp = config.screenWidthDp,
        )
        val books = booksState
        @Suppress("UNUSED_VARIABLE")
        val refreshVer = refreshVersionState
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

        val content: @Composable () -> Unit = {
            Box(modifier = Modifier.fillMaxSize()) {
                if (books.isEmpty()) {
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
                            items = books,
                            key = { "${it.name}-${it.author}-${it.bookUrl}" },
                        ) { book ->
                            if (bookshelfLayout < 2) {
                                BookshelfBookListItem(
                                    book = book,
                                    compact = bookshelfLayout == 1,
                                    isUpdating = isUpdate(book.bookUrl),
                                    showUnread = showUnread,
                                    showLastUpdateTime = showLastUpdateTime,
                                    timeAgoVersion = timeAgoVersionState,
                                    onClick = { open(book) },
                                    onLongClick = { openBookInfo(book) },
                                )
                            } else {
                                BookshelfBookGridItem(
                                    book = book,
                                    showBooknameMode = showBooknameMode,
                                    isUpdating = isUpdate(book.bookUrl),
                                    showUnread = showUnread,
                                    onClick = { open(book) },
                                    onLongClick = { openBookInfo(book) },
                                )
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
                            items = books,
                            key = { "${it.name}-${it.author}-${it.bookUrl}" },
                        ) { book ->
                            BookshelfBookListItem(
                                book = book,
                                compact = bookshelfLayout == 1,
                                isUpdating = isUpdate(book.bookUrl),
                                showUnread = showUnread,
                                showLastUpdateTime = showLastUpdateTime,
                                timeAgoVersion = timeAgoVersionState,
                                onClick = { open(book) },
                                onLongClick = { openBookInfo(book) },
                            )
                        }
                    }
                }
            }
        }

        if (enableRefreshState && books.isNotEmpty()) {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { activityViewModel.upToc(books, onlyUpdateRead) },
                modifier = Modifier.fillMaxSize(),
            ) {
                content()
            }
        } else {
            content()
        }
    }

    fun upBookSort(sort: Int) {
        arguments?.putInt("bookSort", sort)
        bookSort = sort
        if (isAdded) {
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefreshState = enable
    }

    /**
     * 更新书籍列表信息
     */
    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }
                    5 -> list.sortedWith { o1, o2 ->
                        o1.author.cnCompare(o2.author)
                    }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                booksState = list
                delay(100)
            }
        }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        if (!AppConfig.showLastUpdateTime || bookshelfLayout >= 2) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    timeAgoVersionState++
                    delay(30 * 1000)
                }
            }
        }
    }

    fun getBooks(): List<Book> {
        return booksState
    }

    fun gotoTop() {
        scrollToTopNonce++
    }

    fun getBooksCount(): Int {
        return booksState.size
    }

    fun open(book: Book) {
        startActivityForBook(book)
    }

    fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            refreshVersionState++
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            refreshVersionState++
            startLastUpdateTimeJob()
        }
    }
}
