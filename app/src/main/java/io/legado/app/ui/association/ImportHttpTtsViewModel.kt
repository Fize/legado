package io.legado.app.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.HttpTtsImportItem
import io.legado.app.data.repository.HttpTtsImportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImportHttpTtsViewModel @Inject constructor(
    private val repository: HttpTtsImportRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HttpTtsImportUiState())
    val uiState: StateFlow<HttpTtsImportUiState> = _uiState.asStateFlow()
    private var loadedSource: String? = null

    fun load(source: String) {
        if (loadedSource == source) return
        loadedSource = source
        _uiState.value = HttpTtsImportUiState(loading = true)
        viewModelScope.launch {
            runCatching { repository.preview(source) }
                .onSuccess { preview ->
                    _uiState.update { it.copy(items = preview, loading = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(loading = false, error = error.localizedMessage ?: "Import failed") }
                }
        }
    }

    fun toggle(index: Int, selected: Boolean) = _uiState.update { state ->
        if (index !in state.items.indices) state else state.copy(
            items = state.items.toMutableList().also { items ->
                items[index] = items[index].copy(selected = selected)
            },
        )
    }

    fun selectAll(selected: Boolean) = _uiState.update { state ->
        state.copy(items = state.items.map { it.copy(selected = selected) })
    }

    fun replaceSource(index: Int, source: HttpTTS) = _uiState.update { state ->
        if (index !in state.items.indices) state else state.copy(
            items = state.items.toMutableList().also { items ->
                val current = items[index]
                items[index] = current.copy(source = source)
            },
        )
    }

    fun importSelected() {
        val selected = _uiState.value.items.filter { it.selected }.map { it.source }
        if (selected.isEmpty() || _uiState.value.importing) return
        _uiState.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.import(selected) }
                .onSuccess { _uiState.update { it.copy(importing = false, completed = true) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(importing = false, error = error.localizedMessage ?: "Import failed")
                    }
                }
        }
    }
}

data class HttpTtsImportUiState(
    val items: List<HttpTtsImportItem> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
) {
    val selectedCount: Int get() = items.count { it.selected }
    val allSelected: Boolean get() = items.isNotEmpty() && items.all { it.selected }
}
