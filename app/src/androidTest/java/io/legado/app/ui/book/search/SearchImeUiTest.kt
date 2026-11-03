package io.legado.app.ui.book.search

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses the actual system IME, real system font scale and physical screen coordinates. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class SearchImeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val scale = InstrumentationRegistry.getArguments().getString("compatFontScale", "1.0")!!
        .also { require(it in listOf("1.0", "1.3", "2.0")) }
    private val id = UUID.randomUUID().toString()
    private val group = "ime-fixture-$id"
    private val preferences = context.defaultSharedPreferences
    private val oldFilter = preferences.all[PreferKey.searchResultFilter]
    private val oldFlags = automation.serviceInfo.flags
    private val oldFont = setting("system", "font_scale")
    private val oldEffectiveFont = oldFont.toFloatOrNull() ?: 1f
    private val oldAutoRotation = setting("system", "accelerometer_rotation")
    private val oldRotation = setting("system", "user_rotation")
    private val oldHardwareIme = setting("secure", "show_ime_with_hard_keyboard")
    private var scenario: ActivityScenario<AppActivity>? = null
    private var activity: AppActivity? = null
    private val trace = JSONArray()
    private var editorGeometry: JSONObject? = null
    private var queryGeometry: JSONObject? = null
    private var outsideDismissed = false
    private var orientation = "not-opened"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun setting(namespace: String, key: String) = shell("settings get $namespace $key").trim()
    private fun restore(namespace: String, key: String, value: String) {
        if (value == "null") shell("settings delete $namespace $key") else {
            require(value.matches(Regex("[0-9.]+")))
            shell("settings put $namespace $key $value")
        }
    }

    @Before fun setup() {
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("settings put secure show_ime_with_hard_keyboard 1")
        shell("settings put system font_scale $scale")
        compose.waitUntil(15_000) { kotlin.math.abs(context.resources.configuration.fontScale - scale.toFloat()) < .01f }
    }
    @After fun cleanup() {
        val errors = arrayListOf<Throwable>()
        fun finish(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(errors::add) }
        finish {
            automation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "search-ime-$scale-$orientation-last.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
        finish { scenario?.close() }
        finish {
            assertTrue(preferences.edit().apply { when (val value = oldFilter) {
                null -> remove(PreferKey.searchResultFilter)
                is String -> putString(PreferKey.searchResultFilter, value)
                is Boolean -> putBoolean(PreferKey.searchResultFilter, value)
                is Int -> putInt(PreferKey.searchResultFilter, value)
                is Long -> putLong(PreferKey.searchResultFilter, value)
                is Float -> putFloat(PreferKey.searchResultFilter, value)
                is Set<*> -> putStringSet(PreferKey.searchResultFilter, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported saved preference type")
            } }.commit())
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM search_screen_drafts WHERE scope = ?", arrayOf(group))
        }
        // SettingsProvider may persist an unset default as 1.0 asynchronously. Restore
        // the effective value explicitly so the next test cannot race that write.
        finish {
            restore("system", "font_scale", if (oldFont == "null") oldEffectiveFont.toString() else oldFont)
            compose.waitUntil(15_000) { kotlin.math.abs(context.resources.configuration.fontScale - oldEffectiveFont) < .01f }
        }
        finish { restore("secure", "show_ime_with_hard_keyboard", oldHardwareIme) }
        finish { automation.setRotation(if (oldAutoRotation == "1") android.app.UiAutomation.ROTATION_UNFREEZE else oldRotation.toIntOrNull() ?: 0) }
        finish { restore("system", "accelerometer_rotation", oldAutoRotation) }
        finish { restore("system", "user_rotation", oldRotation) }
        finish { automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags } }
        finish {
            assertEquals(oldEffectiveFont, setting("system", "font_scale").toFloat(), .01f)
            assertEquals(oldHardwareIme, setting("secure", "show_ime_with_hard_keyboard"))
            assertEquals(oldAutoRotation, setting("system", "accelerometer_rotation"))
            assertEquals(oldRotation, setting("system", "user_rotation"))
            assertEquals(oldFilter, preferences.all[PreferKey.searchResultFilter])
            trace.put(JSONObject().put("settings_restored", true).put("font_setting_before", oldFont)
                .put("font_setting_after", setting("system", "font_scale")).put("effective_font_before", oldEffectiveFont)
                .put("editor_diagnostic", editorGeometry))
        }
        finish { File(context.cacheDir, "search-ime-$scale-$orientation.json").writeText(trace.toString(2)) }
        if (errors.isNotEmpty()) throw AssertionError("IME test cleanup failed", errors.first())
    }

    private fun ime(): Pair<Int, Boolean> {
        var result = 0 to false
        instrumentation.runOnMainSync {
            ViewCompat.getRootWindowInsets(checkNotNull(activity).window.decorView)?.let {
                result = it.getInsets(WindowInsetsCompat.Type.ime()).bottom to it.isVisible(WindowInsetsCompat.Type.ime())
            }
        }
        return result
    }
    private fun waitForIme() = compose.waitUntil(15_000) { val (bottom, visible) = ime(); bottom > 0 && visible }
    private fun nativeNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.isEnabled && predicate(node)) return node
            repeat(node.childCount) { index -> find(node.getChild(index))?.let { return it } }
            return null
        }
        return automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { find(it.root) }
    }
    private fun nativeBounds(text: String): Rect? {
        val node = nativeNode { it.text?.toString() == text } ?: return null
        var clickable = node
        while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
        return Rect().also(clickable::getBoundsInScreen)
    }
    private fun cursor(value: String, tag: String): Pair<androidx.compose.ui.geometry.Rect, Float> {
        val results = arrayListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        assertEquals(value, result.layoutInput.text.text)
        val line = result.getLineForOffset(value.lastIndex)
        return result.getCursorRect(value.length) to (result.getLineBottom(line) - result.getLineTop(line))
    }
    private fun assertInputIsPainted(value: String, replacement: String, tag: String = "search_filter", prefix: String = "") {
        // Character extra-data coordinates on decorated fields omit their inner padding
        // on some platform versions. Compare actual input ink inside stable screen bounds.
        compose.onNodeWithTag(tag).performTextInputSelection(TextRange(value.length))
        automation.waitForIdle(100, 5_000)
        compose.waitForIdle()
        var field: AccessibilityNodeInfo? = null
        compose.waitUntil(10_000) { field = nativeNode { it.isEditable && it.text?.contains(value) == true }; field != null }
        val node = checkNotNull(field)
        val visible = Rect().also(node::getBoundsInScreen)
        val (beforeCursor, lineHeight) = cursor(value, tag)
        val before = checkNotNull(automation.takeScreenshot())
        var after: android.graphics.Bitmap? = null
        try {
            compose.onNodeWithTag(tag).performTextInputSelection(TextRange(value.length - 4, value.length))
            compose.onNodeWithTag(tag).performTextInput("1111")
            compose.onNodeWithTag(tag).assertTextContains(replacement)
            automation.waitForIdle(100, 5_000); compose.waitForIdle()
            val (afterCursor, _) = cursor(replacement, tag)
            assertEquals("Digit replacement moved the cursor", beforeCursor.left, afterCursor.left, .5f)
            assertEquals("Digit replacement changed lines", beforeCursor.top, afterCursor.top, .5f)
            val current = checkNotNull(nativeNode { it.isEditable && it.text?.contains(replacement) == true })
            val afterBounds = Rect().also(current::getBoundsInScreen)
            assertEquals("Editor width changed between screenshots", visible.width(), afterBounds.width())
            assertEquals("Editor height changed between screenshots", visible.height(), afterBounds.height())
            after = checkNotNull(automation.takeScreenshot())
            val sample = Rect(visible).apply { inset(2, 2) }
            assertTrue(sample.left >= 0 && sample.top >= 0 && sample.right <= before.width && sample.bottom <= before.height && !sample.isEmpty)
            var changed = 0; var minimumX = sample.right; var maximumX = sample.left; var minimumY = sample.bottom; var maximumY = sample.top
            var contrast = 0
            for (y in sample.top until sample.bottom) for (x in sample.left until sample.right) {
                val first = before.getPixel(x, y)
                val second = after.getPixel(x - visible.left + afterBounds.left, y - visible.top + afterBounds.top)
                val delta = (0..2).maxOf { kotlin.math.abs(((first shr (it * 8)) and 255) - ((second shr (it * 8)) and 255)) }
                contrast = maxOf(contrast, delta)
                if (delta >= 32) {
                    changed++; minimumX = minOf(minimumX, x); maximumX = maxOf(maximumX, x)
                    minimumY = minOf(minimumY, y); maximumY = maxOf(maximumY, y)
                }
            }
            val width = maximumX - minimumX + 1; val height = maximumY - minimumY + 1
            editorGeometry = JSONObject().put("before_bounds", visible.toShortString()).put("bounds", afterBounds.toShortString()).put("max_channel_difference", contrast)
                .put("changed_pixels", changed).put("changed_width_px", width).put("changed_height_px", height)
                .put("minimum_ink_height_px", lineHeight * .45f).put("cursor_stationary", true)
            for ((suffix, bitmap) in listOf("edit-before" to before, "edit-after" to checkNotNull(after))) {
                File(context.cacheDir, "search-ime-$scale-$orientation-$prefix$suffix.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            assertTrue("Input ink did not visibly change: $editorGeometry", changed > 32 && width >= activity!!.resources.displayMetrics.density * 8 && height >= lineHeight * .45f)
        } finally { before.recycle(); after?.recycle() }
    }
    private fun assertConfirmAboveIme() {
        val host = checkNotNull(activity)
        val label = host.getString(R.string.ok)
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
        var bounds: Rect? = null
        compose.waitUntil(10_000) { bounds = nativeBounds(label); bounds != null }
        val rectangle = checkNotNull(bounds)
        val (bottom, visible) = ime()
        assertTrue("System keyboard disappeared before the check", visible && bottom > 0)
        val metrics = host.windowManager.currentWindowMetrics.bounds
        val keyboardTop = metrics.bottom - bottom
        trace.put(JSONObject().put("font_scale", host.resources.configuration.fontScale).put("orientation", orientation)
            .put("window", metrics.toShortString()).put("ime_bottom_px", bottom).put("keyboard_top_px", keyboardTop)
            .put("confirm_bounds", rectangle.toShortString()).put("confirm_bottom_px", rectangle.bottom))
        trace.getJSONObject(trace.length() - 1).put("editor", checkNotNull(editorGeometry))
            .put("query_editor", checkNotNull(queryGeometry)).put("outside_dismissed_without_saving", outsideDismissed)
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(context.cacheDir, "search-ime-$scale-$orientation.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        assertTrue("Confirmation is covered by the real IME: $rectangle; keyboard top=$keyboardTop", rectangle.bottom <= keyboardTop)
    }

    private fun exercise(landscape: Boolean) {
        orientation = if (landscape) "landscape" else "portrait"
        assertTrue(automation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0))
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, SearchRoute("", group)))
        // Font/configuration delivery can update API33's rotation after the first
        // freeze request. Apply the desired rotation to the foreground window too.
        assertTrue(automation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0))
        var lastRotationRequest = SystemClock.elapsedRealtime()
        val initialWindowFailure = runCatching {
            compose.waitUntil(45_000) {
                scenario!!.onActivity { activity = it }
                val host = activity ?: return@waitUntil false
                val bounds = host.windowManager.currentWindowMetrics.bounds
                if ((bounds.width() > bounds.height()) != landscape && SystemClock.elapsedRealtime() - lastRotationRequest > 500) {
                    automation.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
                    lastRotationRequest = SystemClock.elapsedRealtime()
                }
                (bounds.width() > bounds.height()) == landscape && kotlin.math.abs(host.resources.configuration.fontScale - scale.toFloat()) < .01f &&
                    runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess
            }
        }.exceptionOrNull()
        if (initialWindowFailure != null) {
            var details = "no activity"
            scenario?.onActivity { host ->
                val bounds = host.windowManager.currentWindowMetrics.bounds
                val queryCount = compose.onAllNodesWithTag("search_query")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size
                val queryEnabled = queryCount > 0 &&
                    runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess
                details = "window=${bounds.width()}x${bounds.height()}, landscape=$landscape, " +
                    "fontScale=${host.resources.configuration.fontScale}, queryNodes=$queryCount, " +
                    "queryEnabled=$queryEnabled"
            }
            val bitmap = automation.takeScreenshot()
            if (bitmap != null) {
                File(context.cacheDir, "search-ime-$scale-$orientation-initial-window-failure.png")
                    .outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            throw AssertionError("Initial search window did not become usable: $details",
                initialWindowFailure)
        }
        val entered = "Draft-$id-8888"
        val draft = entered.dropLast(4) + "1111"
        compose.onNodeWithTag("search_query").performClick()
        instrumentation.runOnMainSync {
            WindowInsetsControllerCompat(activity!!.window, activity!!.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
        waitForIme()
        compose.onNodeWithTag("search_query").performTextInput(entered)
        assertInputIsPainted(entered, draft, tag = "search_query", prefix = "query-")
        queryGeometry = editorGeometry
        editorGeometry = null
        compose.onNodeWithTag("search_query").assertTextContains(draft)
        instrumentation.runOnMainSync { WindowInsetsControllerCompat(activity!!.window, activity!!.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(10_000) { !ime().second }
        compose.onNodeWithTag("search_menu").performClick()
        compose.onNodeWithText(activity!!.getString(R.string.search_result_filter)).performClick()
        compose.onNodeWithTag("search_filter").assertIsDisplayed()
        val bounds = activity!!.windowManager.currentWindowMetrics.bounds
        val x = bounds.right - activity!!.resources.displayMetrics.density * 2
        val y = bounds.exactCenterY()
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search_filter").fetchSemanticsNodes().isEmpty() }
        assertEquals(oldFilter, preferences.all[PreferKey.searchResultFilter])
        outsideDismissed = true
        compose.onNodeWithTag("search_menu").performClick()
        compose.onNodeWithText(activity!!.getString(R.string.search_result_filter)).performClick()
        val filter = "Filter-$id-8888"
        compose.onNodeWithTag("search_filter").performClick().performTextReplacement(filter)
        waitForIme()
        compose.onNodeWithTag("search_filter").assertIsDisplayed().assertTextContains(filter)
        val submitted = filter.dropLast(4) + "1111"
        assertInputIsPainted(filter, submitted)
        assertConfirmAboveIme()
        compose.onNodeWithText(activity!!.getString(R.string.ok)).performClick()
        compose.waitUntil(10_000) { preferences.getString(PreferKey.searchResultFilter, "") == submitted }
        compose.onNodeWithTag("search_filter").assertDoesNotExist()
        compose.onNodeWithTag("search_query").assertTextContains(draft)
    }
    @Test fun portraitInputAndFilterConfirmationRemainUsableWithTheRealIme() = exercise(false)
    @Test fun landscapeInputAndFilterConfirmationRemainUsableWithTheRealIme() = exercise(true)
}
