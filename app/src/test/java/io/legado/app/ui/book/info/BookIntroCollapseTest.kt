package io.legado.app.ui.book.info

import io.legado.app.ui.book.info.intro.introCanCollapse
import io.legado.app.ui.book.info.intro.introDisplayHeight
import org.junit.Assert.*
import org.junit.Test

class BookIntroCollapseTest {
    @Test fun newIntroductionsExpandButRestoredOnesKeepTheirChoice() {
        val old = BookInfoIntroUiState(visible = true, expanded = false, canCollapse = true, toggleVisible = true)
        assertTrue(restoreBookInfoIntro(old, true, true, false).expanded)
        assertFalse(restoreBookInfoIntro(old, true, false, false).expanded)
        assertFalse(restoreBookInfoIntro(old, false, true, true).toggleVisible)
    }
    @Test fun shortAndExactFourLineDescriptionsDoNotCollapse() {
        assertFalse(introCanCollapse(60, 88)); assertFalse(introCanCollapse(88, 88))
    }
    @Test fun contentBeyondTheMeasuredLimitOffersExpansion() { assertTrue(introCanCollapse(89, 88)) }
    @Test fun expansionPreservesTheFullContentHeight() { assertEquals(500, introDisplayHeight(true, 500, 88)) }
    @Test fun largeInlineImagesAndTablesUseTheSameBound() {
        assertTrue(introCanCollapse(300, 88)); assertEquals(88, introDisplayHeight(false, 300, 88))
    }
    @Test fun missingMeasurementsNeverInventNegativeLayoutSizes() {
        assertFalse(introCanCollapse(0, 0)); assertEquals(0, introDisplayHeight(false, -1, 88)); assertEquals(60, introDisplayHeight(false, 60, 88))
    }
}
