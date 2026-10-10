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
}
