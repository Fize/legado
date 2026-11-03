package io.legado.app.ui.book.read.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineHeightStyle
import io.legado.app.model.TextReaderUnderline
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Geometry is retained with the paragraph layout, never in a ViewModel or global paint pool. */
internal data class TextReaderUnderlineRun(
    val line: Int, val left: Float, val right: Float, val y: Float,
    val top: Float, val bottom: Float, val color: Int,
)

internal fun TextReaderUnderline.extent(density: Float): Float = when (mode) {
    3 -> max(width / 2, .5f)
    4, 6 -> width + .5f
    5 -> max(width, 1f) + width / 2
    else -> width / 2
} * density

private fun TextLayoutResult.guideLines(): List<Int> = (0 until lineCount).filter { line ->
    val start = getLineStart(line); val end = getLineEnd(line, visibleEnd = true)
    start < end && layoutInput.text.text.substring(start, end).any { !it.isWhitespace() } &&
        layoutInput.placeholders.none { it.start < end && it.end > start }
}

/** Keep native paragraph shaping, but reserve both stroke extents inside every affected line box. */
internal fun measureTextReaderUnderline(
    original: TextLayoutResult,
    settings: TextReaderUnderline,
    measure: (AnnotatedString) -> TextLayoutResult,
): TextLayoutResult {
    val density = original.layoutInput.density
    val extent = settings.extent(density.density)
    val distance = settings.distance * density.density
    var result = original
    // Centered extra line height gives space on both sides, including large waves above tiny fonts.
    repeat(4) {
        val shortage = result.guideLines().maxOfOrNull { line ->
            val center = result.getLineBaseline(line) + distance
            max(result.getLineTop(line) - (center - extent), center + extent - result.getLineBottom(line))
        } ?: 0f
        if (shortage <= 0f) return result
        val height = (0 until result.lineCount).maxOf { result.getLineBottom(it) - result.getLineTop(it) } +
            2f * ceil(shortage + 1f)
        val input = original.layoutInput.text
        val style = input.paragraphStyles.fold(ParagraphStyle()) { current, range -> current.merge(range.item) }
            .copy(lineHeight = with(density) { height.toSp() }, lineHeightStyle = LineHeightStyle(
                LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Minimum))
        result = measure(AnnotatedString(input.text, input.spanStyles,
            listOf(AnnotatedString.Range(style, 0, input.length))))
    }
    check(result.guideLines().all { line ->
        val center = result.getLineBaseline(line) + distance
        center - extent >= result.getLineTop(line) && center + extent <= result.getLineBottom(line)
    }) { "Reading guides must fit inside native line boxes" }
    return result
}

internal fun textReaderUnderlineRuns(layout: TextLayoutResult, settings: TextReaderUnderline,
    fallbackColor: Int): List<TextReaderUnderlineRun> {
    val extent = settings.extent(layout.layoutInput.density.density)
    return layout.guideLines().mapNotNull { line ->
        val text = layout.layoutInput.text.text
        var start = layout.getLineStart(line)
        var end = layout.getLineEnd(line, visibleEnd = true)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        // Native selection geometry handles bidi runs and excludes the paragraph's leading indent.
        val bounds = layout.getPathForRange(start, end).getBounds()
        // Selection paths can fill the remaining width at a soft wrap; guides follow actual text.
        val left = max(bounds.left, layout.getLineLeft(line))
        val right = min(bounds.right, layout.getLineRight(line))
        if (right <= left) null else {
            val y = layout.getLineBaseline(line) + settings.distance * layout.layoutInput.density.density
            TextReaderUnderlineRun(line, left, right, y, y - extent, y + extent, settings.color ?: fallbackColor)
        }
    }
}

internal fun DrawScope.drawTextReaderUnderlines(runs: List<TextReaderUnderlineRun>, settings: TextReaderUnderline) {
    val width = settings.width * density
    val dash = if (settings.mode == 2 || settings.mode == 6)
        PathEffect.dashPathEffect(floatArrayOf(6 * density, 4 * density)) else null
    runs.forEach { run ->
        val color = Color(run.color)
        // Dots and waves must not extend horizontally into paragraph padding or neighbouring content.
        clipRect(left = run.left, top = run.top, right = run.right, bottom = run.bottom) {
            fun line(y: Float) = drawLine(color, Offset(run.left, y), Offset(run.right, y), width, pathEffect = dash)
            when (settings.mode) {
                1, 2 -> line(run.y)
                3 -> {
                    val radius = max(width / 2, .5f * density)
                    val step = max(radius * 2.5f, 2 * density)
                    var x = run.left + radius
                    while (x <= run.right) { drawCircle(color, radius, Offset(x, run.y)); x += step }
                }
                4, 6 -> {
                    val half = (width + density) / 2
                    line(run.y - half); line(run.y + half)
                }
                5 -> {
                    val amplitude = max(width, density)
                    val wavelength = 6 * density
                    val path = Path().apply {
                        moveTo(run.left, run.y)
                        var x = run.left
                        while (x < run.right) {
                            x = min(x + density, run.right)
                            lineTo(x, run.y + amplitude * sin(((x - run.left) / wavelength * 2 * Math.PI).toFloat()))
                        }
                    }
                    drawPath(path, color, style = Stroke(width))
                }
            }
        }
    }
}
