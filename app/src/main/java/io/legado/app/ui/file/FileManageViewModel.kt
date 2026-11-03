package io.legado.app.ui.file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.repository.FileManagerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class FileManagerUiState(
    val root: File? = null,
    val breadcrumbs: List<File> = emptyList(),
    val files: List<File> = emptyList(),
    val query: String = "",
    val error: String? = null,
) {
    val currentDirectory: File? get() = breadcrumbs.lastOrNull() ?: root
    val visibleFiles: List<File> get() = files.filter { query.isBlank() || it.name.contains(query, true) }
}

@HiltViewModel
class FileManageViewModel @Inject constructor(
    private val repository: FileManagerRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FileManagerUiState(root = repository.root))
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun setQuery(query: String) = _uiState.update { it.copy(query = query) }

    fun open(directory: File) {
        if (!directory.isDirectory) return
        _uiState.update { it.copy(breadcrumbs = it.breadcrumbs + directory, query = "") }
        refresh()
    }

    fun openBreadcrumb(index: Int) {
        _uiState.update { it.copy(breadcrumbs = it.breadcrumbs.take(index + 1), query = "") }
        refresh()
    }

    fun openRoot() {
        _uiState.update { it.copy(breadcrumbs = emptyList(), query = "") }
        refresh()
    }

    fun goBack(): Boolean {
        if (_uiState.value.breadcrumbs.isEmpty()) return false
        _uiState.update { it.copy(breadcrumbs = it.breadcrumbs.dropLast(1), query = "") }
        refresh()
        return true
    }

    fun delete(file: File) = viewModelScope.launch {
        repository.delete(file).onFailure { error ->
            _uiState.update { it.copy(error = error.localizedMessage.orEmpty()) }
        }
        refresh()
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }

    private fun refresh() = viewModelScope.launch {
        val directory = _uiState.value.currentDirectory
        repository.list(directory)
            .onSuccess { files -> _uiState.update { it.copy(files = files, error = null) } }
            .onFailure { error -> _uiState.update { it.copy(files = emptyList(), error = error.localizedMessage.orEmpty()) } }
    }
}
