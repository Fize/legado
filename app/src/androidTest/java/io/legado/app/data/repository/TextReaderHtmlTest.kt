package io.legado.app.data.repository

import android.text.style.*
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.help.TextViewTagHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextReaderHtmlTest {
    @Test fun idsAndLegacyNamesRetainTheirExactDecodedTextRanges() {
        val result = TextReaderHtml.parse("alpha <a id='中+文' name='alias'>β😀</a> omega <a href='#alias'>go</a>")
        assertEquals(listOf("中+文", "alias"), result.anchors.map { it.id })
        result.anchors.forEach { assertEquals("β😀", result.text.subSequence(it.start, it.end).toString()) }
        assertEquals(1, result.text.getSpans(0, result.text.length, URLSpan::class.java).size)
    }
    @Test fun epubFootnoteAndReferenceSemanticsAreCapturedWithoutSourceCharacters() {
        val source = "<p>Main<a epub:type='noteref' href='#n'>[1]</a></p>" +
            "<aside epub:type='footnote' id='n'><p>Note <b>bold</b></p></aside>"
        val result = TextReaderHtml.parse(source)
        val note = result.anchors.single()
        assertTrue(note.note)
        assertEquals("Note bold", result.text.subSequence(note.start, note.end).toString().trim())
        val link = result.text.getSpans(0, result.text.length, URLSpan::class.java).single()
        assertTrue((result.text.getSpanStart(link) to result.text.getSpanEnd(link)) in result.references)
        assertEquals(source.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, tagHandler = TextViewTagHandler()).toString(), result.text.toString())
    }
    @Test fun ariaNotesAndMultipleSemanticTokensAreRecognized() {
        val result = TextReaderHtml.parse("<a role='link doc-noteref' href='#n'>1</a>" +
            "<div id='n' role='doc-footnote'>ARIA note</div><aside id='e' epub:type='other endnote'>End note</aside>")
        assertEquals(2, result.anchors.size); assertTrue(result.anchors.all { it.note })
        assertEquals(1, result.references.size)
    }
    @Test fun metadataInterceptionPreservesLegacyWhitespaceAndStyleRanges() {
        val samples = listOf("<p>A &amp; B</p><div>Two<br>Three</div>",
            "<p id='x'>One<b>two<i>three</p><p>Last", "<ul><li>First</li><li>Second</li></ul>",
            "<p>مرحبا 😀 <span style='color:#00cc22'>green</span></p>",
            "<button id='b'>Go@onclick:1+1</button><hr><p>After</p>", "Text <img src='picture.png'> tail")
        samples.forEach { source ->
            val expected = source.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, tagHandler = TextViewTagHandler())
            val actual = TextReaderHtml.parse(source).text
            assertEquals(source, expected.toString(), actual.toString())
            fun styles(text: android.text.Spanned) = text.getSpans(0, text.length, Any::class.java).filter {
                it is StyleSpan || it is ForegroundColorSpan || it is URLSpan || it is ImageSpan
            }.map { listOf(it.javaClass.name, text.getSpanStart(it), text.getSpanEnd(it), when (it) {
                is StyleSpan -> it.style.toString(); is ForegroundColorSpan -> it.foregroundColor.toString()
                is URLSpan -> it.url; is ImageSpan -> it.source; else -> ""
            }) }
            assertEquals(source, styles(expected), styles(actual))
        }
    }
    @Test fun buttonReplacementUpdatesTheAnchorSpanBeforeItBecomesImmutable() {
        val result = TextReaderHtml.parse("<button id='b'>Go@onclick:veryLongCommand()</button> tail")
        val anchor = result.anchors.single()
        assertEquals("Go", result.text.subSequence(anchor.start, anchor.end).toString())
        assertFalse(result.text.toString().contains("veryLongCommand"))
    }
    @Test fun nestedElementsAndEmptyAnchorsNeverProduceInvalidRanges() {
        val result = TextReaderHtml.parse("<div id='outer'><a id='empty'></a><span id='inner'>Inner</span></div><a name='last'></a>")
        assertEquals(listOf("outer", "empty", "inner", "last"), result.anchors.map { it.id })
        result.anchors.forEach { assertTrue(it.start >= 0 && it.end >= it.start && it.end <= result.text.length) }
        assertEquals("Inner", result.text.subSequence(result.anchors[2].start, result.anchors[2].end).toString())
    }
}
