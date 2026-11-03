package io.legado.app.ui.book.read.compose

import io.legado.app.model.TextReaderDocument
import org.junit.Assert.*
import org.junit.Test

class TextReaderAnnotationRangeTest {
    @Test fun unchangedPositionsRetainAnEntireUnicodeSelection() {
        val document=TextReaderDocument("Title\namber 😀 text",6)
        val start=document.text.indexOf("amber"); val end=start+"amber 😀".length
        val range=textReaderAnnotationRange(document,start,end,6,"amber 😀")
        assertEquals("amber 😀",document.text.substring(range.first,range.last+1))
    }
    @Test fun hidingTheTitleRebasesTheOriginalBodyRange() {
        val document=TextReaderDocument("amber phrase",0)
        assertEquals(0 until 5,textReaderAnnotationRange(document,10,15,10,"amber"))
    }
    @Test fun epubProjectionUsesTheCanonicalRangeForBothEnds() {
        val source=TextReaderDocument("T\nraw amber body",2)
        val document=source.project(TextReaderDocument("A different title\nraw amber body",18),preserveSource=true)
        val at=source.text.indexOf("amber")
        val range=textReaderAnnotationRange(document,at,at+5,2,"amber")
        assertEquals("amber",document.text.substring(range.first,range.last+1))
    }
    @Test fun aUniqueQuoteMovesAfterTheBodyWasEdited() {
        val document=TextReaderDocument("new prefix amber suffix",0)
        assertEquals(11 until 16,textReaderAnnotationRange(document,0,5,0,"amber"))
    }
    @Test fun bookmarksDoNotSearchForAnotherOccurrenceOfThePageExcerpt() {
        val document=TextReaderDocument("first amber second amber",0)
        assertEquals(19,textReaderAnnotationRange(document,19,19,-1,"amber").first)
    }
    @Test fun hugeLegacyCoordinatesRemainBounded() {
        val document=TextReaderDocument("text",0)
        val range=textReaderAnnotationRange(document,Int.MAX_VALUE,Int.MAX_VALUE,-1,"")
        assertTrue(range.first in 0..document.text.length)
    }
    @Test fun aRemovedQuoteDoesNotHighlightUnrelatedTextAtTheOldOffset() {
        assertTrue(textReaderAnnotationRange(TextReaderDocument("other text",0),0,5,0,"amber").isEmpty())
    }
    @Test fun anAmbiguousMovedQuoteIsNotPaintedAtAnUnrelatedOldPosition() {
        assertTrue(textReaderAnnotationRange(TextReaderDocument("other amber amber",0),0,5,0,"amber").isEmpty())
    }

}
