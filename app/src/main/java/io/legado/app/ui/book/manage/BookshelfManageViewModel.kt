package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.installPersistentCover
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.networkCoverForPersistence
import io.legado.app.help.book.networkCoverSourceOrigin
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.mergeFilteredOrder
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import java.io.File

internal data class PersistentCoverResult(
    val saved: Int,
    val skipped: Int,
    val failed: Int,
)

enum class ShelfSelectionOperation { Idle, Running, Succeeded, Failed }

class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {
    private val _selectionOperation = MutableStateFlow(ShelfSelectionOperation.Idle)
    val selectionOperation: StateFlow<ShelfSelectionOperation> = _selectionOperation.asStateFlow()

    fun consumeSelectionOperation() { _selectionOperation.value = ShelfSelectionOperation.Idle }
    private val _uiState = MutableStateFlow(BookshelfManageUiState())
    val uiState: StateFlow<BookshelfManageUiState> = _uiState.asStateFlow()
    private var booksJob: Job? = null
    private var groupsJob: Job? = null
    private var sourceJob: Job? = null
    var groupId: Long = -1L
    var groupName: String? = null
    private var initialised = false
    var batchChangeSourceCoroutine: Coroutine<Unit>? = null
    internal var batchPersistCoverCoroutine: Coroutine<*>? = null
    private val coverOperationSemaphore = Semaphore(1)
    private var coverOperationId = 0

