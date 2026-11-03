package io.legado.app.model

/** 0 = color, 1 = bundled background asset, 2 = local file or content URI. */
data class TextReaderBackground(val kind: Int, val value: String, val alpha: Int = 100, val revision: Long = 0,
    val fallbackColor: Int = 0xffffffff.toInt()) {
    init { require(kind in 0..2 && alpha in 0..100 && revision >= 0) }
}
