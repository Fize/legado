package io.legado.app.ui.book.info

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookInfoLibraryOptions
import io.legado.app.data.repository.BookInfoLibraryError
import io.legado.app.data.repository.BookInfoLibraryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class BookInfoLibraryOperation { Upload, Delete }
enum class BookInfoLibraryPhase {
    Idle, Preparing, Checking, ConfirmOverwrite, Uploading, ConfirmDelete, Removing, Error;
    val busy get() = this in setOf(Preparing, Checking, Uploading, Removing)
}
data class BookInfoLibraryCompletion(val id: String = UUID.randomUUID().toString(), val bookUrl: String, val deleted: Boolean, val read: Boolean)
data class BookInfoLibraryUiState(
    val operation: BookInfoLibraryOperation? = null,
    val phase: BookInfoLibraryPhase = BookInfoLibraryPhase.Idle,
    val bookUrl: String? = null,
    val target: String? = null,
    val requestId: String? = null,
    val local: Boolean = false,
    val deleteOriginal: Boolean = false,
    val deleteRemote: Boolean = false,
    val read: Boolean = false,
    val error: BookInfoLibraryError? = null,
    val errorMessage: String? = null,
    val completion: BookInfoLibraryCompletion? = null,
) { val busy get() = phase.busy }

class BookInfoLibraryController(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val inspect: suspend (String) -> BookInfoLibraryOptions,
    private val exists: suspend (String, String) -> Boolean,
    private val upload: suspend (String, String, Boolean) -> Unit,
    private val remove: suspend (String, Boolean, Boolean, String?) -> Unit,
    private val reportError: (Exception) -> Unit = {},
    private val abandonDelete: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(restore())
    private var attemptGeneration = 0L
    val state = _state.asStateFlow()

    fun beginUpload(bookUrl: String, read: Boolean = false, requestId: String = UUID.randomUUID().toString()): Boolean {
        val current = state.value
        if (current.requestId == requestId) return true
        if (current.operation != null || current.completion != null) return false
        publish(BookInfoLibraryUiState(operation = BookInfoLibraryOperation.Upload, bookUrl = bookUrl, read = read, requestId = requestId))
        prepareUpload()
        return true
    }
    fun beginDelete(bookUrl: String) {
        if (state.value.operation != null || state.value.completion != null) return
        publish(BookInfoLibraryUiState(operation = BookInfoLibraryOperation.Delete, bookUrl = bookUrl, requestId = UUID.randomUUID().toString()))
        launch(BookInfoLibraryPhase.Preparing) {
            val options = inspect(bookUrl)
            publish(state.value.copy(local = options.local, target = options.target, deleteOriginal = options.local && options.deleteOriginal))
            if (options.confirmDelete) publish(state.value.copy(phase = BookInfoLibraryPhase.ConfirmDelete))
            else performRemoval()
        }
    }
    fun setDeleteOriginal(value: Boolean) {
        if (!state.value.busy && state.value.local) publish(state.value.copy(deleteOriginal = value))
    }
    fun setDeleteRemote(value: Boolean) {
        if (!state.value.busy && state.value.local && state.value.target != null) publish(state.value.copy(deleteRemote = value))
    }
    fun confirmDelete() {
        if (state.value.phase == BookInfoLibraryPhase.ConfirmDelete) launch(BookInfoLibraryPhase.Removing) { performRemoval() }
    }
    fun confirmOverwrite() {
        if (state.value.phase == BookInfoLibraryPhase.ConfirmOverwrite) launch(BookInfoLibraryPhase.Uploading) { performUpload(true) }
    }
    fun retry() {
        if (state.value.phase != BookInfoLibraryPhase.Error) return
        when (state.value.operation) {
            BookInfoLibraryOperation.Upload -> prepareUpload()
            BookInfoLibraryOperation.Delete -> publish(state.value.copy(phase = BookInfoLibraryPhase.ConfirmDelete, error = null, errorMessage = null))
            null -> Unit
        }
    }
    fun dismiss() {
        val current = state.value
        if (current.busy) return
        if (current.operation == BookInfoLibraryOperation.Upload && current.read) complete(deleted = false)
        else {
            if (current.operation == BookInfoLibraryOperation.Delete) current.requestId?.let(abandonDelete)
            publish(BookInfoLibraryUiState())
        }
    }
    fun consumeCompletion(id: String) {
        if (state.value.completion?.id == id) publish(state.value.copy(completion = null))
    }
    fun deletionCancelled(id: String) {
        val current = state.value
        if (current.operation == BookInfoLibraryOperation.Delete && current.requestId == id) publish(BookInfoLibraryUiState())
    }
    fun uploadCancelled(id: String) {
        if (state.value.operation == BookInfoLibraryOperation.Upload && state.value.requestId == id) publish(BookInfoLibraryUiState())
    }
    fun reconcileUpload(id: String, status: io.legado.app.data.entities.BookInfoUploadStatus, error: BookInfoLibraryError?) {
        val current = state.value
        if (current.operation != BookInfoLibraryOperation.Upload || current.requestId != id) return
        when (status) {
            io.legado.app.data.entities.BookInfoUploadStatus.Queued, io.legado.app.data.entities.BookInfoUploadStatus.Running ->
                publish(current.copy(phase = BookInfoLibraryPhase.Uploading, error = null, errorMessage = null))
            io.legado.app.data.entities.BookInfoUploadStatus.Completed -> complete(deleted = false)
            io.legado.app.data.entities.BookInfoUploadStatus.Cancelled -> uploadCancelled(id)
            io.legado.app.data.entities.BookInfoUploadStatus.Failed -> if (current.phase == BookInfoLibraryPhase.Uploading || current.error == BookInfoLibraryError.Interrupted)
                publish(current.copy(phase = if (error == BookInfoLibraryError.Conflict) BookInfoLibraryPhase.ConfirmOverwrite else BookInfoLibraryPhase.Error,
                    error = error, errorMessage = null))
        }
    }

    private fun prepareUpload() = launch(BookInfoLibraryPhase.Preparing) {
        val current = state.value
        val key = checkNotNull(current.bookUrl)
        val options = inspect(key)
        if (!options.local) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        val target = options.target ?: throw BookInfoLibraryException(BookInfoLibraryError.NotConfigured)
        if (current.target != null && current.target != target) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        publish(state.value.copy(local = true, target = target, phase = BookInfoLibraryPhase.Checking))
        if (exists(key, target)) publish(state.value.copy(phase = BookInfoLibraryPhase.ConfirmOverwrite))
        else performUpload(false)
    }
    private suspend fun performUpload(overwrite: Boolean) {
        val current = state.value
        val generation = attemptGeneration
        publish(current.copy(phase = BookInfoLibraryPhase.Uploading))
        try { upload(checkNotNull(current.bookUrl), checkNotNull(current.target), overwrite) }
        catch (error: BookInfoLibraryException) {
            if (state.value.requestId == current.requestId && attemptGeneration == generation && !overwrite && error.reason == BookInfoLibraryError.Conflict) {
                publish(state.value.copy(phase = BookInfoLibraryPhase.ConfirmOverwrite)); return
            }
            throw error
        }
        if (state.value.requestId == current.requestId && attemptGeneration == generation) complete(deleted = false)
    }
    private suspend fun performRemoval() {
        val current = state.value
        publish(current.copy(phase = BookInfoLibraryPhase.Removing))
        remove(checkNotNull(current.bookUrl), current.local && current.deleteOriginal, current.local && current.deleteRemote, current.target)
        if (state.value.requestId == current.requestId) complete(deleted = true)
    }
    private fun complete(deleted: Boolean) {
        val current = state.value
        val result = if (deleted || current.read) BookInfoLibraryCompletion(bookUrl = checkNotNull(current.bookUrl), deleted = deleted, read = current.read) else null
        publish(BookInfoLibraryUiState(completion = result))
    }
    private fun launch(phase: BookInfoLibraryPhase, block: suspend () -> Unit) {
        if (state.value.busy) return
        publish(state.value.copy(phase = phase, error = null, errorMessage = null))
        val requestId = state.value.requestId
        val generation = ++attemptGeneration
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                if (state.value.requestId == requestId && attemptGeneration == generation) publish(state.value.copy(phase = BookInfoLibraryPhase.Error, error = BookInfoLibraryError.Interrupted))
                throw cancelled
            }
            catch (error: Exception) {
                reportError(error)
                if (state.value.requestId == requestId && attemptGeneration == generation) publish(state.value.copy(phase = BookInfoLibraryPhase.Error, error = (error as? BookInfoLibraryException)?.reason ?: BookInfoLibraryError.Storage,
                    errorMessage = error.localizedMessage))
            }
        }
    }
    private fun publish(value: BookInfoLibraryUiState) {
        if (value.operation == null && value.completion == null) saved.keys().filter { it.startsWith("libraryAction.") }.forEach { saved.remove<Any>(it) }
        else {
            saved["libraryAction.operation"] = value.operation?.name
            saved["libraryAction.phase"] = value.phase.name
            saved["libraryAction.book"] = value.bookUrl
            saved["libraryAction.target"] = value.target
            saved["libraryAction.request"] = value.requestId
            saved["libraryAction.local"] = value.local
            saved["libraryAction.original"] = value.deleteOriginal
            saved["libraryAction.remote"] = value.deleteRemote
            saved["libraryAction.read"] = value.read
            saved["libraryAction.error"] = value.error?.name
            saved["libraryAction.completion"] = value.completion?.id
            saved["libraryAction.completedBook"] = value.completion?.bookUrl
            saved["libraryAction.deleted"] = value.completion?.deleted
            saved["libraryAction.completedRead"] = value.completion?.read
        }
        _state.value = value
    }
    private fun restore(): BookInfoLibraryUiState {
        val phase = BookInfoLibraryPhase.entries.find { it.name == saved.get<String>("libraryAction.phase") } ?: BookInfoLibraryPhase.Idle
        val completion = saved.get<String>("libraryAction.completion")?.let { id ->
            saved.get<String>("libraryAction.completedBook")?.let { key -> BookInfoLibraryCompletion(id, key, saved["libraryAction.deleted"] ?: false, saved["libraryAction.completedRead"] ?: false) }
        }
        return BookInfoLibraryUiState(operation = BookInfoLibraryOperation.entries.find { it.name == saved.get<String>("libraryAction.operation") },
            phase = if (phase.busy) BookInfoLibraryPhase.Error else phase, bookUrl = saved["libraryAction.book"], target = saved["libraryAction.target"],
            requestId = saved.get<String>("libraryAction.request") ?: if (phase != BookInfoLibraryPhase.Idle) UUID.randomUUID().toString() else null, local = saved["libraryAction.local"] ?: false,
            deleteOriginal = saved["libraryAction.original"] ?: false, deleteRemote = saved["libraryAction.remote"] ?: false,
            read = saved["libraryAction.read"] ?: false, completion = completion,
            error = if (phase.busy) BookInfoLibraryError.Interrupted else BookInfoLibraryError.entries.find { it.name == saved.get<String>("libraryAction.error") })
    }
}
