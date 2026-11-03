package io.legado.app.ui.main.explore

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.ExploreRoute
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Exercises the production discovery route in actual resizable task windows. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ExploreWindowResizeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource(
        bookSourceUrl = "https://explore-window.invalid/$id",
        bookSourceName = "Explore window $id",
        exploreUrl = """<js>JSON.stringify([{title:'Window category',url:'https://explore-window.invalid/category'}]);</js>""",
    )
    private val extraSources = (1..20).map { index ->
        source.copy(bookSourceUrl = "https://explore-window.invalid/$id/$index",
            bookSourceName = "Explore window item $index $id", customOrder = index)
    }
    private val preferences = context.defaultSharedPreferences
    private val oldBackup = preferences.all[PreferKey.autoBackup]
    private val trace = JSONArray()
    private var activity: AppActivity? = null

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun resumed(): AppActivity? {
        var result: AppActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>().firstOrNull()
        }
        return result
    }

    @Before fun setup() {
        Assume.assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        preferences.edit().putBoolean(PreferKey.autoBackup, false).commit()
        appDb.bookSourceDao.insert(source, *extraSources.toTypedArray())
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
        runBlocking { source.clearExploreKindsCache() }
        source.removeLoginHeader()
        appDb.bookSourceDao.delete(source, *extraSources.toTypedArray())
        preferences.edit().apply {
            if (oldBackup == null) remove(PreferKey.autoBackup) else putBoolean(PreferKey.autoBackup, oldBackup as Boolean)
        }.commit()
        File(context.cacheDir, "explore-window-resize.json").writeText(trace.toString(2))
    }

    private fun resize(widthDp: Int, heightDp: Int) {
        val host = checkNotNull(activity)
        val density = host.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        val result = shell("am task resize ${host.taskId} 50 50 ${50 + width} ${50 + height}")
        check(!result.contains("Error") && !result.contains("Exception")) { result }
        compose.waitUntil(10_000) {
            var w = 0; var h = 0
            instrumentation.runOnMainSync { w = host.window.decorView.width; h = host.window.decorView.height }
            abs(w - width) <= 2 * density && abs(h - height) <= 2 * density &&
                host.resources.configuration.screenWidthDp == widthDp
        }
        compose.waitForIdle()
        assertSame(host, resumed())
        val row = compose.onNodeWithText(source.bookSourceName).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("explore_content").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("explore_sources").fetchSemanticsNode().boundsInRoot
        trace.put(JSONObject().put("width_dp", widthDp).put("height_dp", heightDp)
            .put("row_left_px", row.left).put("row_right_px", row.right)
            .put("row_width_dp", row.width / density)
            .put("content_left_px", content.left).put("content_right_px", content.right)
            .put("list_left_px", list.left).put("list_right_px", list.right)
            .put("task_id", host.taskId)
            .put("activity_identity", System.identityHashCode(host)))
        assertTrue("Discovery source row exceeds 720dp: $row at density $density", row.width <= 720 * density + 2)
        assertEquals("List should use available width up to 720dp", minOf(content.width, 720 * density), list.width, 2f)
        assertEquals("List should be centered in its available content area", content.center.x, list.center.x, 2f)
        assertTrue("Source row exceeds the content area", row.left >= content.left && row.right <= content.right)
        if (widthDp == 1200 || widthDp == 360 || heightDp == 360) {
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.cacheDir, "explore-window-$widthDp-$heightDp.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun sourceListRemainsUsableAndBoundedAcrossActualWindowChanges() {
        // Top-level destinations are deliberately ignored as initial activity routes.
        val uri = AppActivity.startIntent(context, SearchRoute("", "")).toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace))
        check(!shell("am start --windowingMode 5 $uri").contains("Error"))
        compose.waitUntil(20_000) { activity = resumed(); activity?.isInMultiWindowMode == true }
        compose.onNode(isSelectable() and hasText(context.getString(R.string.discovery))).performClick()
        compose.waitUntil(10_000) { activity?.currentDestination == ExploreRoute }
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithText(source.bookSourceName).assertIsDisplayed() }.isSuccess }
        resize(1200, 800)
        compose.onNodeWithText(source.bookSourceName).performClick()
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithText("Window category").assertIsDisplayed() }.isSuccess }
        for ((w, h) in listOf(900 to 850, 840 to 600, 600 to 600, 360 to 600, 840 to 360, 1200 to 800)) {
            resize(w, h)
            compose.onNodeWithText("Window category").assertIsDisplayed()
        }
        compose.onNodeWithText(source.bookSourceName).performClick()
        compose.onNodeWithText("Window category").assertDoesNotExist()
        compose.onNodeWithTag("explore_sources").performScrollToNode(hasText(extraSources.last().bookSourceName))
        compose.onNodeWithText(extraSources.last().bookSourceName).assertIsDisplayed()
        compose.onNodeWithTag("explore_sources").performScrollToNode(hasText(source.bookSourceName))
        compose.onNodeWithText(source.bookSourceName).assertIsDisplayed().performClick()
        compose.onNodeWithText("Window category").assertIsDisplayed()
    }
}
