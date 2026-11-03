package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderDocumentTest {
    private fun indented(indent: String, title: String = "T\n"): TextReaderDocument {
        val starts = mutableListOf<TextReaderIndent>()
        val text = buildString {
            append(title)
            listOf("alpha😀beta", "gamma delta", "epsilon").forEach {
                starts += TextReaderIndent(length, length + indent.length)
                append(indent); append(it); append('\n')
            }
        }
        return TextReaderDocument(text, title.length, indents = starts, paragraphIndentLength = indent.length)
    }
    @Test fun indentChangesKeepEveryBodyCharacterAcrossAllParagraphs() {
        for (oldIndent in listOf("", " ", "\u3000\u3000", "    ")) for (newIndent in listOf("", "  ", "\u3000\u3000\u3000")) {
            val old = indented(oldIndent)
            val next = indented(newIndent)
            old.text.indices.filter { it >= old.titleLength && old.indents.none { p -> it in p.start until p.end } &&
                !Character.isLowSurrogate(old.text[it]) }.forEach { offset ->
                val position = next.position(offset, old.titleLength, old.paragraphIndentLength)
                assertEquals("old=$oldIndent new=$newIndent at=$offset", old.text[offset], next.text[position])
            }
        }
    }
    @Test fun indentAndTitleChangesCanBeAppliedInTheSameConversion() {
        val old = indented("  ", "Long title\n")
        val next = indented("", "")
        val offset = old.text.indexOf("delta")
        assertEquals("delta", next.text.substring(next.position(offset, old.titleLength, 2)).take(5))
        assertEquals(0, next.position(2, old.titleLength, 2))
    }
    @Test fun positionsInsideOldIndentMoveToTheFirstBodyCharacter() {
        val old = indented("    "); val next = indented(" ")
        old.indents.forEachIndexed { index, prefix ->
            for (offset in prefix.start until prefix.end) assertEquals(next.indents[index].end,
                next.position(offset, old.titleLength, 4))
        }
    }
    @Test fun unchangedIndentPreservesOffsetsAndHugeRequestsDoNotOverflow() {
        val document = indented("  ")
        document.text.indices.filter { !Character.isLowSurrogate(document.text[it]) }.forEach {
            assertEquals(it, document.position(it, document.titleLength, 2))
        }
        assertEquals(document.indents.first().end, document.position(Int.MAX_VALUE, 0, Int.MAX_VALUE))
    }
    @Test fun indentMetadataIsValidatedAndCopied() {
        val prefixes = mutableListOf(TextReaderIndent(0, 2))
        val document = TextReaderDocument("  Body", 0, indents = prefixes, paragraphIndentLength = 2)
        prefixes.clear(); assertEquals(1, document.indents.size)
        assertThrows(UnsupportedOperationException::class.java) { (document.indents as MutableList<*>).clear() }
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("Body", 0, indents = listOf(TextReaderIndent(0, 2))) }
        assertThrows(IllegalArgumentException::class.java) { document.position(0, previousIndentLength = -1) }
    }
    @Test fun titleAndBodyRetainTheLegacyTerminatingNewlines() {
        val document = TextReaderDocument.create(listOf("Chapter"), listOf("First", "Second"))
        assertEquals("Chapter\nFirst\nSecond\n", document.text)
        assertEquals(8, document.titleLength)
    }
    @Test fun aSplitChapterNumberRemainsPartOfThePositionPrefix() {
        val document = TextReaderDocument.create(listOf("第一章", "标题"), listOf("正文"))
        assertEquals("第一章\n标题\n正文\n", document.text)
        assertEquals(7, document.titleLength)
    }
    @Test fun hiddenTitlesHaveNoPrefix() {
        val document = TextReaderDocument.create(emptyList(), listOf("Body"))
        assertEquals("Body\n", document.text); assertEquals(0, document.titleLength)
    }
    @Test fun emptyContentStillAllowsPositionZero() {
        val document = TextReaderDocument.create(emptyList(), emptyList())
        assertEquals("", document.text); assertEquals(0, document.position(80))
    }
    @Test fun positionsPastTheEndResolveToTheLastReadableCharacter() {
        val document = TextReaderDocument.create(emptyList(), listOf("Body"))
        assertEquals(4, document.position(999)); assertEquals(0, document.position(-1))
    }
    @Test fun positionNormalizationDoesNotSplitASupplementaryCharacter() {
        val document = TextReaderDocument("A😀B", 0)
        assertEquals(1, document.position(2)); assertEquals(3, document.position(3))
    }
    @Test fun aFinalSupplementaryCharacterCannotBeSplitByEndClamping() {
        assertEquals(1, TextReaderDocument("A😀", 0).position(Int.MAX_VALUE))
    }
    @Test fun preservingACharacterAnchorDoesNotDependOnPageDimensions() {
        val document = TextReaderDocument.create(listOf("Title"), listOf("Long paragraph"))
        assertEquals(12, document.position(12)); assertEquals(12, document.position(document.position(12)))
    }
    @Test fun paragraphIndentAndWhitespaceAreNotDiscarded() {
        assertEquals("  Body  \n", TextReaderDocument.create(emptyList(), listOf("  Body  ")).text)
    }
    @Test fun blankTitleLinesDoNotInventExtraPrefixCharacters() {
        assertEquals("Title\nBody\n", TextReaderDocument.create(listOf("", "Title", "  "), listOf("Body")).text)
    }
    @Test fun hidingAndRestoringTheTitlePreservesTheBodyCharacterAnchor() {
        val shown = TextReaderDocument.create(listOf("Title"), listOf("Long paragraph"))
        val hidden = TextReaderDocument.create(emptyList(), listOf("Long paragraph"))
        assertEquals(6, hidden.position(12, shown.titleLength))
        assertEquals(12, shown.position(6, hidden.titleLength))
    }
    @Test fun aPositionInsideTheOldTitleStaysAtTheNewTitleOrAtTheBodyStart() {
        val hidden = TextReaderDocument.create(emptyList(), listOf("Body"))
        assertEquals(0, hidden.position(3, 8))
        assertEquals(6, TextReaderDocument.create(listOf("Title"), listOf("Body")).position(0, 0))
    }
    @Test fun titleAdjustmentCannotOverflowAnInteger() {
        val document = TextReaderDocument.create(listOf("Title"), listOf("Body"))
        assertEquals(document.text.lastIndex, document.position(Int.MAX_VALUE, 0))
    }
    @Test fun richRangesAndImagePlaceholdersShareTheDisplayedUtf16Space() {
        val document = TextReaderDocument("A😀\uFFFCB", 0,
            spans = listOf(TextReaderSpan(1, 3, bold = true)), images = listOf(TextReaderImage(3, "image")),
            links = listOf(TextReaderLink(4, 5, "https://example.invalid")), breaks = setOf(3))
        assertEquals(3, document.images.single().offset)
        assertEquals("😀", document.text.substring(document.spans.single().start, document.spans.single().end))
        assertEquals(setOf(3), document.breaks)
    }
    @Test fun documentCopiesAllMutableRichMetadata() {
        val styles = mutableListOf(TextReaderSpan(0, 1, italic = true))
        val images = mutableListOf(TextReaderImage(1, "image"))
        val links = mutableListOf(TextReaderLink(0, 1, "https://example.invalid"))
        val breaks = mutableSetOf(1)
        val document = TextReaderDocument("A\uFFFC", 0, styles, images, links, breaks)
        styles.clear(); images.clear(); links.clear(); breaks.clear()
        assertEquals(1, document.spans.size); assertEquals(1, document.images.size)
        assertEquals(1, document.links.size); assertEquals(1, document.breaks.size)
        assertThrows(UnsupportedOperationException::class.java) { (document.images as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (document.breaks as MutableSet<*>).clear() }
    }
    @Test fun invalidRichRangesAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("A", 0, listOf(TextReaderSpan(0, 2))) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("A", 0, images = listOf(TextReaderImage(0, "image"))) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("A", 0, breaks = setOf(2)) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("A", 0, listOf(TextReaderSpan(0, 1, relativeSize = Float.NaN))) }
    }
}
