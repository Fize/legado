package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.text.NumberFormat
import java.util.Locale

internal data class MangaFooterLabels(val page: String, val chapter: String, val progress: String)
internal data class MangaFooterPresentation(val text: String, val visible: Boolean, val centered: Boolean)

internal fun mangaProgressFraction(chapterIndex: Int, chapterCount: Int, pageIndex: Int,
    pageCount: Int, doublePage: Boolean): Double {
    if (chapterCount <= 0) return 0.0
    val end = if (doublePage && pageCount > 0) ((pageIndex / 2) * 2 + 1).coerceAtMost(pageCount - 1) else pageIndex
    val progress = if (pageCount <= 0) (chapterIndex + 1.0) / chapterCount
        else (chapterIndex + (end + 1.0) / pageCount) / chapterCount
    val final = chapterIndex + 1 == chapterCount && (pageCount <= 0 || end + 1 == pageCount)
    return progress.coerceIn(0.0, if (final) 1.0 else 0.999)
}

internal fun formatMangaFooter(config: MangaFooterConfig, chapterName: String, chapterIndex: Int,
    chapterCount: Int, pageIndex: Int, pageCount: Int, doublePage: Boolean,
    labels: MangaFooterLabels, locale: Locale): MangaFooterPresentation {
    val parts = mutableListOf<String>()
    if (!config.hideChapterName && chapterName.isNotEmpty()) parts += chapterName
    if (!config.hidePageNumber) {
        val start = if (doublePage) (pageIndex / 2) * 2 else pageIndex
        val end = if (doublePage && pageCount > 0) (start + 1).coerceAtMost(pageCount - 1) else pageIndex
        val page = if (doublePage && end > start) "${start + 1}-${end + 1}/$pageCount" else "${pageIndex + 1}/$pageCount"
        parts += (if (config.hidePageNumberLabel) "" else labels.page) + page
    }
    if (!config.hideChapter) parts += (if (config.hideChapterLabel) "" else labels.chapter) + "${chapterIndex + 1}/$chapterCount"
    if (!config.hideProgressRatio) {
        val number = NumberFormat.getPercentInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
        parts += (if (config.hideProgressRatioLabel) "" else labels.progress) +
            number.format(mangaProgressFraction(chapterIndex, chapterCount, pageIndex, pageCount, doublePage))
    }
    return MangaFooterPresentation(parts.joinToString(" "), !config.hideFooter && pageCount > 0 && chapterCount > 0,
        config.footerOrientation == MangaFooterConfig.ALIGN_CENTER)
}

@Composable
internal fun mangaFooter(state: MangaReaderUiState): MangaFooterPresentation {
    val configuration = LocalConfiguration.current
    val config = remember(state.footerConfigJson) {
        GSON.fromJsonObject<MangaFooterConfig>(state.footerConfigJson).getOrNull() ?: MangaFooterConfig()
    }
    return formatMangaFooter(config, state.chapterName, state.chapterIndex, state.chapterCount,
        state.seekPage, state.seekPageCount, state.doublePage,
        MangaFooterLabels(stringResource(R.string.manga_check_page_number), stringResource(R.string.manga_check_chapter),
            stringResource(R.string.manga_check_progress)), configuration.locales[0])
}
