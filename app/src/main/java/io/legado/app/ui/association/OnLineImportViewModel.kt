package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.OnlineImportRepository
import io.legado.app.data.repository.OnlineImportResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnlineImportUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val confirmReadConfig: Boolean = false,
    val destination: OnlineImportResolution.Destination? = null,
    val errorMessage: String? = null,
    val importComplete: Boolean = false,
    val finished: Boolean = false,
)

@HiltViewModel
class OnLineImportViewModel @Inject constructor(
    private val repository: OnlineImportRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineImportUiState())
    val uiState = _uiState.asStateFlow()
    private var initialized = false
    private var pendingKey: String?
        get() = savedState["pendingReadConfigKey"]
        set(value) { savedState["pendingReadConfigKey"] = value }

    fun initialize(link: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                val previous = pendingKey
                if (previous != null && repository.canRestore(previous)) {
                    _uiState.update { it.copy(loading = false, confirmReadConfig = true) }
                    return@launch
                }
                pendingKey = null
                when (val result = repository.resolve(link)) {
                    is OnlineImportResolution.Destination ->
                        _uiState.update { it.copy(loading = false, destination = result) }
                    is OnlineImportResolution.ReadConfiguration -> {
                        pendingKey = result.key
                        _uiState.update { it.copy(loading = false, confirmReadConfig = true) }
                    }
                    OnlineImportResolution.Empty -> _uiState.update { it.copy(loading = false, finished = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { fail(error) }
        }
    }

    fun consumeDestination() = _uiState.update { it.copy(destination = null) }

    fun importReadConfig() {
        val key = pendingKey ?: return
        if (uiState.value.importing || !uiState.value.confirmReadConfig) return
        _uiState.update { it.copy(confirmReadConfig = false, importing = true) }
        viewModelScope.launch {
            try {
                repository.importReadConfiguration(key)
                _uiState.update { it.copy(importComplete = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { fail(error) }
            finally {
                pendingKey = null
                runCatching { repository.discard(key) }.onFailure { AppLog.put("清理在线导入缓存失败", it) }
                _uiState.update { it.copy(importing = false) }
            }
        }
    }

    fun cancelReadConfigImport() {
        if (uiState.value.importing || uiState.value.finished) return
        val key = pendingKey
        pendingKey = null
        _uiState.update { it.copy(confirmReadConfig = false) }
        viewModelScope.launch {
            try { if (key != null) repository.discard(key) }
            finally { _uiState.update { it.copy(finished = true) } }
        }
    }

    fun dismissResult() = _uiState.update { it.copy(errorMessage = null, importComplete = false, finished = true) }

    private fun fail(error: Throwable) {
        AppLog.put("在线导入失败\n${error.localizedMessage}", error)
        _uiState.update { it.copy(loading = false, errorMessage = repository.errorMessage(error)) }
    }
}
