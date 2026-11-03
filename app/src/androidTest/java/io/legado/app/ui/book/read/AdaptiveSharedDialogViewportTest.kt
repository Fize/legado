package io.legado.app.ui.book.read

import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoMultiChoiceDialog
import io.legado.app.ui.compose.component.LegadoSingleChoiceDialog
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Shared production dialogs in an actual freeform task. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class AdaptiveSharedDialogViewportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var activity: AppActivity? = null

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun resumed(): AppActivity? {
        var result: AppActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>()
                .firstOrNull()
        }
        return result
    }

    private fun resize(host: AppActivity, widthDp: Int, heightDp: Int) {
        val density = host.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val result = shell("am task resize ${host.taskId} 50 50 ${50 + widthPx} ${50 + heightPx}")
        check(!result.contains("Error") && !result.contains("Exception")) { result }
        val tolerance = 2 * density + 2
        compose.waitUntil(15_000) {
            kotlin.math.abs(host.window.decorView.width - widthPx) <= tolerance &&
                kotlin.math.abs(host.window.decorView.height - heightPx) <= tolerance
        }
    }

    private fun assertWidth(host: AppActivity, tag: String): Float {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = host.resources.displayMetrics.density
        assertTrue(
            "Shared dialog is wider than 520dp: ${bounds.width / density}dp",
            bounds.width <= 520 * density + 2,
        )
        return bounds.width / density
    }

    private fun assertChoicesScrollable(lastLabel: String) {
        val cancel = context.getString(R.string.cancel)
        compose.onNodeWithText(cancel).assertIsDisplayed()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText(lastLabel))
        compose.onNodeWithText(lastLabel).assertIsDisplayed()
        compose.onNodeWithText(cancel).assertIsDisplayed()
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
    }

    @Test
    fun choiceDialogsStayWidthLimitedAndScrollInShortTasks() {
        assumeTrue(
            "Requires a freeform-capable device",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
        )
        val uri = AppActivity.startIntent(context, AboutRoute).toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace))
        check(!shell("am start --windowingMode 5 $uri").contains("Error"))
        compose.waitUntil(20_000) {
            activity = resumed()
            activity?.isInMultiWindowMode == true
        }
        val host = checkNotNull(activity)
        val entries = (1..36).map { "Adaptive choice ${it.toString().padStart(2, '0')}" }
        val selected = mutableSetOf<String>()
        var singleWidth = 0f
        var multiWidth = 0f

        instrumentation.runOnMainSync {
            host.setContent {
                var kind by remember { mutableIntStateOf(0) }
                MaterialTheme {
                    when (kind) {
                        0 -> LegadoSingleChoiceDialog(
                            title = "Single adaptive choices",
                            entries = entries,
                            selectedIndex = 0,
                            onDismiss = { kind = 1 },
                            onSelect = {},
                        )
                        1 -> LegadoMultiChoiceDialog(
                            title = "Multiple adaptive choices",
                            entries = entries,
                            entryValues = entries,
                            selectedValues = selected,
                            onDismiss = { kind = 2 },
                            onConfirm = { selected.clear(); selected.addAll(it) },
                        )
                    }
                }
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("legadoSingleChoiceDialog").fetchSemanticsNodes().isNotEmpty()
        }
        resize(host, 1200, 800)
        singleWidth = assertWidth(host, "legadoSingleChoiceDialog")

        resize(host, 900, 360)
        assertChoicesScrollable(entries.last())
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("legadoMultiChoiceDialog").fetchSemanticsNodes().isNotEmpty()
        }

        resize(host, 1200, 800)
        multiWidth = assertWidth(host, "legadoMultiChoiceDialog")
        resize(host, 900, 360)
        assertChoicesScrollable(entries.last())

        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "reader-comfort-shared-dialog.png").outputStream().use {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
        File(context.cacheDir, "reader-comfort-shared-dialog.json").writeText(
            JSONObject()
                .put("task_id", host.taskId)
                .put("activity_identity", System.identityHashCode(host))
                .put("wide_task_dp", JSONObject().put("width", 1200).put("height", 800))
                .put("short_task_dp", JSONObject().put("width", 900).put("height", 360))
                .put("single_width_dp", singleWidth)
                .put("multi_width_dp", multiWidth)
                .put("maximum_width_dp", 520)
                .put("single_scrolled_to_last", true)
                .put("multi_scrolled_to_last", true)
                .put("actions_visible_in_short_task", true)
                .toString(2),
        )
    }
}
