package io.legado.app.model

/** Serializable values only; platform fonts and measured geometry belong to Composition. */
data class TextReaderTypography(
    val letterSpacing: Float = 0f,
    val bodyWeight: Int = 400,
    val titleWeight: Int = 700,
    val titleColor: Int? = null,
    val titleAlignment: Int = 0,
    val justify: Boolean = false,
    val bodyFont: String = "",
    val titleFont: String = "",
    val systemFont: Int = 0,
    val paddingLeft: Int = 24,
    val paddingTop: Int = 24,
    val paddingRight: Int = 24,
    val paddingBottom: Int = 24,
    val lineHeight: Float? = null,
    val titleLineHeight: Float? = null,
    val paragraphSpacing: Float = 0f,
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,
    val underline: TextReaderUnderline = TextReaderUnderline(),
) {
    init {
        require(letterSpacing.isFinite() && letterSpacing in -0.5f..2f)
        require(bodyWeight in 1..1000 && titleWeight in 1..1000)
        require(titleAlignment in 0..3 && systemFont in 0..2)
        require(listOf(paddingLeft, paddingTop, paddingRight, paddingBottom).all { it in 0..256 })
        require(listOfNotNull(lineHeight, titleLineHeight).all { it.isFinite() && it in 0.5f..6f })
        require(paragraphSpacing.isFinite() && paragraphSpacing in 0f..4f)
        require(titleTopSpacing in 0..1024 && titleBottomSpacing in 0..1024)
    }
}
