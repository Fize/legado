package io.legado.app.ui.config

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.SourceTargetStateStore
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.manage.BookSourceActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SourceTargetRecoveryLayoutUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val landscape = InstrumentationRegistry.getArguments().getString("sourceTargetLayoutLandscape") == "true"
    private val message = "无法恢复校验目标，请关闭后重新选择书源"
    private val actionMeasurements = JSONArray()
    private val imeTransitions = JSONArray()
    private val editorTransitions = JSONArray()

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun hideIme() {
        compose.waitForIdle()
        fun shown() = shell("dumpsys input_method").contains("mInputShown=true")
        val before = shown()
        val transition = JSONObject().put("shown_before_back", before)
        imeTransitions.put(transition)
        var lastBackAt = 0L
        var backPresses = 0
        fun dismissIfShown(visible: Boolean) {
            val now = SystemClock.uptimeMillis()
            if (visible && (backPresses == 0 || now - lastBackAt >= 2_000)) {
                shell("input keyevent KEYCODE_BACK")
                lastBackAt = SystemClock.uptimeMillis()
                backPresses++
            }
        }
        dismissIfShown(before)
        transition.put("shown_immediately_after_back", shown())
        // An IME may also appear after the first observation, even after Compose is idle.
        // Require it to remain hidden briefly and dismiss it if it appears meanwhile.
        var hiddenSince = 0L
        compose.waitUntil(10_000) {
            val visible = shown()
            if (visible) {
                hiddenSince = 0L
                dismissIfShown(true)
                false
            } else {
                val now = SystemClock.uptimeMillis()
                if (hiddenSince == 0L) hiddenSince = now
                now - hiddenSince >= 750
            }
        }
        compose.waitForIdle()
        val after = shown()
        transition.put("shown_after_wait", after).put("back_presses", backPresses)
        assertFalse("This acceptance targets the keyboard-hidden layout", after)
    }

    private fun profile(scenario: ActivityScenario<BookSourceActivity>, stage: String, records: JSONArray) {
        var sample = JSONObject()
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val config = activity.resources.configuration
                ready = view.width > 0 && view.height > 0 && (!landscape ||
                    (view.width == 2424 && view.height == 1080 && config.screenWidthDp > config.screenHeightDp &&
                        config.fontScale == 2f && config.densityDpi == 540))
                sample = JSONObject().put("stage", stage).put("width_px", view.width).put("height_px", view.height)
                    .put("width_dp", config.screenWidthDp).put("height_dp", config.screenHeightDp)
                    .put("font_scale", config.fontScale).put("density_dpi", config.densityDpi)
            }
            ready
        }
        records.put(sample)
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.cacheDir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun action(label: String) = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun visibleEditor(stage: String): SemanticsNodeInteraction {
        val editor = compose.onNode(hasSetTextAction())
        val started = android.os.SystemClock.elapsedRealtime()
        val transition = JSONObject().put("stage", stage).put("initially_displayed", editor.isDisplayed())
        editorTransitions.put(transition)
        try {
            if (!editor.isDisplayed()) screenshot("source-target-recovery-valid-editor-$stage-before-wait")
            // A native IME close and the popup's inset/layout update use different clocks.
            compose.waitUntil(10_000) { editor.isDisplayed() }
            editor.assertIsDisplayed()
            transition.put("displayed_after_wait", true)
                .put("wait_ms", android.os.SystemClock.elapsedRealtime() - started)
            return editor
        } catch (error: Throwable) {
            transition.put("displayed_after_wait", false)
            runCatching {
                screenshot("source-target-recovery-valid-editor-$stage-failure")
                File(context.cacheDir, "source-target-recovery-valid-editor-$stage-failure-tree.txt")
                    .writeText(compose.onNode(isDialog(), useUnmergedTree = true).printToString())
                File(context.cacheDir, "source-target-recovery-valid-editor-$stage-failure-ime.txt")
                    .writeText(shell("dumpsys input_method"))
            }.onFailure { transition.put("diagnostic_error", it.toString()) }
            throw error
        }
    }

    private fun actions(): JSONArray {
        assertEquals(context.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val records = JSONArray()
        for (label in listOf(context.getString(R.string.cancel), context.getString(R.string.ok), context.getString(R.string.check_source_config))) {
            val field = action(label).assertIsDisplayed()
            val node = field.fetchSemanticsNode()
            assertTrue("Action width clipped: $label", abs(node.boundsInRoot.width - node.size.width) < 1.5f)
            assertTrue("Action height clipped: $label", abs(node.boundsInRoot.height - node.size.height) < 1.5f)
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val measurements = JSONArray()
            layouts.forEach { layout -> measurements.put(JSONObject()
                .put("width", layout.size.width).put("height", layout.size.height)
                .put("overflow_width", layout.didOverflowWidth).put("overflow_height", layout.didOverflowHeight)
                .put("intrinsic_width", layout.multiParagraph.maxIntrinsicWidth)
                .put("paragraph_width", layout.multiParagraph.width)
                .put("line_count", layout.lineCount)
                .put("line_rights", JSONArray((0 until layout.lineCount).map { layout.getLineRight(it) }))
                .put("last_line_bottom", if (layout.lineCount > 0) layout.getLineBottom(layout.lineCount - 1) else 0)) }
            actionMeasurements.put(JSONObject().put("label", label).put("layouts", measurements))
            assertTrue("No action text layout: $label", layouts.isNotEmpty())
            // Fractional paragraph width may exceed the rounded box even when the glyphs fit.
            // Keep the complete line/ellipsis checks instead of treating that flag as clipping.
            assertTrue("Action text clipped: $label", layouts.all { layout ->
                !layout.didOverflowHeight && (0 until layout.lineCount).all { line ->
                    !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -1f &&
                        layout.getLineRight(line) <= layout.size.width + 1f &&
                        layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f
                }
            })
            records.put(JSONObject().put("label", label).put("width", node.boundsInRoot.width)
                .put("height", node.boundsInRoot.height).put("text_not_clipped", true))
        }
        return records
    }

    private fun exercise(kind: String) {
        val report = JSONObject().put("case", kind).put("requested_short_landscape", landscape)
            .put("scope", "Actual error prompt, visible actions and pointer dismissal across two Activity recreations, keyboard hidden; synthetic targets")
        val profiles = JSONArray()
        val before = CheckSource.keyword
        assertFalse(Debug.isChecking)
        try {
            ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
                if (landscape) scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                profile(scenario, "opened", profiles)
                scenario.onActivity { activity ->
                    if (kind == "missing") {
                        val dialog = SourceCheckDialog.create(before, listOf("https://owned-layout.invalid"), activity.taskId)
                        val store = SourceTargetStateStore(File(context.noBackupFilesDir, "source-target-state"))
                        store.release(store.claim(dialog.requireArguments().getString("targetSnapshot")!!))
                        dialog.showNow(activity.supportFragmentManager, "source-recovery-layout")
                    } else {
                        val directory = File(context.noBackupFilesDir, "source-target-state")
                        val retained = File(context.noBackupFilesDir, "owned-layout-fault-${UUID.randomUUID()}")
                        val args = Bundle().apply {
                            putString("keyword", before)
                            putStringArrayList("targets", arrayListOf("https://owned-layout.invalid"))
                        }
                        assertTrue(directory.renameTo(retained))
                        try {
                            directory.writeText("owned-layout-fault")
                            SourceCheckDialog().apply { arguments = args }
                                .showNow(activity.supportFragmentManager, "source-recovery-layout")
                            assertFalse(args.containsKey("targets"))
                            assertTrue(args.getBoolean("targetsUnavailable"))
                        } finally {
                            args.remove("targets")
                            assertEquals("owned-layout-fault", directory.readText())
                            assertTrue(directory.delete())
                            assertTrue(retained.renameTo(directory))
                        }
                    }
                }
                hideIme()
                profile(scenario, "error-visible", profiles)
                screenshot("source-target-recovery-$kind-initial")
                compose.onNodeWithText(context.getString(R.string.search_book_key)).assertIsDisplayed()
                compose.onNodeWithText(message).assertIsDisplayed()
                report.put("initial_actions", actions())
                repeat(2) { round ->
                    scenario.recreate()
                    hideIme()
                    profile(scenario, "recreated-$round", profiles)
                    screenshot("source-target-recovery-$kind-recreated-$round")
                    compose.onNodeWithText(message).assertIsDisplayed()
                    report.put("actions_after_recreation_$round", actions())
                }
                val draft = "must-not-submit-${UUID.randomUUID()}"
                compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().performTextReplacement(draft)
                hideIme()
                compose.waitUntil(10_000) {
                    val config = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config
                    config.getOrNull(SemanticsProperties.EditableText)?.text == draft &&
                        config.getOrNull(SemanticsProperties.InputText)?.text == draft
                }
                profile(scenario, "before-confirm", profiles)
                screenshot("source-target-recovery-$kind-before-confirm")
                report.put("before_confirm_actions", actions())
                action(context.getString(R.string.ok)).performTouchInput { click() }
                compose.waitForIdle()
                compose.onNodeWithText(message).assertDoesNotExist()
                assertEquals(before, CheckSource.keyword)
                assertFalse(Debug.isChecking)
            }
            report.put("status", "passed").put("recreations", 2).put("keyword_unchanged", true)
                .put("no_check_session_started", true).put("pointer_confirm_closed_dialog", true)
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.toString())
            runCatching { screenshot("source-target-recovery-$kind-failure") }
            throw error
        } finally {
            report.put("profiles", profiles).put("action_measurements", actionMeasurements).put("ime_transitions", imeTransitions)
            File(context.cacheDir, "source-target-recovery-$kind.json").writeText(report.toString(2))
        }
    }

    @Test fun missingSnapshotKeepsRecoveryActionsReachable() = exercise("missing")
    @Test fun failedLegacyConversionKeepsRecoveryActionsReachable() = exercise("legacy")

    @Test fun legacyRecoveryDialogSurvivesRecreationWithKeyboardOpenAndBack() {
        val report = JSONObject().put("status", "running")
        val stages = JSONArray()
        val before = CheckSource.keyword
        try {
            ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val directory = File(context.noBackupFilesDir, "source-target-state")
                    val retained = File(context.noBackupFilesDir, "owned-layout-fault-${UUID.randomUUID()}")
                    val args = Bundle().apply {
                        putString("keyword", before)
                        putStringArrayList("targets", arrayListOf("https://owned-layout.invalid"))
                    }
                    if (!directory.exists()) assertTrue(directory.mkdirs())
                    assertTrue(directory.renameTo(retained))
                    try {
                        directory.writeText("owned-layout-fault")
                        SourceCheckDialog().apply { arguments = args }
                            .showNow(activity.supportFragmentManager, "source-recovery-layout")
                        assertTrue(args.getBoolean("targetsUnavailable"))
                    } finally {
                        args.remove("targets")
                        assertTrue(directory.delete())
                        assertTrue(retained.renameTo(directory))
                    }
                }
                try {
                    repeat(2) { round ->
                        compose.onNode(hasSetTextAction()).performTouchInput { click() }
                        if (!shell("dumpsys input_method").contains("mInputShown=true")) {
                            scenario.onActivity { activity ->
                                @Suppress("DEPRECATION")
                                (activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
                                    .toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, 0)
                            }
                        }
                        compose.waitUntil(10_000) { shell("dumpsys input_method").contains("mInputShown=true") }
                        stages.put(JSONObject().put("round", round).put("ime_before_recreation", true))
                        scenario.recreate()
                        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText(message).assertIsDisplayed()
                        stages.getJSONObject(round).put("dialog_before_hide", true)
                        // Force a real visible keyboard after recreation before testing Back.
                        compose.onNode(hasSetTextAction()).performTouchInput { click() }
                        compose.waitUntil(10_000) { shell("dumpsys input_method").contains("mInputShown=true") }
                        screenshot("source-target-recovery-keyboard-before-back-$round")
                        File(context.cacheDir, "source-target-recovery-keyboard-before-back-$round-window.txt")
                            .writeText(shell("dumpsys window"))
                        File(context.cacheDir, "source-target-recovery-keyboard-before-back-$round-ime.txt")
                            .writeText(shell("dumpsys input_method"))
                        hideIme()
                        compose.onNodeWithText(message).assertIsDisplayed()
                        stages.getJSONObject(round).put("dialog_after_hide", true)
                    }
                    action(context.getString(R.string.cancel)).performClick()
                    compose.onNodeWithText(message).assertDoesNotExist()
                    assertEquals(before, CheckSource.keyword)
                    assertFalse(Debug.isChecking)
                } catch (error: Throwable) {
                    runCatching { screenshot("source-target-recovery-keyboard-live-failure") }
                    runCatching { File(context.cacheDir, "source-target-recovery-keyboard-live-window.txt").writeText(shell("dumpsys window")) }
                    runCatching { File(context.cacheDir, "source-target-recovery-keyboard-live-ime.txt").writeText(shell("dumpsys input_method")) }
                    runCatching { File(context.cacheDir, "source-target-recovery-keyboard-live-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
                    throw error
                }
            }
            report.put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.toString())
            runCatching { screenshot("source-target-recovery-keyboard-failure") }
            throw error
        } finally {
            report.put("stages", stages).put("ime_transitions", imeTransitions)
            File(context.cacheDir, "source-target-recovery-keyboard.json").writeText(report.toString(2))
        }
    }

    @Test fun validKeywordKeepsEditorAndAllActionsReachable() {
        val profiles = JSONArray()
        val before = CheckSource.keyword
        val draft = "visible-${UUID.randomUUID()}"
        val report = JSONObject().put("case", "valid").put("requested_short_landscape", landscape)
            .put("scope", "Valid synthetic target snapshot; visible editor/actions and exact draft across two Activity recreations, pointer cancel, keyboard hidden")
        try {
            ActivityScenario.launch<BookSourceActivity>(Intent(context, BookSourceActivity::class.java)).use { scenario ->
                if (landscape) scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                profile(scenario, "opened", profiles)
                scenario.onActivity { activity ->
                    SourceCheckDialog.create(before, listOf("https://owned-valid-layout.invalid"), activity.taskId)
                        .showNow(activity.supportFragmentManager, "valid-source-layout")
                }
                hideIme()
                profile(scenario, "editor-visible", profiles)
                screenshot("source-target-recovery-valid-initial")
                compose.onNodeWithText(context.getString(R.string.search_book_key)).assertIsDisplayed()
                visibleEditor("opened").performTextReplacement(draft)
                repeat(2) { round ->
                    scenario.recreate()
                    hideIme()
                    profile(scenario, "recreated-$round", profiles)
                    val editor = visibleEditor("recreated-$round")
                    compose.waitUntil(10_000) {
                        val value = editor.fetchSemanticsNode().config
                        value.getOrNull(SemanticsProperties.EditableText)?.text == draft &&
                            value.getOrNull(SemanticsProperties.InputText)?.text == draft
                    }
                    val node = editor.fetchSemanticsNode()
                    assertTrue(abs(node.boundsInRoot.height - node.size.height) < 1.5f)
                    report.put("actions_after_recreation_$round", actions())
                    scenario.onActivity { activity ->
                        val dialog = activity.supportFragmentManager.findFragmentByTag("valid-source-layout")!!
                        val store = SourceTargetStateStore(File(context.noBackupFilesDir, "source-target-state"))
                        assertEquals(listOf(listOf("https://owned-valid-layout.invalid")), store.read(dialog.requireArguments().getString("targetSnapshot")!!))
                    }
                }
                profile(scenario, "before-cancel", profiles)
                report.put("before_cancel_actions", actions())
                screenshot("source-target-recovery-valid-before-confirm")
                action(context.getString(R.string.cancel)).performTouchInput { click() }
                compose.waitForIdle()
                compose.onNode(hasSetTextAction()).assertDoesNotExist()
                assertEquals(before, CheckSource.keyword)
                assertFalse(Debug.isChecking)
            }
            report.put("status", "passed").put("recreations", 2).put("draft_preserved", true)
                .put("exact_target_set_preserved", true).put("keyword_unchanged", true)
                .put("no_check_session_started", true).put("pointer_cancel_closed_dialog", true)
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.toString())
            runCatching { screenshot("source-target-recovery-valid-failure") }
            throw error
        } finally {
            report.put("profiles", profiles).put("action_measurements", actionMeasurements)
                .put("ime_transitions", imeTransitions).put("editor_transitions", editorTransitions)
            File(context.cacheDir, "source-target-recovery-valid.json").writeText(report.toString(2))
        }
    }
}
