package io.legado.app.ui.book.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.ui.navigation.BookInfoRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

internal data class BookInfoEntryBootstrap(val lease: BookInfoPreviewLease, val bookUrl: String,
    val owner: BookInfoRequestOwner? = null, val previewCommitRevision: Long? = null)
internal data class BookInfoEntryRefresh(val childId: String, val bookUrl: String)
internal data class BookInfoEntryState(
    val bootstrap: BookInfoEntryBootstrap? = null,
    val request: BookInfoRequest? = null,
    val child: BookInfoChildRequest? = null,
    val navigation: BookInfoChildRequest? = null,
    val refresh: BookInfoEntryRefresh? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val closed: Boolean = false,
    val refreshRevision: Long = 0,
    val refreshAttempt: Long = 0,
    val returnToReaderRequested: Boolean = false,
)

/** The detail NavEntry owns its request; child results are consumed only while it is resumed. */
@HiltViewModel
class BookInfoEntryViewModel @Inject constructor(
    private val repository: BookInfoNavigationRepository,
    private val previews: BookInfoPreviewStore,
) : ViewModel() {
    private val _state = MutableStateFlow(BookInfoEntryState())
    internal val state = _state.asStateFlow()
    private var route: BookInfoRoute? = null
    private val token = UUID.randomUUID().toString()
    private var bootstrapJob: Job? = null
    private var observation: Job? = null
    private var operation: Job? = null
    private var lease: BookInfoPreviewLease? = null
    private var resumed = false
    private var knownChildren: Set<String>? = null
    @Volatile private var disposed = false
    private val sent = mutableSetOf<String>()
    private var pendingBegin: BookInfoChildKind? = null
    internal val retryChildKind: BookInfoChildKind? get() = pendingBegin
    private var pendingPreparation: Pair<Book, List<BookChapter>>? = null

    fun initialize(route: BookInfoRoute) {
        if (this.route == route) return
        check(this.route == null) { "A detail entry cannot change its request identity" }
        this.route = route
        bootstrap()
    }

    private fun bootstrap() {
        if (bootstrapJob?.isActive == true || disposed) return
        val route = route ?: return
        _state.update { it.copy(loading = true, failed = false) }
        bootstrapJob = viewModelScope.launch {
            try {
                val claimed = withContext(NonCancellable) {
                    val opened = withContext(Dispatchers.IO) { previews.open(route.previewKey) }
                    val request = repository.claimPreview(route.requestId, route.bookUrl, opened, previews, token, route.returnToReader)
                    if (disposed && request != null) repository.dispose(request.navigationOwner(), opened, previews)
                    if (request == null) withContext(Dispatchers.IO) { previews.discard(opened) }
                    opened to request
                }
                if (disposed) return@launch
                lease = claimed.first
                val request = claimed.second
                if (request == null) {
                    _state.update { it.copy(closed = true) }
                    return@launch
                }
                _state.update { it.copy(request = request) }
                val snapshot = repository.entry(request.id)
                val current = snapshot.request ?: request
                val returned = snapshot.child?.takeIf { it.phase == BookInfoChildPhase.Returned &&
                    it.kind == BookInfoChildKind.Reader && !it.readerDeleted }?.returnBookUrl
                val restored = withContext(Dispatchers.IO) {
                    previews.resolveSnapshot(claimed.first, current.id, current.revision, current.bookUrl, current.previewCommitRevision)
                }
                _state.update { it.copy(bootstrap = BookInfoEntryBootstrap(claimed.first.copy(restored = restored?.preview, commitRevision = restored?.commitRevision),
                    returned ?: current.bookUrl, current.navigationOwner(), current.previewCommitRevision),
                    request = current, child = snapshot.child, closed = current.deleted || current.ownerToken != token) }
                observeRequest(request.id)
                if (closeRequested) closeForReader()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail("打开详情请求失败", error) }
            finally { _state.update { it.copy(loading = false) } }
        }
    }


    private var observationFailed = false
    private var closeRequested = false

    internal fun closeForReader() {
        closeRequested = true
        val parent = state.value.request
        if (parent == null) {
            if (bootstrapJob?.isActive == true) return
            val route = route ?: return
            operate { repository.cancelUnopenedReaderDetail(route.requestId, route.bookUrl); _state.update { it.copy(closed = true) } }
            return
        }
        if (parent.ownerToken != token) return
        operate { check(repository.close(parent.navigationOwner())); _state.update { it.copy(closed = true) } }
    }
    private fun observeRequest(id: String) {
        observation?.cancel()
        observationFailed = false
        observation = viewModelScope.launch {
            repository.observeEntry(id).catch {
                observationFailed = true
                fail("观察详情请求失败", it)
            }.collect { snapshot ->
                val parent = snapshot.request
                if (parent != null && parent.ownerToken == token &&
                    parent.revision < (_state.value.request?.revision ?: 0)) return@collect
                if (parent == null || parent.ownerToken != token) {
                    _state.update { it.copy(closed = true, navigation = null, refresh = null) }
                } else {
                    val child = snapshot.child
                    if (child?.kind == pendingBegin) { pendingBegin = null; pendingPreparation = null }
                    _state.update { it.copy(request = parent, child = child, closed = parent.deleted,
                        navigation = it.navigation?.takeIf { nav -> nav.id == child?.id },
                        refresh = it.refresh?.takeIf { refresh -> refresh.childId == child?.id }) }
                    reconcile()
                }
            }
        }
    }

    fun setResumed(value: Boolean) {
        if (resumed == value) return
        resumed = value
        if (value) reconcile(cancelUndisplayed = true)
        else _state.update { it.copy(navigation = null) }
    }

    fun setChildEntries(ids: Set<String>) {
        if (knownChildren == ids) return
        knownChildren = ids
        reconcile()
    }

    /** Called after the UI has pushed the exact child route. */
    fun navigationHandled(childId: String) {
        if (_state.value.navigation?.id != childId) return
        sent += childId
        _state.update { it.copy(navigation = null) }
    }

    fun begin(kind: BookInfoChildKind, book: Book? = null, chapters: List<BookChapter> = emptyList()) {
        val state = _state.value
        val parent = state.request ?: return
        if (!resumed || disposed || state.closed || state.loading || operation?.isActive == true || state.child != null) return
        pendingBegin = kind
        pendingPreparation = book?.let { it to chapters }
        operate {
            val child = checkNotNull(if (book == null) repository.begin(parent.navigationOwner(), kind)
                else repository.prepareAndBegin(parent.navigationOwner(), kind, book, chapters)) {
                "Detail request no longer owns this book"
            }
            val current = checkNotNull(repository.get(parent.id))
            if (current.ownerToken != token) _state.update { it.copy(closed = true) }
            else _state.update { it.copy(request = current, child = child) }
            pendingBegin = null
            pendingPreparation = null
        }
    }

    fun retry() {
        if (closeRequested) { closeForReader(); return }
        if (_state.value.bootstrap == null) bootstrap()
        else if (_state.value.refresh != null) _state.update { it.copy(failed = false, refreshAttempt = it.refreshAttempt + 1) }
        else if (observationFailed) {
            _state.update { it.copy(failed = false) }
            _state.value.request?.let { observeRequest(it.id) }
        } else pendingBegin?.let { begin(it, pendingPreparation?.first, pendingPreparation?.second.orEmpty()) } ?: reconcile()
    }

    fun refreshFailed(childId: String) {
        val state = _state.value
        val refresh = state.refresh?.takeIf { it.childId == childId } ?: return
        val parent = state.request ?: return
        if (state.child?.kind == BookInfoChildKind.Reader) operate {
            if (!repository.bookExists(refresh.bookUrl)) consume(parent, childId)
            else _state.update { it.copy(failed = true) }
        } else _state.update { it.copy(failed = true) }
    }

    /** Acknowledge only after the matching book and its recoverable preview have been refreshed. */
    fun refreshApplied(childId: String, bookUrl: String) {
        val state = _state.value
        val refresh = state.refresh ?: return
        val parent = state.request ?: return
        if (!resumed || disposed || state.closed || refresh.childId != childId || refresh.bookUrl != bookUrl) return
        operate { consume(parent, childId) }
    }

    private suspend fun consume(parent: BookInfoRequest, childId: String) {
        val result = checkNotNull(repository.consume(parent.navigationOwner(), childId))
        _state.update { it.copy(request = result.request, child = null, navigation = null, refresh = null,
            closed = result.request.deleted, refreshRevision = it.refreshRevision + 1) }
    }

    private fun reconcile(cancelUndisplayed: Boolean = false) {
        if (_state.value.returnToReaderRequested || closeRequested) return
        val state = _state.value
        val parent = state.request ?: return
        val child = state.child ?: return
        if (state.refresh?.childId == child.id) return
        if (!resumed || disposed || state.closed || operation?.isActive == true) return
        when (child.phase) {
            BookInfoChildPhase.Requested -> {
                if (child.id !in sent) _state.update { it.copy(navigation = child, failed = false) }
                else if (cancelUndisplayed) operate { repository.cancelRequested(parent.navigationOwner(), child.id) }
            }
            BookInfoChildPhase.Returned, BookInfoChildPhase.Cancelled -> operate {
                if (child.kind == BookInfoChildKind.Catalog && child.phase == BookInfoChildPhase.Returned) {
                    if (parent.returnToReader) _state.update { it.copy(returnToReaderRequested = true) }
                    else {
                        val reader = checkNotNull(repository.continueCatalog(parent.navigationOwner(), child.id))
                        val current = checkNotNull(repository.get(parent.id))
                        if (current.ownerToken != token) _state.update { it.copy(closed = true) }
                        else _state.update { it.copy(request = current, child = reader, navigation = null) }
                    }
                } else {
                    val key = child.returnBookUrl ?: parent.bookUrl
                    val needsRefresh = child.kind == BookInfoChildKind.Edit ||
                        (child.kind == BookInfoChildKind.Reader && child.phase == BookInfoChildPhase.Returned)
                    if (needsRefresh && !child.readerDeleted && repository.bookExists(key)) {
                        _state.update { it.copy(refresh = BookInfoEntryRefresh(child.id, key)) }
                    } else consume(parent, child.id)
                }
            }
            BookInfoChildPhase.Displayed -> if (knownChildren?.contains(child.id) == false) operate {
                repository.abandonDisplayed(parent.navigationOwner(), child.id)
            }
            BookInfoChildPhase.Consumed -> Unit
        }
    }

    private fun operate(action: suspend () -> Unit) {
        if (operation?.isActive == true) return
        _state.update { it.copy(busy = true, failed = false) }
        operation = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail("处理详情返回失败", error) }
            finally {
                operation = null
                _state.update { it.copy(busy = false) }
                if (!_state.value.failed && _state.value.refresh == null) reconcile()
            }
        }
    }

    private fun fail(message: String, error: Throwable) {
        AppLog.put(message, error)
        _state.update { it.copy(failed = true) }
    }

    override fun onCleared() {
        disposed = true
        val owner = _state.value.request?.takeIf { it.ownerToken == token }?.navigationOwner()
        val opened = lease
        if (owner != null && opened != null) repository.dispose(owner, opened, previews)
    }
}
