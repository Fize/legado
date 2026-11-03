package io.legado.app.ui.video

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoChapterLargeTextUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun twoLineTitlesRemainReadableAndClickableAtLargeFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var volumeClicks = 0
        var episodeClicks = 0
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    MaterialTheme {
                        Column {
                            VideoChapterItem("Volume 12", true, true) { volumeClicks++ }
                            VideoChapterItem("Owned video", true, false) { episodeClicks++ }
                        }
                    }
                }
            } }
            for (title in listOf("Volume 12", "Owned video")) {
                val node = compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.size)
                val layout = layouts.single()
                assertTrue("Expected at most two lines for $title", layout.lineCount <= 2)
                assertEquals("Title is truncated: $title", title.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) {
                    assertFalse("Title is ellipsized: $title", layout.isLineEllipsized(line))
                    assertTrue("Title is vertically clipped: $title", layout.getLineBottom(line) <= layout.size.height + 1f)
                }
                node.performTouchInput { click() }
            }
            compose.runOnIdle { assertEquals(1, volumeClicks); assertEquals(1, episodeClicks) }
        }
    }
}
