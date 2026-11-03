package io.legado.app.ui.book.read.compose

import io.legado.app.data.repository.*
import io.legado.app.help.HighlightStyle
import io.legado.app.model.TextReaderDocument
import io.legado.app.model.TextReaderIndent
import org.junit.Assert.*
import org.junit.Test

class TextReaderRuleAnnotationTest {
    private val entry=TextReaderEntry("book","source","name",0,0,1,false,null,0,false,false,20,22,0,chapterUrl="chapter")
    private fun hit(start: Int,end: Int,style: HighlightStyle)=TextReaderRuleMatch(start,end,
        TextReaderHighlightRule(1,"uuid","name","pattern",false,style,100,true,true,"fingerprint"))
    private fun manual(start: Int,end: Int,text: String,style: HighlightStyle)=ReaderAnnotationItem(
        1,true,0,start,end,6,"chapter","Title",text,"","","fingerprint",style)
    @Test fun ruleCanStyleTitleWhileManualRangesStayInBody() {
        val document=TextReaderDocument("Title\namber",6)
        val runs=textReaderCombinedAnnotations(document,entry,listOf(manual(0,11,document.text,HighlightStyle(fill=2))),
            listOf(hit(0,11,HighlightStyle(fill=1))))
        assertEquals(listOf(TextReaderAnnotation(0,6,HighlightStyle(fill=1)),TextReaderAnnotation(6,11,HighlightStyle(fill=2))),runs)
        assertEquals("Title\namber",document.text)
    }
    @Test fun manualFillWinsWithoutErasingRuleTextChannels() {
        val document=TextReaderDocument("Title\namber",6)
        val runs=textReaderCombinedAnnotations(document,entry,listOf(manual(6,11,"amber",HighlightStyle(fill=2))),
            listOf(hit(6,11,HighlightStyle(fill=1,bold=true))))
        assertEquals(1,runs.size); assertEquals(2,runs.single().style.fill); assertTrue(runs.single().style.bold)
    }
    @Test fun laterRuleWinsItsChannelAndPreservesEarlierChannels() {
        val document=TextReaderDocument("Title\namber",6)
        val runs=textReaderCombinedAnnotations(document,entry,emptyList(),listOf(
            hit(6,11,HighlightStyle(fill=1,bold=true)),hit(6,11,HighlightStyle(fill=3,italic=true))))
        assertEquals(1,runs.size); assertEquals(3,runs.single().style.fill)
        assertTrue(runs.single().style.bold); assertTrue(runs.single().style.italic)
    }
    @Test fun ruleNeverDecoratesProcessorInsertedIndentation() {
        val document=TextReaderDocument("Title\n  amber",6,indents=listOf(TextReaderIndent(6,8)),paragraphIndentLength=2)
        val runs=textReaderCombinedAnnotations(document,entry,emptyList(),listOf(hit(0,13,HighlightStyle(fill=1))))
        assertEquals(listOf(0 to 6,8 to 13),runs.map { it.start to it.end })
        assertEquals("Title\n  amber",document.text)
    }
    @Test fun annotationsFromAnotherChapterCannotOverrideCurrentRules() {
        val document=TextReaderDocument("Title\namber",6)
        val runs=textReaderCombinedAnnotations(document,entry,listOf(manual(6,11,"amber",HighlightStyle(fill=2)).copy(chapterUrl="other")),
            listOf(hit(6,11,HighlightStyle(fill=1))))
        assertEquals(listOf(TextReaderAnnotation(6,11,HighlightStyle(fill=1))),runs)
    }
}
