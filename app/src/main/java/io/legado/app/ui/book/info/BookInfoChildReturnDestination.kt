package io.legado.app.ui.book.info

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.*
import io.legado.app.data.repository.BookInfoNavigationRepository
import io.legado.app.ui.book.info.edit.BookInfoEditScreen
import io.legado.app.ui.book.toc.BookTocDestination
import io.legado.app.ui.book.toc.BookTocSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

internal data class BookInfoChildReturnState(val ready: Boolean = false, val completed: Boolean = false,
    val busy: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class BookInfoChildReturnViewModel @Inject constructor(private val repository: BookInfoNavigationRepository) : ViewModel() {
    private val _state = MutableStateFlow(BookInfoChildReturnState())
    internal val state = _state.asStateFlow()
    private data class Request(val id: String, val bookUrl: String, val kind: BookInfoChildKind)
    private sealed interface Delivery {
        data class Catalog(val position: BookInfoNavigationPosition) : Delivery
        data object Edit : Delivery
        data object Cancel : Delivery
    }
    private var request: Request? = null
    private val token = UUID.randomUUID().toString()
    private var child: BookInfoChildRequest? = null
    internal val editOwner: BookInfoChildRequest? get() = child
    private var claimJob: Job? = null
    private var deliveryJob: Job? = null
    private var observation: Job? = null
    private var pending: Delivery? = null
    private var disposed = false

    fun initialize(id: String, bookUrl: String, kind: BookInfoChildKind) {
        val next = Request(id, bookUrl, kind)
        if (request == next) return
        check(request == null) { "A child entry cannot change its request identity" }
        request = next
        claim()
    }

    private fun claim() {
        if (claimJob?.isActive == true || disposed) return
        val request = request ?: return
        _state.update { it.copy(busy = true, failed = false) }
        claimJob = viewModelScope.launch {
            try {
                // Reconcile a committed claim even when the UI disappears during the transaction.
                val owned = withContext(NonCancellable) {
                    repository.claimChild(request.id, request.bookUrl, request.kind, token).also {
                        if (disposed && it != null) repository.cancel(it)
                    }
                }
                if (disposed) return@launch
                child = owned
                _state.update { it.copy(ready = owned != null, completed = owned == null) }
                if (owned != null) observation = viewModelScope.launch {
                    repository.observeChild(owned.id).catch { error ->
                        AppLog.put("观察详情子页面结果失败", error)
                        _state.update { it.copy(failed = true) }
                    }.collect { current ->
                        if (current == null || current.producerToken != token || current.phase !in
                            setOf(BookInfoChildPhase.Requested, BookInfoChildPhase.Displayed)) {
                            _state.update { it.copy(completed = true) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("打开详情子页面失败", error)
                _state.update { it.copy(failed = true) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun select(selection: BookTocSelection) = deliver(Delivery.Catalog(BookInfoNavigationPosition(
        selection.chapterIndex, selection.chapterPosition, selection.chapterChanged, selection.volumeIndex,
        selection.chapterInVolumeIndex, selection.highlightLayoutTitleLength, selection.highlightAnchorText, selection.pdfPageIndex)))
    fun saved() = deliver(Delivery.Edit)
    fun cancel() = deliver(Delivery.Cancel)
    fun retry() { pending?.let(::deliver) ?: claim() }

    private fun deliver(delivery: Delivery) {
        if (_state.value.completed || deliveryJob?.isActive == true || disposed) return
        pending = delivery
        _state.update { it.copy(busy = true, failed = false) }
        deliveryJob = viewModelScope.launch {
            try {
                claimJob?.join()
                _state.update { it.copy(busy = true) }
                val owned = child ?: request?.let {
                    repository.claimChild(it.id, it.bookUrl, it.kind, token).also { claimed -> child = claimed }
                }
                if (owned != null) check(when (delivery) {
                    is Delivery.Catalog -> repository.deliverCatalog(owned, delivery.position)
                    Delivery.Edit -> repository.editWasReturned(owned) || repository.deliverEdit(owned)
                    Delivery.Cancel -> repository.cancel(owned)
                }) { "Child result no longer accepted" }
                pending = null
                _state.update { it.copy(completed = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.put("保存详情子页面结果失败", error)
                _state.update { it.copy(failed = true) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        disposed = true
        request?.let { repository.disposeChild(it.id, token) }
    }
}

@Composable
private fun BookInfoChildReturnFrame(id: String, bookUrl: String, kind: BookInfoChildKind, onBack: () -> Unit,
    controller: BookInfoChildReturnViewModel = viewModel(), content: @Composable (BookInfoChildReturnViewModel) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(id, bookUrl, kind) { controller.initialize(id, bookUrl, kind) }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.state.collect { if (it.completed) back() }
        }
    }
    BackHandler { if (!state.busy) controller.cancel() }
    if (state.ready && !state.busy) content(controller)
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!state.failed) CircularProgressIndicator()
    }
    if (state.failed) AlertDialog(onDismissRequest = controller::cancel,
        text = { Text(stringResource(R.string.error)) },
        confirmButton = { TextButton(onClick = controller::retry) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = controller::cancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun BookInfoCatalogReturnDestination(bookUrl: String, requestId: String, onBack: () -> Unit,
    onOpenLogs: () -> Unit, onOpenRuleManager: () -> Unit) {
    BookInfoChildReturnFrame(requestId, bookUrl, BookInfoChildKind.Catalog, onBack) { controller ->
        BookTocDestination(bookUrl, onBack = controller::cancel, onOpenLogs = onOpenLogs,
            onOpenRuleManager = onOpenRuleManager, onSelect = controller::select)
    }
}

@Composable
fun BookInfoEditReturnDestination(bookUrl: String, requestId: String, onBack: () -> Unit) {
    BookInfoChildReturnFrame(requestId, bookUrl, BookInfoChildKind.Edit, onBack) { controller ->
        BookInfoEditScreen(bookUrl, onBack = controller::cancel, onSaved = controller::saved, owner = controller.editOwner)
    }
}
