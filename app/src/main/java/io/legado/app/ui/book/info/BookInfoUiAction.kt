package io.legado.app.ui.book.info

/** User intent emitted by the book details screen; platform work belongs to its destination. */
sealed interface BookInfoUiAction {
    data object Back : BookInfoUiAction
    data object Edit : BookInfoUiAction
    data object Login : BookInfoUiAction
    data object Refresh : BookInfoUiAction
    data object Top : BookInfoUiAction
    data object SourceVariable : BookInfoUiAction
    data object BookVariable : BookInfoUiAction
    data object Upload : BookInfoUiAction
    data object Logs : BookInfoUiAction
    data object Shelf : BookInfoUiAction
    data class CancelDelete(val id: String) : BookInfoUiAction
    data class CancelUpload(val id: String) : BookInfoUiAction
    data object Read : BookInfoUiAction
    data object Cover : BookInfoUiAction
    data object CoverPhoto : BookInfoUiAction
    data object EditSource : BookInfoUiAction
    data object ChangeSource : BookInfoUiAction
    data object Toc : BookInfoUiAction
    data object Group : BookInfoUiAction
    data object IntroToggle : BookInfoUiAction
    data object IntroRetry : BookInfoUiAction
    data class CanUpdate(val enabled: Boolean) : BookInfoUiAction
    data class SplitLongChapter(val enabled: Boolean) : BookInfoUiAction
    data class DeleteAlert(val enabled: Boolean) : BookInfoUiAction
    data class Callback(val event: BookInfoCallbackEvent, val argument: String? = null) : BookInfoUiAction
    data class IntroMeasured(val key: String?, val collapsible: Boolean) : BookInfoUiAction
    data class IntroButton(val key: String?, val name: String, val script: String) : BookInfoUiAction
    data class IntroImage(val key: String?, val url: String) : BookInfoUiAction
    data class IntroLink(val key: String?, val url: String) : BookInfoUiAction
}

enum class BookInfoCallbackEvent {
    Custom, Share, CopyBookUrl, CopyTocUrl, ClearCache,
    Name, LongName, Author, LongAuthor, Kind, LongKind,
}
