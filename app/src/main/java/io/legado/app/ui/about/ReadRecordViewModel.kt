package io.legado.app.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.repository.ReadRecordRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ReadRecordDestination {
    data class OpenBook(val book: Book) : ReadRecordDestination
    data class SearchBook(val query: String) : ReadRecordDestination
}

data class ReadRecordUiState(
    val allRecords: List<ReadRecordShow> = emptyList(),
    val records: List<ReadRecordShow> = emptyList(),
    val booksByIdentity: Map<Pair<String, String>, Book> = emptyMap(),
    val query: String = "",
    val sortMode: Int = 0,
    val enabled: Boolean = true,
    val simpleLayout: Boolean = false,
    val useDays: Boolean = false,
    val showSeconds: Boolean = true,
    val fixedCard: Boolean = false,
    val loading: Boolean = true,
    val confirmClear: Boolean = false,
    val deleteTarget: ReadRecordShow? = null,
    val authorPickerTarget: ReadRecordShow? = null,
    val authorRemoval: Pair<ReadRecordShow, String>? = null,
    val destination: ReadRecordDestination? = null,
    val error: String? = null,
)

@HiltViewModel
class ReadRecordViewModel @Inject constructor(
    private val repository: ReadRecordRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReadRecordUiState())
    val uiState: StateFlow<ReadRecordUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        val settings = repository.settings()
        _uiState.update {
            it.copy(
                sortMode = settings.sortMode,
                enabled = settings.enabled,
                simpleLayout = settings.simpleLayout,
                useDays = settings.useDays,
                showSeconds = settings.showSeconds,
                fixedCard = settings.fixedCard,
            )
        }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
        val state = _uiState.value
        _uiState.update { it.copy(loading = true) }
        runCatching { repository.load(state.query, state.sortMode) }
            .onSuccess { content ->
                _uiState.update {
                    it.copy(
                        allRecords = content.allRecords,
                        records = content.visibleRecords,
                        booksByIdentity = content.booksByIdentity,
                        loading = false,
                        error = null,
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(loading = false, error = error.localizedMessage.orEmpty()) }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        refresh()
    }

    fun setSortMode(mode: Int) {
        repository.setSortMode(mode)
        _uiState.update { it.copy(sortMode = mode) }
        refresh()
    }

    fun setEnabled(value: Boolean) {
        repository.setEnabled(value)
        _uiState.update { it.copy(enabled = value) }
    }

    fun setSimpleLayout(value: Boolean) {
        repository.setSimpleLayout(value)
        _uiState.update { it.copy(simpleLayout = value) }
    }

    fun setUseDays(value: Boolean) {
        repository.setUseDays(value)
        _uiState.update { it.copy(useDays = value) }
    }

    fun setShowSeconds(value: Boolean) {
        repository.setShowSeconds(value)
        _uiState.update { it.copy(showSeconds = value) }
    }

    fun setFixedCard(value: Boolean) {
        repository.setFixedCard(value)
        _uiState.update { it.copy(fixedCard = value) }
    }

    fun open(record: ReadRecordShow) = viewModelScope.launch {
        val book = repository.findBook(record)
        _uiState.update {
            it.copy(destination = book?.let(ReadRecordDestination::OpenBook)
                ?: ReadRecordDestination.SearchBook(record.bookName))
        }
    }

    fun requestClear() = _uiState.update { it.copy(confirmClear = true) }
    fun requestDelete(record: ReadRecordShow) = _uiState.update { it.copy(deleteTarget = record) }

    fun dismissDialogs() = _uiState.update {
        it.copy(confirmClear = false, deleteTarget = null, authorPickerTarget = null, authorRemoval = null)
    }

    fun clear() = viewModelScope.launch {
        repository.clear()
        dismissDialogs()
        refresh()
    }

    fun delete(record: ReadRecordShow) = viewModelScope.launch {
        repository.delete(record)
        dismissDialogs()
        refresh()
    }

    fun showAuthorPicker(record: ReadRecordShow) = _uiState.update {
        it.copy(deleteTarget = null, authorPickerTarget = record)
    }

    fun requestAuthorRemoval(record: ReadRecordShow, author: String) = _uiState.update {
        it.copy(authorPickerTarget = null, authorRemoval = record to author)
    }

    fun removeAuthor(record: ReadRecordShow, author: String) = viewModelScope.launch {
        repository.removeLegacyAuthor(record, author)
        dismissDialogs()
        refresh()
    }

    fun authors(record: ReadRecordShow): List<String> = ReadRecordAuthors.decode(record.author).toList()
    fun consumeDestination() = _uiState.update { it.copy(destination = null) }
    fun consumeError() = _uiState.update { it.copy(error = null) }
}
