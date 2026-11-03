package io.legado.app.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.AddToBookshelfRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddToBookshelfViewModel @Inject constructor(
    private val repository: AddToBookshelfRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddToBookshelfUiState())
    val uiState: StateFlow<AddToBookshelfUiState> = _uiState.asStateFlow()
    private var loadedUrl: String? = null

    fun load(bookUrl: String) {
        if (loadedUrl == bookUrl) return
        loadedUrl = bookUrl
        _uiState.value = AddToBookshelfUiState(loading = true)
        viewModelScope.launch {
            runCatching { repository.resolve(bookUrl) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(loading = false, book = result.book, completed = true)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(loading = false, error = error.localizedMessage ?: "未找到匹配书源") }
                }
        }
    }
}

data class AddToBookshelfUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val completed: Boolean = false,
    val error: String? = null,
)
