package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTocIntegrationTest {

    @Test
    fun `current chapter url wins after toc reorder`() {
        val highlight = BookHighlight(chapterUrl = "chapter-url", chapterIndex = 2)

        assertEquals(8, resolveHighlightChapterIndex(highlight, mapOf("chapter-url" to 8)))
        assertNull(resolveHighlightChapterIndex(highlight, emptyMap()))
        assertEquals(2, resolveHighlightChapterIndex(highlight.copy(chapterUrl = ""), emptyMap()))
    }

    @Test
    fun `highlight order uses body positions`() {
        val withTitle = BookHighlight(chapterPos = 14, layoutTitleLength = 10)
        val withoutTitle = BookHighlight(chapterPos = 8, layoutTitleLength = 0)

        assertEquals(4, highlightBodyPosition(withTitle))
        assertEquals(8, highlightBodyPosition(withoutTitle))
        assertTrue(highlightBodyPosition(withTitle) < highlightBodyPosition(withoutTitle))
    }
}
