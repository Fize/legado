package io.legado.app.ui.book.manga

import androidx.compose.runtime.Immutable
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage

@Immutable
internal data class MangaReaderUiState(
    val items: List<Any> = emptyList(),
    val anchorItemIndex: Int = 0,
    val contentVersion: Int = 0,
    val layoutVersion: Int = 0,
    val horizontal: Boolean = false,
    val doublePage: Boolean = false,
    val rightToLeft: Boolean = false,
    val scaleEnabled: Boolean = true,
    val clickScrollEnabled: Boolean = true,
    val pageAnimationEnabled: Boolean = true,
    val gray: Boolean = false,
    val eInk: Boolean = false,
    val eInkThreshold: Int = 128,
    val colorFilter: IntArray = intArrayOf(0, 0, 0, 0),
    val hasNextChapter: Boolean = false,
    val nextChapterLoading: Boolean = false,
)

internal sealed interface MangaReaderCommand {
    data class ScrollToItem(val itemIndex: Int, val animated: Boolean = false) : MangaReaderCommand
    data class TurnPage(val direction: Int, val animated: Boolean = true) : MangaReaderCommand
    data class ScrollBy(val pixels: Float) : MangaReaderCommand
}

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

internal fun buildMangaSpreads(items: List<Any>, doublePage: Boolean): List<MangaSpread> {
    val result = ArrayList<MangaSpread>(items.size)
    var index = 0
    while (index < items.size) {
        val first = items[index] as? BaseMangaPage
        if (first == null) {
            index++
            continue
        }
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
