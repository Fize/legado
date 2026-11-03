package io.legado.app.ui.book.read.compose

import org.junit.Assert.*
import org.junit.Test

class TextReaderParagraphSpacingTest {
    private val lines = listOf(TextReaderLine(0, 2, 0f, 10f), TextReaderLine(2, 4, 10f, 20f),
        TextReaderLine(4, 6, 20f, 30f), TextReaderLine(6, 6, 30f, 40f))
    @Test fun gapsChangeGeometryWithoutChangingAnyCharacterRange() {
        val spaced = spaceTextReaderParagraphs("a\nb\nc\n", 0, lines, 5f)
        assertEquals(listOf(0f, 15f, 30f, 40f), spaced.map { it.top })
        assertEquals(lines.map { it.start to it.end }, spaced.map { it.start to it.end })
        assertEquals(listOf(0f, 10f, 20f, 30f), lines.map { it.top })
    }
    @Test fun wrappedLinesDoNotReceiveParagraphGaps() {
        val source = listOf(TextReaderLine(0, 2, 0f, 10f), TextReaderLine(2, 4, 10f, 20f), TextReaderLine(4, 5, 20f, 30f))
        assertEquals(listOf(0f, 10f, 25f), spaceTextReaderParagraphs("abc\nd", 0, source, 5f).map { it.top })
    }
    @Test fun titleLinesAndTerminalNewlinesDoNotAddExtraParagraphGaps() {
        assertEquals(lines, spaceTextReaderParagraphs("a\nb\nc\n", 6, lines, 4f))
        val spaced = spaceTextReaderParagraphs("a\nb\nc\n", 2, lines, 10f)
        assertEquals(listOf(0f, 10f, 30f, 40f), spaced.map { it.top })
    }
    @Test fun imageHeightDoesNotMultiplyTheRequestedParagraphGap() {
        val source = listOf(TextReaderLine(0, 2, 0f, 40f), TextReaderLine(2, 3, 40f, 50f))
        assertEquals(50f, spaceTextReaderParagraphs("a\nb", 0, source, 10f)[1].top, 0f)
    }
    @Test fun spacingCanChangePagesWithoutCreatingAGapOnlyPage() {
        val spaced = spaceTextReaderParagraphs("a\nb\nc\n", 0, lines, 40f)
        val pages = paginateTextReaderLines(6, spaced, 25f)
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 6), pages.map { it.start to it.end })
        assertTrue(pages.all { it.height == 10f })
    }
    @Test fun zeroSpacingAndEmptyTextPreserveNativeGeometry() {
        assertEquals(lines, spaceTextReaderParagraphs("a\nb\nc\n", 0, lines, 0f))
        val empty = listOf(TextReaderLine(0, 0, 0f, 10f))
        assertEquals(empty, spaceTextReaderParagraphs("", 0, empty, 4f))
    }
    @Test fun invalidSpacingIsRejectedBeforePagination() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach {
            assertThrows(IllegalArgumentException::class.java) { spaceTextReaderParagraphs("a\nb\nc\n", 0, lines, it) }
        }
    }
}
