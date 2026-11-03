package io.legado.app.ui.book.manga.entities

import androidx.compose.runtime.Immutable

@Immutable
data class MangaPage(
    override val chapterIndex: Int = 0,//总章节位置
    val chapterSize: Int,//总章节数量
    val mImageUrl: String = "",//当前URL
    val cachePath: String? = null,
    override val index: Int = 0,//当前章节位置
    val imageCount: Int = 0,//当前章节内容总数
    val mChapterName: String = "",//章节名称
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
) : BaseMangaPage
