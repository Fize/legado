package io.legado.app.ui.config

import android.os.ParcelFileDescriptor
import android.graphics.Rect
import android.graphics.Point
import android.view.WindowManager
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.getOrNull
import java.security.MessageDigest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogWindowProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.test.captureCleanupDisplayScreenshot
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.test.displayViewport
import io.legado.app.ui.test.displayScreenshotBackend
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.constant.PreferKey
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import io.legado.app.utils.defaultSharedPreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises production settings and both Compose and AndroidView editors. */
@RunWith(AndroidJUnit4::class)
class SettingsInputDialogUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = listOf(PreferKey.userAgent, PreferKey.customHosts, PreferKey.language, PreferKey.autoBackup, PreferKey.processText)
        .associateWith { preferences.all[it] }
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val trace = JSONArray()
    private val textChecks = JSONArray()
    private val touchTrace = JSONArray()
    private val rotation = InstrumentationRegistry.getArguments().getString("compatSettingsRotation", "0")!!.toInt().also { require(it in 0..1) }
    private var caseName = "not-opened"
    private var editorStep = "not-started"
    private var scenario: ActivityScenario<AppActivity>? = null
    private var activityRoot: View? = null
    private var currentEditor: EditText? = null
    private var currentDialogRoot: View? = null
    private fun label(id: Int) = context.getString(id)

    @Suppress("DEPRECATION")
    private fun rotationFor(landscape: Boolean): Int {
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point().also(display::getRealSize)
        val naturalLandscape = (size.x > size.y) xor (display.rotation % 2 != 0)
        return if (landscape == naturalLandscape) android.app.UiAutomation.ROTATION_FREEZE_0
        else android.app.UiAutomation.ROTATION_FREEZE_90
    }

    @Before fun open() {
        preferences.edit().putString(PreferKey.language, "en").putBoolean(PreferKey.autoBackup, false).commit()
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, SettingsRoute(SettingsSection.Other)))
        automation.setRotation(rotationFor(rotation == 1))
        compose.waitUntil(15_000) {
            val display = Point().also(context.getSystemService(WindowManager::class.java).defaultDisplay::getRealSize)
            var oriented = false
            scenario!!.onActivity {
                activityRoot = it.window.decorView
                oriented = (it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp) == (rotation == 1)
            }
            oriented && (display.x > display.y) == (rotation == 1) &&
                compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun cleanup() = runCleanupSteps(
        {
            captureCleanupDisplayScreenshot(automation).let { image ->
                try { File(context.cacheDir, "settings-input-$caseName-last.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
            }
        },
        { scenario?.close() },
        { automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) },
        {
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                else -> error("Unexpected setting type: $key")
            } } }.commit()
            saved.forEach { (key, value) -> assertEquals("Setting not restored: $key", value, preferences.all[key]) }
            trace.put(JSONObject().put("settings_restored", true))
            File(context.cacheDir, "settings-input-$caseName.json").writeText(trace.toString(2))
            File(context.cacheDir, "settings-input-$caseName-text-checks.json").writeText(textChecks.toString(2))
            File(context.cacheDir, "settings-input-$caseName-touches.json").writeText(touchTrace.toString(2))
        },
    )

    private fun action(id: Int) = compose.onNode(hasText(label(id)) and hasClickAction())
    /**
     * Compose's click waits for global idle. The system IME can keep that signal
     * pending after recreation, so assert the same Compose semantics and then
     * dispatch the click through the platform accessibility tree.
     */
    private fun accessibilityNode(
        text: String? = null,
        editable: Boolean? = null,
    ): AccessibilityNodeInfo? {
        var remaining = 1_000
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || remaining-- <= 0) return null
            if (node.isVisibleToUser && (text == null || node.text?.toString() == text)
                && (editable == null || node.isEditable == editable)) return node
            repeat(node.childCount) {
                find(node.getChild(it))?.let { return it }
            }
            return null
        }
        return find(automation.rootInActiveWindow)
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshNativeDialogViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val windowManagerClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = windowManagerClass.getMethod("getInstance").invoke(null)
            val field = windowManagerClass.getDeclaredField("mViews").apply { isAccessible = true }
            val roots = (field.get(instance) as Collection<View>).filter(View::isAttachedToWindow)

            fun findEditor(view: View): EditText? {
                if (view is EditText && view.isShown) return view
                if (view is ViewGroup) {
                    repeat(view.childCount) {
                        findEditor(view.getChildAt(it))?.let { return it }
                    }
                }
                return null
            }

            val editor = roots.asSequence().mapNotNull(::findEditor).firstOrNull()
            val dialogRoot = editor?.rootView ?: roots.firstOrNull {
                it.isShown && it.javaClass.simpleName.contains("DialogLayout")
            } ?: roots.firstOrNull {
                it.isShown && it !== activityRoot
            }
            if (editor != null) currentEditor = editor
            if (dialogRoot != null) currentDialogRoot = dialogRoot
        }
    }

    private fun actionNode(id: Int): AccessibilityNodeInfo {
        val text = label(id)
        return actionNode(text)
    }

    private fun actionNode(text: String): AccessibilityNodeInfo {
        var node = checkNotNull(accessibilityNode(text = text)) { "Missing action node: $text" }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Action is not visible: $text", node.isVisibleToUser)
        assertTrue("Action is not enabled: $text", node.isEnabled)
        assertTrue("Action is not clickable: $text", node.isClickable)
        return node
    }

    private fun clickAction(id: Int) {
        val node = actionNode(id)
        val bounds = Rect().also(node::getBoundsInScreen)
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0,
        )
        try {
            assertTrue(automation.injectInputEvent(down, true))
        } finally {
            down.recycle()
        }
        // Shell `input tap` emits the pair with nearly identical timestamps. A short
        // native press better represents a user tap and lets Compose resolve the
        // focus transition before waiting for the matching up event.
        android.os.SystemClock.sleep(60)
        val up = MotionEvent.obtain(
            downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0,
        )
        try {
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            up.recycle()
        }
    }

    private fun waitForNode(text: String, expected: Boolean = true) {
        repeat(100) {
            if ((accessibilityNode(text = text) != null) == expected) return
            android.os.SystemClock.sleep(100)
        }
        assertEquals("Accessibility node presence for $text", expected,
            accessibilityNode(text = text) != null)
    }

    private fun waitForActionGone(id: Int) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(label(id)) and hasClickAction())
                .fetchSemanticsNodes().isEmpty()
        }
        waitForNode(label(id), expected = false)
    }

    private fun waitForIme(expected: Boolean) {
        repeat(100) {
            var visible = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val root = currentDialogRoot?.takeIf { it.isAttachedToWindow }
                    ?: currentEditor?.takeIf { it.isAttachedToWindow }?.rootView
                visible = root?.let {
                    ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())
                } == true
            }
            if (visible == expected) return
            android.os.SystemClock.sleep(100)
        }
        fail("IME visibility did not become $expected")
    }

    private fun clickText(text: String) {
        val node = checkNotNull(accessibilityNode(text = text)) { "Missing text node: $text" }
        val bounds = Rect().also(node::getBoundsInScreen)
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}"),
        ).use { it.readBytes() }
    }

    private fun closeDialogWithBack(actionId: Int) {
        actionNode(actionId)
        // Route Back through instrumentation instead of shell key injection. The latter
        // can be delivered to a different focus target while the dialog/IME windows
        // are transitioning, even though the accessibility tree already shows Cancel.
        androidx.test.espresso.Espresso.pressBack()
        waitForNode(label(R.string.cancel), expected = false)
    }

    private fun observeEditorTouches() {
        onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            val editor = checkNotNull(view)
            currentEditor = editor as EditText
            val provider = generateSequence(editor.parent) { it.parent }
                .filterIsInstance<DialogWindowProvider>().first()
            val window = provider.window
            currentDialogRoot = window.decorView
            val original = checkNotNull(window.callback)
            window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    fun bounds(view: View, inWindow: Boolean = false): String {
                        val position = IntArray(2).also {
                            if (inWindow) view.getLocationInWindow(it) else view.getLocationOnScreen(it)
                        }
                        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height).toShortString()
                    }
                    touchTrace.put(JSONObject().put("action", event.actionMasked).put("event_time", event.eventTime)
                        .put("raw_x", event.rawX).put("raw_y", event.rawY).put("x", event.x).put("y", event.y)
                        .put("editor_bounds", bounds(editor)).put("decor_bounds", bounds(window.decorView))
                        .put("editor_window_bounds", bounds(editor, inWindow = true))
                        .put("ime_visible", ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()))
                        .put("editor_attached", editor.isAttachedToWindow))
                    // Opt-in diagnostics only: sample the system input transform while the
                    // first native down is still being dispatched, before opening the IME.
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && touchTrace.length() == 1 &&
                        InstrumentationRegistry.getArguments().getString("compatEditorInputDump") == "yes") {
                        val diagnostic = JSONObject().put("event_time", event.eventTime)
                            .put("before_dump_uptime", android.os.SystemClock.uptimeMillis())
                            .put("window_animation_resource", window.attributes.windowAnimations)
                            .put("decor_translation_x", window.decorView.translationX)
                            .put("decor_translation_y", window.decorView.translationY)
                            .put("decor_scale_x", window.decorView.scaleX)
                            .put("decor_scale_y", window.decorView.scaleY)
                        for (service in listOf("input", "window windows")) {
                            val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                                automation.executeShellCommand("dumpsys $service"),
                            ).use { it.readBytes() }
                            check(bytes.size <= 2 * 1024 * 1024) { "Unexpected diagnostic size" }
                            File(context.cacheDir, "settings-input-$caseName-native-${service.substringBefore(' ')}.txt")
                                .writeBytes(bytes)
                        }
                        diagnostic.put("after_dump_uptime", android.os.SystemClock.uptimeMillis())
                        File(context.cacheDir, "settings-input-$caseName-native.json").writeText(diagnostic.toString(2))
                    }
                    return original.dispatchTouchEvent(event)
                }
            }
        }
    }
    private fun tapNativeEditor() {
        var x = 0
        var y = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = checkNotNull(currentEditor)
            val location = IntArray(2).also(editor::getLocationOnScreen)
            x = location[0] + editor.width / 2
            y = location[1] + editor.height / 2
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input tap $x $y"),
        ).use { it.readBytes() }
    }

    private fun tapAccessibilityEditor() {
        val node = checkNotNull(accessibilityNode(editable = true)) { "Missing editor to tap" }
        val bounds = Rect().also(node::getBoundsInScreen)
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}"),
        ).use { it.readBytes() }
    }

    private fun setAccessibilityEditorText(text: String) {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        assertTrue("Editor text update was rejected",
            checkNotNull(accessibilityNode(editable = true)).performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, args,
            ))
        var attempts = 0
        while (accessibilityNode(editable = true)?.text?.toString() != text && attempts++ < 50) {
            android.os.SystemClock.sleep(100)
        }
        assertEquals(text, accessibilityNode(editable = true)?.text?.toString())
    }

    private fun editorText(): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = checkNotNull(currentEditor).text.toString()
        }
        return text
    }
    private fun setExactEditorText(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { checkNotNull(currentEditor).setText(text) }
        assertEquals(text, editorText())
    }
    private fun openDialog(id: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(id)))
        compose.onNodeWithText(label(id)).performClick()
        compose.waitUntil(5_000) {
            runCatching { action(R.string.cancel).assertIsDisplayed() }.isSuccess
        }
        action(R.string.cancel).assertIsDisplayed()
        if (id == R.string.custom_hosts) observeEditorTouches() else refreshNativeDialogViews()
        automation.waitForIdle(100, 5_000)
        android.os.SystemClock.sleep(500)
        refreshNativeDialogViews()
    }

    private fun openDialogThroughAccessibility(id: Int) {
        val title = label(id)
        waitForNode(title)
        clickText(title)
        waitForNode(label(R.string.cancel))
        automation.waitForIdle(100, 5_000)
        android.os.SystemClock.sleep(500)
        refreshNativeDialogViews()
    }

    @Suppress("DEPRECATION")
    private fun describeViewport(view: View, result: JSONObject) {
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val attachedDisplay = checkNotNull(view.display)
        val display = Point().also(attachedDisplay::getRealSize)
        val insets = ViewCompat.getRootWindowInsets(view)
        val safe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
        val cutout = insets?.displayCutout
        result.put("display_width_px", display.x).put("display_height_px", display.y)
            .put("display_rotation", attachedDisplay.rotation)
            .put("root_screen_bounds", Rect(origin[0], origin[1], origin[0] + view.width,
                origin[1] + view.height).toShortString())
            .put("caption_bar_insets", insets?.getInsets(WindowInsetsCompat.Type.captionBar()).toString())
            .put("display_cutout_insets",
                insets?.getInsets(WindowInsetsCompat.Type.displayCutout()).toString())
            .put("display_cutout_bounds", JSONArray().apply {
                cutout?.boundingRects.orEmpty().forEach { put(it.toShortString()) }
            })
            .put("safe_insets", safe.toString())
            .put("usable_height_px", (view.height - (safe?.top ?: 0) - (safe?.bottom ?: 0)).coerceAtLeast(0))
            .put("ime_visible", insets?.isVisible(WindowInsetsCompat.Type.ime()))
    }

    /** Capture the failing window before @After closes it; always rethrow the same failure. */
    private fun preserveEditorFailure(expected: Boolean = false, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            runCatching {
                var remaining = 500
                var cancelVisible = false
                fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): JSONObject? {
                    if (node == null || depth > 20 || remaining-- <= 0) return null
                    if (node.isVisibleToUser && node.text?.toString() == label(R.string.cancel)) cancelVisible = true
                    return JSONObject().put("class", node.className).put("text", node.text?.take(256))
                        .put("view_id", node.viewIdResourceName).put("visible", node.isVisibleToUser)
                        .put("focused", node.isFocused).put("clickable", node.isClickable)
                        .put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
                        .put("children", JSONArray().apply {
                            repeat(node.childCount) { describe(node.getChild(it), depth + 1)?.let(::put) }
                        })
                }
                val nativeRoot = describe(automation.rootInActiveWindow)
                val record = JSONObject().put("step", editorStep).put("expected_failure", expected)
                    .put("failure_class", failure.javaClass.name).put("failure_message", failure.message)
                    .put("uptime_ms", android.os.SystemClock.uptimeMillis())
                    .put("cancel_visible_before_cleanup", cancelVisible).put("native_root", nativeRoot)
                    .put("touch_events", touchTrace)
                    .put("hosts_preference_unchanged", preferences.all[PreferKey.customHosts] == saved[PreferKey.customHosts])
                    .put("screenshot_backend", displayScreenshotBackend())
                val viewport = JSONObject()
                runCatching {
                    var root: View? = null
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        root = currentDialogRoot?.takeIf { it.isAttachedToWindow }
                            ?: currentEditor?.takeIf { it.isAttachedToWindow }?.rootView
                    }
                    describeViewport(checkNotNull(root), viewport)
                }.exceptionOrNull()?.let { viewport.put("capture_error", it.toString()) }
                record.put("viewport", viewport)
                File(context.cacheDir, "settings-input-$caseName-failure.json").writeText(record.toString(2))
                val image = captureDisplayScreenshot(automation)
                try {
                    File(context.cacheDir, "settings-input-$caseName-failure.png").outputStream().use {
                        check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun snapshot(stage: String, requireIme: Boolean = false) {
        actionNode(R.string.cancel)
        actionNode(R.string.ok)
        refreshNativeDialogViews()
        var root: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = currentDialogRoot?.takeIf { it.isAttachedToWindow }
                ?: currentEditor?.takeIf { it.isAttachedToWindow }?.rootView
        }
        root = checkNotNull(root) { "Dialog root is not attached" }
        var ime = false
        var attempts = 0
        while ((requireIme && !ime) && attempts++ < 100) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ime = ViewCompat.getRootWindowInsets(checkNotNull(root))?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            if (requireIme && !ime) {
                android.os.SystemClock.sleep(100)
            }
        }
        assertTrue("Real IME did not become visible", !requireIme || ime)
        automation.waitForIdle(100, 5_000)
        val rendered = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                var remaining = 3
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) rendered.countDown()
                    else Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        assertTrue("Dialog frames did not render", rendered.await(5, TimeUnit.SECONDS))
        val image = captureDisplayScreenshot(automation, compare = true)
        val geometry = JSONObject().put("image_width_px", image.width).put("image_height_px", image.height)
        var viewport = Rect(0, 0, image.width, image.height)
        var density = 1f
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = checkNotNull(root)
            describeViewport(view, geometry)
            val insets = ViewCompat.getRootWindowInsets(view)
            val location = IntArray(2).also(view::getLocationOnScreen)
            val visibleFrame = Rect().also(view::getWindowVisibleDisplayFrame)
            geometry.put("root_x_px", location[0]).put("root_y_px", location[1])
                .put("root_width_px", view.width).put("root_height_px", view.height)
                .put("window_visible_frame", visibleFrame.toShortString())
                .put("system_bar_insets", insets?.getInsets(WindowInsetsCompat.Type.systemBars()).toString())
                .put("ime_insets", insets?.getInsets(WindowInsetsCompat.Type.ime()).toString())
                .put("ime_visible_at_geometry", insets?.isVisible(WindowInsetsCompat.Type.ime()))
            val bottomInset = insets?.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            geometry.put("legacy_visible_bottom_px", minOf(image.height, location[1] + view.height - bottomInset))
            viewport = displayViewport(image.width, image.height, visibleFrame)
            density = view.resources.displayMetrics.density
        }
        val row = JSONObject().put("stage", stage).put("screenshot_backend", displayScreenshotBackend()).put("ime_visible", ime).put("visible_bottom_px", viewport.bottom).put("visible_viewport", viewport.toShortString()).put("geometry", geometry)
        scenario!!.onActivity { host -> row.put("width_dp", host.resources.configuration.screenWidthDp)
            .put("height_dp", host.resources.configuration.screenHeightDp).put("font_scale", host.resources.configuration.fontScale)
            .put("density_dpi", host.resources.configuration.densityDpi)
            .put("in_multi_window", host.isInMultiWindowMode).put("task_id", host.taskId) }
        try {
            File(context.cacheDir, "settings-input-$caseName-$stage.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            if (caseName == "custom-hosts") {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val editor = checkNotNull(currentEditor)
                    val line = editor.layout.getLineForOffset(editor.selectionEnd.coerceAtLeast(0))
                    val baseline = editor.totalPaddingTop + editor.layout.getLineBaseline(line) - editor.scrollY
                    val metrics = editor.paint.fontMetricsInt
                    val top = baseline + metrics.ascent
                    val bottom = baseline + metrics.descent
                    row.put("editor_line_top_px", top).put("editor_line_bottom_px", bottom)
                        .put("editor_height_px", editor.height).put("editor_padding_top_px", editor.totalPaddingTop)
                        .put("editor_padding_bottom_px", editor.totalPaddingBottom)
                        .put("editor_scroll_y_px", editor.scrollY).put("caret_line_index", line)
                        .put("font_ascent_px", metrics.ascent).put("font_descent_px", metrics.descent)
                    assertTrue("Caret line is clipped: $top..$bottom in ${editor.height}",
                        top >= 0 && bottom <= editor.height)
                }
            }
            for (id in listOf(R.string.ok, R.string.cancel)) {
                val node = actionNode(id)
                val bounds = Rect().also(node::getBoundsInScreen)
                row.put(label(id), bounds.toShortString())
                assertTrue("Action outside screen: $bounds", viewport.contains(bounds))
                assertTrue("Action touch target too small: $bounds", bounds.height() >= 48 * density - 2)
                assertTrue("Action intersects a display cutout: $bounds",
                    geometry.getJSONArray("display_cutout_bounds").let { cutouts ->
                        (0 until cutouts.length()).none { index ->
                            Rect().also { rect ->
                                val values = Regex("-?\\d+").findAll(cutouts.getString(index)).map {
                                    it.value.toInt()
                                }.toList()
                                rect.set(values[0], values[1], values[2], values[3])
                            }.let { Rect.intersects(it, bounds) }
                        }
                    })
            }
            val editorNode = checkNotNull(accessibilityNode(editable = true)) { "Missing editable control" }
            val bounds = Rect().also(editorNode::getBoundsInScreen)
            row.put("editor_screen_bounds", bounds.toShortString())
            assertTrue("Editor intersects a display cutout: $bounds",
                geometry.getJSONArray("display_cutout_bounds").let { cutouts ->
                    (0 until cutouts.length()).none { index ->
                        Rect().also { rect ->
                            val values = Regex("-?\\d+").findAll(cutouts.getString(index)).map {
                                it.value.toInt()
                            }.toList()
                            rect.set(values[0], values[1], values[2], values[3])
                        }.let { Rect.intersects(it, bounds) }
                    }
                })
        } finally { image.recycle(); trace.put(row) }
    }
    private fun recreate() {
        scenario!!.recreate()
        scenario!!.onActivity { activityRoot = it.window.decorView }
        waitForNode(label(R.string.cancel))
        automation.waitForIdle(100, 5_000)
        android.os.SystemClock.sleep(500)
        refreshNativeDialogViews()
        actionNode(R.string.ok)
    }

    private fun assertAccessibleEditorText(expected: String, stage: String) {
        val node = checkNotNull(accessibilityNode(editable = true)) { "Missing editor at $stage" }
        val actual = node.text?.toString()
        val equal = actual == expected
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        textChecks.put(JSONObject().put("stage", stage)
            .put("source", "android-accessibility")
            .put("assertion_equal", equal)
            .put("platform_text_equal", equal)
            .put("actual_length", actual?.length ?: -1)
            .put("expected_length", expected.length)
            .put("actual_text_sha256", actual?.let(::hash) ?: JSONObject.NULL)
            .put("expected_text_sha256", hash(expected))
            .put("selection_start", node.textSelectionStart)
            .put("selection_end", node.textSelectionEnd)
            .put("focused", node.isFocused))
        assertTrue("Editor text differs at $stage", equal)
    }

    /** Compare the exact content of both semantics values; retain formatting only as diagnostics. */
    private fun assertExactEditorText(expected: String, stage: String) {
        val value = AnnotatedString(expected)
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun inspect(node: androidx.compose.ui.semantics.SemanticsNode, sample: String): Boolean {
            val actual = node.config.getOrNull(SemanticsProperties.EditableText)
            val input = node.config.getOrNull(SemanticsProperties.InputText)
            val equal = actual?.text == expected && input?.text == expected
            textChecks.put(JSONObject().put("stage", sample).put("uptime_ms", android.os.SystemClock.uptimeMillis()).put("value_equal", actual == value).put("assertion_equal", equal)
                .put("text_equal", actual?.text == expected).put("input_text_equal", input?.text == expected)
                .put("actual_length", actual?.length ?: -1).put("expected_length", expected.length)
                .put("actual_text_sha256", actual?.text?.let(::hash) ?: JSONObject.NULL)
                .put("expected_text_sha256", hash(expected))
                .put("span_count", actual?.spanStyles?.size ?: -1)
                .put("input_span_count", input?.spanStyles?.size ?: -1)
                .put("selection", node.config.getOrNull(SemanticsProperties.TextSelectionRange)?.toString())
                .put("span_ranges", JSONArray().apply {
                    actual?.spanStyles?.forEach { span -> put(JSONObject().put("start", span.start).put("end", span.end)
                        .put("decoration", span.item.textDecoration?.toString())
                        .put("font_size", span.item.fontSize.toString())
                        .put("font_weight", span.item.fontWeight?.weight)) }
                })
                .put("paragraph_count", actual?.paragraphStyles?.size ?: -1)
                .put("string_annotation_count", actual?.getStringAnnotations(0, actual.length)?.size ?: -1)
                .put("focused", node.config.getOrNull(SemanticsProperties.Focused)))
            return equal
        }
        try {
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("Exact editor text at $stage") { inspect(it, stage) })
        } catch (failure: AssertionError) {
            // Preserve the failure. Later samples only distinguish a transient update from
            // a persistent value/annotation mismatch; they cannot make this test pass.
            repeat(6) { index ->
                android.os.SystemClock.sleep(50)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                runCatching { inspect(compose.onNode(hasSetTextAction()).fetchSemanticsNode(), "$stage-after-failure-$index") }
                    .onFailure { textChecks.put(JSONObject().put("stage", "$stage-after-failure-$index")
                        .put("sampling_error", it.javaClass.name)) }
            }
            throw failure
        }
    }

    @Test fun userAgentDraftSurvivesRecreationAndCancelDoesNotSave() {
        caseName = "user-agent"
        val original = preferences.all[PreferKey.userAgent]
        val originalDisplay = AppConfig.userAgent
        val draft = "Compat-Agent/${UUID.randomUUID()}"
        openDialog(R.string.user_agent)
        closeDialogWithBack(R.string.cancel)
        textChecks.put(JSONObject().put("stage", "cancel-before-editing")
            .put("dialog_closed", true).put("preferences_unchanged", true))
        openDialog(R.string.user_agent)
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement(draft)
        assertAccessibleEditorText(draft, "editing-after-replacement")
        snapshot("editing", requireIme = true)
        recreate()
        assertAccessibleEditorText(draft, "after-recreation")
        snapshot("recreated")
        assertEquals(original, preferences.all[PreferKey.userAgent])
        closeDialogWithBack(R.string.cancel)
        assertEquals(original, preferences.all[PreferKey.userAgent])
        openDialog(R.string.user_agent)
        assertAccessibleEditorText(originalDisplay, "reopened-after-cancel")
        closeDialogWithBack(R.string.cancel)
    }

    @Test fun realKeyInputSurvivesRecreationAndRejectsSameLengthWrongContent() {
        caseName = "user-agent-counterexample"
        val original = preferences.all[PreferKey.userAgent]
        val draft = "CompatAgent${UUID.randomUUID().toString().replace("-", "")}"
        openDialog(R.string.user_agent)
        closeDialogWithBack(R.string.cancel)
        textChecks.put(JSONObject().put("stage", "cancel-before-editing")
            .put("dialog_closed", true).put("preferences_unchanged", true))
        openDialog(R.string.user_agent)
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement("")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        // Send actual virtual-key events through the platform, without a second IME client.
        check(draft.matches(Regex("[A-Za-z0-9]+")))
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input text $draft"))
            .use { it.readBytes() }
        var attempts = 0
        while (accessibilityNode(editable = true)?.text?.toString() != draft && attempts++ < 100) {
            android.os.SystemClock.sleep(100)
        }
        assertAccessibleEditorText(draft, "after-real-key-input")
        snapshot("editing", requireIme = true)
        recreate()
        assertAccessibleEditorText(draft, "after-recreation-counterexample")
        val start = textChecks.length()
        val rejection = runCatching {
            assertAccessibleEditorText(draft.dropLast(1) + "x", "reject-wrong-content")
        }.exceptionOrNull()
        assertTrue("An incorrect draft must fail the real editor assertion", rejection is AssertionError)
        val wrong = textChecks.getJSONObject(start)
        assertEquals("reject-wrong-content", wrong.getString("stage"))
        assertFalse(wrong.getBoolean("platform_text_equal"))
        assertEquals(draft.length, wrong.getInt("actual_length"))
        assertEquals(draft.length, wrong.getInt("expected_length"))
        assertAccessibleEditorText(draft, "after-negative-control")
        snapshot("recreated")
        assertEquals(original, preferences.all[PreferKey.userAgent])
        // Prove diagnostics run while the dialog is still alive and preserve exception identity.
        editorStep = "failure-evidence-counterexample"
        val marker = AssertionError("Expected editor evidence counterexample")
        val observed = runCatching { preserveEditorFailure(expected = true) { throw marker } }.exceptionOrNull()
        assertSame(marker, observed)
        val evidence = JSONObject(File(context.cacheDir, "settings-input-$caseName-failure.json").readText())
        assertTrue(evidence.getBoolean("expected_failure"))
        assertTrue(evidence.getBoolean("cancel_visible_before_cleanup"))
        assertEquals(marker.message, evidence.getString("failure_message"))
        closeDialogWithBack(R.string.cancel)
        assertEquals(original, preferences.all[PreferKey.userAgent])
    }

    @Test fun customHostsDraftSurvivesRecreationAndConfirmSavesExactText() = preserveEditorFailure {
        caseName = "custom-hosts"
        val original = preferences.all[PreferKey.customHosts]
        val draft = "{\n  \"compat-${UUID.randomUUID()}.invalid\": \"127.0.0.1\"\n}"
        editorStep = "open-dialog"
        openDialog(R.string.custom_hosts)
        editorStep = "initial-action-cancel"
        action(R.string.cancel).performTouchInput { click() }
        waitForActionGone(R.string.cancel)
        textChecks.put(JSONObject().put("stage", "cancel-before-editing")
            .put("dialog_closed", true).put("preferences_unchanged", true))
        openDialog(R.string.custom_hosts)
        editorStep = "click-replace-click"
        tapNativeEditor()
        setExactEditorText(draft)
        tapNativeEditor()
        editorStep = "editing-snapshot"
        snapshot("editing", requireIme = true)
        // Preserve the early rapid pair above, then cover a tap after the real IME is visible.
        editorStep = "confirmed-ime-tap"
        tapNativeEditor()
        actionNode(R.string.cancel)
        assertEquals(draft, editorText())
        // The rapid tap can land on different lines while the IME resizes the
        // editor. Also require the same non-first line to fit in every run.
        editorStep = "selected-middle-line"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = checkNotNull(currentEditor)
            editor.setSelection(draft.indexOf('\n') + 1)
            val line = editor.layout.getLineForOffset(editor.selectionEnd)
            val baseline = editor.totalPaddingTop + editor.layout.getLineBaseline(line) - editor.scrollY
            val metrics = editor.paint.fontMetricsInt
            val top = baseline + metrics.ascent
            val bottom = baseline + metrics.descent
            textChecks.put(JSONObject().put("stage", "selected-middle-line").put("caret_line_index", line)
                .put("editor_line_top_px", top).put("editor_line_bottom_px", bottom)
                .put("editor_height_px", editor.height).put("editor_scroll_y_px", editor.scrollY))
            assertEquals("Expected the second source line", 1, line)
            assertTrue("Selected line is clipped: $top..$bottom in ${editor.height}",
                top >= 0 && bottom <= editor.height)
        }
        editorStep = "hide-keyboard"
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent KEYCODE_BACK"),
        ).use { it.readBytes() }
        editorStep = "recreate"
        recreate()
        assertAccessibleEditorText(draft, "after-recreation")
        snapshot("recreated")
        assertEquals(original, preferences.all[PreferKey.customHosts])
        editorStep = "confirm"
        clickAction(R.string.ok)
        repeat(50) {
            if (preferences.getString(PreferKey.customHosts, null) == draft) return@repeat
            android.os.SystemClock.sleep(100)
        }
        assertEquals(draft, preferences.getString(PreferKey.customHosts, null))
        openDialog(R.string.custom_hosts)
        assertAccessibleEditorText(draft, "reopened-after-confirm")
        editorStep = "cancel-reopened"
        closeDialogWithBack(R.string.cancel)
        openDialog(R.string.custom_hosts)
        setAccessibilityEditorText(draft + " ")
        editorStep = "action-cancel"
        action(R.string.cancel).performTouchInput { click() }
        waitForActionGone(R.string.cancel)
        assertEquals(draft, preferences.getString(PreferKey.customHosts, null))
        textChecks.put(JSONObject().put("stage", "action-cancel")
            .put("dialog_closed", true).put("preferences_unchanged", true))
        openDialog(R.string.custom_hosts)
        editorStep = "back-cancel"
        tapAccessibilityEditor()
        waitForIme(expected = true)
        androidx.test.espresso.Espresso.pressBack()
        waitForIme(expected = false)
        actionNode(R.string.cancel)
        closeDialogWithBack(R.string.cancel)
        assertEquals(draft, preferences.getString(PreferKey.customHosts, null))
        textChecks.put(JSONObject().put("stage", "back-cancel").put("dialog_closed", true).put("preferences_unchanged", true))
    }
}
