package io.legado.app.ui.config

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.utils.ACache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.inputmethod.InputMethodManager

/** Production component with the same mutable history contract as online source import. */
@RunWith(AndroidJUnit4::class)
class InputSuggestionDraftUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val draftChecks = JSONArray()
    private val nativeKeyTrace = JSONArray()
    private val historyUrl = "https://history.invalid/source.json"
    private fun editor() = compose.onNode(hasSetTextAction())
    private fun SemanticsNodeInteraction.assertDraft(value: String): SemanticsNodeInteraction {
        fun record(stage: String) {
            val config = fetchSemanticsNode().config
            val editable = config.getOrNull(SemanticsProperties.EditableText)
            val input = config.getOrNull(SemanticsProperties.InputText)
            draftChecks.put(JSONObject().put("stage", stage).put("expected", value)
                .put("editable_text", editable?.text).put("input_text", input?.text)
                .put("editable_equal", editable?.text == value).put("input_equal", input?.text == value)
                .put("annotated_equal", editable == AnnotatedString(value))
                .put("focused", config.getOrNull(SemanticsProperties.Focused))
                .put("uptime_ms", SystemClock.uptimeMillis())
                .put("span_count", editable?.spanStyles?.size)
                .put("paragraph_count", editable?.paragraphStyles?.size))
        }
        record("before-settle")
        try {
            compose.waitUntil(5_000) {
                runCatching {
                    val config = fetchSemanticsNode().config
                    config.getOrNull(SemanticsProperties.EditableText)?.text == value &&
                        config.getOrNull(SemanticsProperties.InputText)?.text == value
                }.getOrDefault(false)
            }
        } catch (failure: ComposeTimeoutException) {
            // Keep the original timeout even if the editor disappeared before sampling.
            runCatching { record("settle-timeout") }
                .onFailure { failure.addSuppressed(it) }
            runCatching { captureInputFailure() }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        }
        record("settled")
        return assert(SemanticsMatcher("EditableText and InputText equal the exact draft characters") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == value &&
                node.config.getOrNull(SemanticsProperties.InputText)?.text == value
        })
    }

    private fun captureInputFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = context.getSystemService(InputMethodManager::class.java)
        draftChecks.put(JSONObject().put("stage", "system-after-timeout")
            .put("uptime_ms", SystemClock.uptimeMillis())
            .put("ime_accepting_text", input.isAcceptingText)
            .put("ime_active", input.isActive)
            .put("active_window_package", instrumentation.uiAutomation.rootInActiveWindow?.packageName))
        for (service in listOf("input_method", "window")) {
            val command = instrumentation.uiAutomation.executeShellCommand("dumpsys $service")
            val text = ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readText() }
            File(context.cacheDir, "input-suggestion-${testName.methodName}-failure-$service.txt").writeText(text)
        }
    }

    @After fun saveDraftDiagnostics() {
        File(context.cacheDir, "input-suggestion-${testName.methodName}.json").writeText(
            JSONObject().put("case", testName.methodName).put("checks", draftChecks)
                .put("native_key_trace", nativeKeyTrace).toString(2),
        )
    }

    private fun traceNativeKey(stage: String) {
        val wallTime = System.currentTimeMillis()
        val uptime = SystemClock.uptimeMillis()
        nativeKeyTrace.put(JSONObject().put("stage", stage).put("wall_time_ms", wallTime)
            .put("uptime_ms", uptime).put("pid", android.os.Process.myPid()))
        Log.i("OnlineImportKeyTrace", "${testName.methodName} $stage wall=$wallTime uptime=$uptime")
    }
    private fun action(id: Int) = compose.onNode(hasText(context.getString(id)) and hasClickAction())

    private fun show(history: MutableList<String>, uriKeyboard: Boolean = false, body: (() -> String?, () -> Boolean) -> Unit) {
        var confirmed: String? = null
        var dismissed = false
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        LegadoTextInputDialog(
                            title = "Import URL", initialValue = "", hint = "url",
                            keyboardOptions = if (uriKeyboard) KeyboardOptions(
                                keyboardType = KeyboardType.Uri, autoCorrectEnabled = false
                            ) else null,
                            suggestions = history,
                            onDeleteSuggestion = { history.remove(it) },
                            onDismiss = { dismissed = true },
                            onConfirm = { confirmed = it },
                        )
                    }
                }
            }
            compose.waitForIdle()
            body({ confirmed }, { dismissed })
        }
    }

    @Test fun nativeRapidUriInputWithInitialHistory() {
        show(mutableListOf(historyUrl), uriKeyboard = true) { _, _ ->
            editor().performClick()
            traceNativeKey("history-http-before")
            InstrumentationRegistry.getInstrumentation().sendStringSync("http")
            traceNativeKey("history-http-after")
            editor().assertDraft("http")
        }
    }

    @Test fun nativeRapidUriInputWithoutInitialHistory() {
        show(mutableListOf(), uriKeyboard = true) { _, _ ->
            editor().performClick()
            traceNativeKey("empty-http-before")
            InstrumentationRegistry.getInstrumentation().sendStringSync("http")
            traceNativeKey("empty-http-after")
            editor().assertDraft("http")
        }
    }

    @Test fun deletingLastMutableHistoryThenTypingKeepsTheEntireDraftAndConfirmation() {
        val history = mutableListOf(historyUrl)
        show(history) { confirmed, dismissed ->
            editor().performTextInput("https://")
            editor().assertDraft("https://")
            compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
            assertTrue(history.isEmpty())
            editor().performTextInput("draft.invalid/new.json")
            editor().assertDraft("https://draft.invalid/new.json")
            action(R.string.ok).performClick()
            compose.runOnIdle {
                assertEquals("https://draft.invalid/new.json", confirmed())
                assertTrue(dismissed())
            }
        }
    }

    @Test fun deletingLastObservableHistoryKeepsDraftBeforeAnotherKeystroke() {
        val history = mutableStateListOf(historyUrl)
        show(history) { confirmed, _ ->
            editor().performTextInput("https://")
            editor().assertDraft("https://").assertIsFocused()
            compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
            editor().assertDraft("https://")
            editor().assertIsFocused()
            compose.onNodeWithText(historyUrl).assertDoesNotExist()
            action(R.string.ok).performClick()
            compose.runOnIdle { assertEquals("https://", confirmed()) }
        }
    }

    @Test fun nativeKeysContinueInTheDraftAfterDeletingTheLastMutableHistory() {
        val history = mutableListOf(historyUrl)
        show(history) { confirmed, _ ->
            editor().performTextInput("https://")
            editor().assertDraft("https://").assertIsFocused()
            compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
            InstrumentationRegistry.getInstrumentation().sendStringSync("draft.invalid/new.json")
            editor().assertDraft("https://draft.invalid/new.json").assertIsFocused()
            action(R.string.ok).performClick()
            compose.runOnIdle { assertEquals("https://draft.invalid/new.json", confirmed()) }
        }
    }

    @Test fun addingAndSelectingHistoryKeepsOneDraftAcrossEditorModes() {
        val history = mutableStateListOf<String>()
        show(history) { confirmed, dismissed ->
            editor().performTextInput("https://")
            compose.runOnIdle { history.add(historyUrl) }
            editor().assertDraft("https://")
            compose.onNodeWithText(historyUrl).performClick()
            editor().assertDraft(historyUrl)
            compose.runOnIdle { history.clear() }
            editor().assertDraft(historyUrl)
            action(R.string.cancel).performClick()
            compose.runOnIdle {
                assertEquals(null, confirmed())
                assertTrue(dismissed())
            }
        }
    }

    @Test fun productionOnlineImportRemovesHistoryImmediatelyAndKeepsNativeTypingAndCancel() {
        val cache = ACache.get(cacheDir = false)
        val key = "bookSourceRecordKey"
        val productionHistoryUrl = "http://history.invalid/source.json"
        val saved = cache.getAsString(key)
        cache.put(key, productionHistoryUrl)
        try {
            ActivityScenario.launch(BookSourceActivity::class.java).use {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("legadoTopAppBarOverflow").fetchSemanticsNodes().size == 1
                }
                fun openImport() {
                    compose.onNodeWithTag("legadoTopAppBarOverflow").performClick()
                    compose.onNodeWithText(context.getString(R.string.import_on_line)).performClick()
                }
                openImport()
                editor().performClick()
                // Native colon input previously let the IME change http to https.
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                traceNativeKey("first-http-before")
                instrumentation.sendStringSync("http")
                traceNativeKey("first-http-after")
                editor().assertDraft("http").assertIsFocused()
                instrumentation.sendStringSync(":")
                editor().assertDraft("http:").assertIsFocused()
                instrumentation.sendStringSync("//")
                editor().assertDraft("http://").assertIsFocused()
                compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
                compose.onNodeWithText(productionHistoryUrl).assertDoesNotExist()
                editor().assertDraft("http://").assertIsFocused()
                InstrumentationRegistry.getInstrumentation().sendStringSync("draft.invalid/new.json")
                editor().assertDraft("http://draft.invalid/new.json")
                assertEquals("", cache.getAsString(key))
                action(R.string.cancel).performClick()
                openImport()
                editor().assertDraft("")
                compose.onNodeWithText(productionHistoryUrl).assertDoesNotExist()
                editor().performClick()
                traceNativeKey("second-http-before")
                instrumentation.sendStringSync("http")
                traceNativeKey("second-http-after")
                editor().assertDraft("http")
                instrumentation.sendStringSync("://127.0.0.1:53800/source.json")
                editor().assertDraft("http://127.0.0.1:53800/source.json")
                action(R.string.cancel).performClick()
            }
        } finally {
            if (saved == null) cache.remove(key) else cache.put(key, saved)
            assertEquals(saved, cache.getAsString(key))
        }
    }
}
