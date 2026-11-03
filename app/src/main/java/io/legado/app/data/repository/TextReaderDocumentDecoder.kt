package io.legado.app.data.repository

import android.graphics.Typeface
import android.text.Layout
import android.text.style.*
import io.legado.app.constant.AppPattern
import io.legado.app.model.*
import io.legado.app.utils.NetworkUtils
import io.legado.app.model.localBook.EpubReadingResource
import org.jsoup.Jsoup

/** Android HTML is reduced to immutable strings, numbers and ranges before crossing into state. */
internal object TextReaderDocumentDecoder {
    fun decode(entry: TextReaderEntry, content: TextReaderChapterContent): TextReaderDocument {
        content.richBody?.let { body ->
            val canonical = decode(entry, TextReaderChapterContent(content.title, content.paragraphs, content.sameTitleRemoved))
            val prefix = canonical.text.substring(0, canonical.titleLength)
            val display = prefix + body.text
            val shift = prefix.length
            return TextReaderDocument(display, shift,
                body.spans.map { it.copy(start = it.start + shift, end = it.end + shift) },
                canonical.images.filter { it.offset < shift } + body.images.map { it.copy(offset = it.offset + shift) },
                body.links.map { it.copy(start = it.start + shift, end = it.end + shift) }, body.breaks.mapTo(hashSetOf()) { it + shift },
                paragraphIndentLength = entry.processing.paragraphIndent.length,
                anchors = body.anchors.map { it.copy(start = it.start + shift, end = it.end + shift) },
                storage = canonical, positions = TextReaderPositionMap.between(canonical.text, display), resources = body.resources)
        }
        val text = StringBuilder()
        val styles = mutableListOf<TextReaderSpan>()
        val images = mutableListOf<TextReaderImage>()
        val links = mutableListOf<TextReaderLink>()
        val breaks = mutableSetOf<Int>()
        val indents = mutableListOf<TextReaderIndent>()
        val anchors = mutableListOf<TextReaderAnchor>()
        fun source(raw: String): String = if (entry.localImages || raw.isBlank()) raw
            else NetworkUtils.getAbsoluteURL(entry.chapterUrl.ifBlank { entry.bookUrl }, raw)
        val split = ChapterTitleParser.split(content.title, entry.splitTitle, entry.isVolume)
        if (entry.titleMode != 2 || entry.isVolume || content.paragraphs.isEmpty()) {
            val lines = split?.let { listOf(it.first, it.second) } ?: content.title.split('\n').filter { it.isNotBlank() }
            lines.forEachIndexed { index, line ->
                text.append(line)
                if (index == (if (split == null) 0 else 1) && !entry.titleImage.isNullOrBlank()) {
                    images += TextReaderImage(text.length, source(checkNotNull(entry.titleImage)), inline = true)
                    text.append('\uFFFC')
                }
                text.append('\n')
            }
        }
        val titleLength = text.length
        content.paragraphs.forEach { paragraph ->
            val trimmed = paragraph.trim()
            when {
                entry.adaptSpecialStyle && trimmed == "[newpage]" -> breaks += text.length
                entry.adaptSpecialStyle && trimmed.startsWith("<usehtml>") && trimmed.lastIndexOf('<') > 9 -> {
                    val parsed = TextReaderHtml.parse(trimmed.substring(9, trimmed.lastIndexOf('<')))
                    val html = parsed.text
                    val base = text.length
                    text.append(html)
                    anchors += parsed.anchors.map { it.copy(start = base + it.start, end = base + it.end) }
                    html.getSpans(0, html.length, Any::class.java).forEach spanLoop@ { span ->
                        val start = html.getSpanStart(span)
                        val end = html.getSpanEnd(span)
                        if (start < 0 || end <= start || end > html.length) return@spanLoop
                        val range = TextReaderSpan(base + start, base + end)
                        when (span) {
                            is StyleSpan -> styles += range.copy(bold = span.style and Typeface.BOLD != 0,
                                italic = span.style and Typeface.ITALIC != 0)
                            is UnderlineSpan -> styles += range.copy(underline = true)
                            is StrikethroughSpan -> styles += range.copy(strike = true)
                            is TypefaceSpan -> styles += range.copy(fontFamily = span.family)
                            is ForegroundColorSpan -> styles += range.copy(foreground = span.foregroundColor)
                            is BackgroundColorSpan -> styles += range.copy(background = span.backgroundColor)
                            is RelativeSizeSpan -> span.sizeChange.takeIf { it.isFinite() && it > 0 }?.let {
                                styles += range.copy(relativeSize = it)
                            }
                            is AbsoluteSizeSpan -> if (span.size > 0) styles += range.copy(
                                absoluteSize = span.size.toFloat(), absoluteSizeInPixels = !span.dip)
                            is SuperscriptSpan -> styles += range.copy(baseline = 1)
                            is SubscriptSpan -> styles += range.copy(baseline = -1)
                            is AlignmentSpan -> styles += range.copy(alignment = when (span.alignment) {
                                Layout.Alignment.ALIGN_CENTER -> 1
                                Layout.Alignment.ALIGN_OPPOSITE -> 2
                                else -> 0
                            })
                            is URLSpan -> links += TextReaderLink(base + start, base + end,
                                span.url.orEmpty().let { if (it.startsWith('#') || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(it)) it else source(it) },
                                (start to end) in parsed.references)
                            is ImageSpan -> if (html[start] == '\uFFFC') images += TextReaderImage(base + start,
                                source(span.source.orEmpty()), inline = true)
                        }
                    }
                }
                else -> {
                    val paragraphStart = text.length
                    val plain = paragraph.replace('袮', '祢')
                    val matcher = AppPattern.imgPattern.matcher(plain)
                    var cursor = 0
                    val buffer = StringBuilder()
                    val inline = mutableListOf<TextReaderImage>()
                    fun flush() {
                        if (buffer.isNotBlank()) {
                            val base = text.length
                            text.append(buffer)
                            images += inline.map { it.copy(offset = base + it.offset) }
                        }
                        buffer.setLength(0); inline.clear()
                    }
                    while (matcher.find()) {
                        buffer.append(plain, cursor, matcher.start())
                        val url = source(matcher.group(1).orEmpty())
                        if (entry.imageStyle.equals("text", true)) {
                            inline += TextReaderImage(buffer.length, url, inline = true)
                            buffer.append('\uFFFC')
                        } else {
                            if (buffer.isNotBlank()) flush()
                            val offset = text.length
                            images += TextReaderImage(offset, url, alignment = when {
                                entry.imageStyle.equals("left", true) -> 0
                                entry.imageStyle.equals("right", true) -> 2
                                else -> 1
                            })
                            text.append('\uFFFC')
                            if (entry.imageStyle.equals("single", true)) { breaks += offset; breaks += offset + 1 }
                        }
                        cursor = matcher.end()
                    }
                    buffer.append(plain, cursor, plain.length)
                    flush()
                    val indent = entry.processing.paragraphIndent
                    if (text.length > paragraphStart && plain.startsWith(indent) &&
                        images.none { it.offset == paragraphStart && !it.inline } &&
                        text.substring(paragraphStart, minOf(text.length, paragraphStart + indent.length)) == indent) {
                        indents += TextReaderIndent(paragraphStart, paragraphStart + indent.length)
                    }
                    text.append('\n')
                }
            }
        }
        val blockEnds = images.filter { !it.inline }.mapTo(hashSetOf()) { it.offset + 1 }
        val lastContent = text.indexOfLast { !it.isWhitespace() }
        val resolvedBreaks = breaks.map { if (it in blockEnds && it < text.length && text[it] == '\n') it + 1 else it }
            .filterTo(hashSetOf()) { it == 0 || it <= lastContent }
        return TextReaderDocument(text.toString(), titleLength, styles, images, links, resolvedBreaks,
            indents, entry.processing.paragraphIndent.length, anchors.distinctBy { it.id })
    }

