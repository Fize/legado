package io.legado.app.data.repository

import android.text.Editable
import android.text.Html
import android.text.Spanned
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import io.legado.app.help.TextViewTagHandler
import io.legado.app.model.TextReaderAnchor
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.XMLReader

/** Observe public SAX events while HtmlCompat continues to own text, whitespace and style spans. */
internal object TextReaderHtml {
    private const val BOOTSTRAP = "legado-reader-metadata"
    private data class Anchor(val id: String, val note: Boolean, val order: Int)
    private class NoteReference
    private data class Frame(val tag: String, val markers: List<Any>)
    data class Result(val text: Spanned, val anchors: List<TextReaderAnchor>, val references: Set<Pair<Int, Int>>)

    fun parse(source: String): Result {
        val handler = MetadataHandler()
        val text = "<$BOOTSTRAP></$BOOTSTRAP>$source".parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, tagHandler = handler)
        val anchors = text.getSpans(0, text.length, Anchor::class.java).sortedBy { it.order }.mapNotNull {
            val start = text.getSpanStart(it); val end = text.getSpanEnd(it)
            if (start < 0 || end < start || end > text.length) null else TextReaderAnchor(it.id, start, end, it.note)
        }
        val references = text.getSpans(0, text.length, NoteReference::class.java).mapNotNull {
            val start = text.getSpanStart(it); val end = text.getSpanEnd(it)
            if (start < 0 || end <= start || end > text.length) null else start to end
        }.toSet()
        return Result(text, anchors, references)
    }

    private class MetadataHandler : Html.TagHandler {
        private val legacy = TextViewTagHandler()
        private var installed = false
        override fun handleTag(opening: Boolean, tag: String?, output: Editable?, reader: XMLReader?) {
            if (opening && tag == BOOTSTRAP && !installed && output != null && reader != null) {
                installed = true
                val original = reader.contentHandler
                val stack = mutableListOf<Frame>()
                var order = 0
                reader.contentHandler = object : ContentHandler by original {
                    override fun startElement(uri: String, local: String, name: String, attributes: Attributes) {
                        val tagName = local.ifEmpty { name }.lowercase(java.util.Locale.ROOT)
                        val before = output.length
                        original.startElement(uri, local, name, attributes)
                        val start = if (tagName in setOf("img", "br", "hr")) before else output.length
                        val types = attributes.value("epub:type").orEmpty().split(Regex("\\s+"))
                        val roles = attributes.value("role").orEmpty().split(Regex("\\s+"))
                        val note = types.any { it in setOf("footnote", "endnote", "rearnote") } ||
                            roles.any { it in setOf("doc-footnote", "doc-endnote") }
                        val ids = listOfNotNull(attributes.value("id"), attributes.value("xml:id"),
                            attributes.value("name").takeIf { tagName == "a" }).filter { it.isNotBlank() }.distinct()
                        val markers = ids.map { Anchor(it, note, order++) }.toMutableList<Any>()
                        if (tagName == "a" && ("noteref" in types || "doc-noteref" in roles)) markers += NoteReference()
                        markers.forEach { output.setSpan(it, start, start, Spanned.SPAN_MARK_MARK) }
                        stack += Frame(tagName, markers)
                    }
                    override fun endElement(uri: String, local: String, name: String) {
                        val tagName = local.ifEmpty { name }.lowercase(java.util.Locale.ROOT)
                        original.endElement(uri, local, name)
                        if (stack.lastOrNull()?.tag != tagName) return
                        stack.removeAt(stack.lastIndex).markers.forEach {
                            val start = output.getSpanStart(it)
                            if (start >= 0) output.setSpan(it, start, output.length,
                                if (start == output.length) Spanned.SPAN_MARK_MARK else Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            } else if (tag != BOOTSTRAP) legacy.handleTag(opening, tag, output, reader)
        }
    }
    private fun Attributes.value(name: String): String? = getValue(name) ?: (0 until length).firstOrNull {
        getQName(it).equals(name, true) || (name == "xml:id" && getURI(it) == "http://www.w3.org/XML/1998/namespace" && getLocalName(it) == "id") ||
            (name == "epub:type" && getURI(it) == "http://www.idpf.org/2007/ops" && getLocalName(it) == "type")
    }?.let(::getValue)
}
