package io.legado.app.ui.book.read.compose

import io.legado.app.help.HighlightStyle
import org.junit.Assert.*
import org.junit.Test

class TextReaderAnnotationMergeTest {
    @Test fun overlappingFillsAreResolvedToOneRunWithoutDoubleAlpha() {
        val runs=textReaderResolveAnnotations(listOf(TextReaderAnnotation(0,10,HighlightStyle(fill=1)),TextReaderAnnotation(5,15,HighlightStyle(fill=2))))
        assertEquals(listOf(0 to 5,5 to 15),runs.map { it.start to it.end }); assertEquals(listOf(1,2),runs.map { it.style.fill })
    }
    @Test fun anOverlappingTextChannelDoesNotEraseTheUnderlyingFill() {
        val runs=textReaderResolveAnnotations(listOf(TextReaderAnnotation(0,10,HighlightStyle(fill=1)),TextReaderAnnotation(4,8,HighlightStyle(bold=true))))
        assertEquals(3,runs.size); assertTrue(runs[1].style.bold); assertEquals(1,runs[1].style.fill)
    }
    @Test fun insertedIndentationRetainsOffsetsButHasNoHighlight() {
        val runs=textReaderResolveAnnotations(listOf(TextReaderAnnotation(0,20,HighlightStyle(fill=1))),listOf(0 until 2,10 until 12))
        assertEquals(listOf(2 to 10,12 to 20),runs.map { it.start to it.end })
    }
    @Test fun adjacentIdenticalStylesAreMergedToAvoidDoubleCaps() {
        assertEquals(listOf(TextReaderAnnotation(0,10,HighlightStyle(fill=1))),textReaderResolveAnnotations(listOf(TextReaderAnnotation(0,5,HighlightStyle(fill=1)),TextReaderAnnotation(5,10,HighlightStyle(fill=1)))))
    }
    @Test fun zeroLengthAnnotationsAreIgnored() {
        assertTrue(textReaderResolveAnnotations(listOf(TextReaderAnnotation(3,3,HighlightStyle(fill=1)))).isEmpty())
    }
}
