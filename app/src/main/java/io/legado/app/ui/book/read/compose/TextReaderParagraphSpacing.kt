package io.legado.app.ui.book.read.compose

/** Adds visual gaps after body paragraphs, without changing source characters or native shaping. */
internal fun spaceTextReaderParagraphs(text: String, titleLength: Int, lines: List<TextReaderLine>,
    gap: Float): List<TextReaderLine> {
    require(titleLength in 0..text.length && gap.isFinite() && gap >= 0f)
    var shift = 0f
    return lines.map { line ->
        val positioned = line.copy(top = line.top + shift, bottom = line.bottom + shift)
        if (line.end > line.start && line.end > titleLength && line.end < text.length && text[line.end - 1] == '\n') {
            shift += gap
        }
        positioned
    }
}
