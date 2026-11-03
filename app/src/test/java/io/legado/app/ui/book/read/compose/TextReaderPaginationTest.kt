package io.legado.app.ui.book.read.compose

import org.junit.Assert.*
import org.junit.Test

class TextReaderPaginationTest {
    @Test fun explicitBreakStartsANewPageWithoutLosingCharacters() {
        val pages = paginateTextReaderLines(20, lines(5), 100f, setOf(8, 16))
        assertEquals(listOf(0 to 8, 8 to 16, 16 to 20), pages.map { it.start to it.end })
    }
    @Test fun leadingAndTrailingBreaksCannotInventBlankPages() {
        assertEquals(1, paginateTextReaderLines(20, lines(5), 100f, setOf(0, 20)).size)
    }
    @Test fun aBreakInAnUnsplitMeasuredLineIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { paginateTextReaderLines(20, lines(5), 100f, setOf(3)) }
    }
    @Test fun phantomParagraphLinesCannotBecomeAnEmptyIntermediatePage() {
        val lines = listOf(TextReaderLine(0, 4, 0f, 10f), TextReaderLine(4, 4, 10f, 20f),
            TextReaderLine(4, 8, 20f, 30f), TextReaderLine(8, 8, 30f, 40f))
        val pages = paginateTextReaderLines(8, lines, 100f, setOf(4))
        assertEquals(listOf(0 to 4, 4 to 8), pages.map { it.start to it.end })
        assertEquals(listOf(0, 2), pages.map { it.firstLine })
    }
    private fun lines(count: Int, size: Float = 10f) = List(count) { i -> TextReaderLine(i * 4, (i + 1) * 4, i * size, (i + 1) * size) }
    private fun pages(height: Float = 20f) = paginateTextReaderLines(20, lines(5), height)
    private val content = TextReaderContentKey("session", "owner", "book", 2, 1)
    private val key = TextReaderRenderKey(content, 300, 20, 1)

    @Test fun wholeLinesCoverTheChapterExactlyOnce() {
        val pages = pages()
        assertEquals(listOf(0 to 8, 8 to 16, 16 to 20), pages.map { it.start to it.end })
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), pages.map { it.firstLine to it.lastLineExclusive })
    }
    @Test fun exactHeightDoesNotCreateAnExtraPage() {
        assertEquals(1, paginateTextReaderLines(8, lines(2), 20f).size)
    }
    @Test fun fractionalLineHeightsDoNotDropALine() {
        val pages = paginateTextReaderLines(20, lines(5, 10.25f), 20.5f)
        assertEquals(listOf(8, 16, 20), pages.map { it.end })
    }
    @Test fun oversizedLineIsKeptAndAdvertisesScrolling() {
        val pages = paginateTextReaderLines(8, lines(2, 50f), 20f)
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.requiresVerticalScroll(20) })
        assertEquals(50f, pages.first().height, 0f)
    }
    @Test fun paragraphGapsCauseABreakWithoutLosingOffsets() {
        val lines = listOf(TextReaderLine(0, 4, 0f, 10f), TextReaderLine(4, 8, 50f, 60f))
        assertEquals(listOf(0 to 4, 4 to 8), paginateTextReaderLines(8, lines, 30f).map { it.start to it.end })
    }
    @Test fun anEmptyChapterStillHasOneAccessiblePage() {
        val page = paginateTextReaderLines(0, listOf(TextReaderLine(0, 0, 0f, 12f)), 20f).single()
        assertEquals(0, page.start); assertEquals(0, page.end)
        assertEquals(0, textReaderPageAtOffset(listOf(page), 100))
    }
    @Test fun terminalNewlineDoesNotCreateAnEmptyLastPage() {
        val lines = listOf(TextReaderLine(0, 4, 0f, 10f), TextReaderLine(4, 4, 10f, 20f))
        assertEquals(1, paginateTextReaderLines(4, lines, 10f).size)
    }
    @Test fun exactBoundaryBelongsToTheFollowingPage() {
        assertEquals(1, textReaderPageAtOffset(pages(), 8))
        assertEquals(2, textReaderPageAtOffset(pages(), 16))
    }
    @Test fun endOfChapterAndInvalidOffsetsAreBounded() {
        assertEquals(0, textReaderPageAtOffset(pages(), -100))
        assertEquals(2, textReaderPageAtOffset(pages(), 20))
        assertEquals(2, textReaderPageAtOffset(pages(), 200))
    }
    @Test fun aCharacterAnchorSurvivesHeightChanges() {
        assertEquals(1, textReaderPageAtOffset(pages(20f), 12))
        assertEquals(3, textReaderPageAtOffset(pages(10f), 12))
        assertEquals(0, textReaderPageAtOffset(pages(50f), 12))
    }
    @Test fun aSurrogatePairCannotBeSplitByARestoredOffset() {
        val text = "a\uD83D\uDE00b"
        assertEquals(1, textReaderCodePointBoundary(text, 2))
        assertEquals(3, textReaderCodePointBoundary(text, 3))
        assertEquals(0, textReaderCodePointBoundary(text, -1))
        assertEquals(4, textReaderCodePointBoundary(text, 100))
    }
    @Test fun staleContentCannotPublishProgress() {
        assertFalse(acceptsTextReaderViewport(key.copy(content = content.copy(generation = 2)), key, pages().first(), 0))
    }
    @Test fun staleWindowOrTypographyCannotPublishProgress() {
        assertFalse(acceptsTextReaderViewport(key.copy(viewportWidth = 600), key, pages().first(), 0))
        assertFalse(acceptsTextReaderViewport(key.copy(viewportHeight = 40), key, pages().first(), 0))
        assertFalse(acceptsTextReaderViewport(key.copy(generation = 2), key, pages().first(), 0))
    }
    @Test fun anotherBookChapterOrOwnerCannotPublishProgress() {
        listOf(content.copy(ownerToken = "new"), content.copy(bookUrl = "other"),
            content.copy(chapterIndex = 3), content.copy(sessionId = "other")).forEach {
            assertFalse(acceptsTextReaderViewport(key.copy(content = it), key, pages().first(), 0))
        }
    }
    @Test fun onlyOffsetsInsideTheAcknowledgedPageAreAccepted() {
        assertTrue(acceptsTextReaderViewport(key, key, pages().first(), 0))
        assertTrue(acceptsTextReaderViewport(key, key, pages().first(), 7))
        assertFalse(acceptsTextReaderViewport(key, key, pages().first(), 8))
        assertFalse(acceptsTextReaderViewport(null, key, pages().first(), 0))
    }
    @Test fun anEmptyPageCanBeAcknowledged() {
        assertTrue(acceptsTextReaderViewport(key, key, TextReaderPageSlice(0, 0, 1, 0, 0, 0f, 10f), 0))
    }
    @Test fun truncatedMeasuredTextIsRejected() { assertThrows(IllegalArgumentException::class.java) { paginateTextReaderLines(24, lines(5), 20f) } }
    @Test fun gapsAndReversedOffsetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { paginateTextReaderLines(8, listOf(TextReaderLine(0, 4, 0f, 10f), TextReaderLine(5, 8, 10f, 20f)), 20f) }
    }
    @Test fun invalidViewportSizesAreRejected() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { height ->
            assertThrows(IllegalArgumentException::class.java) { paginateTextReaderLines(20, lines(5), height) }
        }
    }
    @Test fun invalidLineGeometryIsRejected() {
        listOf(TextReaderLine(0, 4, 10f, 5f), TextReaderLine(0, 4, Float.NaN, 5f), TextReaderLine(0, 4, 0f, Float.POSITIVE_INFINITY)).forEach {
            assertThrows(IllegalArgumentException::class.java) { paginateTextReaderLines(4, listOf(it), 20f) }
        }
    }
    @Test fun resultPagesCannotBeMutatedByAConsumer() {
        assertThrows(UnsupportedOperationException::class.java) { (pages() as MutableList).clear() }
    }
}
