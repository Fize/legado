package io.legado.app.ui.book.info

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookInfoOpenRepository
import io.legado.app.data.repository.BookInfoOpenRequest
import io.legado.app.data.repository.BookInfoPreviewLease
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

internal data class BookInfoOpenState(val pending: BookInfoOpenRequest? = null, val error: String? = null)

@HiltViewModel
class BookInfoOpenViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: BookInfoOpenRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BookInfoOpenState())
    internal val state = _state.asStateFlow()
    private var job: Job? = null
    private var lease: BookInfoPreviewLease? = null
    private var retryWork: (suspend (String) -> BookInfoOpenRequest)? = null
    internal val canRetry: Boolean get() = retryWork != null

    init {
        savedState.get<String>(KEY)?.let { id -> job = viewModelScope.launch {
            try {
                val selection = withContext(NonCancellable) { repository.retain(id).also { lease = it.lease } }
                currentCoroutineContext().ensureActive()
                _state.value = BookInfoOpenState(pending = selection.request,
                    error = if (selection.request == null) "Selected book preview is no longer available" else null)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { lease?.let { repository.release(it) } }
                throw cancelled
            }
            catch (error: Exception) { _state.value = BookInfoOpenState(error = error.localizedMessage ?: "Cannot restore selected book") }
        } }
    }

    fun openShelf(bookUrl: String) = prepare { id -> repository.shelf(bookUrl, id) }
    fun openSelected(book: Book) {
        val snapshot = repository.snapshot(book)
        prepare { id -> repository.selected(snapshot, id) }
    }

    private fun prepare(work: suspend (String) -> BookInfoOpenRequest) {
        if (job?.isActive == true || state.value.pending != null) return
        retryWork = work
        val id = UUID.randomUUID().toString()
        lease?.let(repository::abandon)
        lease = null
        savedState[KEY] = id
        _state.value = BookInfoOpenState()
        job = viewModelScope.launch {
            try {
                val request = withContext(NonCancellable) {
                    work(id).also { lease = repository.retain(id).lease }
                }
                currentCoroutineContext().ensureActive()
                _state.value = BookInfoOpenState(pending = request)
            }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable) { lease?.let { repository.release(it) } }
                throw cancelled
            } catch (error: Exception) {
                _state.value = BookInfoOpenState(error = error.localizedMessage ?: "Cannot open book details")
            }
        }
    }

    internal fun retry() { retryWork?.let(::prepare) }

    internal fun consume(id: String) {
        if (state.value.pending?.id != id) return
        savedState.remove<String>(KEY)
        lease = null
        _state.value = BookInfoOpenState()
    }
    internal fun dismissError() {
        lease?.let(repository::abandon)
        lease = null
        savedState.remove<String>(KEY)
        _state.value = BookInfoOpenState()
    }

    override fun onCleared() {
        lease?.let(repository::abandon)
    }

    private companion object { const val KEY = "bookInfoSelectedPreviewKey" }
}
