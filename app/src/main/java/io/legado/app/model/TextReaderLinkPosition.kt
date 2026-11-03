package io.legado.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small source coordinates; neither chapter content nor raw resource addresses enter saved state. */
@Serializable
data class TextReaderLinkPosition(val chapter: Int, val chapterKey: String, val position: Int,
    val titleLength: Int, val indentLength: Int) {
    init {
        require(chapter >= 0 && chapterKey.matches(Regex("[0-9a-f]{64}")) && position >= 0 &&
            titleLength >= 0 && indentLength >= 0)
    }
    fun encode(): String = Json.encodeToString(this)
    companion object {
        fun decode(value: String): TextReaderLinkPosition? = value.takeIf { it.length <= 512 }?.let {
            runCatching { Json.decodeFromString<TextReaderLinkPosition>(it) }.getOrNull()
        }
    }
}
