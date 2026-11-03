package io.legado.app.ui.compose.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.legado.app.model.BookCoverAppearance
import io.legado.app.utils.toStringArray

internal class CoverTextLayout(internal val glyphs: List<Glyph>, private val background: Color, private val accent: Color) {
    internal data class Glyph(val layout: TextLayoutResult, val position: Offset, val stroke: Float)
    fun draw(scope: DrawScope) = with(scope) {
        glyphs.forEach { glyph ->
            drawText(glyph.layout, background, glyph.position, drawStyle = Stroke(glyph.stroke))
            drawText(glyph.layout, accent, glyph.position, drawStyle = Fill)
        }
    }
}

/** Retained Compose text layouts replace the old View bitmap caches and unowned render jobs. */
@Composable
internal fun rememberCoverTextLayout(title: String, author: String?, width: Int, height: Int, appearance: BookCoverAppearance): CoverTextLayout {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    return remember(title, author, width, height, appearance, density, measurer) {
        val w = width.toFloat(); val h = height.toFloat()
        val glyphs = arrayListOf<CoverTextLayout.Glyph>()
        val family = appearance.typeface?.let(::FontFamily)
        fun textStyle(size: Float, bold: Boolean = true, alignment: TextAlign = TextAlign.Start) = TextStyle(
            fontSize = with(density) { size.toSp() }, fontFamily = family,
            fontWeight = if (family == null && bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = alignment, platformStyle = PlatformTextStyle(includeFontPadding = false))
        fun measure(value: String, size: Float, bold: Boolean = true) = measurer.measure(value, textStyle(size, bold), softWrap = false)
        fun glyph(value: String, size: Float, x: Float, baseline: Float, bold: Boolean) : CoverTextLayout.Glyph {
            val result = measure(value, size, bold)
            return CoverTextLayout.Glyph(result, Offset(x - result.size.width / 2f, baseline - result.firstBaseline), size / if (bold) 6f else 5f)
        }
        val sizes = appearance.fontSizes
        val large = w / 7 * ((sizes?.titleLarge ?: 100) / 100f)
        val small = sizes?.let { w / 7 * it.titleSmall / 100f } ?: (w / 9)
        val hasAuthor = appearance.drawAuthor && !author.isNullOrEmpty()
        if (appearance.horizontal) {
            val titleWidth = (w * .78f).toInt().coerceAtLeast(1)
            // The four-line limit alone can exceed the cover at the largest custom font size.
            val titleBottom = if (hasAuthor) {
                val authorFont = w / 10 * ((sizes?.let { maxOf(it.authorLarge, it.authorSmall) } ?: 100) / 100f)
                h * .92f - measure("国", authorFont, false).firstBaseline - authorFont / 10 - large / 12 - h * .04f
            } else h * .94f
            val titleHeight = (titleBottom - h * .1f).toInt().coerceAtLeast(1)
            fun titleLayout(value: AnnotatedString, size: Float) = measurer.measure(value, textStyle(size, alignment = TextAlign.Center),
                constraints = Constraints(minWidth = titleWidth, maxWidth = titleWidth, maxHeight = titleHeight), maxLines = 4, overflow = TextOverflow.Ellipsis)
            var result = titleLayout(AnnotatedString(title), large)
            var font = large
            if (result.lineCount > 1 || measure(title, large).size.width > titleWidth) {
                val firstLineEnd = result.getLineEnd(0)
                val value = if (appearance.adaptive) AnnotatedString(title) else buildAnnotatedString {
                    append(title)
                    addStyle(SpanStyle(fontSize = with(density) { large.toSp() }), 0, firstLineEnd)
                }
                result = titleLayout(value, small); font = small
            }
            glyphs.add(CoverTextLayout.Glyph(result, Offset((w - titleWidth) / 2, h * .1f), font / 6))
            if (appearance.drawAuthor && !author.isNullOrEmpty()) {
                val authorWidth = (w * .65f).toInt().coerceAtLeast(1)
                var fontSize = w / 10 * ((sizes?.authorLarge ?: 100) / 100f)
                val minimum = sizes?.let { w / 10 * it.authorSmall / 100f } ?: (w / 16)
                if (sizes != null && measure(author, fontSize, false).size.width > authorWidth && minimum > fontSize) fontSize = minimum
                while (fontSize > minimum && measure(author, fontSize, false).size.width > authorWidth) fontSize = maxOf(minimum, fontSize - .5f)
                val resultAuthor = measurer.measure(author, textStyle(fontSize, false, TextAlign.Right),
                    constraints = Constraints(minWidth = authorWidth, maxWidth = authorWidth), maxLines = 1, overflow = TextOverflow.Ellipsis)
                glyphs.add(CoverTextLayout.Glyph(resultAuthor, Offset(w * .9f - authorWidth, h * .92f - resultAuthor.firstBaseline), fontSize / 5))
            }
        } else {
            val titleBounds = Rect(w * .05f, h * .05f, w * if (hasAuthor) .66f else .94f, h * .94f)
            fun bounded(value: CoverTextLayout.Glyph, bounds: Rect): CoverTextLayout.Glyph? {
                val halfStroke = value.stroke / 2
                val position = Offset(maxOf(value.position.x, bounds.left + halfStroke), maxOf(value.position.y, bounds.top + halfStroke))
                if (position.x + value.layout.size.width + halfStroke > bounds.right ||
                    position.y + value.layout.size.height + halfStroke > bounds.bottom) return null
                return value.copy(position = position)
            }
            fun ellipsizeLast(start: Int, bounds: Rect, bold: Boolean) {
                if (glyphs.size <= start) return
                val last = glyphs.last()
                val size = last.stroke * if (bold) 6 else 5
                val result = measure("…", size, bold)
                val halfStroke = last.stroke / 2
                val right = bounds.right - result.size.width - halfStroke
                val bottom = bounds.bottom - result.size.height - halfStroke
                if (right < bounds.left + halfStroke || bottom < bounds.top + halfStroke) return
                glyphs[glyphs.lastIndex] = CoverTextLayout.Glyph(result, Offset(
                    (last.position.x + (last.layout.size.width - result.size.width) / 2f).coerceIn(bounds.left + halfStroke, right),
                    (last.position.y + last.layout.firstBaseline - result.firstBaseline).coerceIn(bounds.top + halfStroke, bottom)), last.stroke)
            }
            val characters = title.toStringArray()
            var font = large
            fun textHeight(size: Float) = measure("国", size).let { it.getLineBottom(0) - it.getLineTop(0) }
            if (appearance.adaptive && characters.size * textHeight(font) > h * .6f) font = small
            var x = w * .2f; var y = h * .2f; var line = 0
            var columnLeft = titleBounds.left
            var columnRight = columnLeft
            fun nextColumn() {
                columnLeft = columnRight + font * .1f
                x += font; line++
                if (!appearance.adaptive) font = sizes?.let { w / 7 * it.titleSmall / 100f } ?: (w / 10)
                y = h * .2f + textHeight(font) * line
            }
            var placed = 0
            for ((index, character) in characters.withIndex()) {
                fun candidate(): CoverTextLayout.Glyph? = bounded(glyph(character, font, x, y, true), titleBounds.copy(left = columnLeft))
                var value = candidate()
                if (value == null) {
                    nextColumn()
                    value = candidate()
                }
                if (value == null) break
                glyphs.add(value); placed++
                columnRight = maxOf(columnRight, value.position.x + value.layout.size.width + value.stroke / 2)
                y += textHeight(font)
                if (y > h * .9f) {
                    if (characters.size - index - 1 == 1) { y -= textHeight(font) / 5; if (!appearance.adaptive) font = small }
                    else nextColumn()
                } else if (y > h * .8f && characters.size - index - 1 > 2) {
                    nextColumn()
                }
            }
            if (placed < characters.size) ellipsizeLast(0, titleBounds, true)
            if (appearance.drawAuthor && !author.isNullOrEmpty()) {
                val charactersAuthor = author.toStringArray()
                var fontAuthor = w / 10 * ((sizes?.authorLarge ?: 100) / 100f)
                if (sizes != null && charactersAuthor.size * textHeight(fontAuthor) > h * .65f) fontAuthor = w / 10 * sizes.authorSmall / 100f
                var yAuthor = maxOf(h * .95f - charactersAuthor.size * textHeight(fontAuthor), h * .3f)
                val authorStart = glyphs.size
                val authorBounds = Rect(w * .69f, h * .05f, w * .96f, h * .95f)
                for (character in charactersAuthor) {
                    val value = bounded(glyph(character, fontAuthor, w * .8f, yAuthor, false), authorBounds) ?: break
                    glyphs.add(value)
                    yAuthor += textHeight(fontAuthor)
                }
                if (glyphs.size - authorStart < charactersAuthor.size) ellipsizeLast(authorStart, authorBounds, false)
            }
        }
        CoverTextLayout(glyphs.toList(), Color(appearance.background), Color(appearance.accent))
    }
}
