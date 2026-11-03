package io.legado.app.ui.book.bookmark

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.data.repository.BookmarkExportFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BookmarkMessage { ExportSuccess, ExportFailed }

data class BookmarkOpenRequest(
    val book: Book,
    val chapterIndex: Int,
    val chapterPos: Int,
)

data class AllBookmarksUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val editingBookmark: Bookmark? = null,
    val pendingOpen: BookmarkOpenRequest? = null,
    val message: BookmarkMessage? = null,
)

@HiltViewModel
class AllBookmarkViewModel @Inject constructor(
    private val repository: BookmarkRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AllBookmarksUiState())
    val uiState: StateFlow<AllBookmarksUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll()
                .catch { error -> AppLog.put("所有书签界面获取数据失败\n${error.localizedMessage}", error) }
                .collect { bookmarks -> _uiState.update { it.copy(bookmarks = bookmarks) } }
        }
    }

    fun open(bookmark: Bookmark) {
        viewModelScope.launch {
            val book = repository.findBook(bookmark)
            _uiState.update { state ->
                if (book == null) {
                    state.copy(editingBookmark = bookmark)
                } else {
                    state.copy(
                        pendingOpen = BookmarkOpenRequest(
                            book = book,
                            chapterIndex = bookmark.chapterIndex,
                            chapterPos = bookmark.chapterPos,
                        ),
                    )
                }
            }
        }
    }

    fun edit(bookmark: Bookmark) {
        _uiState.update { it.copy(editingBookmark = bookmark) }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(editingBookmark = null) }
    }

    fun save(bookmark: Bookmark, bookText: String, content: String) {
        viewModelScope.launch {
            repository.save(bookmark.copy(bookText = bookText, content = content))
            dismissEditor()
        }
    }

    fun delete(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.delete(bookmark)
            dismissEditor()
        }
    }

    fun export(uri: Uri, format: BookmarkExportFormat) {
        viewModelScope.launch {
            val result = repository.export(uri, format)
            result.exceptionOrNull()?.let { AppLog.put("导出失败\n${it.localizedMessage}", it) }
            _uiState.update {
                it.copy(message = if (result.isSuccess) BookmarkMessage.ExportSuccess else BookmarkMessage.ExportFailed)
            }
        }
    }

    fun consumeOpen() {
        _uiState.update { it.copy(pendingOpen = null) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
