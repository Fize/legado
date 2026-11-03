package io.legado.app.ui.config

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Confirmation must read accepted editor input even before the next UI frame. */
@RunWith(AndroidJUnit4::class)
class TextInputCurrentValueUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun confirmationReadsTheLatestAcceptedInputInTheSameUiTurn() {
        val samples = JSONArray()
        var status = "running"
        var failure: String? = null
        try {
            for (mode in listOf("below-editor", "plain", "history", "password", "multiline")) {
                val before = "before-$mode"
                val expected = "after-$mode-fresh"
                val actual = AtomicReference<String?>(null)
                val dismissed = AtomicInteger()
                ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                    scenario.onActivity { host ->
                        host.setContent {
                            MaterialTheme {
                                LegadoTextInputDialog(
                                    title = "Current input", initialValue = before,
                                    suggestions = if (mode == "history") listOf(before) else emptyList(),
                                    isPassword = mode == "password", singleLine = mode != "multiline",
                                    actionsBelowEditor = mode == "below-editor",
                                    onDismiss = { dismissed.incrementAndGet() },
                                    onConfirm = { actual.set(it) },
                                )
                            }
                        }
                    }
                    compose.waitUntil(10_000) {
                        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 &&
                            compose.onAllNodes(hasText(context.getString(R.string.ok)) and hasClickAction())
                                .fetchSemanticsNodes().size == 1
                    }
                    val edit = checkNotNull(compose.onNode(hasSetTextAction()).fetchSemanticsNode()
                        .config[SemanticsActions.SetText].action)
                    val confirm = checkNotNull(compose.onNode(hasText(context.getString(R.string.ok)) and hasClickAction())
                        .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
                    var accepted = false
                    var clicked = false
                    // No frame or synchronization point may intervene between these two events.
                    instrumentation.runOnMainSync {
                        accepted = edit(AnnotatedString(expected))
                        clicked = confirm()
                    }
                    samples.put(JSONObject().put("mode", mode).put("before", before)
                        .put("expected", expected).put("actual", actual.get())
                        .put("set_text_accepted", accepted).put("confirm_action_accepted", clicked)
                        .put("same_ui_turn", true).put("dismiss_count", dismissed.get()))
                    assertTrue(accepted)
                    assertTrue(clicked)
                    assertEquals(1, dismissed.get())
                    assertEquals("Confirmation used an earlier frame's text in $mode", expected, actual.get())
                }
            }
            status = "passed"
        } catch (error: Throwable) { status = "failed"; failure = error.toString(); throw error }
        finally {
            File(context.cacheDir, "input-current-confirm.json").writeText(JSONObject()
                .put("status", status).put("error", failure).put("api", Build.VERSION.SDK_INT)
                .put("samples", samples).toString(2))
        }
    }
}
