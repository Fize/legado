package io.legado.app.model

/** Reading guides are independent of source HTML text decorations. All dimensions are dp. */
data class TextReaderUnderline(
    val mode: Int = 0,
    val color: Int? = null,
    val width: Float = 1f,
    val distance: Float = 4f,
    val body: Boolean = true,
    val title: Boolean = true,
) {
    init {
        require(mode in 0..6)
        require(width.isFinite() && width in 0f..10f)
        require(distance.isFinite() && distance in 0f..30f)
    }
    fun enabled(isTitle: Boolean): Boolean = mode != 0 && width > 0 && if (isTitle) title else body
}
