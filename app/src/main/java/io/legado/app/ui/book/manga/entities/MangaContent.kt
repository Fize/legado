package io.legado.app.ui.book.manga.entities

data class MangaContent(
    val pos: Int,
    val items: List<BaseMangaPage>,
    val curFinish: Boolean,
    val nextFinish: Boolean
)
