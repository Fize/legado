package io.legado.app.ui.book.info

import io.legado.app.data.repository.infoSnapshot

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookInfoChildKind
import io.legado.app.data.entities.BookInfoRequest
import io.legado.app.data.repository.BookInfoRequestOwner
import io.legado.app.data.repository.navigationOwner
import io.legado.app.data.entities.BookIntroDocument
import io.legado.app.data.repository.BookIntroRepository
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.updateTo
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.data.repository.BookInfoPreview
import io.legado.app.data.repository.BookInfoPreviewLease
import io.legado.app.data.repository.BookInfoPreviewStore
import io.legado.app.data.repository.BookInfoRepository
import io.legado.app.data.repository.BookInfoEdit
import io.legado.app.data.repository.BookInfoLibraryRepository
import io.legado.app.data.repository.BookInfoLibraryError
import io.legado.app.data.repository.BookInfoLibraryException
import io.legado.app.data.repository.BookCoverSelection
import io.legado.app.data.repository.CoverSearchRepository
import io.legado.app.ui.book.changecover.CoverSearchController
import io.legado.app.ui.book.changecover.CoverSearchUiState
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.ui.book.group.BookGroupsController
import io.legado.app.ui.book.group.BookGroupsUiState
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.variable.VariableEditorController
import io.legado.app.ui.variable.VariableEditorUiState
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import javax.inject.Inject

data class BookInfoIntroUiState(
    val document: BookIntroDocument? = null,
    val contentKey: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val visible: Boolean = false,
    val canCollapse: Boolean = false,
    val expanded: Boolean = true,
    val toggleVisible: Boolean = false,
)

internal fun restoreBookInfoIntro(
    previous: BookInfoIntroUiState,
    visible: Boolean,
    contentChanged: Boolean,
    savedExpanded: Boolean?,
): BookInfoIntroUiState = previous.copy(
    visible = visible,
    expanded = visible && (contentChanged || (savedExpanded ?: true)),
    canCollapse = visible && !contentChanged && previous.canCollapse,
    toggleVisible = visible && !contentChanged && previous.toggleVisible,
)

sealed interface BookInfoAction {
    data object SelectBooksDir : BookInfoAction
    data class ShowToast(val message: String?) : BookInfoAction
    data class ShowToastResource(val resourceId: Int) : BookInfoAction
    data class RunScript(val sourceKey: String, val bookUrl: String, val name: String, val script: String, val bookRevision: Long) : BookInfoAction
}

data class BookInfoPendingAction(val id: Long, val action: BookInfoAction)
data class BookInfoSourceChangeCompleted(val id: String, val bookUrl: String, val dismiss: Boolean,
    val removedSource: io.legado.app.data.entities.SearchBook?)

data class BookInfoChildPreparation(val id: String, val kind: BookInfoChildKind, val failed: Boolean = false,
    val committedRevision: Long? = null)

data class BookInfoReaderReturnPreparation(val id: String, val ready: Boolean = false, val failed: Boolean = false)

data class BookInfoUiState(
    val book: Book? = null,
    val bookRevision: Long = 0,
    val chaptersRevision: Long = 0,
    val sourceRevision: Long = 0,
    val chapters: List<BookChapter> = emptyList(),
    val loading: Boolean = false,
    val inBookshelf: Boolean = false,
    val shelfMutation: Boolean = false,
    val metadataWrites: Int = 0,
    val recoveryReady: Boolean = false,
    val initializationFailed: Boolean = false,
    val previewCommitRevision: Long? = null,
    val appliedPreviewCommitRevision: Long? = null,
    val entryRecoveryFailed: Boolean = false,
    val entryRecoveryAttempt: Long = 0,
    val childPreparation: BookInfoChildPreparation? = null,
    val readerReturnPreparation: BookInfoReaderReturnPreparation? = null,
    val hasCustomButton: Boolean = false,
    val groups: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val source: BookSource? = null,
    val coverRevision: Int = 0,
    val intro: BookInfoIntroUiState = BookInfoIntroUiState(),
    val webFiles: List<BookInfoWebFile> = emptyList(),
    val fileImport: BookInfoFilesUiState = BookInfoFilesUiState(),
    val libraryAction: BookInfoLibraryUiState = BookInfoLibraryUiState(),
    val pendingDelete: io.legado.app.data.repository.BookInfoPendingDelete? = null,
    val pendingUpload: io.legado.app.data.repository.BookInfoPendingUpload? = null,
    val cancellingUpload: Boolean = false,
    val uploadCancellationFailed: Boolean = false,
    val cancellingDelete: Boolean = false,
    val deleteCancellationFailed: Boolean = false,
    val coverSearch: CoverSearchUiState = CoverSearchUiState(),
    val pendingActions: List<BookInfoPendingAction> = emptyList(),
    val sourceChangeCompleted: BookInfoSourceChangeCompleted? = null,
    val variableEditor: VariableEditorUiState? = null,
    val groupsEditor: BookGroupsUiState? = null,
)

private val webFileSuffixPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")

internal fun normalizeWebFileName(
    fileName: String,
    rawSuffix: String?,
    replaceExistingSuffix: Boolean = true,
): String {
    val suffix = rawSuffix
        ?.trim()
        ?.trimStart('.')
        ?.takeIf { webFileSuffixPattern.matches(it) }
        ?: return fileName
    val baseName = fileName.trimEnd('.')
    if (baseName.isEmpty()) return fileName
    val dotIndex = baseName.lastIndexOf('.')
    if (dotIndex <= 0) {
        return "$baseName.$suffix"
    }
    val currentSuffix = baseName.substring(dotIndex + 1)
    if (currentSuffix.equals(suffix, ignoreCase = true)) return baseName
    return if (replaceExistingSuffix) {
        "${baseName.substring(0, dotIndex)}.$suffix"
    } else {
        "$baseName.$suffix"
    }
}

internal class BookInfoNetworkLoadingCounter(
    private val onLoadingChanged: (Boolean) -> Unit,
) {
    private var activeLoads = 0

    @Synchronized
    fun begin() {
        if (activeLoads++ == 0) onLoadingChanged(true)
    }

    @Synchronized
    fun end() {
        check(activeLoads > 0) { "No active network load" }
        if (--activeLoads == 0) onLoadingChanged(false)
    }
}

