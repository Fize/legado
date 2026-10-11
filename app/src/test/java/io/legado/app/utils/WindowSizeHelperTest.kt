package io.legado.app.utils

import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizeHelperTest {

    @Test
    fun `resolveWindowWidthSizeClass classifies compact medium and expanded widths`() {
        assertEquals(WindowWidthSizeClass.COMPACT, resolveWindowWidthSizeClass(360))
        assertEquals(WindowWidthSizeClass.COMPACT, resolveWindowWidthSizeClass(599))
        assertEquals(WindowWidthSizeClass.MEDIUM, resolveWindowWidthSizeClass(600))
        assertEquals(WindowWidthSizeClass.MEDIUM, resolveWindowWidthSizeClass(839))
        assertEquals(WindowWidthSizeClass.EXPANDED, resolveWindowWidthSizeClass(840))
        assertEquals(WindowWidthSizeClass.EXPANDED, resolveWindowWidthSizeClass(1280))
    }

    @Test
    fun `resolveBookshelfSpanCount adapts list and grid columns across screen sizes`() {
        // List modes (0 and 1): 1 column on phone, 2 columns on tablet
        assertEquals(1, resolveBookshelfSpanCount(0, 390))
        assertEquals(1, resolveBookshelfSpanCount(1, 390))
        assertEquals(2, resolveBookshelfSpanCount(0, 720))
        assertEquals(2, resolveBookshelfSpanCount(1, 1080))

        // Default 3-column grid: 3 on phone, 5 on medium tablet, 6 on expanded tablet
        assertEquals(3, resolveBookshelfSpanCount(3, 390))
        assertEquals(5, resolveBookshelfSpanCount(3, 720))
        assertEquals(6, resolveBookshelfSpanCount(3, 1080))

        // Explicit 6-column grid stays 6 on medium and expanded
        assertEquals(6, resolveBookshelfSpanCount(6, 720))
        assertEquals(6, resolveBookshelfSpanCount(6, 1080))
    }

    @Test
    fun `resolveDialogWidth caps width on expanded screens`() {
        // Phone (390dp, 1080px): 0.9f -> 972px (not capped)
        assertEquals(972, resolveDialogWidth(1080, 0.9f, 390, 1120))
        // Tablet (1080dp, 2560px): 0.9f -> 2304px, capped to maxWidthPx (1120px)
        assertEquals(1120, resolveDialogWidth(2560, 0.9f, 1080, 1120))
    }

    @Test
    fun `resolveAdaptiveReaderPadding centers wide single-page and double-page columns`() {
        // Phone single page (1080px wide, max 1200px): keeps configured 40px padding
        assertEquals(40 to 40, resolveAdaptiveReaderPadding(1080, 40, 40, false, 1200, 1000))

        // Tablet portrait single page (1600px wide, max 1200px, configured 40px):
        // currentWidth = 1520, extra = (1520 - 1200) / 2 = 160 -> 200 to 200
        assertEquals(200 to 200, resolveAdaptiveReaderPadding(1600, 40, 40, false, 1200, 1000))

        // Tablet landscape double page (2560px wide -> 1280px per column, max 1000px, configured 40px):
        // currentColWidth = 1200, extra = (1200 - 1000) / 2 = 100 -> 140 to 140
        assertEquals(140 to 140, resolveAdaptiveReaderPadding(2560, 40, 40, true, 1200, 1000))
    }

    @Test
    fun `resolveMangaColumnWidth constrains vertical webtoon width on wide screens`() {
        // Phone vertical scroll (1080x2400, max 1360px): MATCH_PARENT
        assertEquals(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resolveMangaColumnWidth(1080, 2400, isHorizontalScroll = false, maxVerticalColumnWidthPx = 1360)
        )
        // Tablet portrait vertical scroll (1600x2560, max 1360px): constrained to 1360px
        assertEquals(
            1360,
            resolveMangaColumnWidth(1600, 2560, isHorizontalScroll = false, maxVerticalColumnWidthPx = 1360)
        )
        // Tablet landscape vertical scroll (2560x1600, max 1360px): min(1360, 1600*0.85=1360) -> 1360px
        assertEquals(
            1360,
            resolveMangaColumnWidth(2560, 1600, isHorizontalScroll = false, maxVerticalColumnWidthPx = 1360)
        )
        // Horizontal page mode always returns MATCH_PARENT
        assertEquals(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resolveMangaColumnWidth(2560, 1600, isHorizontalScroll = true, maxVerticalColumnWidthPx = 1360)
        )
    }

    @Test
    fun `resolveIllustrationSize scales illustrations cleanly without overflowing page height`() {
        // FULL style where scaled height would exceed visibleHeight (1000x1500 in 1200x1200):
        // First scales to 1200x1800, then clamps to height=1200 -> 800x1200
        assertEquals(800 to 1200, resolveIllustrationSize(1000, 1500, 1200, 1200, isFullStyle = true))

        // DEFAULT style on high-DPI tablet (600x800 in 1600x2000):
        // 600 < 1600*0.6 (960) and >= 240 -> scales up to 75% of visibleWidth (1200x1600)
        assertEquals(1200 to 1600, resolveIllustrationSize(600, 800, 1600, 2000, isFullStyle = false))

        // Small inline icon (64x64) is NOT scaled up in DEFAULT style
        assertEquals(64 to 64, resolveIllustrationSize(64, 64, 1600, 2000, isFullStyle = false))
    }

    @Test
    fun `shouldUseTwoPane only enables split panes on wide landscape screens`() {
        // Phone portrait & landscape
        assertEquals(false, shouldUseTwoPane(411, 914))
        assertEquals(false, shouldUseTwoPane(914, 411).let { shouldUseTwoPane(800, 400) })
        // Tablet portrait (800dp x 1280dp): stays single-pane so left/right columns aren't cramped
        assertEquals(false, shouldUseTwoPane(800, 1280))
        // Square-ish foldable (840dp x 820dp, aspect ratio <= 1.05): stays single-pane
        assertEquals(false, shouldUseTwoPane(840, 820))
        // Tablet landscape (1280dp x 800dp): enables two-pane master-detail
        assertEquals(true, shouldUseTwoPane(1280, 800))
    }

    @Test
    fun `resolveBookListSpanCount and tablet content helpers adapt to tablet portrait`() {
        // Book list span count (ExploreShow & Search): 1 on phone, 2 on tablet portrait & landscape
        assertEquals(1, resolveBookListSpanCount(411))
        assertEquals(2, resolveBookListSpanCount(800))
        assertEquals(2, resolveBookListSpanCount(1280))

        // Tablet single-pane content padding (1600px wide, max 1440px, min 48px -> (1600-1440)/2 = 80px)
        assertEquals(0, resolveTabletContentPadding(1080, 411, 1440, 48))
        assertEquals(80, resolveTabletContentPadding(1600, 800, 1440, 48))

        // Explore category pills: 3-column phone basis (0.25f) becomes 5-column (0.18f) in tablet portrait single-pane
        assertEquals(0.25f, resolveExploreFlexBasisPercent(0.25f, 411, isTwoPane = false), 0.001f)
        assertEquals(0.18f, resolveExploreFlexBasisPercent(0.25f, 800, isTwoPane = false), 0.001f)
        assertEquals(0.25f, resolveExploreFlexBasisPercent(0.25f, 1280, isTwoPane = true), 0.001f)
    }

    @Test
    fun `shouldUseMangaDoublePage and resolveMangaSpreadSlot pair pages in landscape`() {
        assertEquals(false, shouldUseMangaDoublePage(800, 1280, enableMangaDoublePage = true))
        assertEquals(true, shouldUseMangaDoublePage(1280, 800, enableMangaDoublePage = true))
        assertEquals(false, shouldUseMangaDoublePage(1280, 800, enableMangaDoublePage = false))

        // Even chapter (10 pages), LTR
        assertEquals(MangaSpreadSlot.LEFT, resolveMangaSpreadSlot(0, 10, isDoublePage = true, rightToLeft = false))
        assertEquals(MangaSpreadSlot.RIGHT, resolveMangaSpreadSlot(1, 10, isDoublePage = true, rightToLeft = false))
        assertEquals(MangaSpreadSlot.RIGHT, resolveMangaSpreadSlot(9, 10, isDoublePage = true, rightToLeft = false))

        // Odd chapter (9 pages), LTR: page 8 (last page) stands alone as SINGLE
        assertEquals(MangaSpreadSlot.LEFT, resolveMangaSpreadSlot(6, 9, isDoublePage = true, rightToLeft = false))
        assertEquals(MangaSpreadSlot.RIGHT, resolveMangaSpreadSlot(7, 9, isDoublePage = true, rightToLeft = false))
        assertEquals(MangaSpreadSlot.SINGLE, resolveMangaSpreadSlot(8, 9, isDoublePage = true, rightToLeft = false))

        // Even chapter (10 pages), RTL: first page of spread is RIGHT, second is LEFT
        assertEquals(MangaSpreadSlot.RIGHT, resolveMangaSpreadSlot(0, 10, isDoublePage = true, rightToLeft = true))
        assertEquals(MangaSpreadSlot.LEFT, resolveMangaSpreadSlot(1, 10, isDoublePage = true, rightToLeft = true))
    }

    @Test
    fun `resolveMangaPageEdgeBackgroundColor extracts clean paper color and ignores panel bleed`() {
        val white = 0xFFFFFFFF.toInt()
        val redPanelBleed = 0xFFD93829.toInt()
        val bluePanelBleed = 0xFF28559A.toInt()
        val darkBlack = 0xFF101012.toInt()

        // 1. Standard manga page: 40% white paper margin + 60% colorful bleeding panels -> resolves to pure white
        val whitePaperSamples = intArrayOf(
            white, white, white, white,
            redPanelBleed, redPanelBleed, redPanelBleed,
            bluePanelBleed, bluePanelBleed, bluePanelBleed,
        )
        assertEquals(white, resolveMangaPageEdgeBackgroundColor(whitePaperSamples))

        // 2. Dark background manhua page: 80% dark black margin + 20% panel colors -> resolves to darkBlack
        val darkSamples = intArrayOf(
            darkBlack, darkBlack, darkBlack, darkBlack,
            darkBlack, darkBlack, darkBlack, darkBlack,
            redPanelBleed, bluePanelBleed,
        )
        assertEquals(darkBlack, resolveMangaPageEdgeBackgroundColor(darkSamples))

        // 3. Sepia/parchment background page: dominant bucket returns the sepia color
        val sepia = 0xFFE8DCC4.toInt()
        val sepiaSamples = IntArray(8) { sepia } + intArrayOf(redPanelBleed, bluePanelBleed)
        assertEquals(sepia, resolveMangaPageEdgeBackgroundColor(sepiaSamples))

        // 4. Color filter sync
        assertEquals(0xFFCCCCCC.toInt(), applyMangaColorFilterToBgColor(white, 51, 51, 51))
    }
}

