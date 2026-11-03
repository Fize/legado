package io.legado.app.ui.book.manga

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.legado.app.data.entities.BookProgress
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage

@Immutable
internal data class MangaReaderUiState(
    val items: List<BaseMangaPage> = emptyList(),
    val loadingVisible: Boolean = true,
    val interactive: Boolean = true,
    val navigationPending: Boolean = false,
    val detailNavigation: io.legado.app.data.entities.ReaderDetailRequest? = null,
    val loadingErrorMessage: String? = null,
    @StringRes val loadingErrorMessageRes: Int? = null,
    val canRetryLoading: Boolean = false,
    val menuVisible: Boolean = false,
    val bookTitle: String = "",
    val chapterName: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterUrl: String = "",
    val sourceName: String = "",
    val sourceOrigin: String? = null,
    val bookUrl: String? = null,
    val seekPage: Int = 0,
    val seekPageCount: Int = 1,
    val autoPageEnabled: Boolean = false,
    val autoScrollEnabled: Boolean = false,
    val preDownloadCount: Int = 10,
    val scaleDisabled: Boolean = true,
    val longClickSaveEnabled: Boolean = true,
    val clickScrollDisabled: Boolean = false,
    val autoPageSpeed: Int = 3,
    val horizontalScrollEnabled: Boolean = false,
    val doublePageEnabled: Boolean = true,
    val rightToLeftEnabled: Boolean = false,
    val horizontalPageSnapDisabled: Boolean = false,
    val pageAnimationDisabled: Boolean = false,
    val titleHidden: Boolean = false,
    val eInkEnabled: Boolean = false,
    val grayEnabled: Boolean = false,
    val footerConfigJson: String = "",
    val colorFilterJson: String = "",
    val horizontalMode: Boolean = false,
    val anchorItemIndex: Int = 0,
    val contentVersion: Int = 0,
    val layoutVersion: Int = 0,
    val horizontal: Boolean = false,
    val doublePage: Boolean = false,
    val rightToLeft: Boolean = false,
    val scaleEnabled: Boolean = true,
    val clickScrollEnabled: Boolean = true,
    val pageAnimationEnabled: Boolean = true,
    val pageSnapEnabled: Boolean = true,
    val gray: Boolean = false,
    val eInk: Boolean = false,
    val eInkThreshold: Int = 150,
    val colorFilter: IntArray = intArrayOf(0, 0, 0, 0),
    val hasNextChapter: Boolean = false,
    val nextChapterLoading: Boolean = false,
    val pendingCloudProgress: BookProgress? = null,
    val noticeMessage: String? = null,
    val dialog: MangaReaderDialog? = null,
    val exit: MangaReaderExit? = null,
    val windowBrightness: Int? = null,
    val command: MangaScrollRequest? = null,
    val commandVersion: Long = 0,
)

internal enum class MangaReaderExit { Closed, AddedToBookshelf, Deleted }

internal sealed interface MangaReaderDialog {
    data object Footer : MangaReaderDialog
    data object ColorFilter : MangaReaderDialog
    data object Epaper : MangaReaderDialog
    data object CloudProgress : MangaReaderDialog
    data object BrowserOpenMode : MangaReaderDialog
    data object AddToBookshelf : MangaReaderDialog
    data object ChangeSource : MangaReaderDialog
    data class DownloadRange(
        val startChapter: Int,
        val endChapter: Int,
    ) : MangaReaderDialog
    data class NumberPicker(
        val kind: MangaNumberSetting,
        val initialValue: Int,
        val minimumValue: Int,
    ) : MangaReaderDialog
}

internal enum class MangaNumberSetting {
    PreDownload,
    AutoPageSpeed,
}

internal sealed interface MangaReaderCommand {
    data class ScrollToItem(val itemIndex: Int, val animated: Boolean = false) : MangaReaderCommand
    data class TurnPage(val direction: Int, val animated: Boolean = true) : MangaReaderCommand
    data class ScrollBy(val pixels: Float) : MangaReaderCommand
}

