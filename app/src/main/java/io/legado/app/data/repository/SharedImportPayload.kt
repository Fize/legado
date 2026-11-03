package io.legado.app.data.repository

import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.isJson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun jsonImportType(map: Map<String, *>): String? =
        when {
            map["type"] == HighlightRuleFile.TYPE ->
                "highlightRule"

            map.containsKey("bookSourceUrl") ->
                "bookSource"

            map.containsKey("sourceUrl") ->
                "rssSource"

            map.containsKey("pattern") && map.containsKey("style") &&
                map.containsKey("uuid") && !map.containsKey("replacement") ->
                "highlightRule"

            map.containsKey("pattern") && (map.containsKey("style") || map.containsKey("uuid")) ->
                null

            map.containsKey("pattern") ->
                "replaceRule"

            map.containsKey("themeName") ->
                "theme"

            map.containsKey("showRule") ->
                "dictRule"

            map.containsKey("name") && map.containsKey("rule") ->
                "txtRule"

            map.containsKey("name") && map.containsKey("url") ->
                "httpTts"

            map.containsKey("name") && map.containsKey("author") ->
                "bookshelf"

            else -> null
        }

private val sharedImportUrlRegex =
    Regex("""(?<!["'])https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

private val sharedImportUrlTrailingPunctuation =
    setOf(
        '.', ',', ';', ':', '!', '?',
        '\u3002', '\uff0c', '\uff1b', '\uff1a', '\uff01', '\uff1f', '\u3001'
    )

private val sharedImportUrlBrackets =
    listOf(
        '(' to ')', '[' to ']', '{' to '}',
        '\uff08' to '\uff09', '\u3010' to '\u3011', '\u300a' to '\u300b'
    )

internal fun extractSharedImportUrl(text: String): String? {
    if (text.isJson()) return null
    return sharedImportUrlRegex.findAll(text)
        .map { it.value.trimSharedImportUrlSuffix() }
        .filter { it.toHttpUrlOrNull() != null }
        .singleOrNull()
}

private fun String.trimSharedImportUrlSuffix(): String {
    var result = trimEnd { it in sharedImportUrlTrailingPunctuation }
    sharedImportUrlBrackets.forEach { (open, close) ->
        while (result.endsWith(close) && result.count { it == close } > result.count { it == open }) {
            result = result.dropLast(1)
        }
    }
    return result
}

internal fun isSupportedSharedImportMimeType(mimeType: String?): Boolean =
    mimeType.equals("text/plain", ignoreCase = true) ||
        mimeType.equals("text/*", ignoreCase = true) ||
        mimeType.equals("application/json", ignoreCase = true) ||
        mimeType.equals("application/epub+zip", ignoreCase = true) ||
        mimeType.equals("application/pdf", ignoreCase = true) ||
        mimeType.equals("application/zip", ignoreCase = true) ||
        mimeType.equals("application/x-zip-compressed", ignoreCase = true) ||
        mimeType.equals("application/x-rar-compressed", ignoreCase = true) ||
        mimeType.equals("application/vnd.rar", ignoreCase = true) ||
        mimeType.equals("application/x-7z-compressed", ignoreCase = true) ||
        mimeType.equals("application/mobi", ignoreCase = true) ||
        mimeType.equals("application/x-mobipocket-ebook", ignoreCase = true) ||
        mimeType.equals("application/azw", ignoreCase = true) ||
        mimeType.equals("application/azw3", ignoreCase = true) ||
        mimeType.equals("application/x-mobi8-ebook", ignoreCase = true) ||
        mimeType.equals("application/octet-stream", ignoreCase = true)