    fun epub(parts: List<EpubReadingResource>, imageStyle: String = "default", complete: Boolean = false): TextReaderDocument {
        val text = StringBuilder(); val spans = mutableListOf<TextReaderSpan>(); val images = mutableListOf<TextReaderImage>()
        val links = mutableListOf<TextReaderLink>(); val anchors = mutableListOf<TextReaderAnchor>()
        val breaks = hashSetOf<Int>()
        parts.forEach { resource ->
            val body = Jsoup.parse(resource.html).body()
            if (!complete) body.getAllElements().filter { it.hasAttr("hidden") ||
                Regex("display\\s*:\\s*none\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("style")) }.forEach { it.remove() }
            val entry = TextReaderEntry("epub", "epub", "", 0, 0, 1, false, null, 2, false, true,
                20, 0, 0xff000000.toInt(), localImages = true)
            val decoded = decode(entry, TextReaderChapterContent("", listOf("<usehtml>${body.outerHtml()}</usehtml>"), false))
            val base = text.length
            text.append(decoded.text); if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
            spans += decoded.spans.map { it.copy(start = it.start + base, end = it.end + base) }
            images += decoded.images.map { it.copy(offset = it.offset + base, inline = imageStyle.equals("text", true),
                alignment = when (imageStyle.lowercase(java.util.Locale.ROOT)) { "left" -> 0; "right" -> 2; else -> 1 }) }
            links += decoded.links.map { it.copy(start = it.start + base, end = it.end + base) }
            anchors += decoded.anchors.map { it.copy(start = it.start + base, end = it.end + base, resource = resource.address.path) }
            if (complete || resource.startsAtRoot) anchors += TextReaderAnchor("",base,text.length,resource=resource.address.path)
            if (imageStyle.equals("single", true)) decoded.images.forEach { breaks += base + it.offset; breaks += base + it.offset + 1 }
        }
        return TextReaderDocument(text.toString(), 0, spans, images, links, breaks, anchors = anchors.distinctBy { it.resource to it.id },
            resources = parts.mapTo(hashSetOf()) { it.address.path }, completeResources = complete)
    }
}
