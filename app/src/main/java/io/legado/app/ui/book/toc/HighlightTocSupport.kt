package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookHighlight

internal fun resolveHighlightChapterIndex(
    highlight: BookHighlight,
    chapterIndexes: Map<String, Int>,
): Int? = if (highlight.chapterUrl.isBlank()) {
    highlight.chapterIndex
} else {
    chapterIndexes[highlight.chapterUrl]
}

internal fun highlightBodyPosition(highlight: BookHighlight): Int =
    (highlight.chapterPos - highlight.layoutTitleLength.coerceAtLeast(0)).coerceAtLeast(0)
