package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.config.MangaFooterConfig
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MangaReaderFooterTest {
    private val labels = MangaFooterLabels("P", "C", "R")

    @Test fun singlePageFooterKeepsTheChapterAndPageFractions() {
        val footer = formatMangaFooter(MangaFooterConfig(), "Chapter 2", 1, 3, 1, 4, false, labels, Locale.US)
        assertEquals("Chapter 2 P2/4 C2/3 R50.0%", footer.text)
        assertTrue(footer.visible); assertFalse(footer.centered)
    }

    @Test fun doublePagesShowTheContainingSpreadWithoutCrossingTheChapter() {
        assertEquals("Chapter P1-2/3 C1/1 R66.7%",
            formatMangaFooter(MangaFooterConfig(), "Chapter", 0, 1, 1, 3, true, labels, Locale.US).text)
        assertEquals(1.0, mangaProgressFraction(0, 1, 2, 3, true), 0.00001)
    }

    @Test fun roundedProgressDoesNotClaimCompletionBeforeTheLastImage() {
        val progress = mangaProgressFraction(999, 1000, 0, 1000, false)
        assertEquals(0.999, progress, 0.000001)
        val config = MangaFooterConfig(hideChapter = true, hidePageNumber = true, hideChapterName = true)
        val footer = formatMangaFooter(config, "", 999, 1000, 0, 1000, false, labels, Locale.GERMANY)
        assertTrue(footer.text.contains("99,9")); assertFalse(footer.text.contains("100"))
    }

    @Test fun labelsAndVisibilityAreIndependentUserChoices() {
        val config = MangaFooterConfig(hideChapterLabel = true, hidePageNumberLabel = true,
            hideProgressRatioLabel = true, hideChapterName = true, hideFooter = true,
            footerOrientation = MangaFooterConfig.ALIGN_CENTER)
        val footer = formatMangaFooter(config, "Hidden", 1, 3, 1, 4, false, labels, Locale.US)
        assertEquals("2/4 2/3 50.0%", footer.text)
        assertFalse(footer.visible); assertTrue(footer.centered)
    }

    @Test fun anEmptyVolumeDoesNotDisplayAnInventedImageCount() {
        assertFalse(formatMangaFooter(MangaFooterConfig(), "Volume", 1, 3, 0, 0, false, labels, Locale.US).visible)
    }

    @Test fun anUnknownChapterCountNeverProducesANonFiniteFraction() {
        assertEquals(0.0, mangaProgressFraction(0, 0, 0, 0, false), 0.0)
    }
}
