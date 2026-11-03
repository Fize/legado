package io.legado.app.model

/** Semantic ranges use display UTF-16 offsets; a source projection preserves persistent positions. */
data class TextReaderSpan(
    val start: Int, val end: Int,
    val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
    val strike: Boolean = false, val monospace: Boolean = false,
    val foreground: Int? = null, val background: Int? = null,
    val relativeSize: Float? = null, val absoluteSize: Float? = null,
    val baseline: Int = 0, val absoluteSizeInPixels: Boolean = false,
    val alignment: Int? = null, val fontFamily: String? = null,
)

data class TextReaderImage(val offset: Int, val source: String, val inline: Boolean = false, val alignment: Int = 1)
data class TextReaderLink(val start: Int, val end: Int, val target: String, val noteReference: Boolean = false)
data class TextReaderAnchor(val id: String, val start: Int, val end: Int, val note: Boolean = false, val resource: String? = null) {
    val key: String = textReaderLinkKey(resource?.let { "${it.length}:$it$id" } ?: id)
}
internal fun textReaderLinkKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
/** Only prefixes inserted by the content processor are formatting, never source whitespace/HTML. */
data class TextReaderIndent(val start: Int, val end: Int)

class TextReaderDocument(val text: String, val titleLength: Int,
    spans: List<TextReaderSpan> = emptyList(), images: List<TextReaderImage> = emptyList(),
    links: List<TextReaderLink> = emptyList(), breaks: Set<Int> = emptySet(),
    indents: List<TextReaderIndent> = emptyList(), val paragraphIndentLength: Int = 0,
    anchors: List<TextReaderAnchor> = emptyList(),
    val storage: TextReaderDocument? = null,
    val positions: TextReaderPositionMap? = null,
    resources: Set<String> = emptySet(), val completeResources: Boolean = false) {
    val resources: Set<String> = java.util.Collections.unmodifiableSet(HashSet(resources))
    val storageTitleLength get() = storage?.titleLength ?: titleLength
    val storageIndentLength get() = storage?.paragraphIndentLength ?: paragraphIndentLength
    fun storagePosition(display: Int) = storage?.position(checkNotNull(positions).toStorage(display)) ?: position(display)
    fun restorePosition(saved: Int, oldTitle: Int? = null, oldIndent: Int? = null): Int = storage?.let {
        position(checkNotNull(positions).toDisplay(it.position(saved, oldTitle, oldIndent)))
    } ?: position(saved, oldTitle, oldIndent)
    val spans: List<TextReaderSpan> = java.util.Collections.unmodifiableList(ArrayList(spans))
    val images: List<TextReaderImage> = java.util.Collections.unmodifiableList(ArrayList(images))
    val links: List<TextReaderLink> = java.util.Collections.unmodifiableList(ArrayList(links))
    val breaks: Set<Int> = java.util.Collections.unmodifiableSet(HashSet(breaks))
    val indents: List<TextReaderIndent> = java.util.Collections.unmodifiableList(ArrayList(indents))
    val anchors: List<TextReaderAnchor> = java.util.Collections.unmodifiableList(ArrayList(anchors))
    init {
        require(titleLength in 0..text.length)
        spans.forEach { require(it.start in 0..text.length && it.end in it.start..text.length)
            require(it.relativeSize == null || it.relativeSize.isFinite() && it.relativeSize > 0)
            require(it.absoluteSize == null || it.absoluteSize.isFinite() && it.absoluteSize > 0)
            require(it.baseline in -1..1 && (it.alignment == null || it.alignment in 0..2)) }
        images.forEach { require(it.offset in text.indices && text[it.offset] == '\uFFFC' && it.alignment in 0..2) }
        require(images.map { it.offset }.distinct().size == images.size)
        links.forEach { require(it.start in 0..text.length && it.end in it.start..text.length) }
        require(breaks.all { it in 0..text.length })
        require(anchors.map { it.resource to it.id }.distinct().size == anchors.size)
        require((storage == null) == (positions == null))
        anchors.forEach { require((it.id.isNotBlank() || it.id.isEmpty() && it.resource != null) &&
            it.start in 0..text.length && it.end in it.start..text.length) }
        require(paragraphIndentLength >= 0)
        indents.forEachIndexed { index, indent ->
            require(indent.start in titleLength until text.length && indent.end in indent.start..text.length)
            require(indent.end - indent.start == paragraphIndentLength)
            if (index > 0) require(indent.start > indents[index - 1].end)
        }
    }

    /** A popup uses a separate display document; its offsets are never persisted as book progress. */
    fun slice(start: Int, end: Int): TextReaderDocument {
        require(start in 0..text.length && end in start..text.length)
        return TextReaderDocument(text.substring(start, end), 0,
            spans.filter { it.start < end && it.end > start }.map { it.copy(start = maxOf(start, it.start) - start, end = minOf(end, it.end) - start) },
            images.filter { it.offset in start until end }.map { it.copy(offset = it.offset - start) },
            links.filter { it.start < end && it.end > start }.map { it.copy(start = maxOf(start, it.start) - start, end = minOf(end, it.end) - start) },
            breaks.filter { it in start..end }.mapTo(hashSetOf()) { it - start })
    }

    /** Carry semantic ranges through text processing; layout objects never cross this boundary. */
    fun project(target: TextReaderDocument, preserveSource: Boolean = false): TextReaderDocument {
        val map = TextReaderPositionMap.between(text, target.text)
        return TextReaderDocument(target.text, target.titleLength,
            spans.map { it.copy(start = map.toDisplay(it.start), end = maxOf(map.toDisplay(it.start), map.toDisplayEnd(it.end))) }.filter { it.end > it.start },
            images.mapNotNull { image -> val at = map.toDisplay(image.offset)
                if (at in target.text.indices && target.text[at] == '\uFFFC' && map.toDisplayEnd(image.offset + 1) > at)
                    image.copy(offset = at) else null }.distinctBy { it.offset },
            links.map { it.copy(start = map.toDisplay(it.start), end = maxOf(map.toDisplay(it.start), map.toDisplayEnd(it.end))) }.filter { it.end > it.start },
            breaks.mapTo(hashSetOf(), map::toDisplay), target.indents, target.paragraphIndentLength,
            anchors.map { it.copy(start = map.toDisplay(it.start), end = maxOf(map.toDisplay(it.start), map.toDisplayEnd(it.end))) },
            storage = if (preserveSource) this else null, positions = if (preserveSource) map else null,
            resources = resources, completeResources = completeResources)
    }

    fun position(requested: Int, previousTitleLength: Int? = null, previousIndentLength: Int? = null): Int {
        require(previousTitleLength == null || previousTitleLength >= 0)
        require(previousIndentLength == null || previousIndentLength >= 0)
        val oldTitle = previousTitleLength ?: titleLength
        val adjusted = if (requested < oldTitle) requested.coerceAtMost((titleLength - 1).coerceAtLeast(0)).toLong()
            else {
                val body = requested.toLong() - oldTitle
                var shift = 0L
                var prefixEnd: Long? = null
                if (previousIndentLength != null && previousIndentLength != paragraphIndentLength) {
                    for (indent in indents) {
                        val oldStart = indent.start.toLong() - titleLength + shift
                        if (body < oldStart) break
                        if (body < oldStart + previousIndentLength) {
                            prefixEnd = indent.end.toLong() - titleLength
                            break
                        }
                        shift += previousIndentLength.toLong() - paragraphIndentLength
                    }
                }
                titleLength + (prefixEnd ?: (body - shift))
            }
        val bounded = adjusted.coerceIn(0, (text.length - 1).coerceAtLeast(0).toLong()).toInt()
        return if (bounded in 1 until text.length && Character.isHighSurrogate(text[bounded - 1]) &&
            Character.isLowSurrogate(text[bounded])) bounded - 1 else bounded
    }

    companion object {
        /** Rich paragraphs are decoded before entering this function; markup is never an offset. */
        fun create(titleLines: List<String>, paragraphs: List<String>): TextReaderDocument =
            createDecoded(titleLines, paragraphs.map { it to true })

        /** HTML blocks already contain their own line endings; plain paragraphs append one. */
        fun createDecoded(titleLines: List<String>, paragraphs: List<Pair<String, Boolean>>): TextReaderDocument {
            val title = titleLines.filter { it.isNotBlank() }.joinToString("") { "$it\n" }
            val body = paragraphs.joinToString("") { (text, terminate) -> if (terminate) "$text\n" else text }
            return TextReaderDocument(title + body, title.length)
        }
    }
}
