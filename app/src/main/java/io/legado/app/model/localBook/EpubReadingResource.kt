package io.legado.app.model.localBook

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A decoded, container-relative path. It is never a device file path or a network address. */
class EpubResourceAddress private constructor(val path: String, val fragment: String?) {
    val key: String = io.legado.app.model.textReaderLinkKey(path)
    val href: String get() = URI("epub", null, "/$path", null, fragment).toASCIIString()
    override fun equals(other: Any?) = other is EpubResourceAddress && path == other.path && fragment == other.fragment
    override fun hashCode() = 31 * path.hashCode() + (fragment?.hashCode() ?: 0)
    override fun toString() = href

    companion object {
        fun fromPath(path: String, fragment: String? = null): EpubResourceAddress? {
            if (path.startsWith('/') || path.isBlank() || invalid(path) || fragment?.let(::invalid) == true) return null
            val parts = mutableListOf<String>()
            for (part in path.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
            return parts.takeIf { it.isNotEmpty() }?.let { EpubResourceAddress(it.joinToString("/"), fragment) }
        }

        /** Decode URL escapes exactly once, preserving literal '+' and rejecting malformed UTF-8. */
        fun resolve(basePath: String, reference: String): EpubResourceAddress? {
            val base = fromPath(basePath) ?: return null
            if (reference.startsWith("//") || invalid(reference)) return null
            val internal = reference.startsWith("epub:/", true) && !reference.startsWith("epub://", true)
            if (!internal && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(reference)) return null
            val value = if (internal) reference.substring(5) else reference
            val fragment = if ('#' in value) decode(value.substringAfter('#')) ?: return null else null
            val encodedPath = value.substringBefore('#').substringBefore('?')
            val path = decode(encodedPath) ?: return null
            val resolved = when {
                path.startsWith('/') -> path.drop(1)
                path.isEmpty() -> base.path
                else -> base.path.substringBeforeLast('/', "").let { if (it.isEmpty()) path else "$it/$path" }
            }
            return fromPath(resolved, fragment)
        }

        private fun invalid(value: String) = value.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }
        private fun hex(char: Char) = char.takeIf { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }?.digitToInt(16)
        private fun decode(value: String): String? = runCatching {
            buildString {
                var index = 0
                while (index < value.length) {
                    if (value[index] != '%') { append(value[index++]); continue }
                    val bytes = ByteArrayOutputStream()
                    while (index < value.length && value[index] == '%') {
                        require(index + 2 < value.length)
                        val high = hex(value[index + 1])
                        val low = hex(value[index + 2])
                        require(high != null && low != null)
                        bytes.write(high * 16 + low); index += 3
                    }
                    append(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())))
                }
            }
        }.getOrNull()
    }
}

/** HTML remains scoped to the resource that owns its IDs, links, images and chapter boundary. */
data class EpubReadingResource(val address: EpubResourceAddress, val html: String, val startsAtRoot: Boolean = true)

class EpubReadingChapter(parts: List<EpubReadingResource>, val legacyText: String? = null) {
    val parts: List<EpubReadingResource> = java.util.Collections.unmodifiableList(ArrayList(parts))
}
