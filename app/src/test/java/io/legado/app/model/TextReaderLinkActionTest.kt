package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderLinkActionTest {
    private val document = TextReaderDocument("ref section note", 0, anchors = listOf(
        TextReaderAnchor("section", 4, 11), TextReaderAnchor("中+文", 4, 11), TextReaderAnchor("note", 12, 16, true)))
    private fun action(target: String, note: Boolean = false, chapter: String = "https://example.invalid/books/ch.xhtml?token=1") =
        document.linkAction(TextReaderLink(0, 3, target, note), chapter)
    @Test fun aChapterRootCannotTruncateAWholeResourceNoteReference() {
        val root=TextReaderAnchor("",0,4,resource="book.xhtml")
        val chapter=TextReaderDocument("body",0,anchors=listOf(root),resources=setOf("book.xhtml"))
        val link=TextReaderLink(0,0,"epub:/book.xhtml",true)
        assertEquals(TextReaderLinkAction.Jump(0),chapter.linkAction(link.copy(noteReference=false),"book.xhtml"))
        assertTrue(chapter.linkAction(link,"book.xhtml") is TextReaderLinkAction.Resource)
        val complete=TextReaderDocument("body",0,anchors=listOf(root),resources=setOf("book.xhtml"),completeResources=true)
        assertEquals(TextReaderLinkAction.Note(root),complete.linkAction(link,"book.xhtml"))
    }
    @Test fun fragmentsDecodeUnicodeAndPreserveLiteralPluses() {
        assertEquals(TextReaderLinkAction.Jump(4), action("#%E4%B8%AD+%E6%96%87"))
        assertEquals(TextReaderLinkAction.Jump(4), action("#%E4%B8%AD%2B%E6%96%87"))
    }
    @Test fun onlyTheSameResourceAndQueryCanAddressItsLocalAnchors() {
        assertEquals(TextReaderLinkAction.Jump(4), action("ch.xhtml?token=1#section"))
        assertTrue(action("ch.xhtml?token=2#section") is TextReaderLinkAction.Unsupported)
        assertTrue(action("https://other.invalid/ch.xhtml#section") is TextReaderLinkAction.External)
        assertEquals(TextReaderLinkAction.Jump(4), action("ch.xhtml#section", chapter = "OEBPS/ch.xhtml"))
    }
    @Test fun emptyAndTopFragmentsGoToTheDocumentStart() {
        assertEquals(TextReaderLinkAction.Jump(0), action("#"))
        assertEquals(TextReaderLinkAction.Jump(0), action("#top"))
    }
    @Test fun missingOrMalformedFragmentsNeverBecomeExternalCommands() {
        assertEquals(TextReaderLinkAction.Missing, action("#missing"))
        assertEquals(TextReaderLinkAction.Missing, action("#bad%ZZ"))
        assertEquals(TextReaderLinkAction.Unsupported, action("javascript:alert(1)#section"))
        assertEquals(TextReaderLinkAction.Unsupported, action("file:///private/note#section"))
    }
    @Test fun semanticNotesAndNoteReferencesOpenTheirTargetRange() {
        assertEquals(TextReaderLinkAction.Note(document.anchors.last()), action("#note"))
        assertEquals(TextReaderLinkAction.Note(document.anchors.first()), action("#section", note = true))
        val empty = TextReaderDocument("ref", 0, anchors = listOf(TextReaderAnchor("empty", 3, 3, true)))
        assertEquals(TextReaderLinkAction.Missing, empty.linkAction(TextReaderLink(0, 3, "#empty"), "chapter"))
    }
    @Test fun aSliceRetainsStylesImagesAndClippedLinksInItsOwnCoordinateSpace() {
        val original = TextReaderDocument("AB\uFFFCnoteZ", 0, spans = listOf(TextReaderSpan(0, 7, bold = true)),
            images = listOf(TextReaderImage(2, "image")), links = listOf(TextReaderLink(1, 6, "#note", true)), breaks = setOf(2, 7))
        val slice = original.slice(2, 7)
        assertEquals("\uFFFCnote", slice.text)
        assertEquals(TextReaderSpan(0, 5, bold = true), slice.spans.single())
        assertEquals(0, slice.images.single().offset)
        assertEquals(TextReaderLink(0, 4, "#note", true), slice.links.single())
        assertEquals(setOf(0, 5), slice.breaks)
        assertEquals("AB\uFFFCnoteZ", original.text)
    }
    @Test fun anchorMetadataIsValidatedAndCopied() {
        val anchors = mutableListOf(TextReaderAnchor("a", 0, 1))
        val copied = TextReaderDocument("A", 0, anchors = anchors)
        anchors.clear(); assertEquals(1, copied.anchors.size)
        assertThrows(UnsupportedOperationException::class.java) { (copied.anchors as MutableList<*>).clear() }
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("A", 0, anchors = listOf(TextReaderAnchor("a", 0, 2))) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderDocument("A", 0, anchors = listOf(TextReaderAnchor("a", 0, 1), TextReaderAnchor("a", 1, 1))) }
    }
    @Test fun unknownCrossResourceLocalTargetsAreNotSentToAnExternalApp() {
        assertEquals(TextReaderLinkAction.Unsupported, action("notes.xhtml#note", chapter = "OEBPS/ch.xhtml"))
        assertEquals(TextReaderLinkAction.External("HTTPS://example.invalid/other"), action("HTTPS://example.invalid/other"))
    }
    @Test fun savedAnchorKeysAreBoundedAndIndependentOfTheRawIdLength() {
        val anchor = TextReaderAnchor("中+".repeat(100_000), 0, 1)
        assertEquals(64, anchor.key.length)
        assertTrue(anchor.key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(anchor.key, anchor.copy(start = 1, end = 2).key)
        assertNotEquals(anchor.key, anchor.copy(id = anchor.id + "x").key)
    }
    @Test fun epubIdsRemainScopedToTheirResourceAndUnknownTargetsRequestOwnedData() {
        val first = TextReaderAnchor("same",0,3,resource="Text/a.xhtml")
        val second = TextReaderAnchor("same",4,7,true,"Notes/b.xhtml")
        val document = TextReaderDocument("one two",0,anchors=listOf(first,second),resources=setOf("Text/a.xhtml","Notes/b.xhtml"))
        assertNotEquals(first.key,second.key)
        assertEquals(TextReaderLinkAction.Jump(0),document.linkAction(TextReaderLink(0,3,"epub:/Text/a.xhtml#same"),""))
        assertEquals(TextReaderLinkAction.Note(second),document.linkAction(TextReaderLink(0,3,"epub:/Notes/b.xhtml#same"),""))
        assertTrue(document.linkAction(TextReaderLink(0,3,"epub:/Notes/other.xhtml#n",true),"") is TextReaderLinkAction.Resource)
    }
    @Test fun aCompleteResourceSupportsWholeFileNoteReferencesAndRejectsMissingFragments() {
        val root = TextReaderAnchor("",0,4,resource="Notes/n.xhtml")
        val document = TextReaderDocument("Note",0,anchors=listOf(root),resources=setOf("Notes/n.xhtml"),completeResources=true)
        assertEquals(TextReaderLinkAction.Note(root),document.linkAction(TextReaderLink(0,4,"epub:/Notes/n.xhtml",true),""))
        assertEquals(TextReaderLinkAction.Missing,document.linkAction(TextReaderLink(0,4,"epub:/Notes/n.xhtml#missing",true),""))
        assertEquals(TextReaderLinkAction.Missing,document.linkAction(TextReaderLink(0,4,"epub://outside/n.xhtml",true),""))
    }
}
