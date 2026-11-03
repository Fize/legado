package io.legado.app.ui.variable

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.SourceVariableRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State belongs to the owning screen ViewModel; the dialog holds no draft or callbacks. */
data class VariableEditorUiState(
    val source: Boolean,
    val key: String,
    val comment: String,
    val draft: String,
    val ready: Boolean,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

class VariableEditorController(
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val repository: SourceVariableRepository,
    private val saveBook: suspend (String, String?) -> Unit = { _, _ -> error("Unsupported variable target") },
) {
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var session = 0L

    init { if (_state.value?.ready == false) retryLoad() }

    fun openSource(key: String, comment: String?) = open(VariableEditorUiState(true, key, comment.orEmpty(), "", false))
    fun openBook(key: String, value: String, comment: String?) = open(VariableEditorUiState(false, key, comment.orEmpty(), value, true))

    private fun open(value: VariableEditorUiState) {
        if (_state.value?.saving == true) return
        operation?.cancel()
        session++
        publish(value)
        if (!value.ready) retryLoad()
    }

    fun updateDraft(value: String) {
        val current = _state.value ?: return
        if (current.loading || current.saving || !current.ready) return
        publish(current.copy(draft = value, error = null))
    }

    fun retryLoad() {
        val current = _state.value ?: return
        if (current.ready || current.saving) return
        operation?.cancel()
        val request = ++session
        publish(current.copy(loading = true, error = null))
        operation = scope.launch {
            try {
                val value = repository.read(current.key)
                if (request == session) publish(current.copy(draft = value, loading = false, ready = true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == session) publish(current.copy(loading = false, error = error.localizedMessage ?: error.toString()))
            }
        }
    }

    fun save() {
        val current = _state.value ?: return
        if (!current.ready || current.loading || current.saving) return
        val request = session
        publish(current.copy(saving = true, error = null))
        operation = scope.launch {
            try {
                val value = current.draft.takeIf(String::isNotEmpty)
                if (current.source) repository.write(current.key, value) else saveBook(current.key, value)
                if (request == session) close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == session) publish(current.copy(error = error.localizedMessage ?: error.toString()))
            }
        }
    }

    fun dismiss() {
        if (_state.value?.saving == true) return
        operation?.cancel()
        close()
    }

    private fun close() { session++; publish(null) }

    private fun publish(value: VariableEditorUiState?) {
        if (value == null) savedState.remove<String>("variableEditorKey") else savedState["variableEditorKey"] = value.key
        if (value != null) {
            savedState["variableEditorSource"] = value.source
            savedState["variableEditorComment"] = value.comment
            savedState["variableEditorDraft"] = value.draft
            savedState["variableEditorReady"] = value.ready
        } else {
            listOf("variableEditorSource", "variableEditorComment", "variableEditorDraft", "variableEditorReady").forEach { savedState.remove<Any>(it) }
        }
        _state.value = value
    }

    private fun restore(): VariableEditorUiState? {
        val key = savedState.get<String>("variableEditorKey") ?: return null
        return VariableEditorUiState(savedState["variableEditorSource"] ?: true, key,
            savedState["variableEditorComment"] ?: "", savedState["variableEditorDraft"] ?: "", savedState["variableEditorReady"] ?: false)
    }
}
