package io.legado.app.data.repository

import io.legado.app.data.entities.*
import org.junit.Assert.*
import org.junit.Test

class BookIntroParserTest {
    private val base = "https://example.test/books/42"
    private fun html(text: String) = BookIntroParser.parse("<usehtml>$text</usehtml>", base)
    private fun text(doc: BookIntroDocument) = doc.blocks.filterIsInstance<IntroBlock.Paragraph>().flatMap { it.content }.filterIsInstance<IntroInline.Text>().joinToString("") { it.text }
    @Test fun plainTextIsNotInterpretedAsMarkup() {
        val raw = "Line one\n\n  Line two <button>Literal</button>"
        val doc = BookIntroParser.parse(raw, base)
        assertEquals(IntroFormat.Plain, doc.format); assertEquals(raw, text(doc))
    }
    @Test fun incompleteWrappersRemainLiteralText() {
        listOf("<md>Missing close", "<usehtml>Missing close", "<useweb>Missing close").forEach { assertEquals(it, text(BookIntroParser.parse(it, base))) }
    }
    @Test fun explicitlyRequestedWebDocumentsRemainWebDocuments() {
        val script = "<script>source.put('key','value')</script>"
        val doc = BookIntroParser.parse("<useweb>$script</useweb>", base)
        assertEquals(script, doc.webHtml); assertTrue(doc.blocks.isEmpty())
    }
    @Test fun ordinaryHtmlDoesNotEvaluateOrDisplayScripts() {
        assertEquals("Visible", text(html("<script>throw Error('never')</script><style>.x{}</style><p>Visible</p>")))
    }
    @Test fun nestedStylesAndLinksArePreservedAsData() {
        val p = html("<p><b><i>Bold italic</i></b> <a href='../target'>Link</a></p>").blocks.single() as IntroBlock.Paragraph
        val runs = p.content.filterIsInstance<IntroInline.Text>()
        assertTrue(runs.first().style.bold && runs.first().style.italic)
        assertEquals("https://example.test/target", runs.last().link)
    }
    @Test fun legacyButtonScriptsKeepWhitespaceAndEntities() {
        val p = html("<button>Login@onclick:source.put('x', 'a  b &lt; c');</button>").blocks.single() as IntroBlock.Paragraph
        assertEquals(IntroInline.Button("Login", "source.put('x', 'a  b < c');"), p.content.single())
    }
    @Test fun onclickAttributesAndPlainButtonsHaveDistinctSemantics() {
        val p = html("<button onclick=\"book.setName('New')\">Change</button><button>Text</button>").blocks.single() as IntroBlock.Paragraph
        assertEquals(IntroInline.Button("Change", "book.setName('New')"), p.content.first())
        assertEquals("Text", (p.content.last() as IntroInline.Text).text)
    }
    @Test fun imageUrlsKeepOptionsAndResolveAgainstTheCurrentBook() {
        val p = html("""<img src="../image.png,{&quot;click&quot;:&quot;source.put('x',1)&quot;,&quot;width&quot;:&quot;50%&quot;}" alt="Cover">""").blocks.single() as IntroBlock.Paragraph
        val image = p.content.single() as IntroInline.Image
        assertTrue(image.url.startsWith("https://example.test/image.png,")); assertEquals("50%", image.width); assertEquals("source.put('x',1)", image.click)
    }
    @Test fun largeDataUrlsAreNotTreatedAsRelativeUrls() {
        val url = "data:image/png;base64," + "A".repeat(1000000)
        val image = ((html("<img src='$url'>").blocks.single() as IntroBlock.Paragraph).content.single() as IntroInline.Image)
        assertEquals(url, image.url)
    }
    @Test fun markdownTablesCodeAndEmphasisUseTheSameDocumentModel() {
        val doc = BookIntroParser.parse("<md>**Strong** and `code`\n\n| A | B |\n|---|---|\n| One | Two |</md>", base)
        assertEquals(IntroFormat.Markdown, doc.format)
        assertTrue(doc.blocks.any { it is IntroBlock.Table && it.rows.size == 2 })
        val runs = (doc.blocks.first() as IntroBlock.Paragraph).content.filterIsInstance<IntroInline.Text>()
        assertTrue(runs.any { it.style.bold }); assertTrue(runs.any { it.style.code })
    }
    @Test fun listsQuotesRulesAndHeadingsAreStructured() {
        val doc = html("<h2>Heading</h2><blockquote><p>Quote</p></blockquote><ol start='3'><li>One</li><li>Two</li></ol><hr>")
        assertTrue((doc.blocks.first() as IntroBlock.Paragraph).content.filterIsInstance<IntroInline.Text>().first().style.size > 1)
        assertEquals(1, (doc.blocks[1] as IntroBlock.Paragraph).quote)
        assertEquals("3. One4. Two", text(doc.copy(blocks = doc.blocks.drop(2))))
        assertEquals(IntroBlock.Rule, doc.blocks.last())
    }
    @Test fun preformattedCodePreservesIndentationAndLineBreaks() {
        assertEquals("  first\n    second", text(html("<pre>  first\n    second</pre>")))
    }
}
