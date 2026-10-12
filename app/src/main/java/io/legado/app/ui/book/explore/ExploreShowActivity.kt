package io.legado.app.ui.book.explore

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.resolveBookListSpanCount
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseComposeActivity<ExploreShowViewModel>() {
    override val viewModel by viewModels<ExploreShowViewModel>()

    private var titleState by mutableStateOf("")
    private var booksState by mutableStateOf<List<SearchBook>>(emptyList())
    private var categoriesState by mutableStateOf<List<ExploreCategory>>(emptyList())
    private var selectedCategoryState by mutableStateOf<ExploreCategory?>(null)
    private var showCategoriesState by mutableStateOf(AppConfig.showExploreCategories)
    private var pageState by mutableIntStateOf(1)
    private var oldPage by mutableIntStateOf(-1)
    private var isClearAll by mutableStateOf(false)
    private var topLoadingState by mutableStateOf(false)
    private var topErrorState by mutableStateOf<String?>(null)
    private var bottomLoadingState by mutableStateOf(true)
    private var bottomHasMoreState by mutableStateOf(true)
    private var bottomErrorState by mutableStateOf<String?>(null)
    private var addBooksBusyState by mutableStateOf(false)
    private var adapterRefreshVersion by mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        titleState = intent.getStringExtra("exploreName").orEmpty()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.categoryData.observe(this) {
            titleState = it.title
            selectedCategoryState = it
        }
        viewModel.categoriesData.observe(this) { updateCategories() }
        viewModel.initData(intent, savedInstanceState)
        viewModel.errorLiveData.observe(this) {
            if (it != null) {
                bottomLoadingState = false
                bottomErrorState = it
            }
        }
        viewModel.errorTopLiveData.observe(this) {
            if (it != null) {
                topLoadingState = false
                topErrorState = it
            }
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapterRefreshVersion++
        }
        viewModel.pageLiveData.observe(this) {
            pageState = it ?: 1
        }
        viewModel.addBooksBusy.observe(this) {
            addBooksBusyState = it == true
        }
    }

    override fun onResume() {
        super.onResume()
        updateCategories()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateCategories() {
        val show = AppConfig.showExploreCategories
        showCategoriesState = show
        if (show) viewModel.loadCategories()
        categoriesState = viewModel.categoriesData.value.orEmpty().map {
            ExploreCategory(it.title, it.url.orEmpty())
        }
    }

    private fun alertAddLoadedBooksToShelf() {
        val loadedBooks = viewModel.getLoadedBooks()
        if (loadedBooks.isEmpty()) {
            toastOnUi(R.string.no_loaded_books_to_add)
            return
        }
        alert(titleResource = R.string.add_loaded_books_to_bookshelf) {
            setMessage(
                getString(R.string.add_loaded_books_to_bookshelf_message, loadedBooks.size)
            )
            yesButton {
                val started = viewModel.addLoadedBooksToShelf(loadedBooks)
                if (!started) {
                    toastOnUi(R.string.add_loaded_books_to_bookshelf_in_progress)
                }
            }
            noButton()
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((bottomHasMoreState && !bottomLoadingState && !topLoadingState && bottomErrorState == null) || forceLoad) {
            bottomErrorState = null
            bottomLoadingState = true
            bottomHasMoreState = true
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !bottomLoadingState && !topLoadingState && topErrorState == null) || forceLoad) {
            topErrorState = null
            topLoadingState = true
            viewModel.explore(oldPage - 1)
        } else if (oldPage <= 1) {
            viewModel.showPage(1)
        }
    }

    private fun upData(state: ExploreListState) {
        booksState = state.books
        oldPage = state.firstPage
        topLoadingState = false
        if (state.loading) {
            bottomLoadingState = true
            bottomHasMoreState = true
            bottomErrorState = null
        } else if (state.books.isEmpty()) {
            bottomLoadingState = false
            bottomHasMoreState = false
        } else if (!state.hasMore) {
            bottomLoadingState = false
            bottomHasMoreState = false
        } else {
            bottomLoadingState = false
            bottomHasMoreState = true
        }
        if (!state.loading && isClearAll) {
            isClearAll = false
        }
    }

    @Composable
    override fun Content() {
        ExploreShowScreen(
            title = titleState,
            onBack = { finish() },
            page = pageState,
            oldPage = oldPage,
            showCategories = showCategoriesState,
            categories = categoriesState,
            selectedCategory = selectedCategoryState,
            addBooksBusy = addBooksBusyState,
            books = booksState,
            topLoading = topLoadingState,
            topError = topErrorState,
            bottomLoading = bottomLoadingState,
            bottomHasMore = bottomHasMoreState,
            bottomError = bottomErrorState,
            adapterRefreshVersion = adapterRefreshVersion,
            isInBookshelf = { viewModel.isInBookShelf(it) },
            onToggleCategories = {
                AppConfig.showExploreCategories = !AppConfig.showExploreCategories
                updateCategories()
            },
            onAddLoadedBooks = ::alertAddLoadedBooksToShelf,
            onChangePageClick = {
                val page = viewModel.pageLiveData.value ?: 1
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(page)
                    .show {
                        if (page != it) {
                            oldPage = it
                            viewModel.skipPage(it)
                            topLoadingState = false
                            topErrorState = null
                            bottomHasMoreState = true
                            bottomErrorState = null
                            isClearAll = true
                            booksState = emptyList()
                            viewModel.explore()
                        }
                    }
            },
            onSelectCategory = { category ->
                if (category != viewModel.categoryData.value) {
                    isClearAll = false
                    viewModel.switchCategory(category)
                }
            },
            onLoadBottom = ::scrollToBottom,
            onLoadTop = ::scrollToTop,
            onBookClick = { book ->
                startActivity<BookInfoActivity> {
                    putExtra("name", book.name)
                    putExtra("author", book.author)
                    putExtra("bookUrl", book.bookUrl)
                }
            },
        )
    }
}

