package io.legado.app.model

/** A load owns these values for its entire lifetime, including retry and delayed downloads. */
data class TextReaderProcessing(
    val paragraphIndent: String = "\u3000\u3000",
    val adaptSpecialStyle: Boolean = false,
    val chineseConverterType: Int = 0,
    val manualReplaceRules: Boolean = false,
) {
    init { require(chineseConverterType in 0..2) }
}
