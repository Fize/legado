package io.legado.app.ui.association

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.test.displayScreenshotBackend
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Check actual touch targets while the real keyboard remains expanded. */
@RunWith(AndroidJUnit4::class)
class SourceImportImeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val profile = InstrumentationRegistry.getArguments().getString("sourceImeProfile", "normal")!!
        .also { require(it.matches(Regex("[a-z0-9-]+"))) }
    private val samples = JSONArray()
    private val queryEntries = JSONArray()
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
    private fun pointer(rect: Rect) {
        assertTrue("Native target is empty: $rect", rect.width() > 0 && rect.height() > 0)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun awaitInputConnection(host: AppActivity) {
        var stableSince = SystemClock.uptimeMillis()
        var diagnostic = "unavailable"
        try {
            compose.waitUntil(15_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val focused = host.currentFocus
                    val manager = host.getSystemService(InputMethodManager::class.java)
                    val windowFocused = host.window.decorView.hasWindowFocus()
                    val active = focused != null && manager.isActive(focused)
                    val accepting = manager.isAcceptingText
                    diagnostic = "windowFocused=$windowFocused focused=${focused?.javaClass?.name} " +
                        "focusedAttached=${focused?.isAttachedToWindow} active=$active accepting=$accepting"
                    ready = windowFocused && focused != null && active && accepting
                }
                val keyboard = ime(host)
                diagnostic += " ime=$keyboard"
                ready = ready && keyboard.second && keyboard.first > 0
                if (!ready) stableSince = SystemClock.uptimeMillis()
                ready && SystemClock.uptimeMillis() - stableSince >= 400
            }
        } catch (failure: Throwable) {
            throw AssertionError("Input connection did not become ready: $diagnostic", failure)
        }
    }

    private fun screenshot(stage: String): Pair<Int, Int> {
        val bitmap = captureDisplayScreenshot(automation)
        try {
            File(context.cacheDir, "source-import-ime-$profile-$stage.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            return bitmap.width to bitmap.height
        } finally { bitmap.recycle() }
    }

    private fun targets(host: AppActivity, query: String, stage: String): Map<String, Rect> {
        val recreated = stage.startsWith("confirm-recreated")
        if (recreated) {
            // A recreated window may retain Compose focus while the platform's
            // input connection still belongs to the old window. Exercise the
            // same tap a user makes to resume editing in the new window.
            compose.onNodeWithTag("sourceImportSearch").performClick()
        }
        compose.onNodeWithTag("sourceImportSearch").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.runOnMainSync {
            WindowInsetsControllerCompat(host.window, host.window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        if (recreated) {
            fun text() = compose.onNodeWithTag("sourceImportSearch").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)?.text
            shell("input text z")
            try {
                compose.waitUntil(5_000) {
                    val observed = text()
                    observed != null && observed.length == query.length + 1 &&
                        observed.indices.any { observed[it] == 'z' && observed.removeRange(it, it + 1) == query }
                }
            } catch (failure: Throwable) {
                throw AssertionError("Native key did not reach recreated editor", failure)
            }
            val node = compose.onNodeWithTag("sourceImportSearch").fetchSemanticsNode()
            val inserted = checkNotNull(node.config.getOrNull(SemanticsProperties.EditableText)).text
            val selection = checkNotNull(node.config.getOrNull(SemanticsProperties.TextSelectionRange))
            val insertedAt = inserted.indices.single { inserted[it] == 'z' && inserted.removeRange(it, it + 1) == query }
            assertEquals("Cursor must follow the inserted character", insertedAt + 1, selection.start)
            assertEquals("Insertion must leave a collapsed cursor", selection.start, selection.end)
            shell("input keyevent KEYCODE_DEL")
            compose.waitUntil(5_000) { text() == query }
        } else {
            awaitInputConnection(host)
        }
        var previous: String? = null
        var since = SystemClock.uptimeMillis()
        fun logical(tag: String): Rect {
            val r = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            val w = windowBounds(host)
            return Rect(kotlin.math.floor(r.left).toInt() + w.left, kotlin.math.floor(r.top).toInt() + w.top,
                kotlin.math.ceil(r.right).toInt() + w.left, kotlin.math.ceil(r.bottom).toInt() + w.top)
        }
        compose.waitUntil(15_000) {
            val snapshot = (listOf("selectAll", "cancel", "ok").map { logical("sourceImportFooter:$it") } +
                listOf(logical("sourceImportSearch"), ime(host))).toString()
            if (snapshot != previous) { previous = snapshot; since = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - since >= 500
        }
        val window = windowBounds(host)
        val keyboard = ime(host)
        val top = window.bottom - keyboard.first
        val controls = listOf("selectAll", "cancel", "ok").associateWith { logical("sourceImportFooter:$it") }
        val editor = logical("sourceImportSearch")
        val image = screenshot(stage)
        val sample = JSONObject().put("stage", stage).put("api", Build.VERSION.SDK_INT)
            .put("window", window.toShortString()).put("font_scale", host.resources.configuration.fontScale)
            .put("density_dpi", host.resources.configuration.densityDpi)
            .put("ime_visible", keyboard.second).put("ime_bottom_px", keyboard.first).put("keyboard_top_px", top)
            .put("editor_bounds", editor.toShortString()).put("query", query)
            .put("controls", JSONObject().apply { controls.forEach { (name, rect) -> put(name, rect.toShortString()) } })
            .put("screenshot_backend", displayScreenshotBackend()).put("screenshot_width_px", image.first)
            .put("screenshot_height_px", image.second)
        if (recreated) sample.put("native_edit_after_recreate", true)
        samples.put(sample)
        fun safe(r: Rect) = r.width() > 0 && r.height() > 0 && r.top >= window.top && r.bottom <= top &&
            r.left >= window.left && r.right <= window.right
        assertTrue("Keyboard disappeared: $sample", keyboard.second && keyboard.first > 0)
        controls.forEach { (name, r) -> assertTrue("$name is covered by keyboard/window: $sample", safe(r)) }
        assertTrue("Editor overlaps keyboard or actions: $sample", safe(editor) && controls.values.none { Rect.intersects(editor, it) })
        val rectangles = controls.values.toList()
        for (i in rectangles.indices) for (j in 0 until i) assertFalse("Actions overlap: $sample", Rect.intersects(rectangles[i], rectangles[j]))
        compose.onNodeWithTag("sourceImportSearch").assertTextEquals(query)
        val native = controls.mapValues { (_, logical) ->
            val node = nativeNodes { it.isClickable && it.isEnabled }.map { bounds(it) }.distinct()
                .filter { it.contains(logical.centerX(), logical.centerY()) && safe(it) }
                .minByOrNull { it.width().toLong() * it.height() }
            assertNotNull("No exposed native touch target for $logical", node)
            checkNotNull(node)
        }
        assertEquals(1, nativeNodes { it.isEditable && it.text?.toString() == query }.size)
        sample.put("native_controls", JSONObject().apply { native.forEach { (name, r) -> put(name, r.toShortString()) } })
        return native
    }

    @Test fun expandedKeyboardKeepsImportCancelAndConfirmReachableAndScoped() {
        val id = UUID.randomUUID().toString().take(8)
        val query = "needle$id"
        val dao = appDb.bookSourceDao
        val firstOrder = dao.maxOrder + 1
        val candidates = (0..2).map { index -> BookSource("https://import-ime-$id-$index.invalid",
            if (index == 0) query else "hidden-$id-$index", bookSourceGroup = "incoming", bookSourceComment = "input-$index", customOrder = firstOrder + index) }
        val existing = candidates.drop(1).map { it.copy(bookSourceName = "Original-$id", bookSourceGroup = "keep", bookSourceComment = "untouched") }
        val prefs = context.defaultSharedPreferences
        val keys = listOf(PreferKey.autoBackup, PreferKey.importReplaceSource, PreferKey.importRememberGroup,
            PreferKey.importLastGroup, PreferKey.importLastGroupAdd)
        val oldPrefs = keys.associateWith { prefs.all[it] }
        val oldIme = shell("settings get secure show_ime_with_hard_keyboard")
        val oldFlags = automation.serviceInfo.flags
        var status = "running"
        var errorText: String? = null
        var cancelled = false
        var imported = false
        try {
            assertTrue(candidates.all { dao.getBookSource(it.bookSourceUrl) == null })
            dao.insert(*existing.toTypedArray())
            val before = existing.associate { it.bookSourceUrl to GSON.toJson(dao.getBookSource(it.bookSourceUrl)) }
            prefs.edit().putBoolean(PreferKey.autoBackup, false).putBoolean(PreferKey.importReplaceSource, false)
                .putBoolean(PreferKey.importRememberGroup, false).remove(PreferKey.importLastGroup)
                .remove(PreferKey.importLastGroupAdd).commit()
            shell("settings put secure show_ime_with_hard_keyboard 1")
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            repeat(2) { attempt ->
                ActivityScenario.launch(AppActivity::class.java).use { scenario ->
                    try {
                        val route = BookSourceImportRoute(GSON.toJson(candidates))
                        scenario.onActivity { it.navigateTo(route) }
                        fun <T> model(action: ImportBookSourceViewModel.() -> T): T {
                            var result: T? = null
                            scenario.onActivity { host ->
                                val owner = navigationViewModelStores(host, BookshelfRoute).getOrCreateOwner(NavEntry(route) {}.contentKey)
                                result = ViewModelProvider(owner)[ImportBookSourceViewModel::class.java].action()
                            }
                            @Suppress("UNCHECKED_CAST") return result as T
                        }
                        compose.waitUntil(20_000) { model { uiState.value.successCount == 3 && !uiState.value.isUpdating } }
                        model { setSelection(0, true); setSelection(1, false); setSelection(2, false) }
                        var inputHost: AppActivity? = null
                        scenario.onActivity { inputHost = it }
                        compose.onNodeWithTag("sourceImportSearch").performClick()
                        instrumentation.runOnMainSync {
                            val host = checkNotNull(inputHost)
                            WindowInsetsControllerCompat(host.window, host.window.decorView).show(WindowInsetsCompat.Type.ime())
                        }
                        compose.waitUntil(15_000) {
                            val keyboard = ime(checkNotNull(inputHost))
                            keyboard.second && keyboard.first > 0
                        }
                        compose.onNodeWithTag("sourceImportSearch").assertIsFocused()
                        awaitInputConnection(checkNotNull(inputHost))
                        queryEntries.put(JSONObject().put("attempt", attempt)
                            .put("current_window_input_connection_ready", true).put("query", query))
                        shell("input text $query")
                        val stages = if (attempt == 0) listOf("cancel") else listOf("confirm-opened", "confirm-recreated-1", "confirm-recreated-2")
                        stages.forEachIndexed { index, stage ->
                            if (index > 0) scenario.recreate()
                            compose.waitUntil(10_000) { model { searchQuery == query && selectStatus.toList() == listOf(true, false, false) } }
                            var host: AppActivity? = null
                            scenario.onActivity { host = it }
                            var controls = targets(checkNotNull(host), query, stage)
                            if (index == 0) {
                                pointer(checkNotNull(controls["selectAll"]))
                                compose.waitUntil(10_000) { model { selectStatus.toList() == listOf(false, false, false) } }
                                // Reopen real IME if the platform closes it after a footer touch.
                                controls = targets(checkNotNull(host), query, "$stage-unselected")
                                pointer(checkNotNull(controls["selectAll"]))
                                compose.waitUntil(10_000) { model { selectStatus.toList() == listOf(true, false, false) } }
                                controls = targets(checkNotNull(host), query, "$stage-reselected")
                            }
                            samples.getJSONObject(samples.length() - 1).put("selection", JSONArray(model { selectStatus.toList() }))
                            if (attempt == 0 || index == stages.lastIndex) {
                                assertTrue(ime(checkNotNull(host)).second)
                                pointer(checkNotNull(controls[if (attempt == 0) "cancel" else "ok"]))
                            }
                        }
                        if (attempt == 0) {
                            compose.waitUntil(15_000) { compose.onAllNodesWithTag("sourceImportSearch").fetchSemanticsNodes().isEmpty() }
                            assertNull(dao.getBookSource(candidates[0].bookSourceUrl)); cancelled = true
                        } else {
                            compose.waitUntil(20_000) { dao.getBookSource(candidates[0].bookSourceUrl) != null }
                            val saved = checkNotNull(dao.getBookSource(candidates[0].bookSourceUrl))
                            assertEquals(query, saved.bookSourceName); assertEquals("incoming", saved.bookSourceGroup)
                            assertEquals("input-0", saved.bookSourceComment); imported = true
                        }
                        before.forEach { (url, json) -> assertEquals(json, GSON.toJson(dao.getBookSource(url))) }
                    } catch (failure: Throwable) {
                        runCatching { screenshot("failure") }
                        runCatching { File(context.cacheDir, "source-import-ime-$profile-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
                        throw failure
                    }
                }
            }
            status = "passed"
        } catch (failure: Throwable) { status = "failed"; errorText = failure.toString(); throw failure }
        finally {
            candidates.forEach { dao.delete(it.bookSourceUrl) }
            prefs.edit().apply { oldPrefs.forEach { (key, value) -> when (value) {
                null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
            } } }.commit()
            if (oldIme == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else { require(oldIme.matches(Regex("[0-9]+"))); shell("settings put secure show_ime_with_hard_keyboard $oldIme") }
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            File(context.cacheDir, "source-import-ime-$profile.json").writeText(JSONObject()
                .put("status", status).put("error", errorText).put("profile", profile).put("samples", samples)
                .put("native_query_entries", queryEntries)
                .put("cancel_did_not_import", cancelled).put("confirm_imported_selected_only", imported)
                .put("fixtures_removed", candidates.all { dao.getBookSource(it.bookSourceUrl) == null })
                .put("preferences_restored", oldPrefs.all { (key, value) -> prefs.all[key] == value })
                .put("hardware_ime_setting_restored", shell("settings get secure show_ime_with_hard_keyboard") == oldIme).toString(2))
        }
    }
}
