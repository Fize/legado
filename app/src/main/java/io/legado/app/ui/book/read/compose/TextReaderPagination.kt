package io.legado.app.ui.book.read.compose

/** Keys contain no text, layout object, Context, or platform callback. */
data class TextReaderContentKey(
    val sessionId: String,
    val ownerToken: String,
    val bookUrl: String,
    val chapterIndex: Int,
    val generation: Long,
) {
    init { require(sessionId.isNotBlank() && ownerToken.isNotBlank() && bookUrl.isNotBlank()); require(chapterIndex >= 0 && generation >= 0) }
}

data class TextReaderRenderKey(
    val content: TextReaderContentKey,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val generation: Long,
) {
    init { require(viewportWidth > 0 && viewportHeight > 0 && generation >= 0) }
}

/** Chapter offsets use UTF-16, matching persisted progress and Compose text APIs. */
internal data class TextReaderLine(val start: Int, val end: Int, val top: Float, val bottom: Float)

internal data class TextReaderPageSlice(
    val index: Int,
    val firstLine: Int,
    val lastLineExclusive: Int,
    val start: Int,
    val end: Int,
    val top: Float,
    val height: Float,
) {
    fun requiresVerticalScroll(viewportHeight: Int): Boolean = height > viewportHeight
}

/** Whole measured lines are partitioned without changing their shaping or paragraph context. */
internal fun paginateTextReaderLines(textLength: Int, lines: List<TextReaderLine>, viewportHeight: Float,
    forcedBreaks: Set<Int> = emptySet()): List<TextReaderPageSlice> {
    require(textLength >= 0 && viewportHeight.isFinite() && viewportHeight > 0)
    require(lines.isNotEmpty())
    lines.forEachIndexed { index, line ->
        require(line.start in 0..textLength && line.end in line.start..textLength)
        require(line.top.isFinite() && line.bottom.isFinite() && line.bottom > line.top && line.top >= 0)
        if (index == 0) require(line.start == 0)
        else {
            require(line.start == lines[index - 1].end) { "Measured lines must cover the chapter without gaps" }
            require(line.top >= lines[index - 1].top && line.bottom >= lines[index - 1].bottom)
        }
    }
    require(lines.last().end == textLength) { "Truncated text cannot become a reading layout" }
    val boundaries = lines.mapTo(hashSetOf()) { it.start }.apply { add(textLength) }
    require(forcedBreaks.all { it in boundaries }) { "A page break must start a measured paragraph" }
    // Paragraph style boundaries and terminal newlines can produce measured phantom lines with
    // no source characters. Keep their geometry in the layout, but never turn them into pages.
    val measured = if (textLength == 0) listOf(IndexedValue(0, lines.first()))
        else lines.withIndex().filter { it.value.start != it.value.end }
    val lineCount = measured.size
    val pages = ArrayList<TextReaderPageSlice>()
    var first = 0
    while (first < lineCount) {
        var end = first + 1
        while (end < lineCount && measured[end].value.start !in forcedBreaks && measured[end].value.bottom - measured[first].value.top <= viewportHeight) end++
        val nextOffset = if (end < lineCount) measured[end].value.start else textLength
        pages += TextReaderPageSlice(pages.size, measured[first].index, measured[end - 1].index + 1,
            measured[first].value.start, nextOffset, measured[first].value.top,
            measured[end - 1].value.bottom - measured[first].value.top)
        first = end
    }
    return java.util.Collections.unmodifiableList(pages)
}

internal fun textReaderPageAtOffset(pages: List<TextReaderPageSlice>, offset: Int): Int {
    require(pages.isNotEmpty())
    val target = offset.coerceIn(0, pages.last().end)
    var low = 0; var high = pages.lastIndex
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (pages[middle].start <= target) low = middle else high = middle - 1
    }
    return low
}

internal fun textReaderCodePointBoundary(text: String, offset: Int): Int {
    val bounded = offset.coerceIn(0, text.length)
    return if (bounded in 1 until text.length && Character.isHighSurrogate(text[bounded - 1]) &&
        Character.isLowSurrogate(text[bounded])) bounded - 1 else bounded
}

/** Only the currently installed render generation may acknowledge a visible page. */
internal fun acceptsTextReaderViewport(current: TextReaderRenderKey?, reported: TextReaderRenderKey,
    page: TextReaderPageSlice, offset: Int): Boolean = current == reported &&
    offset >= page.start && (offset < page.end || page.start == page.end && offset == page.start)