@Composable
internal fun ExploreShowScreen(
    title: String,
    onBack: (() -> Unit)?,
    page: Int,
    oldPage: Int,
    showCategories: Boolean,
    categories: List<ExploreCategory>,
    selectedCategory: ExploreCategory?,
    addBooksBusy: Boolean,
    books: List<SearchBook>,
    topLoading: Boolean,
    topError: String?,
    bottomLoading: Boolean,
    bottomHasMore: Boolean,
    bottomError: String?,
    adapterRefreshVersion: Int,
    isInBookshelf: (SearchBook) -> Boolean,
    onToggleCategories: () -> Unit,
    onAddLoadedBooks: () -> Unit,
    onChangePageClick: () -> Unit,
    onSelectCategory: (ExploreCategory) -> Unit,
    onLoadBottom: (Boolean) -> Unit,
    onLoadTop: (Boolean) -> Unit,
    onBookClick: (SearchBook) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val spanCount = remember(configuration.screenWidthDp) {
        resolveBookListSpanCount(configuration.screenWidthDp)
    }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 2
        }
    }
    LaunchedEffect(isAtBottom, books.size) {
        if (isAtBottom && books.isNotEmpty()) {
            onLoadBottom(false)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }.distinctUntilChanged().collect { atTop ->
            if (atTop && books.isNotEmpty()) {
                onLoadTop(false)
            }
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = title,
                onBack = onBack,
                actions = {
                    TextButton(onClick = onChangePageClick) {
                        Text(text = stringResource(R.string.menu_page, page))
                    }
                },
                menuActions = buildList {
                    add(
                        LegadoMenuAction(
                            title = stringResource(R.string.show_explore_categories),
                            checked = showCategories,
                            onClick = onToggleCategories,
                        ),
                    )
                    if (!addBooksBusy) {
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.add_loaded_books_to_bookshelf),
                                onClick = onAddLoadedBooks,
                            ),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            if (showCategories && categories.isNotEmpty()) {
                val rows = remember(categories) { splitExploreCategoryRows(categories) }
                Surface(
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        rows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 40.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.forEach { category ->
                                    val isSelected = category == selectedCategory
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 40.dp)
                                            .clickable {
                                                coroutineScope.launch {
                                                    gridState.scrollToItem(0)
                                                }
                                                onSelectCategory(category)
                                            }
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = category.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(spanCount),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (oldPage > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadMoreFooterItem(
                            isLoading = topLoading,
                            hasMore = true,
                            errorText = topError,
                            emptyText = null,
                            onClick = { onLoadTop(true) },
                        )
                    }
                }

                itemsIndexed(
                    items = books,
                    key = { index, item -> "${item.bookUrl}_$index" },
                ) { _, item ->
                    val inShelf = remember(item.bookUrl, item.name, item.author, adapterRefreshVersion) {
                        isInBookshelf(item)
                    }
                    SearchBookGridItem(
                        book = item,
                        isInBookshelf = inShelf,
                        hasReadRecord = false,
                        originCount = null,
                        onClick = { onBookClick(item) },
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadMoreFooterItem(
                        isLoading = bottomLoading,
                        hasMore = bottomHasMore,
                        errorText = bottomError,
                        emptyText = if (books.isEmpty() && !bottomLoading) {
                            stringResource(R.string.empty)
                        } else {
                            null
                        },
                        onClick = {
                            if (!bottomLoading) {
                                onLoadBottom(true)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchBookGridItem(
    book: SearchBook,
    isInBookshelf: Boolean,
    hasReadRecord: Boolean,
    originCount: Int?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val kinds = remember(book.kind) { book.getKindList() }
    val intro = remember(book.intro) { book.trimIntro(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegadoBookCover(
                path = book.coverUrl,
                name = book.name,
                author = book.author,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                sourceOrigin = book.origin,
                modifier = Modifier
                    .width(78.dp)
                    .height(108.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isInBookshelf) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF4CAF50), CircleShape),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (hasReadRecord) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFFFF9800), CircleShape),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = book.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (originCount != null && originCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = originCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.author_show, book.author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (kinds.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        kinds.forEach { kind ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    text = kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }

                if (!book.latestChapterTitle.isNullOrEmpty()) {
                    Text(
                        text = stringResource(R.string.lasted_show, book.latestChapterTitle.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (intro.isNotBlank()) {
                    Text(
                        text = intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun LoadMoreFooterItem(
    isLoading: Boolean,
    hasMore: Boolean,
    errorText: String?,
    emptyText: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            errorText != null -> Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            emptyText != null -> Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !hasMore -> Text(
                text = stringResource(R.string.bottom_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun <T> splitExploreCategoryRows(categories: List<T>): List<List<T>> {
    if (categories.isEmpty()) return emptyList()
    val rowCount = ((categories.size - 1) / 10 + 1).coerceAtMost(3)
    val perRow = categories.size / rowCount
    val extra = categories.size % rowCount
    var start = 0
    return List(rowCount) { row ->
        val end = start + perRow + if (row < extra) 1 else 0
        categories.subList(start, end).also { start = end }
    }
}
