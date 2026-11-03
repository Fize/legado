package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.book.SearchBookShelfHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.mergeActiveShelfBook
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.VideoPlay
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap


@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _uiState = MutableStateFlow(ExploreShowUiState())
    internal val uiState = _uiState.asStateFlow()
    private var bookSource: BookSource? = null
    private var initialized = false
    private var categoriesLoading = false
    private var firstLoadedPage = 1
    private val paginationState = ExplorePaginationState()
    private val booksLock = Any()
    private var books = linkedSetOf<SearchBook>()
    private val addBooksLock = Any()
    private var addBooksCoroutine: Coroutine<*>? = null

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                _uiState.update { it.copy(bookshelfVersion = it.bookshelfVersion + 1) }
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun initData(intent: Intent, savedState: Bundle? = null) {
        initData(intent.extras, savedState)
    }

    fun initData(
        sourceUrl: String,
        exploreName: String,
        exploreUrl: String,
        savedState: Bundle? = null,
    ) {
        initData(
            Bundle().apply {
                putString("sourceUrl", sourceUrl)
                putString("exploreName", exploreName)
                putString("exploreUrl", exploreUrl)
            },
            savedState,
        )
    }

    fun initData(arguments: Bundle?, savedState: Bundle? = null) {
        if (initialized) return
        initialized = true
        val initialCategory = ExploreCategory(
            savedState?.getString("exploreName") ?: arguments?.getString("exploreName").orEmpty(),
            savedState?.getString("exploreUrl") ?: arguments?.getString("exploreUrl").orEmpty(),
        )
        _uiState.update { it.copy(selectedCategory = initialCategory, title = initialCategory.title) }
        skipPage((savedState?.getInt("explorePage", 1) ?: 1).coerceAtLeast(1))
        execute {
            arguments?.getString("sourceUrl")?.let { appDb.bookSourceDao.getBookSource(it) }
                ?: error(context.getString(R.string.error_no_source))
        }.onSuccess {
            bookSource = it
            explore()
            if (AppConfig.showExploreCategories) loadCategories()
        }.onError {
            _uiState.update { state -> state.copy(bottomError = it.stackTraceStr, loading = false) }
        }
    }

    fun saveState(outState: Bundle) {
        uiState.value.selectedCategory?.let {
            outState.putString("exploreName", it.title)
            outState.putString("exploreUrl", it.url)
        }
        outState.putInt("explorePage", uiState.value.page)
    }

    fun loadCategories() {
        val source = bookSource ?: return
        if (categoriesLoading || uiState.value.categories.isNotEmpty()) return
        categoriesLoading = true
        execute { source.exploreKinds() }.onSuccess { kinds ->
            val categories = kinds.filter {
                it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() &&
                        !it.title.startsWith("ERROR:")
            }
            _uiState.update { it.copy(categories = categories) }
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }.onFinally { categoriesLoading = false }
    }

    internal fun switchCategory(category: ExploreCategory) {
        if (bookSource == null || category.url.isBlank() || uiState.value.selectedCategory == category) return
        skipPage(1)
        _uiState.update {
            it.copy(
                title = category.title,
                selectedCategory = category,
                bottomError = null,
                topError = null,
            )
        }
        explore()
    }

    /**
     * 上滑触发的增量更新
     */
    fun explore(page: Int) {
        val source = bookSource
        val url = uiState.value.selectedCategory?.url
        if (source == null || url == null) return
        if (paginationState.isLoading) return
        val request = paginationState.startPage(page) ?: return
        WebBook.exploreBook(viewModelScope, source, url, request.page)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                if (!paginationState.isActive(request)) return@onSuccess
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                withContext(Main) {
                    if (!paginationState.complete(request)) return@withContext
                    val previousCount = synchronized(booksLock) { books.size }
                    val loadedBooks = synchronized(booksLock) {
                        val newBooks = linkedSetOf<SearchBook>()
                        newBooks.addAll(searchBooks)
                        newBooks.addAll(books)
                        books = newBooks
                        books.toList()
                    }
                    firstLoadedPage = request.page
                    _uiState.update {
                        it.copy(
                            books = loadedBooks,
                            firstPage = firstLoadedPage,
                            hasMore = it.hasMore,
                            loading = false,
                            prependCount = loadedBooks.size - previousCount,
                            page = request.page,
                            topError = null,
                        )
                    }
                }
            }.onError {
                if (!paginationState.fail(request)) return@onError
                it.printOnDebug()
                _uiState.update { state -> state.copy(topError = it.stackTraceStr) }
            }
    }

    fun skipPage(page: Int) {
        if (paginationState.skipTo(page)) {
            synchronized(booksLock) {
                books.clear()
            }
            firstLoadedPage = page
            _uiState.update {
                it.copy(
                    books = emptyList(),
                    page = page,
                    firstPage = page,
                    hasMore = true,
                    loading = true,
                    prependCount = null,
                    bottomError = null,
                    topError = null,
                )
            }
        }
    }

    fun showPage(page: Int) {
        if (page > 0) _uiState.update { it.copy(page = page) }
    }

    fun clearBottomError() {
        _uiState.update { it.copy(bottomError = null) }
    }

    fun clearTopError() {
        _uiState.update { it.copy(topError = null) }
    }

    fun explore() {
        val source = bookSource
        val url = uiState.value.selectedCategory?.url
        if (source == null || url == null) return
        if (paginationState.isLoading) return
        val request = paginationState.startNextPage()
        _uiState.update { it.copy(loading = true, bottomError = null) }
        WebBook.exploreBook(viewModelScope, source, url, request.page)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                if (!paginationState.isActive(request)) return@onSuccess
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                withContext(Main) {
                    if (!paginationState.complete(request)) return@withContext
                    val previousCount = synchronized(booksLock) { books.size }
                    val loadedBooks = synchronized(booksLock) {
                        books.addAll(searchBooks)
                        books.toList()
                    }
                    _uiState.update {
                        it.copy(
                            books = loadedBooks,
                            firstPage = firstLoadedPage,
                            hasMore = loadedBooks.size > previousCount,
                            loading = false,
                            prependCount = null,
                            page = request.page,
                            bottomError = null,
                        )
                    }
                }
            }.onError {
                if (!paginationState.fail(request)) return@onError
                it.printOnDebug()
                _uiState.update { state ->
                    state.copy(bottomError = it.stackTraceStr, loading = false)
                }
            }
    }

    fun getLoadedBooks(): List<SearchBook> {
        return synchronized(booksLock) { books.toList() }
    }

    fun addLoadedBooksToShelf(
        loadedBooks: List<SearchBook>,
    ): Boolean {
        val snapshot = loadedBooks.toList()
        if (snapshot.isEmpty()) return false
        val task = synchronized(addBooksLock) {
            if (addBooksCoroutine != null) return false
            executeLazy {
                val result = SearchBookShelfHelp.addLoadedBooksToShelf(snapshot)
                val callBackBooks = result.addedBooks.map { book ->
                    kotlin.runCatching {
                        appDb.bookSourceDao.getBookSource(book.origin)
                    }.getOrNull() to book
                }
                withContext(NonCancellable + Main.immediate) {
                    val activeBooks = result.addedBooks
                        .flatMap(::syncActiveBook)
                        .distinctBy { it.bookUrl }
                    val shelfStates = activeBooks.map {
                        ShelfState(it.bookUrl, it.type, it.order)
                    }
                    if (shelfStates.isNotEmpty()) {
                        withContext(IO) {
                            shelfStates.forEach {
                                appDb.bookDao.updateShelfState(it.bookUrl, it.type, it.order)
                            }
                        }
                    }
                    val activeBooksByUrl = activeBooks.associateBy { it.bookUrl }
                    val mergedCallBackBooks = callBackBooks.map { (source, book) ->
                        source to (activeBooksByUrl[book.bookUrl] ?: book)
                    }
                    SourceCallBack.callBackBooks(
                        SourceCallBack.ADD_BOOK_SHELF,
                        mergedCallBackBooks,
                    )
                }
                result
            }.also {
                addBooksCoroutine = it
            }
        }
        _uiState.update { it.copy(addBooksBusy = true) }
        task.onSuccess { result ->
            context.toastOnUi(
                context.getString(
                    R.string.add_loaded_books_to_bookshelf_result,
                    result.added,
                    result.skipped,
                )
            )
        }.onError {
            AppLog.put("发现列表批量加入书架失败\n${it.localizedMessage}", it)
            val message = it.localizedMessage
            if (message.isNullOrBlank()) {
                context.toastOnUi(R.string.add_loaded_books_to_bookshelf_failed)
            } else {
                context.toastOnUi(
                    "${context.getString(R.string.add_loaded_books_to_bookshelf_failed)}\n$message"
                )
            }
        }
        task.invokeOnCompletion {
            synchronized(addBooksLock) {
                if (addBooksCoroutine === task) {
                    addBooksCoroutine = null
                }
            }
            _uiState.update { it.copy(addBooksBusy = false) }
        }
        task.start()
        return true
    }

    private fun syncActiveBook(book: Book): List<Book> {
        val activeBooks = arrayListOf<Book>()
        mergeActiveShelfBook(ReadBook.book, book)?.let {
            ReadBook.book = it
            ReadBook.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        mergeActiveShelfBook(AudioPlay.book, book)?.let {
            AudioPlay.book = it
            AudioPlay.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        mergeActiveShelfBook(VideoPlay.book, book)?.let {
            VideoPlay.book = it
            VideoPlay.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        return activeBooks
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

    private data class ShelfState(
        val bookUrl: String,
        val type: Int,
        val order: Int,
    )

}

internal data class ExplorePageRequest(
    val page: Int,
    internal val requestId: Long,
    internal val advancesNextPage: Boolean,
)

internal data class ExploreCategory(val title: String, val url: String)

internal data class ExploreShowUiState(
    val title: String = "",
    val selectedCategory: ExploreCategory? = null,
    val categories: List<ExploreKind> = emptyList(),
    val books: List<SearchBook> = emptyList(),
    val page: Int = 1,
    val firstPage: Int = 1,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val prependCount: Int? = null,
    val topError: String? = null,
    val bottomError: String? = null,
    val addBooksBusy: Boolean = false,
    val bookshelfVersion: Int = 0,
)

internal class ExplorePaginationState {

    private var requestSequence = 0L
    private var activeRequestId: Long? = null

    val isLoading: Boolean
        @Synchronized get() = activeRequestId != null

    var nextPage = 1
        private set

    @Synchronized
    fun skipTo(page: Int): Boolean {
        if (page <= 0) return false
        activeRequestId = null
        nextPage = page
        return true
    }

    @Synchronized
    fun startNextPage(): ExplorePageRequest {
        return startRequest(nextPage, advancesNextPage = true)
    }

    @Synchronized
    fun startPage(page: Int): ExplorePageRequest? {
        if (page <= 0) return null
        return startRequest(page, advancesNextPage = false)
    }

    @Synchronized
    fun isActive(request: ExplorePageRequest): Boolean {
        return activeRequestId == request.requestId
    }

    @Synchronized
    fun complete(request: ExplorePageRequest): Boolean {
        if (!isActive(request)) return false
        if (request.advancesNextPage) nextPage = request.page + 1
        activeRequestId = null
        return true
    }

    @Synchronized
    fun fail(request: ExplorePageRequest): Boolean {
        if (!isActive(request)) return false
        activeRequestId = null
        return true
    }

    private fun startRequest(page: Int, advancesNextPage: Boolean): ExplorePageRequest {
        val requestId = ++requestSequence
        activeRequestId = requestId
        return ExplorePageRequest(page, requestId, advancesNextPage)
    }
}
