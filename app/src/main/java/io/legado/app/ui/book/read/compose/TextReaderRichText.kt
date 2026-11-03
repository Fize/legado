package io.legado.app.ui.book.read.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.legado.app.model.TextReaderDocument
import io.legado.app.model.TextReaderTypography

internal fun textReaderBodyStyle(fontSize: Int, color: Int, typography: TextReaderTypography,
    family: FontFamily, locales: LocaleList = LocaleList.current): TextStyle = TextStyle(fontSize = fontSize.coerceAtLeast(1).sp,
    color = Color(color), fontFamily = family, fontWeight = FontWeight(typography.bodyWeight),
    letterSpacing = typography.letterSpacing.em,
    lineHeight = typography.lineHeight?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Minimum),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    localeList = locales,
    lineBreak = LineBreak.Paragraph.copy(strictness = LineBreak.Strictness.Strict),
    hyphens = Hyphens.Auto,
    textAlign = if (typography.justify) TextAlign.Justify else TextAlign.Start)

internal fun textReaderAnnotatedString(document: TextReaderDocument, fontSize: Int, titleSize: Int,
    density: Density, linkColor: Color, typography: TextReaderTypography = TextReaderTypography(),
    titleFamily: FontFamily? = null): AnnotatedString = buildAnnotatedString {
    append(document.text)
    fun imageEnd(offset: Int): Int = (offset + 1).let { if (it < document.text.length && document.text[it] == '\n') it + 1 else it }
    val boundaries = (document.breaks + setOf(0, document.text.length) +
        document.images.filter { !it.inline }.flatMap { listOf(it.offset, imageEnd(it.offset)) }).sorted().distinct()
    boundaries.zipWithNext().forEach { (start, end) ->
        if (end > start) addStyle(ParagraphStyle(), start, end)
    }
    if (document.titleLength > 0) {
        addStyle(SpanStyle(fontWeight = FontWeight(typography.titleWeight), fontFamily = titleFamily,
            color = typography.titleColor?.let(::Color) ?: Color.Unspecified,
            fontSize = (fontSize + titleSize).coerceAtLeast(1).sp), 0, document.titleLength)
        // Split title ranges at existing image/page paragraph boundaries, preserving their offsets.
        boundaries.zipWithNext().forEach { (start, end) ->
            if (start < document.titleLength) addStyle(ParagraphStyle(
                lineBreak = LineBreak.Heading, hyphens = Hyphens.None,
                lineHeight = typography.titleLineHeight?.let { ((fontSize + titleSize).coerceAtLeast(1) * it).sp }
                    ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                textAlign = when (typography.titleAlignment) {
                1 -> TextAlign.Center
                3 -> TextAlign.End
                else -> TextAlign.Start
            }), start, minOf(end, document.titleLength))
        }
    }
    document.spans.forEach { span ->
        val family = when (span.fontFamily) {
            "serif" -> FontFamily.Serif
            "sans-serif" -> FontFamily.SansSerif
            "cursive" -> FontFamily.Cursive
            "monospace" -> FontFamily.Monospace
            else -> if (span.monospace) FontFamily.Monospace else null
        }
        val size = span.absoluteSize?.let { if (span.absoluteSizeInPixels) with(density) { it.toSp() } else it.sp }
            ?: span.relativeSize?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified
        addStyle(SpanStyle(fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null, fontFamily = family,
            color = span.foreground?.let(::Color) ?: Color.Unspecified,
            background = span.background?.let(::Color) ?: Color.Unspecified,
            fontSize = size, baselineShift = when (span.baseline) {
                1 -> BaselineShift.Superscript
                -1 -> BaselineShift.Subscript
                else -> null
            }, textDecoration = when {
                span.underline && span.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                span.underline -> TextDecoration.Underline
                span.strike -> TextDecoration.LineThrough
                else -> null
            }), span.start, span.end)
        span.alignment?.let { alignment ->
            boundaries.zipWithNext().forEach { (from, to) ->
                val start = maxOf(from, span.start); val end = minOf(to, span.end)
                if (end > start) addStyle(ParagraphStyle(textAlign = when (alignment) {
                    1 -> TextAlign.Center
                    2 -> TextAlign.End
                    else -> TextAlign.Start
                }), start, end)
            }
        }
    }
    document.links.forEach { link ->
        addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), link.start, link.end)
        addStringAnnotation("reader-link", link.target, link.start, link.end)
    }
    document.images.filter { !it.inline }.forEach { image ->
        addStyle(ParagraphStyle(textAlign = when (image.alignment) {
            0 -> TextAlign.Start
            2 -> TextAlign.End
            else -> TextAlign.Center
        }), image.offset, imageEnd(image.offset))
    }
}
