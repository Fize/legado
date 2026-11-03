package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookIntroIndentTest {

    @Test
    fun `indents every non-empty unindented paragraph`() {
        val intro = "第一段\n第二段\n\n第三段"

        assertEquals(
            listOf("第一段\n", "第二段\n", "第三段"),
            introIndentRanges(intro).map { intro.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun `keeps manual indentation and blank paragraphs unchanged`() {
        val intro = "　　全角缩进\n 普通空格\n\n未缩进"

        assertEquals(
            listOf("未缩进"),
            introIndentRanges(intro).map { intro.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun `only plain descriptions receive automatic paragraph indentation`() {
        val parser = io.legado.app.data.repository.BookIntroParser
        val plain = parser.parse("Plain paragraph", "https://example.test/book")
        assertTrue((plain.blocks.single() as io.legado.app.data.entities.IntroBlock.Paragraph).indent)
        listOf("<usehtml><p>HTML</p></usehtml>", "<md>**Markdown**</md>").forEach { raw ->
            val document = parser.parse(raw, "https://example.test/book")
            assertTrue(document.blocks.filterIsInstance<io.legado.app.data.entities.IntroBlock.Paragraph>().none { it.indent })
        }
        assertEquals(io.legado.app.data.entities.IntroFormat.Web, parser.parse("<useweb><p>Web</p></useweb>", "https://example.test/book").format)
    }
}