    fun initialize(initialGroupId: Long) {
        if (initialised) return
        initialised = true
        groupId = initialGroupId
        groupsJob = viewModelScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
                emit(emptyList())
            }.flowOn(Dispatchers.IO).conflate().collect { groups ->
                val currentName = groups.firstOrNull { group -> group.groupId == groupId }?.groupName
                    ?: context.getString(R.string.no_group)
                groupName = currentName
                _uiState.update { state -> state.copy(groups = groups, groupName = currentName) }
            }
        }
        selectGroup(initialGroupId)
    }

    fun selectGroup(groupId: Long) {
        this.groupId = groupId
        val currentGroup = uiState.value.groups.firstOrNull { it.groupId == groupId }
        groupName = currentGroup?.groupName ?: context.getString(R.string.no_group)
        _uiState.update { it.copy(groupId = groupId, groupName = groupName.orEmpty()) }
        booksJob?.cancel()
        booksJob = viewModelScope.launch {
            val bookSort = AppConfig.getBookSortByGroupId(groupId)
            appDb.bookDao.flowByGroup(groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { first, second -> first.name.cnCompare(second.name) }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
                emit(emptyList())
            }.flowOn(Dispatchers.IO).conflate().collect { books ->
                _uiState.update { it.copy(books = books) }
            }
        }
    }

    fun searchSources(query: String) {
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            val flow = if (query.isBlank()) appDb.bookSourceDao.flowEnabled()
            else appDb.bookSourceDao.flowSearchEnabled(query)
            flow.catch { emit(emptyList()) }.flowOn(Dispatchers.IO).collect { sources ->
                _uiState.update { it.copy(sources = sources) }
            }
        }
    }

    suspend fun source(sourceUrl: String): BookSource? = appDb.bookSourceDao.getBookSource(sourceUrl)

    fun changeSourceByUrl(books: List<Book>, sourceUrl: String) {
        viewModelScope.launch {
            source(sourceUrl)?.let { changeSource(books, it) }
        }
    }

    fun assignGroup(books: List<Book>, groupId: Long, add: Boolean = false, remove: Boolean = false) {
        val updates = books.map { book ->
            val group = when {
                add -> book.group or groupId
                remove -> book.group and groupId.inv()
                else -> groupId
            }
            book.copy(group = group)
        }
        if (updates.isNotEmpty()) updateBook(*updates.toTypedArray())
    }

    fun changeSelectedGroups(bookUrls: List<String>, mask: Long, remove: Boolean) {
        if (_selectionOperation.value == ShelfSelectionOperation.Running) return
        if (bookUrls.isEmpty() || mask == 0L) { _selectionOperation.value = ShelfSelectionOperation.Failed; return }
        _selectionOperation.value = ShelfSelectionOperation.Running
        executeLazy {
            if (remove) appDb.bookDao.removeGroupsFromBooks(bookUrls, mask.inv())
            else appDb.bookDao.addGroup(bookUrls, mask)
        }.onSuccess { _selectionOperation.value = ShelfSelectionOperation.Succeeded }
            .onError { _selectionOperation.value = ShelfSelectionOperation.Failed }.start()
    }

    fun removeSelectedFromShelf(books: List<Book>) {
        if (_selectionOperation.value == ShelfSelectionOperation.Running) return
        if (books.isEmpty()) { _selectionOperation.value = ShelfSelectionOperation.Failed; return }
        _selectionOperation.value = ShelfSelectionOperation.Running
        deleteBook(books, deleteOriginal = false) { success ->
            _selectionOperation.value = if (success) ShelfSelectionOperation.Succeeded else ShelfSelectionOperation.Failed
        }
    }

    fun cancelBatchOperation() {
        batchChangeSourceCoroutine?.cancel()
        batchPersistCoverCoroutine?.cancel()
    }

    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        execute {
            val array = Array(books.size) {
                books[it].copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.updatePreservingCustomCoverUrl(*array)
        }
    }

    fun updateBook(vararg book: Book) {
        execute {
            appDb.bookDao.updatePreservingCustomCoverUrl(*book)
        }
    }

    fun updateBookOrder(books: List<Book>, resetAll: Boolean) {
        execute {
            if (resetAll) {
                appDb.runInTransaction {
                    val reordered = mergeFilteredOrder(
                        appDb.bookDao.allShelfByOrder,
                        books,
                    ) { it.bookUrl }
                    reordered.forEachIndexed { index, book -> book.order = index + 1 }
                    appDb.bookDao.updateOrder(reordered)
                }
            } else {
                appDb.bookDao.updateOrder(books)
            }
        }
    }

    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        executeLazy {
            books.forEach { it.saveReadRecordSnapshot() }
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(it.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, it)
                }
            }
        }.onSuccess { onComplete?.invoke(true) }.onError { error ->
            if (onComplete != null) onComplete(false) else context.toastOnUi(error.localizedMessage ?: context.getString(R.string.error))
        }.start()
    }

    fun saveAllUseBookSourceToFile(success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun changeSource(books: List<Book>, source: BookSource) {
        batchChangeSourceCoroutine?.cancel()
        batchChangeSourceCoroutine = execute {
            val changeSourceDelay = AppConfig.batchChangeSourceDelay * 1000L
            books.forEachIndexed { index, book ->
                _uiState.update {
                    it.copy(batchChangeSourceProgress = "${index + 1} / ${books.size}")
                }
                if (book.isLocal) return@forEachIndexed
                if (book.origin == source.bookSourceUrl) return@forEachIndexed
                val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
                    .onFailure {
                        AppLog.put("搜索书籍出错\n${it.localizedMessage}", it, true)
                    }.getOrNull() ?: return@forEachIndexed
                kotlin.runCatching {
                    if (newBook.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, newBook)
                    }
                }.onFailure {
                    AppLog.put("获取书籍详情出错\n${it.localizedMessage}", it, true)
                    return@forEachIndexed
                }
                WebBook.getChapterListAwait(source, newBook)
                    .onFailure {
                        AppLog.put("获取目录出错\n${it.localizedMessage}", it, true)
                    }.getOrNull()?.let { toc ->
                        book.migrateTo(newBook, toc)
                        newBook.removeType(BookType.updateError)
                        replaceBookAfterSourceChange(book, newBook, toc, clearActiveReader = false)
                    }
                delay(changeSourceDelay)
            }
        }.onStart {
            _uiState.update { it.copy(batchChangeSource = true) }
        }.onFinally {
            _uiState.update { it.copy(batchChangeSource = false) }
        }
    }

    fun clearCache(books: List<Book>) {
        execute {
            books.forEach {
                BookHelp.clearCache(it)
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    fun persistNetworkCovers(books: List<Book>) {
        val operationId = beginCoverOperation()
        batchPersistCoverCoroutine = execute(semaphore = coverOperationSemaphore) {
            var saved = 0
            var skipped = 0
            var failed = 0
            val coversDir = File(context.externalFiles, "covers")
            books.forEachIndexed { index, book ->
                currentCoroutineContext().ensureActive()
                val currentBook = appDb.bookDao.getBook(book.bookUrl)
                if (currentBook == null) {
                    skipped++
                    return@forEachIndexed
                }
                if (operationId == coverOperationId) {
                    _uiState.update {
                        it.copy(
                            batchPersistCoverProgress = context.getString(
                                R.string.persist_cover_progress,
                                index + 1,
                                books.size,
                            ),
                        )
                    }
                }
                val coverUrl = currentBook.networkCoverForPersistence()
                if (coverUrl == null) {
                    skipped++
                    return@forEachIndexed
                }
                val expectedOrigin = currentBook.origin
                val expectedCoverUrl = currentBook.coverUrl
                val expectedCustomCoverSource = currentBook.customCoverSource
                val expectedCustomCoverUrl = currentBook.customCoverUrl
                val expectedPersistedCoverUrl = currentBook.persistedCoverUrl
                try {
                    var options = RequestOptions().set(
                        OkHttpModelLoader.loadOnlyWifiOption,
                        AppConfig.loadCoverOnlyWifi
                    )
                    currentBook.networkCoverSourceOrigin()?.let {
                        options = options.set(OkHttpModelLoader.sourceOriginOption, it)
                    }
                    val target = ImageLoader.loadFile(context, coverUrl)
                        .apply(options)
                        .submit()
                    try {
                        val downloaded = runInterruptible { target.get() }
                        currentCoroutineContext().ensureActive()
                        val validationTarget = Glide.with(context)
                            .load(downloaded)
                            .submit(1, 1)
                        try {
                            runInterruptible { validationTarget.get() }
                        } finally {
                            Glide.with(context).clear(validationTarget)
                        }
                        val persistent = installPersistentCover(downloaded, coversDir)
                        currentCoroutineContext().ensureActive()
                        if (
                            appDb.bookDao.updatePersistedCoverUrlIfUnchanged(
                                book.bookUrl,
                                expectedOrigin,
                                expectedCoverUrl,
                                expectedCustomCoverUrl,
                                expectedPersistedCoverUrl,
                                persistent.absolutePath,
                                expectedCustomCoverSource
                            ) == 1
                        ) {
                            saved++
                        } else {
                            skipped++
                        }
                    } finally {
                        Glide.with(context).clear(target)
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed++
                    AppLog.put("保存封面失败: ${currentBook.name}\n${e.localizedMessage}", e)
                }
            }
            PersistentCoverResult(saved, skipped, failed)
        }.onStart {
            if (operationId == coverOperationId) {
                _uiState.update { it.copy(batchPersistCover = true) }
            }
        }.onSuccess {
            context.toastOnUi(
                context.getString(
                    R.string.persist_cover_result,
                    it.saved,
                    it.skipped,
                    it.failed
                )
            )
        }.onFinally {
            if (operationId == coverOperationId) {
                _uiState.update { it.copy(batchPersistCover = false) }
            }
        }
    }

    fun restoreNetworkCovers(books: List<Book>) {
        val operationId = beginCoverOperation()
        batchPersistCoverCoroutine = execute(semaphore = coverOperationSemaphore) {
            books.sumOf { book ->
                currentCoroutineContext().ensureActive()
                val currentBook = appDb.bookDao.getBook(book.bookUrl) ?: return@sumOf 0
                val expectedCoverUrl = currentBook.persistedCoverUrl ?: return@sumOf 0
                appDb.bookDao.clearPersistedCoverUrlIfUnchanged(
                    currentBook.bookUrl,
                    expectedCoverUrl,
                )
            }
        }.onSuccess {
            if (operationId == coverOperationId) {
                context.toastOnUi(context.getString(R.string.restore_network_cover_result, it))
            }
        }
    }

    fun restoreSourceCovers(books: List<Book>) {
        val operationId = beginCoverOperation()
        batchPersistCoverCoroutine = execute(semaphore = coverOperationSemaphore) {
            books.sumOf { book ->
                currentCoroutineContext().ensureActive()
                val currentBook = appDb.bookDao.getBook(book.bookUrl) ?: return@sumOf 0
                if (
                    currentBook.customCoverUrl.isNullOrEmpty() &&
                    currentBook.persistedCoverUrl.isNullOrEmpty()
                ) {
                    return@sumOf 0
                }
                appDb.bookDao.clearCoverOverridesIfUnchanged(
                    currentBook.bookUrl,
                    currentBook.customCoverUrl,
                    currentBook.persistedCoverUrl,
                    currentBook.customCoverSource,
                )
            }
        }.onSuccess {
            if (operationId == coverOperationId) {
                context.toastOnUi(context.getString(R.string.restore_source_cover_result, it))
            }
        }
    }

    private fun beginCoverOperation(): Int {
        val operationId = ++coverOperationId
        batchPersistCoverCoroutine?.cancel()
        _uiState.update { it.copy(batchPersistCover = false) }
        return operationId
    }

}

data class BookshelfManageUiState(
    val groupId: Long = -1,
    val groupName: String = "",
    val groups: List<io.legado.app.data.entities.BookGroup> = emptyList(),
    val books: List<Book> = emptyList(),
    val sources: List<io.legado.app.data.entities.BookSourcePart> = emptyList(),
    val batchChangeSource: Boolean = false,
    val batchChangeSourceProgress: String = "",
    val batchPersistCover: Boolean = false,
    val batchPersistCoverProgress: String = "",
)
