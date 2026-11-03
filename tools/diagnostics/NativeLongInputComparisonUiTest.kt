package io.legado.app.ui.config

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.ui.test.captureDisplayScreenshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File

/** Compare real shell-injected keys in the production dialog and a plain Compose field. */
@RunWith(AndroidJUnit4::class)
class NativeLongInputComparisonUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val samples = JSONArray()
    private val payload = """[{"bookSourceUrl":"https://formal-a-a655274bec.invalid","bookSourceName":"Sa655274bec-A","bookSourceGroup":"keep","bookSourceComment":"owned-A-a655274bec","bookSourceType":0,"customOrder":1},{"bookSourceUrl":"https://formal-b-a655274bec.invalid","bookSourceName":"Sa655274bec-B","bookSourceGroup":"keep","bookSourceComment":"owned-B-a655274bec","bookSourceType":0,"customOrder":2},{"bookSourceUrl":"https://formal-c-a655274bec.invalid","bookSourceName":"Sa655274bec-C","bookSourceGroup":"keep","bookSourceComment":"owned-C-a655274bec","bookSourceType":0,"customOrder":3}]"""
    private var failure: String? = null

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun editor() = compose.onNode(hasSetTextAction())
    private fun value(): String = editor().fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text ?: error("No editable text")

    @After fun saveDiagnostics() {
        File(context.cacheDir, "native-long-input-${testName.methodName}.json").writeText(
            JSONObject().put("test", testName.methodName).put("payload_length", payload.length)
                .put("samples", samples).put("failure", failure ?: JSONObject.NULL).toString(2),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun exercise(
        mode: String, synchronousKeys: Boolean = false, directEvents: Boolean = false,
        refreshEventTime: Boolean = false,
    ) {
        assertEquals(571, payload.length)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            try {
                scenario.onActivity { host ->
                    host.setContent {
                        MaterialTheme {
                            if (mode == "production") {
                                LegadoTextInputDialog(
                                    title = "Import online", initialValue = "", hint = "url",
                                    onDismiss = {}, onConfirm = {},
                                )
                            } else if (mode == "simple-dialog" || mode == "scroll-dialog") {
                                val state = remember { TextFieldState() }
                                BasicAlertDialog(
                                    onDismissRequest = {}, modifier = Modifier.fillMaxSize(),
                                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Surface(modifier = if (mode == "scroll-dialog") Modifier.widthIn(max = 520.dp) else Modifier) {
                                            if (mode == "scroll-dialog") {
                                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                                    OutlinedTextField(state = state, label = { Text("url") }, modifier = Modifier.fillMaxWidth())
                                                }
                                            } else {
                                                OutlinedTextField(state = state)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val state = remember { TextFieldState() }
                                OutlinedTextField(state = state, modifier = Modifier.onPreviewKeyEvent { event ->
                                    Log.i("LegadoKeyProbe", "plain ${event.type} ${event.key}")
                                    false
                                })
                            }
                        }
                    }
                }
                compose.waitForIdle()
                editor().performClick().assertIsFocused()
                compose.waitUntil(15_000) { shell("dumpsys input_method").contains("mInputShown=true") }
                for (offset in payload.indices step 96) {
                    val end = minOf(payload.length, offset + 96)
                    val expected = payload.substring(0, end)
                    val chunk = payload.substring(offset, end)
                    val started = SystemClock.elapsedRealtime()
                    Log.i("LegadoKeyProbe", "start ${testName.methodName} $offset")
                    var eventCount = 0
                    var acceptedEvents = 0
                    var firstRejectedIndex = -1
                    var firstRejectedAgeMs = -1L
                    var lastAcceptedAgeMs = -1L
                    if (directEvents) {
                        val events = checkNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                            .getEvents(chunk.toCharArray()))
                        eventCount = events.size
                        events.forEachIndexed { index, event ->
                            val sent = if (refreshEventTime) {
                                KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), event.repeatCount)
                            } else event
                            val ageMs = SystemClock.uptimeMillis() - sent.eventTime
                            if (instrumentation.uiAutomation.injectInputEvent(sent, true)) {
                                acceptedEvents++
                                lastAcceptedAgeMs = ageMs
                            } else if (firstRejectedIndex < 0) {
                                firstRejectedIndex = index
                                firstRejectedAgeMs = ageMs
                            }
                        }
                    } else if (synchronousKeys) {
                        instrumentation.sendStringSync(chunk)
                    } else {
                        // UiAutomation passes this space-free argument directly;
                        // adding shell quotes would inject the quote characters.
                        shell("input text $chunk")
                    }
                    Log.i("LegadoKeyProbe", "end ${testName.methodName} $offset")
                    var observed = value()
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (observed != expected && expected.startsWith(observed) && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(250)
                        observed = value()
                    }
                    samples.put(JSONObject().put("offset", offset).put("expected_length", end)
                        .put("observed_length", observed.length).put("prefix", expected.startsWith(observed))
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("event_count", eventCount).put("accepted_events", acceptedEvents)
                        .put("first_rejected_index", firstRejectedIndex)
                        .put("first_rejected_age_ms", firstRejectedAgeMs)
                        .put("last_accepted_age_ms", lastAcceptedAgeMs)
                        .put("event_time_refreshed", refreshEventTime))
                    if (directEvents) assertEquals("Input injection failed at offset $offset", eventCount, acceptedEvents)
                    assertEquals("Native keys missing or changed at offset $offset", expected, observed)
                }
            } catch (error: Throwable) {
                failure = error.toString()
                runCatching {
                    val bitmap = captureDisplayScreenshot(instrumentation.uiAutomation)
                    try {
                        File(context.cacheDir, "native-long-input-${testName.methodName}.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                }
                throw error
            }
        }
    }

    @Test fun plainComposeField() = exercise(mode = "field")
    @Test fun plainComposeDialog() = exercise(mode = "simple-dialog")
    @Test fun scrollComposeDialog() = exercise(mode = "scroll-dialog")
    @Test fun productionDialog() = exercise(mode = "production")
    @Test fun productionDialogSynchronousKeys() = exercise(mode = "production", synchronousKeys = true)
    @Test fun productionDialogDirectEvents() = exercise(mode = "production", directEvents = true)
    @Test fun productionDialogFreshDirectEvents() = exercise(mode = "production", directEvents = true, refreshEventTime = true)

    @Test fun generatedBatchUsesOneEventTime() {
        val events = checkNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(payload.substring(0, 96).toCharArray()))
        val times = events.map { it.eventTime }.distinct()
        samples.put(JSONObject().put("event_count", events.size).put("distinct_event_times", times.size))
        assertTrue(events.isNotEmpty())
        assertEquals(1, times.size)
    }
}
