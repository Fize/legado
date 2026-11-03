package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderPositionMapTest {
    private fun monotone(a: String, b: String, map: TextReaderPositionMap) {
        val forward = (0..a.length).map(map::toDisplay)
        val backward = (0..b.length).map(map::toStorage)
        assertTrue(forward.all { it in 0..b.length }); assertTrue(backward.all { it in 0..a.length })
        assertTrue(forward.zipWithNext().all { it.first <= it.second })
        assertTrue(backward.zipWithNext().all { it.first <= it.second })
        assertEquals(b.length, forward.last()); assertEquals(a.length, backward.last())
    }
    @Test fun identicalDocumentsAndTheirEndBoundariesAreIdentityMappings() {
        val text = "章\n  αβ 😀\nBody"
        val map = TextReaderPositionMap.between(text, text)
        (0..text.length).forEach { assertEquals(it, map.toStorage(it)); assertEquals(it, map.toDisplay(it)) }
    }
    @Test fun indentationAndParagraphWhitespaceKeepEveryVisibleCharacter() {
        val a = "Title\n　　One\n　　Two😀\n"
        val b = "Title\nOne\n\nTwo😀\n"
        val map = TextReaderPositionMap.between(a, b); monotone(a, b, map)
        listOf("Title", "One", "Two", "😀").forEach {
            assertEquals(b.indexOf(it), map.toDisplay(a.indexOf(it)))
            assertEquals(a.indexOf(it), map.toStorage(b.indexOf(it)))
        }
    }
    @Test fun entityDecodingAndInsertedContentRetainTheFollowingAnchors() {
        val a = "Prefix &amp; middle &lt; tail 😀"
        val b = "Prefix & middle < tail 😀"
        val map = TextReaderPositionMap.between(a, b); monotone(a, b, map)
        listOf("Prefix", "middle", "tail", "😀").forEach { assertEquals(b.indexOf(it), map.toDisplay(a.indexOf(it))) }
    }
    @Test fun emptyAndCompletelyReplacedDocumentsRemainBounded() {
        for ((a,b) in listOf("" to "Text", "Text" to "", "" to "", "a".repeat(2000) to "b".repeat(3000))) {
            monotone(a, b, TextReaderPositionMap.between(a, b))
        }
    }
    @Test fun randomInsertionsAndDeletionsHaveMonotoneMappings() {
        val random = java.util.Random(31)
        repeat(300) {
            val a = buildString { repeat(random.nextInt(80)) { append(('a'.code + random.nextInt(8)).toChar()) } }
            val b = buildString { repeat(random.nextInt(80)) { append(('a'.code + random.nextInt(8)).toChar()) } }
            monotone(a, b, TextReaderPositionMap.between(a, b))
        }
    }
    @Test fun largeRepeatedParagraphsUseWhitespaceAlignmentWithoutAnchorDrift() {
        val a = (0..9999).joinToString("\n") { "　　正文相同😀" }
        val b = (0..9999).joinToString("\n\n") { "正文相同😀" }
        val map = TextReaderPositionMap.between(a,b); monotone(a,b,map)
        assertEquals(b.lastIndexOf("正文"), map.toDisplay(a.lastIndexOf("正文")))
    }
    @Test fun largeEntityRichChaptersPreserveInteriorAnchorsWithoutApproximation() {
        val a = (0..1999).joinToString("\n") { "　　Paragraph $it &amp; words &#x1F600;" }
        val b = (0..1999).joinToString("\n") { "Paragraph $it & words 😀" }
        val map = TextReaderPositionMap.between(a,b); monotone(a,b,map)
        listOf(1,17,513,1097,1998).forEach { index ->
            val word = "Paragraph $index "
            assertEquals(b.indexOf(word),map.toDisplay(a.indexOf(word)))
            assertEquals(a.indexOf(word),map.toStorage(b.indexOf(word)))
        }
    }
    @Test fun documentProjectionPreservesSemanticRangesAndPersistentCoordinates() {
        val raw = TextReaderDocument("Before\nNote😀", 0, spans = listOf(TextReaderSpan(7,13,bold=true)),
            links = listOf(TextReaderLink(7,11,"#note")), anchors = listOf(TextReaderAnchor("note",7,13,true)))
        val text = "  Before\n  Note😀\n"
        val projected = raw.project(TextReaderDocument(text,0))
        assertEquals("Note😀", text.substring(projected.spans.single().start, projected.spans.single().end))
        val canonical = TextReaderDocument("Before\n　　Note😀\n",0)
        val document = TextReaderDocument(text,0, storage=canonical, positions=TextReaderPositionMap.between(canonical.text,text))
        assertEquals(text.indexOf("Note"), document.restorePosition(canonical.text.indexOf("Note")))
        assertEquals(canonical.text.indexOf("Note"), document.storagePosition(text.indexOf("Note")))
        assertEquals(text.indexOf("😀"), document.restorePosition(canonical.text.indexOf("😀") + 1))
    }
}
