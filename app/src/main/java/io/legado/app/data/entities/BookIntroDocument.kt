package io.legado.app.data.entities

enum class IntroFormat { Plain, Html, Markdown, Web }
data class IntroStyle(val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
    val strike: Boolean = false, val code: Boolean = false, val color: String? = null, val background: String? = null, val size: Float = 1f)
sealed interface IntroInline {
    data class Text(val text: String, val style: IntroStyle = IntroStyle(), val link: String? = null) : IntroInline
    data class Image(val url: String, val alt: String, val click: String?, val width: String?, val height: String?, val alignment: String? = null) : IntroInline
    data class Button(val label: String, val script: String) : IntroInline
}
sealed interface IntroBlock {
    data class Paragraph(val content: List<IntroInline>, val indent: Boolean = false, val preformatted: Boolean = false,
        val alignment: String? = null, val quote: Int = 0) : IntroBlock
    data class Table(val rows: List<List<Paragraph>>, val headerRows: Int) : IntroBlock
    data object Rule : IntroBlock
}
data class BookIntroDocument(val format: IntroFormat, val blocks: List<IntroBlock>, val webHtml: String? = null)
