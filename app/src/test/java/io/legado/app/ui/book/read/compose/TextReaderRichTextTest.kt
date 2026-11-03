package io.legado.app.ui.book.read.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import io.legado.app.model.*
import org.junit.Assert.*
import org.junit.Test

class TextReaderRichTextTest {
    @Test fun longFormBodyAndHeadingsUseSeparateNativeBreakPoliciesWithoutAddingCharacters() {
        val locale = androidx.compose.ui.text.intl.LocaleList("en-US")
        val body = textReaderBodyStyle(20, 0xff000000.toInt(), TextReaderTypography(),
            androidx.compose.ui.text.font.FontFamily.SansSerif, locale)
        assertEquals(androidx.compose.ui.text.style.LineBreak.Strategy.HighQuality, body.lineBreak.strategy)
        assertEquals(androidx.compose.ui.text.style.LineBreak.Strictness.Strict, body.lineBreak.strictness)
        assertEquals(androidx.compose.ui.text.style.Hyphens.Auto, body.hyphens)
        assertEquals(locale, body.localeList)
        val document = TextReaderDocument("Title\nBody", 6)
        val result = textReaderAnnotatedString(document, 20, 4, Density(1f), Color.Blue)
        val title = result.paragraphStyles.single { it.start == 0 && it.end == 6 &&
            it.item.lineBreak == androidx.compose.ui.text.style.LineBreak.Heading }
        assertEquals(androidx.compose.ui.text.style.Hyphens.None, title.item.hyphens)
        assertEquals(document.text, result.text)
    }
    @Test fun titleWeightColorAndAlignmentUseTheCapturedTypography() {
        val document = TextReaderDocument("Title\nBody", 6)
        val result = textReaderAnnotatedString(document, 20, 4, Density(1f), Color.Blue,
            TextReaderTypography(titleWeight = 300, titleColor = 0xffff0000.toInt(), titleAlignment = 3))
        val title = result.spanStyles.single()
        assertEquals(0, title.start); assertEquals(6, title.end)
        assertEquals(FontWeight.Light, title.item.fontWeight)
        assertEquals(Color.Red, title.item.color)
        assertTrue(result.paragraphStyles.any { it.start == 0 && it.end == 6 && it.item.textAlign == TextAlign.End })
        assertEquals(document.text, result.text)
    }
    @Test fun centeredTitleDoesNotCenterTheBodyOrCrossAnExplicitBreak() {
        val result = textReaderAnnotatedString(TextReaderDocument("ABBody", 2, breaks = setOf(1)),
            20, 4, Density(1f), Color.Blue, TextReaderTypography(titleAlignment = 1))
        val centered = result.paragraphStyles.filter { it.item.textAlign == TextAlign.Center }
        assertEquals(listOf(0 to 1, 1 to 2), centered.map { it.start to it.end })
    }
    @Test fun stylesPreserveTheExactDisplayStringAndRanges() {
        val document = TextReaderDocument("Bold italic", 0, listOf(TextReaderSpan(0, 4, bold = true),
            TextReaderSpan(5, 11, italic = true, underline = true, foreground = 0xffff0000.toInt())))
        val result = textReaderAnnotatedString(document, 20, 4, Density(1f), Color.Blue)
        assertEquals(document.text, result.text)
        assertTrue(result.spanStyles.any { it.start == 0 && it.end == 4 && it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.start == 5 && it.end == 11 && it.item.fontStyle == FontStyle.Italic &&
            it.item.textDecoration == TextDecoration.Underline && it.item.color == Color.Red })
    }
    @Test fun linksRetainLogicalRangesInsteadOfInsertingTheirTargetsIntoText() {
        val document = TextReaderDocument("Read link", 0, links = listOf(TextReaderLink(5, 9, "https://example.invalid")))
        val result = textReaderAnnotatedString(document, 20, 4, Density(1f), Color.Blue)
        assertEquals("Read link", result.text)
        assertEquals("https://example.invalid", result.getStringAnnotations("reader-link", 5, 9).single().item)
    }
    @Test fun explicitBreaksAndBlockImagesCreateParagraphBoundariesWithoutCharacters() {
        val document = TextReaderDocument("A\uFFFCB", 0, images = listOf(TextReaderImage(1, "image")), breaks = setOf(2))
        val result = textReaderAnnotatedString(document, 20, 4, Density(1f), Color.Blue)
        assertEquals(document.text, result.text)
        assertTrue(result.paragraphStyles.any { it.start == 1 && it.end == 2 })
        assertTrue(result.paragraphStyles.any { it.start == 2 && it.end == 3 })
    }
    @Test fun physicalHtmlFontSizesAreConvertedThroughTheCurrentDensity() {
        val document = TextReaderDocument("A", 0, listOf(TextReaderSpan(0, 1, absoluteSize = 40f, absoluteSizeInPixels = true)))
        val result = textReaderAnnotatedString(document, 20, 4, Density(2f, 1f), Color.Blue)
        assertEquals(20f, result.spanStyles.single().item.fontSize.value, 0f)
    }
}
