package io.legado.app.ui.test

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayViewportTest {
    @Test fun floatingDialogRootDoesNotCutOffAVisibleAction() {
        val action = Rect(823, 1233, 975, 1359)
        assertFalse(Rect(0, 393, 1080, 1338).contains(action))
        assertTrue(displayViewport(1080, 1920, Rect(0, 0, 1080, 1920)).contains(action))
    }

    @Test fun keyboardAndSideBarOcclusionRemainRejected() {
        val viewport = displayViewport(1080, 1920, Rect(0, 63, 1080, 1019))
        assertTrue(viewport.contains(Rect(823, 814, 975, 940)))
        assertFalse(viewport.contains(Rect(823, 940, 975, 1020)))
        assertFalse(displayViewport(1920, 1080, Rect(0, 63, 1758, 1080))
            .contains(Rect(1750, 800, 1800, 930)))
    }

    @Test fun screenshotEdgesClampTheSystemFrame() {
        val viewport = displayViewport(1080, 1920, Rect(-10, -20, 1100, 1940))
        assertEquals(Rect(0, 0, 1080, 1920), viewport)
        assertFalse(viewport.contains(Rect(900, 1800, 1081, 1900)))
        assertFalse(viewport.contains(Rect(900, 1800, 1000, 1921)))
    }

    @Test fun invalidFramesFailInsteadOfFallingBackToTheWholeDisplay() {
        for (frame in listOf(Rect(), Rect(1081, 0, 1200, 1920))) {
            try { displayViewport(1080, 1920, frame); fail("Invalid frame was accepted: $frame") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
