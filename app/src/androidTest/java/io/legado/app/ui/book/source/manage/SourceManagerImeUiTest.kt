package io.legado.app.ui.book.source.manage

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.test.displayScreenshotBackend
import io.legado.app.utils.GSON
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real IME bounds and pointer actions; semantic clicks must not bypass keyboard occlusion. */
@RunWith(AndroidJUnit4::class)
class SourceManagerImeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val profile = InstrumentationRegistry.getArguments().getString("sourceImeProfile", "normal")!!
        .also { require(it.matches(Regex("[a-z0-9-]+"))) }
    private val samples = JSONArray()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    private fun windowBounds(host: Activity): Rect = if (Build.VERSION.SDK_INT >= 30) {
        host.windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { host.windowManager.defaultDisplay.getRealMetrics(it) }
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
    private fun ime(host: Activity): Pair<Int, Boolean> {
        var result = 0 to false
        instrumentation.runOnMainSync {
            ViewCompat.getRootWindowInsets(host.window.decorView)?.let {
                result = it.getInsets(WindowInsetsCompat.Type.ime()).bottom to
                    it.isVisible(WindowInsetsCompat.Type.ime())
            }
        }
        return result
    }
    private fun nativeNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = arrayListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser && predicate(node)) found += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.forEach { visit(it.root) }
        return found
    }
    private fun bounds(node: AccessibilityNodeInfo, clickable: Boolean = true): Rect {
        var current = node
        if (clickable) while (!current.isClickable && current.parent != null) current = current.parent
        if (!current.refresh()) return Rect()
        return Rect().also(current::getBoundsInScreen)
    }
    private fun nativeLabel(label: String): Rect? {
        val layers = automation.windows.associate { it.id to it.layer }
        val matches = nativeNodes { it.text?.toString() == label && it.isEnabled }
        val highest = matches.maxOfOrNull { layers[it.windowId] ?: -1 } ?: return null
        return matches.filter { (layers[it.windowId] ?: -1) == highest }.map { bounds(it) }.distinct().singleOrNull()
    }
    private fun stableLabel(host: BookSourceActivity, label: String): Rect {
        var result: Rect? = null
        var previous: Pair<Rect?, Pair<Int, Boolean>>? = null
        var since = SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            result = nativeLabel(label)
            val keyboard = ime(host)
            val current = result?.let(::Rect) to keyboard
            if (current != previous) { previous = current; since = SystemClock.uptimeMillis() }
            result != null && checkNotNull(result).width() > 0 && SystemClock.uptimeMillis() - since >= 350 &&
                checkNotNull(result).bottom <= windowBounds(host).bottom - if (keyboard.second) keyboard.first else 0
        }
        return checkNotNull(result)
    }
    private fun footerMenu(): Rect? = nativeNodes {
        it.contentDescription?.toString() == context.getString(R.string.more_menu) && it.isEnabled
    }.map { bounds(it) }.maxByOrNull { it.bottom }
    private fun awaitPopupStable(host: BookSourceActivity) {
        var previous: String? = null
        var since = SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val windows = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedBy { it.layer }.map { window -> Rect().also(window::getBoundsInScreen).toShortString() }
            val snapshot = (windows to ime(host)).toString()
            if (snapshot != previous) { previous = snapshot; since = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - since >= 500
        }
    }
    private fun pointer(rect: Rect) {
        assertTrue("Native target is empty: $rect", rect.width() > 0 && rect.height() > 0)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun screenshot(name: String): Pair<Int, Int> {
        val bitmap = captureDisplayScreenshot(automation)
        try {
            File(context.cacheDir, "source-manager-ime-$profile-$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            return bitmap.width to bitmap.height
        } finally { bitmap.recycle() }
    }
    private fun awaitFooterIme(host: BookSourceActivity, query: String, stage: String, record: Boolean = true): Rect {
        compose.onNodeWithTag("legadoSearchField").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.runOnMainSync {
            WindowInsetsControllerCompat(host.window, host.window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(15_000) { val (bottom, visible) = ime(host); bottom > 0 && visible }
        compose.waitForIdle()
        automation.waitForIdle(100, 5_000)
        // Native IME visibility can arrive before Compose has placed every child.
        // Wait for both the input and footer geometry to settle before measuring.
        var previousLayout: String? = null
        var stableSince = SystemClock.uptimeMillis()
        compose.waitUntil(15_000) {
            val snapshot = listOf(ime(host),
                compose.onNodeWithTag("selectionOverflow").fetchSemanticsNode().boundsInWindow,
                compose.onNodeWithTag("legadoSearchField").fetchSemanticsNode().boundsInWindow).toString()
            if (snapshot != previousLayout) { previousLayout = snapshot; stableSince = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - stableSince >= 500
        }
        val (imeBottom, visible) = ime(host)
        assertTrue("Keyboard must still be expanded", visible && imeBottom > 0)
        val window = windowBounds(host)
        val keyboardTop = window.bottom - imeBottom
        fun screenBounds(node: SemanticsNodeInteraction): Rect {
            val rect = node.fetchSemanticsNode().boundsInWindow
            return Rect(kotlin.math.floor(rect.left).toInt() + window.left,
                kotlin.math.floor(rect.top).toInt() + window.top,
                kotlin.math.ceil(rect.right).toInt() + window.left,
                kotlin.math.ceil(rect.bottom).toInt() + window.top)
        }
        val logicalMenu = screenBounds(compose.onNodeWithTag("selectionOverflow"))
        // Occluded controls may be absent from native accessibility. Preserve their
        // drawn layout bounds for the failure, then require native exposure before input.
        val compact = compose.onAllNodesWithText(context.getString(R.string.delete)).fetchSemanticsNodes().isEmpty()
        val controls = if (compact) listOf("menu" to logicalMenu) else listOf("menu" to logicalMenu,
            "delete" to screenBounds(compose.onNodeWithText(context.getString(R.string.delete))),
            "inverse" to screenBounds(compose.onNodeWithText(context.getString(R.string.revert_selection))))
        val editor = nativeNodes { it.isEditable && it.text?.toString() == query }.singleOrNull()
        val editorBounds = editor?.let { bounds(it, clickable = false) }
            ?: screenBounds(compose.onNodeWithTag("legadoSearchField"))
        val sample = JSONObject().put("stage", stage).put("api", Build.VERSION.SDK_INT)
            .put("screenshot_backend", displayScreenshotBackend())
            .put("window", window.toShortString()).put("keyboard_top_px", keyboardTop)
            .put("ime_bottom_px", imeBottom).put("ime_visible", visible)
            .put("font_scale", host.resources.configuration.fontScale)
            .put("density_dpi", host.resources.configuration.densityDpi)
            .put("query", query).put("editor_bounds", editorBounds.toShortString())
            .put("compact_actions", compact)
            .put("controls", JSONObject().apply { controls.forEach { (name, rect) -> put(name, rect.toShortString()) } })
        if (record) {
            samples.put(sample)
            val (width, height) = screenshot(stage)
            sample.put("screenshot_width_px", width).put("screenshot_height_px", height)
        }
        for ((name, rect) in controls) {
            assertTrue("$name is covered by the real IME: $sample", rect.width() > 0 && rect.height() > 0 &&
                rect.top >= window.top && rect.bottom <= keyboardTop && rect.left >= window.left && rect.right <= window.right)
        }
        assertTrue("Search editor overlaps actions or keyboard: $sample", editorBounds.height() > 0 &&
            editorBounds.top >= window.top && editorBounds.bottom <= keyboardTop &&
            editorBounds.left >= window.left && editorBounds.right <= window.right &&
            controls.none { Rect.intersects(editorBounds, it.second) })
        compose.onNodeWithTag("legadoSearchField").assertTextEquals(query)
        var menu: Rect? = null
        compose.waitUntil(10_000) {
            menu = nativeNodes {
                it.contentDescription?.toString() == context.getString(R.string.more_menu) && it.isEnabled
            }.map { bounds(it) }.distinct().singleOrNull { it.contains(logicalMenu.centerX(), logicalMenu.centerY()) }
            menu != null && (compact || nativeLabel(context.getString(R.string.delete)) != null &&
                nativeLabel(context.getString(R.string.revert_selection)) != null) &&
                nativeNodes { it.isEditable && it.text?.toString() == query }.size == 1
        }
        return checkNotNull(menu)
    }

    @Test fun expandedKeyboardKeepsSourceActionsReachableAndScopedAfterRecreation() {
        val id = UUID.randomUUID().toString().take(8)
        val query = "ime$id"
        val dao = appDb.bookSourceDao
        val order = dao.maxOrder + 1
        val selected = BookSource("https://selected-$id.invalid", "$query-A", bookSourceGroup = "keep", customOrder = order)
        val untouched = BookSource("https://untouched-$id.invalid", "$query-B", bookSourceGroup = "keep", bookSourceComment = "untouched-$id", customOrder = order + 1)
        val other = BookSource("https://other-$id.invalid", "$query-C", bookSourceGroup = "keep", customOrder = order + 2)
        val oldHardwareIme = shell("settings get secure show_ime_with_hard_keyboard")
        val oldFlags = automation.serviceInfo.flags
        var status = "running"
        var failure: String? = null
        dao.insert(selected, untouched, other)
        val untouchedBefore = GSON.toJson(dao.getBookSource(untouched.bookSourceUrl))
        val otherBefore = GSON.toJson(dao.getBookSource(other.bookSourceUrl))
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            ActivityScenario.launch(BookSourceActivity::class.java).use { scenario ->
                try {
                    compose.waitUntil(15_000) { compose.onAllNodes(hasText(selected.bookSourceName, substring = true)).fetchSemanticsNodes().isNotEmpty() }
                    fun checkbox(name: String) = compose.onNode(
                        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and hasAnySibling(hasText(name, substring = true)), useUnmergedTree = true)
                    checkbox(selected.bookSourceName).performClick()
                    compose.onNodeWithTag("bookSourceSearch").performClick()
                    compose.onNodeWithTag("legadoSearchField").performTextReplacement(query)
                    var expectedGroup = "keep"
                    repeat(3) { index ->
                        if (index > 0) scenario.recreate()
                        compose.waitUntil(10_000) {
                            compose.onAllNodes(hasText(context.getString(R.string.select_all_count, 1, 3)) or
                                hasText(context.getString(R.string.select_count, 1, 3)))
                                .fetchSemanticsNodes().size == 1
                        }
                        compose.onNodeWithTag("legadoSearchField").assertTextEquals(query)
                        // Rows can be outside a short viewport. The exact 1/3 count and
                        // subsequent selected-only database commit prove restored scope.
                        compose.onNode(hasText(context.getString(R.string.select_all_count, 1, 3)) or
                            hasText(context.getString(R.string.select_count, 1, 3))).assertIsDisplayed()
                        var host: BookSourceActivity? = null
                        scenario.onActivity { host = it }
                        val stage = if (index == 0) "opened" else "recreated-$index"
                        var menu = awaitFooterIme(checkNotNull(host), query, stage)
                        // The IME is still visible immediately before a physical pointer click.
                        assertTrue(ime(checkNotNull(host)).second)
                        pointer(menu)
                        if (samples.getJSONObject(index).getBoolean("compact_actions")) {
                            repeat(2) { inversion ->
                                awaitPopupStable(checkNotNull(host))
                                compose.onNodeWithTag("selectionCompactInverse").performScrollTo()
                                pointer(stableLabel(checkNotNull(host), context.getString(R.string.revert_selection)))
                                val count = if (inversion == 0) 2 else 1
                                compose.waitUntil(10_000) {
                                    compose.onAllNodes(hasText(context.getString(R.string.select_all_count, count, 3)) or
                                        hasText(context.getString(R.string.select_count, count, 3))).fetchSemanticsNodes().size == 1
                                }
                                menu = awaitFooterIme(checkNotNull(host), query, "$stage-inverse-$inversion", record = false)
                                assertTrue(ime(checkNotNull(host)).second)
                                pointer(menu)
                            }
                            samples.getJSONObject(index).put("compact_inverse_round_trip_verified", true)
                            awaitPopupStable(checkNotNull(host))
                            compose.onNodeWithTag("selectionCompactMainAction").performScrollTo()
                            pointer(stableLabel(checkNotNull(host), context.getString(R.string.delete)))
                            var cancel: Rect? = null
                            compose.waitUntil(10_000) {
                                cancel = nativeNodes { it.viewIdResourceName == "android:id/button2" && it.isEnabled }
                                    .map { bounds(it) }.singleOrNull()
                                cancel != null
                            }
                            pointer(checkNotNull(cancel))
                            compose.waitForIdle()
                            assertEquals(expectedGroup, dao.getBookSource(selected.bookSourceUrl)?.bookSourceGroup)
                            assertEquals(untouchedBefore, GSON.toJson(dao.getBookSource(untouched.bookSourceUrl)))
                            assertEquals(otherBefore, GSON.toJson(dao.getBookSource(other.bookSourceUrl)))
                            samples.getJSONObject(index).put("compact_delete_cancel_verified", true)
                            menu = awaitFooterIme(checkNotNull(host), query, "$stage-delete-cancel", record = false)
                            assertTrue(ime(checkNotNull(host)).second)
                            pointer(menu)
                        }
                        awaitPopupStable(checkNotNull(host))
                        compose.onNode(hasText(context.getString(R.string.add_group)) and hasAnyAncestor(isPopup())).performScrollTo()
                        var add: Rect? = null
                        var previousBounds: Rect? = null
                        var previousIme: Pair<Int, Boolean>? = null
                        var stableSince = SystemClock.uptimeMillis()
                        compose.waitUntil(10_000) {
                            add = nativeLabel(context.getString(R.string.add_group))
                            val current = ime(checkNotNull(host))
                            if (add != previousBounds || current != previousIme) {
                                previousBounds = add?.let(::Rect)
                                previousIme = current
                                stableSince = SystemClock.uptimeMillis()
                            }
                            add != null && checkNotNull(add).width() > 0 &&
                                SystemClock.uptimeMillis() - stableSince >= 350 &&
                                (!current.second || checkNotNull(add).bottom <=
                                    windowBounds(checkNotNull(host)).bottom - current.first)
                        }
                        val currentIme = ime(checkNotNull(host))
                        if (currentIme.second) assertTrue("Group action is covered by IME", checkNotNull(add).bottom <= windowBounds(checkNotNull(host)).bottom - currentIme.first)
                        samples.getJSONObject(index).put("group_menu_bounds", checkNotNull(add).toShortString())
                            .put("group_menu_ime_visible", currentIme.second)
                            .put("group_menu_keyboard_top_px", windowBounds(checkNotNull(host)).bottom - if (currentIme.second) currentIme.first else 0)
                        pointer(checkNotNull(add))
                        compose.waitUntil(10_000) {
                            compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
                                .fetchSemanticsNodes().size == 1
                        }
                        val editor = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
                        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")))
                        val group = "added-$id-$index"
                        editor.performTextReplacement(group)
                        compose.onNode(hasText(context.getString(R.string.ok)) and hasClickAction()).performClick()
                        expectedGroup += ",$group"
                        compose.waitUntil(15_000) { dao.getBookSource(selected.bookSourceUrl)?.bookSourceGroup == expectedGroup }
                        assertEquals(untouchedBefore, GSON.toJson(dao.getBookSource(untouched.bookSourceUrl)))
                        assertEquals(otherBefore, GSON.toJson(dao.getBookSource(other.bookSourceUrl)))
                        compose.onNodeWithTag("legadoSearchField").assertTextEquals(query)
                        samples.getJSONObject(index).put("native_menu_and_group_pointer_used", true)
                            .put("selected_group", expectedGroup).put("untouched_source_unchanged", true)
                    }
                    status = "passed"
                } catch (error: Throwable) {
                    runCatching { screenshot("failure") }
                    runCatching { File(context.cacheDir, "source-manager-ime-$profile-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
                    throw error
                }
            }
        } catch (error: Throwable) { status = "failed"; failure = error.toString(); throw error }
        finally {
            dao.delete(selected, untouched, other)
            if (oldHardwareIme == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else { require(oldHardwareIme.matches(Regex("[0-9]+"))); shell("settings put secure show_ime_with_hard_keyboard $oldHardwareIme") }
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            File(context.cacheDir, "source-manager-ime-$profile.json").writeText(JSONObject()
                .put("status", status).put("error", failure).put("profile", profile).put("samples", samples)
                .put("hardware_ime_setting_restored", shell("settings get secure show_ime_with_hard_keyboard") == oldHardwareIme)
                .put("fixtures_removed", dao.getBookSource(selected.bookSourceUrl) == null && dao.getBookSource(untouched.bookSourceUrl) == null && dao.getBookSource(other.bookSourceUrl) == null)
                .toString(2))
        }
    }
}
