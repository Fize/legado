package io.legado.app.model.localBook

/** The persisted EPUB cache contract includes this single entity decoding and literal-image guard. */
internal fun epubCacheText(content: String): String = if ('&' in content)
    org.apache.commons.text.StringEscapeUtils.unescapeHtml4(content.replace("&lt;img", "&lt; img", true)) else content
