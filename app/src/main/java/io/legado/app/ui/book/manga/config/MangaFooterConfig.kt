package io.legado.app.ui.book.manga.config

import androidx.annotation.Keep

@Keep
data class MangaFooterConfig(
    var hideChapterLabel: Boolean = false,
    var hideChapter: Boolean = false,
    var hidePageNumberLabel: Boolean = false,
    var hidePageNumber: Boolean = false,
    var hideProgressRatioLabel: Boolean = false,
    var hideProgressRatio: Boolean = false,
    var footerOrientation: Int = ALIGN_LEFT,//默认靠左
    var hideFooter: Boolean = false,
    var hideChapterName:Boolean=false,
) {
    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
    }
}
