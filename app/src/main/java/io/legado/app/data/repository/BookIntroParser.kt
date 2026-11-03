package io.legado.app.data.repository

import io.legado.app.data.entities.*

import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/** Parses untrusted source markup into data; source scripts execute only after an explicit UI action. */
internal object BookIntroParser {
    fun parse(raw: String, baseUrl: String): BookIntroDocument {
        val wrappers = listOf("<useweb>" to IntroFormat.Web, "<usehtml>" to IntroFormat.Html, "<md>" to IntroFormat.Markdown)
        val wrapper = wrappers.firstOrNull { raw.startsWith(it.first) }
        val end = raw.lastIndexOf('<')
        if (wrapper == null || end < wrapper.first.length) return BookIntroDocument(IntroFormat.Plain,
            listOf(IntroBlock.Paragraph(listOf(IntroInline.Text(raw)), indent = true)))
        val body = raw.substring(wrapper.first.length, end)
        if (wrapper.second == IntroFormat.Web) return BookIntroDocument(IntroFormat.Web, emptyList(), body)
        val html = if (wrapper.second == IntroFormat.Markdown) {
            val extensions = listOf(TablesExtension.create())
            HtmlRenderer.builder().extensions(extensions).build().render(Parser.builder().extensions(extensions).build().parse(body))
        } else body
        val root = Jsoup.parseBodyFragment(html, baseUrl).body()
        return BookIntroDocument(wrapper.second, blocks(root, baseUrl))
    }
    private fun blocks(root: Element, base: String, quote: Int = 0): List<IntroBlock> {
        val result = arrayListOf<IntroBlock>()
        val pending = arrayListOf<IntroInline>()
        fun flush() { if (pending.any { it !is IntroInline.Text || it.text.isNotBlank() }) result.add(IntroBlock.Paragraph(pending.toList(), quote = quote)); pending.clear() }
        root.childNodes().forEach { node ->
            val tag = (node as? Element)?.normalName()
            when {
                tag in setOf("script", "style", "head", "noscript") -> Unit
                tag == "hr" -> { flush(); result.add(IntroBlock.Rule) }
                tag == "table" -> {
                    flush(); val table = node as Element
                    val rows = table.select("tr").filter { it.closest("table") === table }.map { row ->
                        row.children().filter { it.normalName() in setOf("td", "th") }.map { cell ->
                            IntroBlock.Paragraph(inlines(cell, base, IntroStyle(bold = cell.normalName() == "th")), alignment = cell.attr("align").takeIf(String::isNotBlank))
                        }
                    }.filter(List<IntroBlock.Paragraph>::isNotEmpty)
                    if (rows.isNotEmpty()) result.add(IntroBlock.Table(rows, if (table.selectFirst("thead") != null || table.selectFirst("tr th") != null) 1 else 0))
                }
                tag in setOf("ul", "ol") -> {
                    flush(); val list = node as Element
                    list.children().filter { it.normalName() == "li" }.forEachIndexed { index, item ->
                        val prefix = if (tag == "ol") "${(list.attr("start").toIntOrNull() ?: 1) + index}. " else "• "
                        val nested = blocks(item, base, quote + 1)
                        if (nested.isEmpty()) result.add(IntroBlock.Paragraph(listOf(IntroInline.Text(prefix)), quote = quote))
                        else {
                            val first = nested.first()
                            result.add(if (first is IntroBlock.Paragraph) first.copy(content = listOf(IntroInline.Text(prefix)) + first.content, quote = quote) else IntroBlock.Paragraph(listOf(IntroInline.Text(prefix)), quote = quote))
                            if (first !is IntroBlock.Paragraph) result.add(first)
                            result.addAll(nested.drop(1))
                        }
                    }
                }
                tag == "blockquote" -> { flush(); result.addAll(blocks(node as Element, base, quote + 1)) }
                tag in setOf("p", "pre", "h1", "h2", "h3", "h4", "h5", "h6") -> {
                    flush(); val element = node as Element
                    val heading = tag?.drop(1)?.toIntOrNull()
                    result.add(IntroBlock.Paragraph(inlines(element, base, IntroStyle(bold = heading != null, code = tag == "pre", size = heading?.let { (1.8f - it * .1f).coerceAtLeast(1f) } ?: 1f)),
                        preformatted = tag == "pre", alignment = element.attr("align").takeIf(String::isNotBlank), quote = quote))
                }
                tag in setOf("div", "section", "article", "main") -> { flush(); result.addAll(blocks(node as Element, base, quote)) }
                else -> pending.addAll(inlines(node, base))
            }
        }
        flush()
        return result
    }
    private fun inlines(node: Node, base: String, parent: IntroStyle = IntroStyle(), link: String? = null): List<IntroInline> {
        if (node is TextNode) return listOf(IntroInline.Text((if (parent.code) node.wholeText else node.text()), parent, link))
        if (node !is Element) return emptyList()
        val tag = node.normalName()
        if (tag in setOf("script", "style", "noscript")) return emptyList()
        if (tag == "br") return listOf(IntroInline.Text("\n", parent, link))
        if (tag == "img") {
            val url = resolve(base, node.attr("src"))
            val matcher = AnalyzeUrl.paramPattern.matcher(url)
            val options = if (matcher.find()) GSON.fromJsonObject<Map<String, String>>(url.substring(matcher.end())).getOrNull().orEmpty() else emptyMap()
            return listOf(IntroInline.Image(url, node.attr("alt"), options["click"] ?: node.attr("onclick").takeIf(String::isNotBlank),
                options["width"] ?: node.attr("width").takeIf(String::isNotBlank), options["height"] ?: node.attr("height").takeIf(String::isNotBlank), options["style"] ?: node.attr("align").takeIf(String::isNotBlank)))
        }
        if (tag == "button") {
            val text = node.wholeText()
            val label = text.substringBefore("@onclick:").trim()
            val script = if ("@onclick:" in text) text.substringAfter("@onclick:").trim() else node.attr("onclick")
            return if (script.isNotBlank()) listOf(IntroInline.Button(label, script)) else listOf(IntroInline.Text(label, parent, link))
        }
        val css = node.attr("style").split(';').mapNotNull { declaration ->
            val pair = declaration.split(':', limit = 2); if (pair.size == 2) pair[0].trim().lowercase() to pair[1].trim() else null
        }.toMap()
        val style = parent.copy(bold = parent.bold || tag in setOf("b", "strong") || css["font-weight"] in setOf("bold", "700"),
            italic = parent.italic || tag in setOf("i", "em") || css["font-style"] == "italic",
            underline = parent.underline || tag == "u", strike = parent.strike || tag in setOf("s", "del", "strike"),
            code = parent.code || tag in setOf("code", "pre"), color = css["color"] ?: node.attr("color").takeIf(String::isNotBlank) ?: parent.color,
            background = css["background-color"] ?: parent.background)
        val href = if (tag == "a") node.attr("href").takeIf(String::isNotBlank)?.let { resolve(base, it) } else link
        return node.childNodes().flatMap { inlines(it, base, style, href) }
    }
    private fun resolve(base: String, raw: String): String {
        if (raw.isBlank() || raw.startsWith("data:") || raw.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"))) return raw
        val matcher = AnalyzeUrl.paramPattern.matcher(raw)
        val split = if (matcher.find()) matcher.start() else raw.length
        return runCatching { URI(base.substringBefore(',')).resolve(raw.take(split)).toString() + raw.substring(split) }.getOrDefault(raw)
    }
}
