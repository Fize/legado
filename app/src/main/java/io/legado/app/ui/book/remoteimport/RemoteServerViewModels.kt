package io.legado.app.ui.book.remoteimport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.data.repository.RemoteBookImportRepository
import io.legado.app.data.repository.RemoteImportServer
import io.legado.app.data.repository.RemoteServerDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class RemoteServersUiState(
    val servers: List<RemoteImportServer> = emptyList(),
    val selected: Long = DEFAULT_WEBDAV_ID,
    val loading: Boolean = true,
    val deleteTarget: RemoteImportServer? = null,
    val finished: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RemoteServersViewModel @Inject constructor(
    private val repository: RemoteBookImportRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteServersUiState())
    val uiState = _uiState.asStateFlow()
    private var initialized = false
    fun initialize() {
        if (initialized) return
        initialized = true
        operation {
            val selected = savedState.get<Long>("selected") ?: repository.selectedServer()
            _uiState.update { it.copy(selected = selected) }
            repository.servers().collect { servers -> _uiState.update { it.copy(servers = servers, loading = false) } }
        }
    }
    fun select(id: Long) { savedState["selected"] = id; _uiState.update { it.copy(selected = id) } }
    fun confirm() = operation { repository.selectServer(uiState.value.selected); _uiState.update { it.copy(finished = true) } }
    fun useDefault() { select(DEFAULT_WEBDAV_ID); confirm() }
    fun requestDelete(server: RemoteImportServer?) = _uiState.update { it.copy(deleteTarget = server) }
    fun confirmDelete() {
        val target = uiState.value.deleteTarget ?: return
        requestDelete(null)
        operation { repository.deleteServer(target.id); if (uiState.value.selected == target.id) select(DEFAULT_WEBDAV_ID) }
    }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }
    private fun operation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _uiState.update { it.copy(loading = false, message = repository.errorMessage(error)) } }
    }
}

data class RemoteServerEditUiState(
    val draft: RemoteServerDraft = RemoteServerDraft(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val ready: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RemoteServerEditViewModel @Inject constructor(
    private val repository: RemoteBookImportRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteServerEditUiState())
    val uiState = _uiState.asStateFlow()
    private var initialized = false
    fun initialize(id: Long?) {
        if (initialized) return
        initialized = true
        operation {
            val draft = savedState.get<String>("draft")?.let { Json.decodeFromString<RemoteServerDraft>(it) }
                ?: repository.serverDraft(id)
            _uiState.update { it.copy(draft = draft, loading = false, ready = true) }
        }
    }
    fun changeDraft(draft: RemoteServerDraft) {
        if (!uiState.value.ready || uiState.value.saving) return
        savedState["draft"] = Json.encodeToString(draft)
        _uiState.update { it.copy(draft = draft) }
    }
    fun save() {
        if (!uiState.value.ready || uiState.value.saving || uiState.value.loading) return
        val draft = uiState.value.draft
        _uiState.update { it.copy(saving = true) }
        operation {
            try { repository.saveServer(draft); _uiState.update { it.copy(saved = true) } }
            finally { _uiState.update { it.copy(saving = false) } }
        }
    }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }
    private fun operation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _uiState.update { it.copy(loading = false, message = repository.errorMessage(error)) } }
    }
}
