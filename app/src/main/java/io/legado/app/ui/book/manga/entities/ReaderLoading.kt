package io.legado.app.ui.book.manga.entities

import androidx.compose.runtime.Immutable

@Immutable
data class ReaderLoading(
    override val chapterIndex: Int = 0,
    override val index: Int = 0,
    val mMessage: String? = null,
    val isVolume: Boolean = false
) : BaseMangaPage

