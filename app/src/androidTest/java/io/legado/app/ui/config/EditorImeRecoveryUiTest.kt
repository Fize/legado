package io.legado.app.ui.config

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Point
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real production dialogs in a physically underconstrained freeform window. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class EditorImeRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = listOf(PreferKey.userAgent, PreferKey.customHosts, PreferKey.language, PreferKey.autoBackup)
        .associateWith { preferences.all[it] }
    private val frames = JSONArray()
    private val keys = JSONArray()
    private val touches = JSONArray()
    private val report = JSONObject()
    private var activity: AppActivity? = null
    private var observedRoot: View? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var observedWindow: Window? = null
    private var previousWindowCallback: Window.Callback? = null
    private var codeView: EditText? = null
    private var caseName = "not-started"
    @Volatile private var phase = "not-started"
    private val firstIme = CountDownLatch(1)
    private var injector: Thread? = null
    private val injectionFailure = AtomicReference<Throwable?>()

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

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
        preferences.edit().putString(PreferKey.language, "en").putBoolean(PreferKey.autoBackup, false).commit()
    }

    private fun screenshot(stage: String) {
        val image = captureDisplayScreenshot(automation)
        try {
            File(context.cacheDir, "ime-recovery-$caseName-$stage.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }

    private fun waitForStableDisplay() {
        var previous: android.graphics.Bitmap? = null
        var unchangedSince = SystemClock.uptimeMillis()
        try {
            compose.waitUntil(5_000) {
                val current = captureDisplayScreenshot(automation)
                val same = previous?.sameAs(current) == true
                previous?.recycle()
                previous = current
                if (!same) unchangedSince = SystemClock.uptimeMillis()
                same && SystemClock.uptimeMillis() - unchangedSince >= 150
            }
        } finally { previous?.recycle() }
    }

    private fun stopObserving() {
        instrumentation.runOnMainSync {
            listener?.let { callback -> observedRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(callback) }
            listener = null
            previousWindowCallback?.let { observedWindow?.callback = it }
            previousWindowCallback = null
            observedWindow = null
        }
    }

    @After fun cleanup() = runCleanupSteps(
        { injector?.join(15_000); assertTrue("Key injector still running", injector?.isAlive != true) },
        { stopObserving() },
        { screenshot("last") },
        { instrumentation.runOnMainSync { resumedActivityOnMain()?.finish() } },
        {
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                else -> error("Unexpected preference type: $key")
            } } }.commit()
            saved.forEach { (key, value) -> assertEquals("Preference not restored: $key", value, preferences.all[key]) }
            report.put("preferences_restored", true)
        },
        {
            report.put("frames", frames).put("key_events", keys).put("touch_events", touches).put("last_phase", phase)
            File(context.cacheDir, "ime-recovery-$caseName.json").writeText(report.toString(2))
        },
    )

    private fun resumedActivityOnMain() = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<AppActivity>().firstOrNull()

    private fun imeVisible(): Boolean {
        var result = false
        instrumentation.runOnMainSync {
            result = observedRoot?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true
        }
        return result
    }

    private fun observe() {
        stopObserving()
        onView(isRoot()).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            val root = checkNotNull(view)
            observedRoot = root
            fun provider(view: View): DialogWindowProvider? {
                if (view is DialogWindowProvider) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    provider(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val window = checkNotNull(provider(root)).window
            val original = checkNotNull(window.callback)
            observedWindow = window
            previousWindowCallback = original
            window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (phase != "fullscreen-retry") return original.dispatchTouchEvent(event)
                    val editor = codeView
                    val origin = IntArray(2)
                    editor?.getLocationOnScreen(origin)
                    val row = JSONObject().put("phase", phase).put("action", event.actionMasked)
                        .put("event_time", event.eventTime).put("raw_x", event.rawX).put("raw_y", event.rawY)
                        .put("x", event.x).put("y", event.y).put("root_window_focused", root.hasWindowFocus())
                        .put("soft_input_mode", window.attributes.softInputMode)
                    editor?.let {
                        row.put("editor_bounds", Rect(origin[0], origin[1], origin[0] + it.width, origin[1] + it.height).toShortString())
                            .put("editor_focused", it.isFocused)
                            .put("selection_before", it.selectionStart).put("scroll_before", it.scrollY)
                            .put("show_soft_input_on_focus", it.showSoftInputOnFocus)
                            .put("ime_active_for_editor", context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(it))
                    }
                    val handled = original.dispatchTouchEvent(event)
                    editor?.let { row.put("selection_after", it.selectionStart).put("scroll_after", it.scrollY) }
                    touches.put(row.put("handled", handled))
                    return handled
                }
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val focused = root.findFocus()
                    val row = JSONObject().put("phase", phase).put("key_code", event.keyCode)
                        .put("action", event.action).put("event_time", event.eventTime)
                        .put("uptime_ms", SystemClock.uptimeMillis()).put("root_identity", System.identityHashCode(root))
                        .put("ime_visible", ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()))
                        .put("focused_class", focused?.javaClass?.name).put("is_text_editor", focused?.onCheckIsTextEditor())
                        .put("text_before", codeView?.text?.toString())
                        .put("selection_before", codeView?.selectionStart)
                    val handled = original.dispatchKeyEvent(event)
                    row.put("handled", handled).put("text_after", codeView?.text?.toString())
                        .put("selection_after", codeView?.selectionStart)
                    keys.put(row)
                    return handled
                }
            }
            var previous = ""
            val callback = ViewTreeObserver.OnPreDrawListener {
                val insets = ViewCompat.getRootWindowInsets(root)
                val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val ime = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                val safe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
                val origin = IntArray(2).also(root::getLocationOnScreen)
                val row = JSONObject().put("phase", phase).put("ime_visible", ime).put("ime_bottom_px", imeBottom)
                    .put("usable_height_px", root.height - (safe?.top ?: 0) - (safe?.bottom ?: 0))
                    .put("root_bounds", Rect(origin[0], origin[1], origin[0] + root.width, origin[1] + root.height).toShortString())
                    .put("root_identity", System.identityHashCode(root))
                codeView?.let { editor -> row.put("editor_identity", System.identityHashCode(editor))
                    .put("editor_height_px", editor.height).put("editor_focused", editor.isFocused)
                    .put("editor_scroll_y_px", editor.scrollY).put("selection_start", editor.selectionStart)
                    .put("text", editor.text.toString())
                    val point = IntArray(2).also(editor::getLocationOnScreen)
                    row.put("editor_bounds", Rect(point[0], point[1], point[0] + editor.width, point[1] + editor.height).toShortString())
                }
                val signature = row.toString()
                if (signature != previous && frames.length() < 900) {
                    row.put("uptime_ms", SystemClock.uptimeMillis()); frames.put(row); previous = signature
                }
                if (ime || imeBottom > 0) firstIme.countDown()
                true
            }
            listener = callback
            root.viewTreeObserver.addOnPreDrawListener(callback)
        }
    }

    private fun assertText(expected: String, native: Boolean) {
        if (native) onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            assertEquals(expected, (view as EditText).text.toString())
        } else {
            val config = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
            assertEquals(expected, config.getOrNull(SemanticsProperties.EditableText)?.text)
            assertEquals(expected, config.getOrNull(SemanticsProperties.InputText)?.text)
        }
    }

    @Suppress("DEPRECATION")
    private fun exercise(native: Boolean, checkOrder: Boolean = false, burst: Boolean = false) {
        check(!checkOrder || native)
        check(!burst || checkOrder)
        caseName = if (burst) "code-burst" else if (checkOrder) "code-order" else if (native) "code" else "compose"
        val confirm = native && !checkOrder
        report.put("case", caseName)
        try {
            phase = "launch"
            val uri = AppActivity.startIntent(context, SettingsRoute(SettingsSection.Other)).toUri(Intent.URI_INTENT_SCHEME)
            check(uri.none(Char::isWhitespace))
            check(!shell("am start --windowingMode 5 $uri").contains("Error"))
            compose.waitUntil(15_000) { activity = resumed(); activity?.isInMultiWindowMode == true }
            val task = activity!!.taskId
            shell("am task resize $task 303 196 2121 938")
            compose.waitUntil(10_000) { activity!!.window.decorView.width == 1818 && activity!!.window.decorView.height == 742 }
            report.put("task_id", task).put("font_scale", activity!!.resources.configuration.fontScale)
                .put("density", activity!!.resources.displayMetrics.density)
                .put("original_activity_identity", System.identityHashCode(activity))
            val display = Point().also(activity!!.windowManager.defaultDisplay::getRealSize)
            assertEquals(2424, display.x); assertEquals(1080, display.y)
            assertEquals(2f, activity!!.resources.configuration.fontScale, .01f)
            assertEquals(3.375f, activity!!.resources.displayMetrics.density, .01f)
            report.put("display_width_px", display.x).put("display_height_px", display.y)
            val label = context.getString(if (native) R.string.custom_hosts else R.string.user_agent)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performTouchInput { click() }
            compose.onNode(hasText(context.getString(R.string.cancel)) and hasClickAction()).assertIsDisplayed()
            val draft = if (native && !checkOrder) "{}" else "DRAFT-"
            val suffix = if (checkOrder) "qwertyuiopasdfghjklzxcvbnm0123456789" else if (native) "   " else "queued"
            report.put("queued_suffix", suffix).put("injection_method",
                if (burst) "shell-input-text" else "uiautomation-synchronous-key-events")
            if (native) {
                onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(replaceText(draft))
                onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    codeView = view as EditText
                    report.put("original_editor_identity", System.identityHashCode(view))
                }
            } else compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            val fieldId = if (native) 0 else compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
            observe(); screenshot("before")
            phase = "open-ime"
            injector = Thread {
                try {
                    check(firstIme.await(10, TimeUnit.SECONDS)) { "Actual IME was never observed" }
                    report.put("key_injection_started_ms", SystemClock.uptimeMillis())
                    if (burst) {
                        check(suffix.all { it.isLetterOrDigit() })
                        report.put("burst_command_output", shell("input text $suffix"))
                    } else {
                        val events = checkNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(suffix.toCharArray()))
                        events.forEach { check(automation.injectInputEvent(it, true)) }
                    }
                    report.put("key_injection_completed_ms", SystemClock.uptimeMillis())
                } catch (error: Throwable) { injectionFailure.set(error) }
            }.also { it.start() }
            if (native) onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(click())
            else compose.onNode(hasSetTextAction()).performTouchInput { click() }
            phase = "await-recovery"
            // Keep driving composition while native input is in flight. Joining
            // first would pause the Compose test clock and suppress the very
            // IME transition that this test needs to exercise.
            compose.waitUntil(25_000) {
                injectionFailure.get()?.let { throw it }
                firstIme.count == 0L && injector?.isAlive == false && !imeVisible() &&
                    runCatching { compose.onNodeWithTag("legadoEditorImeRecovery").assertIsDisplayed() }.isSuccess
            }
            injector!!.join(15_000); injectionFailure.get()?.let { throw it }
            assertFalse(injector!!.isAlive)
            assertEquals("Recovery was checked before the actual IME appeared", 0L, firstIme.count)
            report.put("actual_ime_observed_before_recovery", true)
            val expected = draft + suffix
            if (checkOrder) compose.waitUntil(5_000) {
                var length = -1
                instrumentation.runOnMainSync { length = codeView!!.length() }
                length == expected.length
            }
            assertText(expected, native)
            val recovery = compose.onNodeWithTag("legadoEditorImeRecovery").fetchSemanticsNode().config
            assertEquals(LiveRegionMode.Polite, recovery.getOrNull(SemanticsProperties.LiveRegion))
            report.put("recovery_message_visible", true).put("recovery_live_region_polite", true)
            if (native) {
                assertTrue("Recovery measured the editor at zero height", (0 until frames.length()).all {
                    frames.getJSONObject(it).optInt("editor_height_px", 1) > 0
                })
            }
            if (native) assertSame("Recovery replaced the native editor", codeView,
                onNativeEditor())
            else assertEquals("Recovery replaced the Compose field", fieldId, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
            val preference = if (native) PreferKey.customHosts else PreferKey.userAgent
            assertEquals(saved[preference], preferences.all[preference])
            report.put("recovered_exact_text", expected).put("draft_not_committed_during_recovery", true)
                .put("same_editor_during_recovery", true)
            waitForStableDisplay()
            assertFalse(imeVisible())
            val hintLayout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("legadoEditorImeRecovery").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(hintLayout))
            }
            assertEquals(1, hintLayout.size)
            assertFalse("Recovery hint is clipped", hintLayout.single().hasVisualOverflow)
            for (line in 0 until hintLayout.single().lineCount) {
                assertFalse("Recovery hint is ellipsized", hintLayout.single().isLineEllipsized(line))
            }
            val actionHeights = JSONArray()
            for (label in listOf(R.string.ok, R.string.cancel)) {
                val node = compose.onNode(hasText(context.getString(label)) and hasClickAction()).assertIsDisplayed()
                    .fetchSemanticsNode()
                assertTrue("Recovery action is below 48dp", node.boundsInRoot.height >= 48 * 3.375f - 1)
                actionHeights.put(node.boundsInRoot.height)
            }
            report.put("recovery_hint_not_clipped", true).put("recovery_action_heights_px", actionHeights)
            screenshot("recovered")
            report.put("recovery_ime_animation_finished", true)
            phase = "recreate"
            stopObserving(); val old = activity
            instrumentation.runOnMainSync { old!!.recreate() }
            compose.waitUntil(15_000) { activity = resumed(); activity != null && activity !== old }
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("legadoEditorImeRecovery").assertIsDisplayed() }.isSuccess
            }
            assertText(expected, native); assertEquals(task, activity!!.taskId)
            codeView = if (native) onNativeEditor() else null
            observe(); report.put("recreated_exact_text", expected)
                .put("recreated_activity_identity", System.identityHashCode(activity))
            phase = "fullscreen-retry"
            shell("am task resize $task 0 0 2424 1080")
            shell("am start -W --windowingMode 1 --activity-single-top -n ${context.packageName}/io.legado.app.ui.AppActivity")
            compose.waitUntil(15_000) { activity = resumed(); activity != null && !activity!!.isInMultiWindowMode }
            assertEquals(task, activity!!.taskId); assertText(expected, native)
            codeView = if (native) onNativeEditor() else null
            observe()
            compose.waitUntil(10_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val root = observedRoot
                    val origin = IntArray(2)
                    root?.getLocationOnScreen(origin)
                    ready = root?.hasWindowFocus() == true && root.isAttachedToWindow &&
                        root.width == 2424 && root.height == 1080 && origin.contentEquals(intArrayOf(0, 0))
                }
                ready
            }
            report.put("fullscreen_native_window_ready", true)
            if (native) onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(click())
            else compose.onNode(hasSetTextAction()).performTouchInput { click() }
            val retryAppeared = runCatching {
                compose.waitUntil(1_000) { imeVisible() }
            }.isSuccess
            if (!retryAppeared) {
                instrumentation.runOnMainSync {
                    val focused = observedRoot?.findFocus() ?: activity?.window?.currentFocus
                    if (focused != null) {
                        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .showSoftInput(focused, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            compose.waitUntil(10_000) { imeVisible() &&
                compose.onAllNodesWithTag("legadoEditorImeRecovery").fetchSemanticsNodes().isEmpty() }
            assertText(expected, native); waitForStableDisplay(); screenshot("resized")
            report.put("retry_ime_visible", true).put("retry_ime_required_explicit_show", !retryAppeared)
                .put("retry_exact_text", expected)
            phase = "complete"
            compose.onNode(hasText(context.getString(if (confirm) R.string.ok else R.string.cancel)) and hasClickAction())
                .performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(context.getString(R.string.cancel)) and hasClickAction()).fetchSemanticsNodes().isEmpty() }
            assertEquals(if (confirm) expected else saved[preference], preferences.all[preference])
            report.put("completion", if (confirm) "confirmed" else "cancelled").put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.toString())
                .put("preference_unchanged_at_failure", saved.all { (key, value) -> preferences.all[key] == value || key == PreferKey.language || key == PreferKey.autoBackup })
            runCatching {
                report.put("input_method_dump_uptime_ms", SystemClock.uptimeMillis())
                    .put("input_method_at_failure", shell("dumpsys input_method"))
            }.onFailure { report.put("input_method_dump_error", it.toString()) }
            screenshot("failure")
            throw error
        }
    }

    private fun onNativeEditor(): EditText {
        var editor: EditText? = null
        onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            editor = view as EditText
        }
        return checkNotNull(editor)
    }

    @Test fun codeDraftSurvivesImeRecoveryRecreationAndConfirmation() = exercise(true)
    @Test fun composeDraftSurvivesImeRecoveryAndCancellation() = exercise(false)
    @Test fun codeDraftKeepsDistinctQueuedKeyOrder() = exercise(true, checkOrder = true)
    @Test fun codeDraftKeepsBurstKeyOrder() = exercise(true, checkOrder = true, burst = true)
}