internal sealed interface MangaScrollRequest {
    data class ToPage(val chapterIndex: Int, val pageIndex: Int, val animated: Boolean) : MangaScrollRequest
    data class ByPixels(val pixels: Float) : MangaScrollRequest
}

internal fun resolveMangaScrollRequest(state: MangaReaderUiState, command: MangaReaderCommand): MangaScrollRequest? {
    if (command is MangaReaderCommand.ScrollBy) return MangaScrollRequest.ByPixels(command.pixels)
    val index = when (command) {
        is MangaReaderCommand.ScrollToItem -> command.itemIndex
        is MangaReaderCommand.TurnPage -> if (state.horizontal) {
            val spreads = buildMangaSpreads(state.items, state.doublePage)
            val current = findMangaSpreadIndex(spreads, state.anchorItemIndex)
            spreads.getOrNull((current + command.direction).coerceIn(0, (spreads.size - 1).coerceAtLeast(0)))?.firstItemIndex ?: return null
        } else (state.anchorItemIndex + command.direction).coerceIn(0, (state.items.size - 1).coerceAtLeast(0))
        is MangaReaderCommand.ScrollBy -> return null
    }
    val page = state.items.getOrNull(index) ?: return null
    val animated = when (command) { is MangaReaderCommand.ScrollToItem -> command.animated; is MangaReaderCommand.TurnPage -> command.animated; else -> false }
    return MangaScrollRequest.ToPage(page.chapterIndex, page.index, animated)
}

internal fun mangaScrollTarget(items: List<BaseMangaPage>, request: MangaScrollRequest.ToPage): Int =
    items.indexOfFirst { it.chapterIndex == request.chapterIndex && it.index == request.pageIndex }

@Immutable
internal data class MangaSpread(
    val first: BaseMangaPage,
    val second: MangaPage? = null,
    val firstItemIndex: Int,
) {
    val key: String = buildString {
        append(first.chapterIndex).append(':').append(first.index)
        second?.let { append('+').append(it.index) }
    }
}

internal fun buildMangaSpreads(items: List<BaseMangaPage>, doublePage: Boolean): List<MangaSpread> {
    val result = ArrayList<MangaSpread>(items.size)
    var index = 0
    while (index < items.size) {
        val first = items[index]
        val firstPage = first as? MangaPage
        val second = if (doublePage && firstPage != null && firstPage.index % 2 == 0) {
            (items.getOrNull(index + 1) as? MangaPage)?.takeIf {
                it.chapterIndex == firstPage.chapterIndex && it.index == firstPage.index + 1
            }
        } else {
            null
        }
        result += MangaSpread(first, second, index)
        index += if (second == null) 1 else 2
    }
    return result
}

internal fun findMangaSpreadIndex(spreads: List<MangaSpread>, itemIndex: Int): Int {
    if (spreads.isEmpty()) return 0
    val exact = spreads.indexOfLast { it.firstItemIndex <= itemIndex }
    return exact.coerceAtLeast(0)
}

/** Match the rendered identity as well as its content/layout generation, including chapter cards. */
internal fun currentMangaItemIndex(state: MangaReaderUiState, item: BaseMangaPage,
    contentVersion: Int, layoutVersion: Int): Int {
    if (!state.interactive || state.loadingVisible || state.contentVersion != contentVersion || state.layoutVersion != layoutVersion) return -1
    return state.items.indexOfFirst { current ->
        when {
            current is MangaPage && item is MangaPage -> current.chapterIndex == item.chapterIndex &&
                current.index == item.index && current.mImageUrl == item.mImageUrl
            current is io.legado.app.ui.book.manga.entities.ReaderLoading &&
                item is io.legado.app.ui.book.manga.entities.ReaderLoading -> current == item
            else -> false
        }
    }
}

internal fun isCurrentMangaPageReport(state: MangaReaderUiState, page: MangaPage, contentVersion: Int, layoutVersion: Int): Boolean =
    currentMangaItemIndex(state, page, contentVersion, layoutVersion) >= 0
