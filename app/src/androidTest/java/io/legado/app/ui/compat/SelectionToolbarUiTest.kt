package io.legado.app.ui.compat

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual production component, constrained width/font/direction; not a physical resize test. */
@RunWith(AndroidJUnit4::class)
class SelectionToolbarUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun allSelectionActionsRemainVisibleAndTouchable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val trace = JSONArray()
        var width by mutableFloatStateOf(320f)
        var font by mutableFloatStateOf(2f)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var density = 1f
        var checked = 0; var reverted = 0; var applied = 0; var menu = 0
        val reverse = context.getString(R.string.revert_selection)
        val main = context.getString(R.string.add_to_bookshelf)
        val more = context.getString(R.string.more_menu)
        val count = context.getString(R.string.select_all_count, 17, 128)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, font), LocalLayoutDirection provides direction) {
                    MaterialTheme { Box(Modifier.fillMaxSize()) {
                        LegadoSelectActionBar(17, 128, { checked++ }, { reverted++ }, main, { applied++ },
                            listOf(LegadoMenuAction("Fixture menu", onClick = { menu++ })),
                            Modifier.width(width.dp).testTag("selection-under-test"))
                    } }
                }
            } }
            val available = compose.onRoot().fetchSemanticsNode().boundsInRoot.width / density
            val widths = listOf(320f, 360f, 480f, 600f, 840f).filter { it <= available + 1f }
            assertTrue("Need at least a 320dp component viewport", widths.isNotEmpty())
            // Start with the narrow/large-font case so the old fixed row is a negative control.
            val cases = listOf(320f to 2f) + widths.flatMap { w -> listOf(1f, 1.3f, 2f).map { w to it } }.filter { it != (320f to 2f) }
            for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) for ((w, scale) in cases) {
                compose.runOnIdle { width = w; font = scale; direction = layout }
                compose.waitForIdle()
                val bar = compose.onNodeWithTag("selection-under-test").fetchSemanticsNode().boundsInRoot
                assertEquals(w * density, bar.width, 1f)
                val checkbox = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                fun button(label: String) = compose.onNode(hasClickAction() and (hasText(label) or hasAnyDescendant(hasText(label))))
                val moreButton = compose.onNode(hasClickAction() and (hasContentDescription(more) or hasAnyDescendant(hasContentDescription(more))))
                val actions = listOf(checkbox, button(reverse), button(main), moreButton)
                actions.forEach { action ->
                    action.assertIsDisplayed().assertIsEnabled()
                    val b = action.fetchSemanticsNode().boundsInRoot
                    assertTrue("Action clipped at $w/$scale/$layout: $b outside $bar", b.left >= bar.left - 1 && b.right <= bar.right + 1 && b.top >= bar.top - 1 && b.bottom <= bar.bottom + 1)
                    val touch = action.fetchSemanticsNode().touchBoundsInRoot
                    assertTrue("Touch target too small at $w/$scale/$layout: $touch", touch.width >= 48 * density - 1 && touch.height >= 48 * density - 1)
                    assertTrue("Touch target clipped at $w/$scale/$layout: $touch outside $bar", touch.left >= bar.left - 1 && touch.right <= bar.right + 1 && touch.top >= bar.top - 1 && touch.bottom <= bar.bottom + 1)
                }
                val textLayouts = JSONArray()
                for (text in listOf(count, reverse, main)) {
                    val results = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    assertEquals(1, results.size)
                    val result = results.single()
                    val visible = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertEquals(text, result.layoutInput.text.text)
                    assertEquals(text.length, result.getLineEnd(result.lineCount - 1))
                    for (line in 0 until result.lineCount) {
                        assertFalse("Ellipsized label: $text", result.isLineEllipsized(line))
                        assertTrue("Line clipped at $w/$scale/$layout: $text $visible", result.getLineLeft(line) >= -1 && result.getLineRight(line) <= visible.width + 1 && result.getLineTop(line) >= -1 && result.getLineBottom(line) <= visible.height + 1)
                    }
                    for (offset in text.indices) {
                        val glyph = result.getBoundingBox(offset)
                        assertTrue("Character clipped at $w/$scale/$layout: $text/$offset $glyph $visible", glyph.left >= -1 && glyph.right <= visible.width + 1 && glyph.top >= -1 && glyph.bottom <= visible.height + 1)
                    }
                    textLayouts.put(JSONObject().put("text", text).put("lines", result.lineCount)
                        .put("width_px", result.size.width).put("height_px", result.size.height)
                        .put("visible_width_px", visible.width).put("visible_height_px", visible.height)
                        .put("paragraph_width", result.multiParagraph.width).put("paragraph_height", result.multiParagraph.height)
                        .put("overflow_flag", result.hasVisualOverflow).put("all_characters_fit", true))
                }
                val prior = listOf(checked, reverted, applied, menu)
                actions.take(3).forEach { it.performTouchInput { click() } }
                actions.last().performTouchInput { click() }
                compose.onNodeWithText("Fixture menu").performTouchInput { click() }
                compose.runOnIdle { assertEquals(prior.map { it + 1 }, listOf(checked, reverted, applied, menu)) }
                if (w == 320f && scale == 2f) {
                    val image = compose.onNodeWithTag("selection-under-test").captureToImage().asAndroidBitmap()
                    File(context.cacheDir, "selection-toolbar-${layout.name}.png").outputStream().use {
                        assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                trace.put(JSONObject().put("width_dp", w).put("font_scale", scale).put("direction", layout.name)
                    .put("height_dp", bar.height / density).put("all_actions_touched", true).put("text_not_clipped", true).put("text_layouts", textLayouts))
            }
        }
        File(context.cacheDir, "selection-toolbar.json").writeText(trace.toString(2))
    }
}
