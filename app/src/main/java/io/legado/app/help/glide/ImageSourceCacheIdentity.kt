package io.legado.app.help.glide

import java.security.MessageDigest

/** Context changes which bytes a source fetches, even when its image URL stays the same. */
internal fun imageSourceCacheIdentity(url: String, headers: Map<String, String>, source: String?, book: String?, manga: Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String?) {
        if (value == null) digest.update("-1:".toByteArray())
        else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray())
            digest.update(bytes)
        }
    }
    field("legado-image-source-v1"); field(url); field(source); field(book); field(manga.toString())
    headers.toSortedMap().forEach { (name, value) -> field(name); field(value) }
    val hex = "0123456789abcdef"
    return buildString(64) { digest.digest().forEach { byte ->
        val value = byte.toInt() and 0xff
        append(hex[value ushr 4]); append(hex[value and 0xf])
    } }
}
