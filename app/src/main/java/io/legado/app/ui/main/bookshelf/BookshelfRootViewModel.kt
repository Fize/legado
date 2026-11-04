package io.legado.app.ui.main.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookshelfRepository
import io.legado.app.help.config.AppConfig
import io.legado.app.constant.EventBus
import io.legado.app.utils.postEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BookshelfRootUiState(
    val groupId: Long = BookGroup.IdRoot,
    val groups: List<BookGroup> = emptyList(),
    val books: List<Book> = emptyList(),
    val groupItems: List<BookshelfGroupItem> = emptyList(),
    val layout: Int = AppConfig.bookshelfLayout,
    val margin: Int = AppConfig.bookshelfMargin,
    val showUnread: Boolean = AppConfig.showUnread,
    val showLastUpdateTime: Boolean = AppConfig.showLastUpdateTime,
    val showBooknameMode: Int = AppConfig.showBookname,
    val showRecentReading: Boolean = AppConfig.showBookshelfRecentReading,
    val showBookshelfStats: Boolean = AppConfig.showBookshelfStats,
    val bookCount: Int = 0,
    val readingCount: Int = 0,
    val continueBook: Book? = null,
) {
    val currentGroup: BookGroup? get() = groups.firstOrNull { it.groupId == groupId }
    val items: List<Any>
        get() = if (groupId == BookGroup.IdRoot) orderBookshelfItems(groupItems, books) else books
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfRootViewModel @Inject constructor(
    repository: BookshelfRepository,
) : ViewModel() {

    private val groupId = MutableStateFlow(BookGroup.IdRoot)
    private val preferencesRevision = MutableStateFlow(0)

    val uiState: StateFlow<BookshelfRootUiState> = combine(groupId, preferencesRevision) { group, _ -> group }
        .flatMapLatest { selectedGroup ->
            val showRecentReading = AppConfig.showBookshelfRecentReading
            val showBookshelfStats = AppConfig.showBookshelfStats
            combine(
                repository.observe(selectedGroup),
                repository.observeHeader(showRecentReading, showBookshelfStats),
            ) { content, header ->
                BookshelfRootUiState(
                    groupId = selectedGroup,
                    groups = content.groups,
                    books = content.books,
                    groupItems = content.groupItems,
                    layout = AppConfig.bookshelfLayout,
                    margin = AppConfig.bookshelfMargin,
                    showUnread = AppConfig.showUnread,
                    showLastUpdateTime = AppConfig.showLastUpdateTime,
                    showBooknameMode = AppConfig.showBookname,
                    showRecentReading = showRecentReading,
                    showBookshelfStats = showBookshelfStats,
                    bookCount = header.bookCount,
                    readingCount = header.readingCount,
                    continueBook = header.continueBook,
                )
            }
        }
        .catch { emit(BookshelfRootUiState(groupId = groupId.value)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BookshelfRootUiState(),
        )

    fun openGroup(group: BookGroup) {
        groupId.value = group.groupId
    }

    fun goBack(): Boolean {
        if (groupId.value == BookGroup.IdRoot) return false
        groupId.value = BookGroup.IdRoot
        return true
    }

    fun switchGroup(offset: Int): Boolean {
        val target = adjacentBookshelfGroupId(uiState.value.groups, groupId.value, offset) ?: return false
        groupId.value = target
        return true
    }

    fun applyConfig(newState: BookshelfConfigState) {
        val groupStyleChanged = AppConfig.bookGroupStyle != newState.groupStyle
        AppConfig.bookGroupStyle = newState.groupStyle
        AppConfig.bookshelfReadProgressMode = newState.readProgressMode
        AppConfig.showUnread = newState.showUnread
        AppConfig.showLastUpdateTime = newState.showLastUpdateTime
        AppConfig.showWaitUpCount = newState.showWaitUpBooks
        AppConfig.showBookshelfFastScroller = newState.showFastScroller
        AppConfig.showBookshelfRecentReading = newState.showRecentReading
        AppConfig.showBookshelfStats = newState.showBookshelfStats
        AppConfig.bookshelfLayout = newState.bookshelfLayout
        AppConfig.bookshelfSort = newState.bookshelfSort
        AppConfig.showBookname = newState.showBookname
        AppConfig.bookshelfMargin = newState.margin
        preferencesRevision.value++
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
        if (groupStyleChanged) postEvent(EventBus.NOTIFY_MAIN, false)
    }
}

internal fun adjacentBookshelfGroupId(
    groups: List<BookGroup>,
    currentGroupId: Long,
    offset: Int,
): Long? {
    val visible = groups.filterNot { it.isRootHiddenSystemGroup }.map(BookGroup::groupId)
    val currentIndex = visible.indexOf(currentGroupId)
    if (currentIndex < 0) return null
    return visible.getOrNull(currentIndex + offset)
}