@HiltViewModel
class BookInfoViewModel @Inject constructor(
    private val repository: BookInfoRepository,
    private val savedState: SavedStateHandle,
    private val previewStore: BookInfoPreviewStore,
    private val groupRepository: BookGroupRepository,
    private val libraryRepository: BookInfoLibraryRepository,
    coverRepository: CoverSearchRepository,
    private val introRepository: BookIntroRepository,
    private val navigationRepository: io.legado.app.data.repository.BookInfoNavigationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookInfoUiState(
        intro = BookInfoIntroUiState(expanded = savedState["introExpanded"] ?: true),
    ))
    val uiState = _uiState.asStateFlow()
    private val currentBook: Book? get() = uiState.value.book?.infoSnapshot()
    var inBookshelf: Boolean
        get() = uiState.value.inBookshelf
        set(value) { _uiState.update { it.copy(inBookshelf = value, recoveryReady = false) }; persistPreview() }
    var bookSource: BookSource?
        get() = uiState.value.source
        set(value) { _uiState.update { it.copy(source = value?.infoSnapshot(), sourceRevision = it.sourceRevision + 1, hasCustomButton = value?.customButton == true) } }
    private var initialized = false
    private var entryIdentity: Pair<String, String>? = null
    private var bookBootstrapJob: Job? = null
    private var childPreparationJob: Job? = null
    private var readerReturnJob: Job? = null
    private var coverLoadJob: Job? = null
    private var groupNamesJob: Job? = null
    private var kindsJob: Job? = null
    private val actionSequence = AtomicLong()
    private var changeSourceJob: Job? = null
    private var bookLoadJob: Job? = null
    private val bookLoadSequence = AtomicLong()
    private val bookRedirects = mutableMapOf<String, String>()
    private val networkLoadingCounter = BookInfoNetworkLoadingCounter { loading -> _uiState.update { it.copy(loading = loading) } }

    private data class PreviewWrite(val revision: Long, val preview: BookInfoPreview, val bookRevision: Long,
        val chaptersRevision: Long, val commitRevision: Long?)
    private data class AppliedPreview(val fileRevision: Long?, val requestRevision: Long?)
    private var previewKey: String = savedState.get<String>("previewKey") ?: UUID.randomUUID().toString().also { savedState["previewKey"] = it }
    private val previewLease = CompletableDeferred<BookInfoPreviewLease>()
    private val previewWrites = Channel<PreviewWrite>(Channel.CONFLATED)
    private val previewSequence = AtomicLong()
    private val previewPublishLock = Any()
    private val bookWriteMutex = Mutex()
    private data class RecordedMetadataEdit(val revision: Long, val bookUrl: String, val edit: BookInfoEdit)
    private data class EditBaseline(val book: Book, val revision: Long)
    private val metadataEditSequence = AtomicLong()
    private val metadataEdits = mutableMapOf<Class<out BookInfoEdit>, RecordedMetadataEdit>()
    private val variableEditorController = VariableEditorController(savedState, viewModelScope,
        repository.sourceVariables, ::saveEditorBookVariable)
    val groupsController = BookGroupsController(savedState, viewModelScope, groupRepository, ::applyGroupSelection)

    val filesController = BookInfoFilesController(savedState, viewModelScope, ::performFileOperation,
        { repository.archiveEntries(Uri.parse(it)) }, { enqueueUiAction(BookInfoAction.SelectBooksDir) },
        { AppLog.put("详情文件导入失败", it) })

    val libraryController = BookInfoLibraryController(savedState, viewModelScope,
        libraryRepository::inspect, libraryRepository::remoteExists, ::uploadLibraryBook, ::removeLibraryBook,
        { AppLog.put("详情书架操作失败", it) }, { id ->
            viewModelScope.launch {
                try { currentBook?.let { currentEntryOwner(it.bookUrl) }?.let { libraryRepository.cancelDelete(it, id) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { AppLog.put("取消详情删除失败", error) }
            }
        })

    val coverController = CoverSearchController(savedState, viewModelScope, coverRepository, ::saveSelectedCover,
        { AppLog.put("封面搜索失败", it) })

    private fun editBaseline(fallback: Book): EditBaseline =
        EditBaseline(currentBook ?: fallback.infoSnapshot(), metadataEditSequence.get())

    init {
        viewModelScope.launch {
            uiState.map { it.book?.bookUrl }.distinctUntilChanged().collectLatest { key ->
                _uiState.update { it.copy(pendingUpload = null, uploadCancellationFailed = false) }
                if (key != null && entryIdentity != null) libraryRepository.observeUpload(key).collect { pending ->
                    _uiState.update { if (it.book?.bookUrl == key) it.copy(pendingUpload = pending) else it }
                }
            }
        }
        viewModelScope.launch {
            combine(libraryController.state, uiState.map { it.book?.bookUrl }.distinctUntilChanged()) { action, key ->
                action.takeIf { it.operation == BookInfoLibraryOperation.Upload && it.bookUrl == key }?.requestId
            }.distinctUntilChanged().collectLatest { id ->
                if (id != null && entryIdentity != null) libraryRepository.observeUploadCommand(id).collect { command ->
                    if (command != null) {
                        if (command.status == io.legado.app.data.entities.BookInfoUploadStatus.Completed) bookWriteMutex.withLock {
                            val stored = withContext(IO) { repository.findBook(command.bookUrl) }
                            if (stored != null && currentBook?.bookUrl == stored.bookUrl) publishBook(stored)
                        }
                        libraryController.reconcileUpload(id, command.status,
                            BookInfoLibraryError.entries.find { it.name == command.error })
                    }
                }
            }
        }
        viewModelScope.launch {
            uiState.map { it.book?.bookUrl }.distinctUntilChanged().collectLatest { key ->
                _uiState.update { it.copy(pendingDelete = null, deleteCancellationFailed = false) }
                if (key != null && entryIdentity != null) libraryRepository.observeDelete(key).collect { pending ->
                    _uiState.update { if (it.book?.bookUrl == key) it.copy(pendingDelete = pending) else it }
                }
            }
        }
        viewModelScope.launch { coverController.state.collect { cover -> _uiState.update { it.copy(coverSearch = cover) } } }
        viewModelScope.launch {
            libraryController.state.collect { action -> _uiState.update { it.copy(libraryAction = action) } }
        }
        viewModelScope.launch {
            combine(filesController.state, libraryController.state, uiState.map { it.book?.bookUrl }.distinctUntilChanged()) { files, _, key -> files.completion to key }
                .collect { (completed, key) ->
                    if (completed?.upload == true && completed.bookUrl == key && key != null &&
                        libraryController.beginUpload(key, completed.read, completed.id)) filesController.consumeCompletion(completed.id)
                }
        }
        viewModelScope.launch {
            filesController.state.collect { files -> _uiState.update { it.copy(fileImport = files) } }
        }
        viewModelScope.launch {
            groupsController.state.collect { groups -> _uiState.update { it.copy(groupsEditor = groups.takeIf { it.open }) } }
        }
        viewModelScope.launch {
            variableEditorController.state.collect { editor -> _uiState.update { it.copy(variableEditor = editor) } }
        }
        viewModelScope.launch(IO) {
            try {
                val lease = previewLease.await()
                for (write in previewWrites) {
                    if (!previewStore.save(lease, write.revision, write.preview, write.commitRevision)) {
                        if (previewStore.withCurrentLease(lease) { true } != true) break
                        continue
                    }
                    _uiState.update { state ->
                        if (write.revision == previewSequence.get() && state.bookRevision == write.bookRevision &&
                            state.chaptersRevision == write.chaptersRevision && state.inBookshelf == write.preview.inBookshelf &&
                            state.source?.bookSourceUrl == write.preview.sourceKey) state.copy(recoveryReady = true) else state
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("保存详情预览恢复状态失败", error) }
        }
    }

    private fun persistPreview() = synchronized(previewPublishLock) {
        val state = uiState.value
        val book = state.book?.infoSnapshot() ?: return@synchronized
        val snapshot = BookInfoPreview(book, state.chapters.filter { it.bookUrl == book.bookUrl }.map { it.infoSnapshot() },
            state.source?.bookSourceUrl, state.inBookshelf, book.infoHtml, book.tocHtml, book.downloadUrls?.toList())
        val revision = previewSequence.incrementAndGet()
        _uiState.update { it.copy(recoveryReady = false) }
        previewWrites.trySend(PreviewWrite(revision, snapshot, state.bookRevision, state.chaptersRevision, state.previewCommitRevision))
        Unit
    }


    private fun showToast(message: CharSequence?) {
        enqueueUiAction(BookInfoAction.ShowToast(message?.toString()))
    }

    private fun showToast(resourceId: Int) {
        enqueueUiAction(BookInfoAction.ShowToastResource(resourceId))
    }

    private fun enqueueUiAction(action: BookInfoAction) {
        val pending = BookInfoPendingAction(actionSequence.incrementAndGet(), action)
        _uiState.update { it.copy(pendingActions = it.pendingActions + pending) }
    }

    internal fun consumeUiAction(pending: BookInfoPendingAction) {
        _uiState.update { it.copy(pendingActions = it.pendingActions.filterNot { item -> item.id == pending.id }) }
    }

    private fun publishBook(book: Book, chapters: List<BookChapter>? = null, onShelf: Boolean? = null,
        commit: AppliedPreview? = null) {
        _uiState.update { state ->
            val sameBook = state.book?.bookUrl == book.bookUrl
            state.copy(book = book.infoSnapshot(), bookRevision = state.bookRevision + 1,
                chapters = chapters?.map { it.infoSnapshot() } ?: if (sameBook) state.chapters else emptyList(),
                chaptersRevision = state.chaptersRevision + if (chapters != null || !sameBook) 1 else 0,
                groups = if (sameBook) state.groups else emptyList(),
                kinds = if (sameBook) state.kinds else emptyList(),
                webFiles = if (sameBook) state.webFiles else emptyList(),
                inBookshelf = onShelf ?: state.inBookshelf, recoveryReady = false,
                previewCommitRevision = if (commit != null) commit.fileRevision else state.previewCommitRevision,
                appliedPreviewCommitRevision = if (commit != null) commit.requestRevision else state.appliedPreviewCommitRevision,
                entryRecoveryFailed = if (commit != null) false else state.entryRecoveryFailed)
        }
        persistPreview()
        viewModelScope.launch(Main.immediate) {
            uiState.value.book?.let { current ->
                setIntroContent(current.getDisplayIntro())
                savedState["bookUrl"] = current.bookUrl
                savedState["name"] = current.name
                savedState["author"] = current.author
            }
        }
    }

    private fun publishChapters(chapters: List<BookChapter>) {
        _uiState.update { it.copy(chapters = chapters.map { chapter -> chapter.infoSnapshot() }, chaptersRevision = it.chaptersRevision + 1, recoveryReady = false) }
        persistPreview()
    }

    private var introJob: Job? = null
    private val introGeneration = AtomicLong()
    private var introRequest: Pair<String?, String?>? = null
    fun setIntroContent(content: String?) {
        val bookUrl = uiState.value.book?.bookUrl
        val request = bookUrl to content
        if (introRequest == request) return
        introRequest = request
        introJob?.cancel()
        val key = "$bookUrl:${content?.hashCode()}"
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest((bookUrl.orEmpty() + "\u0000" + content.orEmpty()).toByteArray()).joinToString("") { "%02x".format(it) }
        val changed = savedState.get<String>("introContentFingerprint")?.let { it != fingerprint }
            ?: (savedState.get<String>("introContentKey") != key)
        savedState["introContentKey"] = key
        savedState["introContentFingerprint"] = fingerprint
        val intro = restoreBookInfoIntro(uiState.value.intro, !content.isNullOrBlank(), changed, savedState["introExpanded"])
            .copy(document = null, contentKey = "$key:${introGeneration.incrementAndGet()}", loading = !content.isNullOrBlank(), failed = false)
        savedState["introExpanded"] = intro.expanded
        _uiState.update { it.copy(intro = intro) }
        if (content.isNullOrBlank()) return
        introJob = viewModelScope.launch {
            try {
                val document = introRepository.parse(content, bookUrl.orEmpty())
                if (uiState.value.intro.contentKey == intro.contentKey) _uiState.update { it.copy(intro = it.intro.copy(document = document, loading = false)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("解析书籍简介失败", error)
                if (uiState.value.intro.contentKey == intro.contentKey) _uiState.update { it.copy(intro = it.intro.copy(loading = false, failed = true)) }
            }
        }
    }
    fun onIntroButtonClick(key: String?, name: String, script: String) {
        val current = uiState.value.intro
        if (current.contentKey == key && !current.loading && !current.failed) onButtonClick(name, script)
    }

    fun retryIntro() {
        val content = introRequest?.second
        introRequest = null
        setIntroContent(content)
    }
    fun onIntroMeasured(key: String?, canCollapse: Boolean) {
        _uiState.update { state ->
            if (state.intro.contentKey != key) state else state.copy(intro = state.intro.copy(canCollapse = canCollapse, toggleVisible = canCollapse))
        }
    }

    fun toggleIntroExpanded() {
        val expanded = !uiState.value.intro.expanded
        savedState["introExpanded"] = expanded
        _uiState.update { it.copy(intro = it.intro.copy(expanded = expanded)) }
    }

    private fun cancelBookLoad() {
        bookLoadSequence.incrementAndGet()
        viewModelScope.launch(Main.immediate) { bookLoadJob?.cancel() }
    }

    private fun startBookLoad(scope: CoroutineScope, block: suspend (Long) -> Unit) {
        val sequence = bookLoadSequence.incrementAndGet()
        viewModelScope.launch(Main.immediate) {
            if (sequence != bookLoadSequence.get()) return@launch
            bookLoadJob?.cancel()
            bookLoadJob = scope.launch(Main.immediate) {
                networkLoadingCounter.begin()
                try { block(sequence) }
                finally { networkLoadingCounter.end() }
            }
        }
    }

    private suspend fun ensureCurrentLoad(sequence: Long) {
        currentCoroutineContext().ensureActive()
        if (sequence != bookLoadSequence.get()) throw CancellationException("Superseded book load")
    }

    /** Reapply user intent, including an edit which restores a field to its original value. */
    private fun mergeConcurrentEdits(result: Book, baseline: EditBaseline): Book = result.infoSnapshot().also { merged ->
        val latest = currentBook?.takeIf { it.bookUrl == baseline.book.bookUrl } ?: return@also
        synchronized(metadataEdits) {
            metadataEdits.values.filter { it.bookUrl == baseline.book.bookUrl && it.revision > baseline.revision }
                .forEach { it.edit.applyTo(merged) }
        }
        if (latest.order != baseline.book.order) merged.order = latest.order
        if (latest.durChapterTime != baseline.book.durChapterTime) merged.durChapterTime = latest.durChapterTime
    }

    private fun redirectBookWrites(oldUrl: String, newUrl: String) {
        if (oldUrl == newUrl) return
        bookRedirects.replaceAll { _, value -> if (value == oldUrl) newUrl else value }
        bookRedirects[oldUrl] = newUrl
    }

    fun initData(bookUrl: String, name: String = "", author: String = "") {
        initializeData(bookUrl, name, author)
    }

    internal fun initializeEntry(route: io.legado.app.ui.navigation.BookInfoRoute, bootstrap: BookInfoEntryBootstrap) {
        if (initialized) return
        previewKey = route.previewKey
        savedState["previewKey"] = route.previewKey
        entryIdentity = bootstrap.owner?.let { it.id to it.token }
        initializeData(bootstrap.bookUrl, "", "", bootstrap)
    }

    internal fun retryEntryInitialization(route: io.legado.app.ui.navigation.BookInfoRoute, bootstrap: BookInfoEntryBootstrap) {
        if (currentBook != null || bookBootstrapJob?.isActive == true) return
        initialized = false
        initializeEntry(route, bootstrap)
    }

    private fun initializeData(bookUrl: String, name: String, author: String, entry: BookInfoEntryBootstrap? = null) {
        if (initialized) return
        initialized = true
        _uiState.update { it.copy(initializationFailed = false) }
        val bookUrl = if (entry != null) entry.bookUrl else savedState.get<String>("bookUrl")?.takeIf(String::isNotBlank) ?: bookUrl
        val name = if (entry != null) "" else savedState.get<String>("name")?.takeIf(String::isNotBlank) ?: name
        val author = if (entry != null) "" else savedState.get<String>("author")?.takeIf(String::isNotBlank) ?: author
        savedState["bookUrl"] = bookUrl
        savedState["name"] = name
        savedState["author"] = author
        bookBootstrapJob = viewModelScope.launch(Main.immediate) {
            try {
                val lease = try { entry?.lease ?: kotlinx.coroutines.withContext(IO) { previewStore.open(previewKey) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    previewLease.completeExceptionally(error)
                    AppLog.put("初始化详情预览恢复状态失败", error)
                    null
                }
                if (lease != null) {
                    _uiState.update { it.copy(previewCommitRevision = lease.commitRevision,
                        appliedPreviewCommitRevision = entry?.previewCommitRevision) }
                    previewLease.complete(lease)
                    filesController.state.value.completion?.bookUrl?.let { key ->
                        if (entry != null && key != bookUrl) return@let
                        val imported = kotlinx.coroutines.withContext(IO) { repository.findBook(key) }
                        if (imported != null) {
                            prepareBook(imported, !imported.isNotShelf)
                            return@launch
                        }
                        filesController.state.value.completion?.let { filesController.consumeCompletion(it.id) }
                    }
                    lease.restored?.takeIf { entry == null || it.book.bookUrl == bookUrl }?.let { restored ->
                        restorePreview(restored)
                        return@launch
                    }
                }
                val found = kotlinx.coroutines.withContext(IO) {
                    if (entry == null) repository.findBook(name, author)?.let { return@withContext it to !it.isNotShelf }
                    if (bookUrl.isNotBlank()) {
                        repository.findBook(bookUrl)?.let { return@withContext it to !it.isNotShelf }
                        repository.findSearchBook(bookUrl)?.toBook()?.let { return@withContext it to false }
                    }
                    if (entry == null) repository.findFirstSearchBook(name, author)?.toBook()?.let { it to false } else null
                } ?: throw NoStackTraceException("未找到书籍")
                prepareBook(found.first, found.second)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _uiState.update { it.copy(initializationFailed = true) }
                AppLog.put(error.localizedMessage, error)
                showToast(error.localizedMessage)
            }
        }
    }

    private suspend fun restorePreview(preview: BookInfoPreview, commit: AppliedPreview? = null, loadMissing: Boolean = true) {
        val persisted = kotlinx.coroutines.withContext(IO) { repository.findBook(preview.book.bookUrl) }
        val onShelf = persisted?.isNotShelf == false
        val book = (if (onShelf) persisted else preview.book).infoSnapshot().apply {
            val sameSource = !onShelf || origin == preview.book.origin
            infoHtml = preview.infoHtml.takeIf { sameSource }
            tocHtml = preview.tocHtml.takeIf { sameSource && tocUrl == preview.book.tocUrl }
            downloadUrls = preview.downloadUrls?.takeIf { sameSource }?.toList()
            if (!onShelf && persisted != null) {
                durChapterIndex = persisted.durChapterIndex
                durChapterPos = persisted.durChapterPos
                durChapterTitle = persisted.durChapterTitle
                durVolumeIndex = persisted.durVolumeIndex
                chapterInVolumeIndex = persisted.chapterInVolumeIndex
            }
        }
        val source = kotlinx.coroutines.withContext(IO) {
            if (book.isLocal) null else repository.findBookSource(if (onShelf) book.origin else preview.sourceKey ?: book.origin)
        }
        val persistedChapters = if (onShelf) kotlinx.coroutines.withContext(IO) { repository.chapters(book.bookUrl) } else emptyList()
        bookSource = source
        val chapters = if (onShelf) persistedChapters else preview.chapters.filter { it.bookUrl == book.bookUrl }
        publishBook(book, chapters, onShelf, commit)
        if (!loadMissing) return
        upCoverByRule(book)
        when {
            book.isWebFile && !book.downloadUrls.isNullOrEmpty() -> loadWebFile(book)
            book.isWebFile || book.tocUrl.isBlank() && !book.isLocal -> loadBookInfo(book, runPreUpdateJs = onShelf)
            chapters.isEmpty() -> loadChapter(book, isFromBookInfo = true)
        }
    }

    fun upBook(name: String, author: String) {
        if (uiState.value.shelfMutation) return
        val current = currentBook ?: return
        bookBootstrapJob?.cancel()
        cancelBookLoad()
        bookBootstrapJob = viewModelScope.launch(Main.immediate) {
            try {
                bookWriteMutex.withLock {
                    val updated = kotlinx.coroutines.withContext(IO) {
                        repository.findBook(name.ifBlank { current.name }, author.ifBlank { current.author })
                            ?: repository.findBook(current.bookUrl)
                    }
                    updated?.let { prepareBook(it, !it.isNotShelf) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("重新加载书籍详情失败", error)
                showToast(error.localizedMessage)
            }
        }
    }

    internal fun refreshEntryBook(bookUrl: String) {
        if (uiState.value.shelfMutation) return
        bookBootstrapJob?.cancel()
        cancelBookLoad()
        bookBootstrapJob = viewModelScope.launch(Main.immediate) {
            try {
                applyEntryRefresh(bookUrl)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("重新加载详情书籍失败", error); showToast(error.localizedMessage) }
        }
    }

    internal suspend fun applyEntryRefresh(bookUrl: String): Boolean {
        return try {
            val revision = bookWriteMutex.withLock {
                val current = kotlinx.coroutines.withContext(IO) { repository.findBook(bookUrl) } ?: return false
                prepareBook(current, !current.isNotShelf)
                uiState.value.bookRevision
            }
            uiState.first { it.book?.bookUrl == bookUrl && it.bookRevision >= revision && it.recoveryReady }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { AppLog.put("刷新详情返回失败", error); showToast(error.localizedMessage); false }
    }

    internal suspend fun awaitEntryInitialization() { bookBootstrapJob?.join() }

    internal fun pauseLoadingForChild() {
        cancelBookLoad()
        bookBootstrapJob?.cancel()
        changeSourceJob?.cancel()
        coverLoadJob?.cancel()
    }

    internal fun needsEntryReconciliation(request: BookInfoRequest): Boolean =
        currentBook != null && (currentBook?.bookUrl != request.bookUrl ||
            uiState.value.appliedPreviewCommitRevision != request.previewCommitRevision)

    internal fun retryEntryRecovery() {
        _uiState.update { it.copy(entryRecoveryFailed = false, entryRecoveryAttempt = it.entryRecoveryAttempt + 1) }
    }

    internal suspend fun reconcileEntryBook(request: BookInfoRequest) {
        val identity = entryIdentity ?: return
        if (request.id != identity.first || request.ownerToken != identity.second || !needsEntryReconciliation(request)) return
        try {
            bookWriteMutex.withLock { adoptCommittedEntry() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            AppLog.put("恢复详情提交失败", error)
            _uiState.update { it.copy(entryRecoveryFailed = true) }
        }
    }

    private suspend fun ownedEntry(): BookInfoRequest {
        val identity = checkNotNull(entryIdentity) { "Detail request is not initialized" }
        return checkNotNull(navigationRepository.get(identity.first)?.takeIf {
            it.ownerToken == identity.second && !it.deleted
        }) { "Detail request no longer owns this book" }
    }

    /** The caller holds the book write mutex; no replacement load is started during this handoff. */
    private suspend fun adoptCommittedEntry(): BookInfoRequest {
        val request = ownedEntry()
        if (!needsEntryReconciliation(request)) return request
        val snapshot = kotlinx.coroutines.withContext(IO) {
            previewStore.resolveSnapshot(previewLease.await(), request.id, request.revision, request.bookUrl,
                request.previewCommitRevision)
        }
        val commit = AppliedPreview(snapshot?.commitRevision, request.previewCommitRevision)
        if (snapshot?.preview?.book?.bookUrl == request.bookUrl) restorePreview(snapshot.preview, commit, false)
        else {
            val book = kotlinx.coroutines.withContext(IO) { repository.findBook(request.bookUrl) }
            checkNotNull(book) { "Committed detail preview is unavailable" }
            prepareBook(book, !book.isNotShelf, commit, false)
        }
        return request
    }

    private suspend fun committedPreview(owner: BookInfoRequestOwner, bookUrl: String): AppliedPreview {
        val snapshot = kotlinx.coroutines.withContext(IO) {
            previewStore.resolveSnapshot(previewLease.await(), owner.id, owner.revision + 1, bookUrl, owner.revision + 1)
        }
        check(snapshot?.preview?.book?.bookUrl == bookUrl) { "Committed detail preview is unavailable" }
        return AppliedPreview(snapshot.commitRevision, owner.revision + 1)
    }

    internal fun requestChild(kind: BookInfoChildKind) {
        val state = uiState.value
        if (state.book == null || state.shelfMutation || state.metadataWrites > 0 || state.childPreparation != null) return
        startChildPreparation(BookInfoChildPreparation(UUID.randomUUID().toString(), kind))
    }

    internal fun requestReturnToReader() {
        if (readerReturnJob?.isActive == true || uiState.value.readerReturnPreparation?.ready == true) return
        val preparation = BookInfoReaderReturnPreparation(UUID.randomUUID().toString())
        _uiState.update { it.copy(readerReturnPreparation = preparation) }
        readerReturnJob = viewModelScope.launch {
            try {
                listOf(bookLoadJob, bookBootstrapJob, changeSourceJob, coverLoadJob).filterNotNull().forEach { it.cancelAndJoin() }
                uiState.first { it.metadataWrites == 0 }
                if (uiState.value.shelfMutation && uiState.value.inBookshelf) {
                    val parent = ownedEntry()
                    navigationRepository.prepareReaderReturn(parent.navigationOwner(), checkNotNull(currentBook), uiState.value.chapters)
                } else bookWriteMutex.withLock {
                    val parent = adoptCommittedEntry()
                    navigationRepository.prepareReaderReturn(parent.navigationOwner(), checkNotNull(currentBook), uiState.value.chapters)
                }
                _uiState.update { it.copy(readerReturnPreparation = preparation.copy(ready = true)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("准备返回阅读器失败", error)
                _uiState.update { it.copy(readerReturnPreparation = preparation.copy(failed = true)) }
            }
        }
    }

    internal fun cancelReturnPreparation() {
        if (readerReturnJob?.isActive != true) _uiState.update { it.copy(readerReturnPreparation = null) }
    }

    internal fun retryChildPreparation() {
        val preparation = uiState.value.childPreparation?.takeIf { it.failed } ?: return
        startChildPreparation(preparation.copy(failed = false))
    }

    internal fun cancelChildPreparation() {
        if (uiState.value.childPreparation?.failed == true) _uiState.update { it.copy(childPreparation = null) }
    }

    internal fun acknowledgeChildRequest(id: String?, parentRevision: Long?) {
        _uiState.update { state ->
            val preparation = state.childPreparation ?: return@update state
            if (preparation.id == id || (preparation.committedRevision != null && parentRevision != null &&
                    parentRevision >= preparation.committedRevision)) state.copy(childPreparation = null) else state
        }
    }

    private fun startChildPreparation(preparation: BookInfoChildPreparation) {
        if (childPreparationJob?.isActive == true) return
        _uiState.update { it.copy(childPreparation = preparation) }
        childPreparationJob = viewModelScope.launch(Main.immediate) {
            try {
                pauseLoadingForChild()
                listOfNotNull(bookLoadJob, bookBootstrapJob, changeSourceJob, coverLoadJob).distinct().forEach { it.join() }
                bookWriteMutex.withLock {
                    val request = adoptCommittedEntry()
                    pauseLoadingForChild()
                    val state = uiState.value
                    check(!state.shelfMutation && state.metadataWrites == 0) { "Book changes are still being saved" }
                    if (preparation.kind == BookInfoChildKind.Catalog && state.chapters.isEmpty()) {
                        showToast(R.string.chapter_list_empty)
                        _uiState.update { it.copy(childPreparation = null) }
                        return@withLock
                    }
                    val book = checkNotNull(state.book).infoSnapshot().apply {
                        if (!state.inBookshelf) addType(BookType.notShelf)
                    }
                    val child = checkNotNull(navigationRepository.prepareAndBegin(request.navigationOwner(), preparation.kind,
                        book, state.chapters.map { it.infoSnapshot() }, preparation.id))
                    _uiState.update { if (it.childPreparation?.id == preparation.id)
                        it.copy(childPreparation = preparation.copy(committedRevision = child.revision)) else it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("准备详情子页面失败", error)
                _uiState.update { if (it.childPreparation?.id == preparation.id)
                    it.copy(childPreparation = preparation.copy(failed = true)) else it }
            }
        }
    }

    private suspend fun currentEntryOwner(bookUrl: String): io.legado.app.data.repository.BookInfoRequestOwner? {
        val identity = entryIdentity ?: return null
        val parent = navigationRepository.get(identity.first)
        check(parent != null && parent.ownerToken == identity.second && parent.bookUrl == bookUrl && !parent.deleted &&
            parent.previewCommitRevision == uiState.value.appliedPreviewCommitRevision) {
            "Detail request no longer owns this book"
        }
        return io.legado.app.data.repository.BookInfoRequestOwner(parent.id, parent.ownerToken, parent.revision, parent.bookUrl)
    }

    private suspend fun prepareBook(book: Book, onShelf: Boolean, commit: AppliedPreview? = null, loadMissing: Boolean = true) {
        val source = kotlinx.coroutines.withContext(IO) {
            if (book.isLocal) null else repository.findBookSource(book.origin)
        }
        if (!loadMissing) {
            val chapters = kotlinx.coroutines.withContext(IO) { repository.chapters(book.bookUrl) }
            bookSource = source
            publishBook(book, chapters, onShelf, commit)
            return
        }
        bookSource = source
        publishBook(book, onShelf = onShelf, commit = commit)
        upCoverByRule(book)
        if (book.tocUrl.isEmpty() && !book.isLocal) {
            loadBookInfo(book, runPreUpdateJs = onShelf)
        } else {
            val chapters = kotlinx.coroutines.withContext(IO) { repository.chapters(book.bookUrl) }
            currentCoroutineContext().ensureActive()
            if (currentBook?.bookUrl != book.bookUrl) return
            if (chapters.isNotEmpty()) publishChapters(chapters)
            else loadChapter(book, isFromBookInfo = true)
        }
    }

    private fun upCoverByRule(book: Book) {
        coverLoadJob?.cancel()
        coverLoadJob = viewModelScope.launch(Main.immediate) {
            if (!book.getDisplayCover().isNullOrBlank()) return@launch
            try {
                val cover = repository.searchCover(book.infoSnapshot()) ?: return@launch
                val current = currentBook ?: return@launch
                if (current.bookUrl == book.bookUrl && current.getDisplayCover().isNullOrBlank()) changeCover(cover)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("获取封面失败", error) }
        }
    }

    fun refreshBook(book: Book) {
        val snapshot = book.infoSnapshot()
        val baseline = editBaseline(snapshot)
        startBookLoad(viewModelScope) { sequence ->
            if (snapshot.isLocal) {
                try { repository.refreshLocalFile(snapshot) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (error is ObjectNotFoundException) snapshot.origin = BookType.localTag
                    else AppLog.put("下载远程书籍<${snapshot.name}>失败", error)
                }
            } else bookSource?.let { snapshot.originName = it.bookSourceName }
            ensureCurrentLoad(sequence)
            loadInfoAndChapters(snapshot, false, true, sequence, baseline)
        }
    }

    fun loadBookInfo(
        book: Book,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope
    ) {
        val snapshot = book.infoSnapshot()
        val baseline = editBaseline(snapshot)
        startBookLoad(scope) { sequence -> loadInfoAndChapters(snapshot, canReName, runPreUpdateJs, sequence, baseline) }
    }

    private suspend fun loadInfoAndChapters(book: Book, canRename: Boolean, runPreUpdateJs: Boolean, sequence: Long, baseline: EditBaseline) {
        var oldBook = baseline.book
        try {
            val loaded = if (book.isLocal) {
                repository.loadLocalBookInfo(book)
                book
            } else {
                val source = bookSource ?: run { showToast(R.string.error_no_source); return }
                repository.loadBookInfo(source.infoSnapshot(), book, canRename)
            }
            ensureCurrentLoad(sequence)
            if (entryIdentity == null && !loaded.isLocal && !inBookshelf) {
                repository.findLibraryMatch(loaded)?.let { stored ->
                    ensureCurrentLoad(sequence)
                    stored.updateTo(loaded)
                    oldBook = stored
                    inBookshelf = true
                }
            }
            if (loaded.isWebFile) {
                bookWriteMutex.withLock {
                    ensureCurrentLoad(sequence)
                    val merged = mergeConcurrentEdits(loaded, baseline)
                    val owner = currentEntryOwner(oldBook.bookUrl)
                    val refreshed = if (owner != null) {
                        navigationRepository.refreshPreview(owner, previewLease.await(), previewStore,
                            previewSequence.incrementAndGet(), BookInfoPreview(merged, emptyList(), bookSource?.bookSourceUrl,
                                inBookshelf, merged.infoHtml, merged.tocHtml, merged.downloadUrls), oldBook, false,
                            uiState.value.previewCommitRevision)
                    } else {
                        if (inBookshelf) repository.saveSnapshot(merged, true)
                        null
                    }
                    val commit = owner?.let { committedPreview(it, merged.bookUrl) }
                    ensureCurrentLoad(sequence)
                    val published = mergeConcurrentEdits(refreshed?.book ?: merged, baseline)
                    redirectBookWrites(oldBook.bookUrl, published.bookUrl)
                    publishBook(published, onShelf = refreshed?.inBookshelf, commit = commit)
                    loadWebFile(published)
                }
            } else loadChaptersAndCommit(loaded, runPreUpdateJs, true, oldBook, baseline, sequence)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ensureCurrentLoad(sequence)
            currentBook?.let { publishBook(it) }
            AppLog.put("获取书籍信息失败\n${error.localizedMessage}", error)
            showToast(R.string.error_get_book_info)
        }
    }

    fun loadChapter(
        book: Book,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope,
        isFromBookInfo: Boolean = false,
        oldBook: Book = book.infoSnapshot(),
    ) {
        val snapshot = book.infoSnapshot()
        val previous = oldBook.infoSnapshot()
        val baseline = editBaseline(previous)
        startBookLoad(scope) { sequence ->
            loadChaptersAndCommit(snapshot, runPreUpdateJs, isFromBookInfo, previous, baseline, sequence)
        }
    }

    private suspend fun loadChaptersAndCommit(
        book: Book, runPreUpdateJs: Boolean, fromBookInfo: Boolean,
        oldBook: Book, baseline: EditBaseline, sequence: Long,
    ) {
        try {
            val chapters = if (book.isLocal) repository.loadLocalChapters(book) else {
                val source = bookSource ?: run { showToast(R.string.error_no_source); return }
                repository.loadChapterList(source.infoSnapshot(), book, runPreUpdateJs, fromBookInfo)
            }
            ensureCurrentLoad(sequence)
            bookWriteMutex.withLock {
                ensureCurrentLoad(sequence)
                val merged = mergeConcurrentEdits(book, baseline)
                val owner = currentEntryOwner(oldBook.bookUrl)
                val refreshed = if (owner != null) {
                    merged.removeType(BookType.updateError)
                    navigationRepository.refreshPreview(owner, previewLease.await(), previewStore,
                        previewSequence.incrementAndGet(), BookInfoPreview(merged, chapters, bookSource?.bookSourceUrl,
                            inBookshelf, merged.infoHtml, merged.tocHtml, merged.downloadUrls), oldBook,
                        expectedCommitRevision = uiState.value.previewCommitRevision)
                } else {
                    if (inBookshelf || book.isLocal) {
                        merged.removeType(BookType.updateError)
                        repository.commitLoadedChapters(oldBook, merged, chapters)
                    }
                    null
                }
                val commit = owner?.let { committedPreview(it, merged.bookUrl) }
                ensureCurrentLoad(sequence)
                val published = mergeConcurrentEdits(refreshed?.book ?: merged, baseline)
                redirectBookWrites(oldBook.bookUrl, published.bookUrl)
                publishBook(published, chapters, refreshed?.inBookshelf, commit)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ensureCurrentLoad(sequence)
            currentBook?.let { publishBook(it) }
            AppLog.put("获取目录失败\n${error.localizedMessage}", error)
            if (book.isLocal) showToast("LoadTocError:${error.localizedMessage}")
            else showToast(R.string.error_get_chapter_list)
        }
    }

    fun loadGroup(groupId: Long) {
        val key = currentBook?.bookUrl
        groupNamesJob?.cancel()
        groupNamesJob = viewModelScope.launch(Main.immediate) {
            try {
                val names = kotlinx.coroutines.withContext(IO) { repository.groupNames(groupId) }
                if (currentBook?.bookUrl == key) _uiState.update { it.copy(groups = names.toList()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (currentBook?.bookUrl == key) _uiState.update { it.copy(groups = emptyList()) }
                AppLog.put("加载书籍分组名称失败", error)
            }
        }
    }

    fun loadKinds(book: Book) {
        val snapshot = book.infoSnapshot()
        kindsJob?.cancel()
        kindsJob = viewModelScope.launch(Main.immediate) {
            try {
                val kinds = repository.kinds(snapshot)
                if (currentBook?.bookUrl == snapshot.bookUrl) _uiState.update { it.copy(kinds = kinds.toList()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (currentBook?.bookUrl == snapshot.bookUrl) _uiState.update { it.copy(kinds = emptyList()) }
            }
        }
    }

    fun refreshCover() {
        _uiState.update { it.copy(coverRevision = it.coverRevision + 1) }
    }

    fun refreshBookSource(book: Book) {
        bookSource = repository.findBookSource(book.origin)
    }

    fun hasBookSource(sourceUrl: String): Boolean = repository.hasBookSource(sourceUrl)

    private suspend fun loadWebFile(book: Book) {
        val baseline = editBaseline(book)
        _uiState.update { it.copy(webFiles = emptyList()) }
        try {
            val source = bookSource?.infoSnapshot()
            val fallback = if (book.author.isBlank()) book.name else "${book.name} 作者：${book.author}"
            val files = repository.downloadCandidates(book, source).map {
                BookInfoWebFile(it.url, normalizeWebFileName(it.fileName ?: fallback, it.suffix, it.fileName != null))
            }
            book.latestChapterTitle = "已下载"
            publishBook(mergeConcurrentEdits(book, baseline), emptyList())
            _uiState.update { it.copy(webFiles = files) }
            filesController.syncCandidates(book.bookUrl, files)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            showToast("LoadWebFileError\n${error.localizedMessage}")
        }
    }

    fun openWebFiles(readAfterImport: Boolean = false) {
        if (uiState.value.shelfMutation) return
        val book = currentBook ?: return
        if (uiState.value.webFiles.isEmpty()) { showToast("Unexpected webFileData"); return }
        filesController.open(book.bookUrl, repository.uploadImportedBookByDefault(), readAfterImport)
    }

    fun setImportedFileUpload(value: Boolean) {
        if (filesController.state.value.busy) return
        filesController.setUpload(value)
        repository.setUploadImportedBookByDefault(value)
    }

    private suspend fun performFileOperation(key: String, operation: BookInfoFileOperation): BookInfoFileResult {
        if (uiState.value.shelfMutation) throw BookInfoFileException(BookInfoFileError.Changed)
        val snapshot = currentBook?.takeIf { it.bookUrl == key } ?: throw BookInfoFileException(BookInfoFileError.Changed)
        val source = bookSource?.infoSnapshot()
        cancelBookLoad()
        bookBootstrapJob?.cancel()
        _uiState.update { it.copy(shelfMutation = true) }
        fun checkCurrent() {
            val current = currentBook
            if (current?.bookUrl != key || current.origin != snapshot.origin) throw BookInfoFileException(BookInfoFileError.Changed)
        }
        try {
            val imported = when (operation) {
                is BookInfoFileOperation.Import -> repository.importOnlineFile(operation.file.url, snapshot.getExportFileName(operation.file.suffix), source)
                is BookInfoFileOperation.ImportEntry -> repository.importArchive(Uri.parse(operation.uri), snapshot.getExportFileName(operation.entry.substringAfterLast('.')), operation.entry)
                is BookInfoFileOperation.Download -> {
                    val uri = repository.downloadFile(operation.file.url, snapshot.getExportFileName(operation.file.suffix), source)
                    checkCurrent()
                    return BookInfoFileResult.Downloaded(uri.toString())
                }
            }
            return bookWriteMutex.withLock {
                checkCurrent()
                val local = repository.mergeLocalBook(imported, currentBook, currentEntryOwner(key))
                currentCoroutineContext().ensureActive()
                bookSource = null
                publishBook(local, emptyList(), onShelf = true)
                loadChapter(local)
                BookInfoFileResult.Imported(local.bookUrl)
            }
        } finally { _uiState.update { it.copy(shelfMutation = false) } }
    }

    fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit = {},
    ) {
        performSourceChange(source, book, toc, onSuccess, null)
    }

    fun requestSourceChange(source: BookSource, book: Book, toc: List<BookChapter>,
        deleteAfter: io.legado.app.data.entities.SearchBook? = null, dismiss: Boolean = true) {
        performSourceChange(source, book, toc, null,
            BookInfoSourceChangeCompleted(UUID.randomUUID().toString(), book.bookUrl, dismiss, deleteAfter))
    }

    fun consumeSourceChange(id: String) {
        _uiState.update { if (it.sourceChangeCompleted?.id == id) it.copy(sourceChangeCompleted = null) else it }
    }

    private fun performSourceChange(source: BookSource, book: Book, toc: List<BookChapter>,
        onSuccess: (() -> Unit)?, completion: BookInfoSourceChangeCompleted?) {
        if (uiState.value.shelfMutation) return
        cancelBookLoad()
        changeSourceJob?.cancel()
        val candidate = book.infoSnapshot()
        val chapters = toc.map { it.infoSnapshot() }
        changeSourceJob = viewModelScope.launch(Main.immediate) {
            try {
                bookWriteMutex.withLock {
                    val baseline = editBaseline(candidate)
                    val previous = currentBook
                    previous?.migrateTo(candidate, chapters)
                    if (inBookshelf && entryIdentity == null) {
                        candidate.removeType(BookType.updateError)
                        repository.changeSource(previous, candidate, chapters)
                    }
                    currentCoroutineContext().ensureActive()
                    val merged = if (previous != null) mergeConcurrentEdits(candidate, baseline) else candidate
                    val owner = previous?.let { currentEntryOwner(it.bookUrl) }
                    if (owner != null) {
                        navigationRepository.replacePreview(owner, previewLease.await(), previewStore,
                            previewSequence.incrementAndGet(), BookInfoPreview(merged, chapters, source.bookSourceUrl,
                                inBookshelf, merged.infoHtml, merged.tocHtml, merged.downloadUrls), checkNotNull(previous),
                            uiState.value.previewCommitRevision)
                    }
                    val commit = owner?.let { committedPreview(it, merged.bookUrl) }
                    val published = mergeConcurrentEdits(merged, baseline)
                    previous?.let { redirectBookWrites(it.bookUrl, published.bookUrl) }
                    bookSource = source
                    publishBook(published, chapters, commit = commit)
                    if (published.isWebFile) loadWebFile(published)
                }
                postEvent(EventBus.SOURCE_CHANGED, candidate.bookUrl)
                completion?.removedSource?.let { removed ->
                    kotlinx.coroutines.withContext(IO + kotlinx.coroutines.NonCancellable) { repository.removeCompletedSource(removed) }
                }
                if (completion != null) _uiState.update { it.copy(sourceChangeCompleted = completion) }
                onSuccess?.invoke()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("换源失败", error)
                showToast(error.localizedMessage)
            }
        }
    }

    fun topBook() {
        val key = currentBook?.bookUrl ?: return
        viewModelScope.launch(Main.immediate) {
            try {
                bookWriteMutex.withLock {
                    val saved = (bookRedirects[key] ?: key).let { repository.pin(it, currentEntryOwner(it)) }
                    currentBook?.takeIf { it.bookUrl == saved.bookUrl }?.let {
                        it.order = saved.order
                        it.durChapterTime = saved.durChapterTime
                        publishBook(it)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("置顶失败", error); showToast(error.localizedMessage) }
        }
    }

    fun saveBook(
        book: Book?,
        preserveCustomCoverUrl: Boolean = true,
        withChapters: Boolean = false,
        preserveReadingProgress: Boolean = true,
        success: (() -> Unit)? = null,
    ) {
        book ?: return
        cancelBookLoad()
        val snapshot = book.infoSnapshot()
        if (!inBookshelf) snapshot.addType(BookType.notShelf)
        val baseline = editBaseline(snapshot)
        val chapters = if (withChapters) uiState.value.chapters.map { it.infoSnapshot() } else null
        viewModelScope.launch(Main.immediate) {
            try {
                val saved = bookWriteMutex.withLock {
                    if (currentBook?.bookUrl != snapshot.bookUrl) return@withLock false
                    val merged = mergeConcurrentEdits(snapshot, baseline)
                    repository.saveSnapshot(merged, preserveCustomCoverUrl, chapters, preserveReadingProgress, currentEntryOwner(merged.bookUrl))
                    publishBook(mergeConcurrentEdits(merged, baseline))
                    true
                }
                if (saved) success?.invoke() else showToast("书籍已更新，请重试")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("保存书籍失败", error)
                showToast(error.localizedMessage)
            }
        }
    }

    fun addToBookshelf(success: (() -> Unit)?) {
        val book = currentBook ?: return
        if (uiState.value.shelfMutation) return
        cancelBookLoad()
        _uiState.update { it.copy(shelfMutation = true) }
        val source = bookSource?.infoSnapshot()
        val chapters = uiState.value.chapters.map { it.infoSnapshot() }
        viewModelScope.launch(Main.immediate) {
            try {
                bookWriteMutex.withLock {
                    kotlinx.coroutines.withContext(IO) {
                        repository.addToBookshelf(book, chapters, currentEntryOwner(book.bookUrl))
                        SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, source, book)
                    }
                    publishBook(book, onShelf = true)
                }
                success?.invoke()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("添加书架失败", error)
                showToast(error.localizedMessage)
            } finally { _uiState.update { it.copy(shelfMutation = false) } }
        }
    }

    fun openCoverSearch() {
        if (uiState.value.shelfMutation) return
        currentBook?.let { coverController.open(it.bookUrl, it.name, it.author) }
    }
    private suspend fun saveSelectedCover(key: String, selection: BookCoverSelection) {
        bookWriteMutex.withLock {
            check(currentBook?.bookUrl == key && !uiState.value.shelfMutation) { "Book changed. Reopen cover selection." }
            val edit = BookInfoEdit.Cover(selection.url, selection.source)
            if (inBookshelf) repository.applyEdit(key, edit, currentEntryOwner(key))
            val book = checkNotNull(currentBook?.takeIf { it.bookUrl == key })
            edit.applyTo(book)
            synchronized(metadataEdits) {
                metadataEdits[edit.javaClass] = RecordedMetadataEdit(metadataEditSequence.incrementAndGet(), key, edit)
            }
            publishBook(book)
        }
    }

    fun changeCover(coverUrl: String) {
        editMetadata(BookInfoEdit.Cover(coverUrl))
    }

    fun setBookVariable(variable: String?) {
        editMetadata(BookInfoEdit.Variable(variable))
    }

    fun openSourceVariableEditor() {
        val source = bookSource ?: return
        variableEditorController.openSource(source.getKey(), source.variableComment)
    }

    fun openBookVariableEditor() {
        if (uiState.value.shelfMutation) return
        val book = currentBook ?: return
        variableEditorController.openBook(book.bookUrl, book.getCustomVariable(), bookSource?.variableComment)
    }

    fun updateVariableDraft(value: String) = variableEditorController.updateDraft(value)
    fun saveVariableEditor() = variableEditorController.save()
    fun dismissVariableEditor() = variableEditorController.dismiss()
    fun retryVariableEditor() = variableEditorController.retryLoad()

    private suspend fun saveEditorBookVariable(key: String, value: String?) {
        bookWriteMutex.withLock {
            check(currentBook?.bookUrl == key && !uiState.value.shelfMutation) { "Book changed. Reopen the variable editor." }
            val edit = BookInfoEdit.Variable(value)
            if (inBookshelf) repository.applyEdit(key, edit, currentEntryOwner(key))
            val book = checkNotNull(currentBook?.takeIf { it.bookUrl == key })
            edit.applyTo(book)
            synchronized(metadataEdits) {
                metadataEdits[edit.javaClass] = RecordedMetadataEdit(metadataEditSequence.incrementAndGet(), key, edit)
            }
            publishBook(book)
        }
    }

    fun setCanUpdate(enabled: Boolean) {
        editMetadata(BookInfoEdit.CanUpdate(enabled))
    }

    fun changeGroup(groupId: Long) {
        if (uiState.value.shelfMutation) return
        editMetadata(BookInfoEdit.Group(groupId))
        loadGroup(groupId)
    }

    fun openGroupSelection() {
        if (uiState.value.shelfMutation) return
        val book = currentBook ?: return
        groupsController.openSelection(book.bookUrl, book.group)
    }

    private suspend fun applyGroupSelection(key: String, mask: Long, owners: Map<Long, String>) {
        cancelBookLoad()
        bookWriteMutex.withLock {
            check(currentBook?.bookUrl == key && !uiState.value.shelfMutation) { "Book changed. Reopen group selection." }
            val book = checkNotNull(currentBook)
            val wasOnShelf = inBookshelf
            val edit = BookInfoEdit.Group(mask)
            edit.applyTo(book)
            _uiState.update { it.copy(shelfMutation = true) }
            try {
                val assignment = repository.assignGroups(book, uiState.value.chapters, wasOnShelf, owners, currentEntryOwner(key))
                if (!wasOnShelf && assignment.inBookshelf) {
                    kotlinx.coroutines.withContext(IO) {
                        runCatching { SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, bookSource, book) }
                            .onFailure { AppLog.put("书源加入书架回调失败", it) }
                    }
                }
                val confirmedEdit = BookInfoEdit.Group(assignment.mask)
                synchronized(metadataEdits) {
                    metadataEdits[confirmedEdit.javaClass] = RecordedMetadataEdit(metadataEditSequence.incrementAndGet(), key, confirmedEdit)
                }
                publishBook(book, onShelf = assignment.inBookshelf)
                loadGroup(assignment.mask)
            } finally { _uiState.update { it.copy(shelfMutation = false) } }
        }
    }

    private fun editMetadata(edit: BookInfoEdit) {
        if (uiState.value.shelfMutation) return
        val book = currentBook ?: return
        edit.applyTo(book)
        synchronized(metadataEdits) {
            metadataEdits[edit.javaClass] = RecordedMetadataEdit(metadataEditSequence.incrementAndGet(), book.bookUrl, edit)
        }
        // Publish before starting IO: the next action must see this edit immediately.
        publishBook(book)
        if (!inBookshelf) {
            if (edit is BookInfoEdit.Group && edit.id > 0) addToBookshelf(null)
            return
        }
        _uiState.update { it.copy(metadataWrites = it.metadataWrites + 1) }
        viewModelScope.launch {
            try {
                bookWriteMutex.withLock {
                    (bookRedirects[book.bookUrl] ?: book.bookUrl).let { repository.applyEdit(it, edit, currentEntryOwner(it)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("保存书籍修改失败", error)
                showToast(error.localizedMessage)
            } finally {
                _uiState.update { it.copy(metadataWrites = it.metadataWrites - 1) }
            }
        }
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = currentBook
        if (toastNull && book == null) {
            showToast("book is null")
        }
        return book
    }

    fun requestUpload() {
        if (!uiState.value.shelfMutation && uiState.value.pendingUpload?.status !in setOf(
                io.legado.app.data.entities.BookInfoUploadStatus.Queued, io.legado.app.data.entities.BookInfoUploadStatus.Running))
            currentBook?.let { libraryController.beginUpload(it.bookUrl) }
    }
    fun requestDelete() {
        if (!uiState.value.shelfMutation) currentBook?.let { libraryController.beginDelete(it.bookUrl) }
    }
    fun cancelPendingDelete(id: String) {
        val pending = uiState.value.pendingDelete?.takeIf { it.id == id && it.cancellable } ?: return
        if (uiState.value.cancellingDelete) return
        _uiState.update { it.copy(cancellingDelete = true, deleteCancellationFailed = false) }
        viewModelScope.launch {
            try {
                val owner = checkNotNull(currentEntryOwner(pending.bookUrl))
                if (libraryRepository.cancelDelete(owner, id)) libraryController.deletionCancelled(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("取消详情删除失败", error)
                _uiState.update { it.copy(deleteCancellationFailed = true) }
            } finally { _uiState.update { it.copy(cancellingDelete = false) } }
        }
    }
    fun cancelPendingUpload(id: String) {
        val pending = uiState.value.pendingUpload?.takeIf { it.id == id } ?: return
        if (uiState.value.cancellingUpload) return
        _uiState.update { it.copy(cancellingUpload = true, uploadCancellationFailed = false) }
        viewModelScope.launch {
            try {
                val owner = checkNotNull(currentEntryOwner(pending.bookUrl))
                if (libraryRepository.cancelUpload(owner, id)) libraryController.uploadCancelled(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("取消详情上传失败", error)
                _uiState.update { it.copy(uploadCancellationFailed = true) }
            } finally { _uiState.update { it.copy(cancellingUpload = false) } }
        }
    }
    private fun requireLibraryBook(key: String) {
        if (currentBook?.bookUrl != key || uiState.value.shelfMutation) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    }
    private suspend fun uploadLibraryBook(key: String, target: String, overwrite: Boolean) {
        requireLibraryBook(key)
        cancelBookLoad()
        bookBootstrapJob?.cancel()
        _uiState.update { it.copy(shelfMutation = true) }
        try {
            bookWriteMutex.withLock {
                val updated = libraryRepository.upload(key, target, overwrite, currentEntryOwner(key), currentBook,
                    libraryController.state.value.requestId)
                if (currentBook?.bookUrl == key) publishBook(updated)
            }
        } finally { _uiState.update { it.copy(shelfMutation = false) } }
    }
    private suspend fun removeLibraryBook(key: String, original: Boolean, remote: Boolean, target: String?) {
        requireLibraryBook(key)
        cancelBookLoad()
        bookBootstrapJob?.cancel()
        _uiState.update { it.copy(shelfMutation = true) }
        try {
            bookWriteMutex.withLock {
                libraryRepository.remove(key, original, remote, target, currentEntryOwner(key),
                    libraryController.state.value.requestId, currentBook)
                inBookshelf = false
            }
        } finally { _uiState.update { it.copy(shelfMutation = false) } }
    }

    fun delBook(deleteOriginal: Boolean = false) {
        cancelBookLoad()
        viewModelScope.launch(Main.immediate) {
            try {
                bookWriteMutex.withLock {
                    currentBook?.let { book ->
                        kotlinx.coroutines.withContext(IO) {
                            repository.deleteBook(book)
                            if (book.isLocal) repository.deleteLocalFile(book, deleteOriginal)
                        }
                        inBookshelf = false
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("删除书籍失败", error); showToast(error.localizedMessage) }
        }
    }

    fun clearCache(book: Book) {
        val snapshot = book.infoSnapshot()
        viewModelScope.launch(Main.immediate) {
            try {
                repository.clearCache(snapshot)
                showToast(R.string.clear_cache_success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showToast("清理缓存出错\n${error.localizedMessage}") }
        }
    }

    fun upEditBook() {
        val key = currentBook?.bookUrl ?: return
        cancelBookLoad()
        viewModelScope.launch(Main.immediate) {
            val updated = kotlinx.coroutines.withContext(IO) { repository.findBook(key) }
            if (currentBook?.bookUrl == key && updated != null) publishBook(updated)
        }
    }


    fun onButtonClick(name: String, click: String) {
        if (uiState.value.shelfMutation) return
        val source = bookSource ?: return
        val book = currentBook ?: return
        enqueueUiAction(BookInfoAction.RunScript(source.bookSourceUrl, book.bookUrl, name, click, uiState.value.bookRevision))
    }

    fun completeScript(action: BookInfoAction.RunScript, book: Book) {
        viewModelScope.launch(Main.immediate) {
            if (!uiState.value.shelfMutation && uiState.value.childPreparation == null &&
                uiState.value.bookRevision == action.bookRevision &&
                uiState.value.book?.bookUrl == action.bookUrl && bookSource?.bookSourceUrl == action.sourceKey) {
                cancelBookLoad()
                publishBook(book)
            }
        }
    }

    fun reportScriptError(action: BookInfoAction.RunScript, error: Throwable) {
        AppLog.put("${action.sourceKey}: ${error.localizedMessage}", error)
        showToast("${action.name} click error\n${error.localizedMessage}")
    }

}
