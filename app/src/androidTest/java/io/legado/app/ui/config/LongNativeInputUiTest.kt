package io.legado.app.ui.config

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Long native key input and confirmation must retain every character. */
@RunWith(AndroidJUnit4::class)
class LongNativeInputUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val samples = JSONArray()
    private val payload = """[{"bookSourceUrl":"https://formal-a-a655274bec.invalid","bookSourceName":"Sa655274bec-A","bookSourceGroup":"keep","bookSourceComment":"owned-A-a655274bec","bookSourceType":0,"customOrder":1},{"bookSourceUrl":"https://formal-b-a655274bec.invalid","bookSourceName":"Sa655274bec-B","bookSourceGroup":"keep","bookSourceComment":"owned-B-a655274bec","bookSourceType":0,"customOrder":2},{"bookSourceUrl":"https://formal-c-a655274bec.invalid","bookSourceName":"Sa655274bec-C","bookSourceGroup":"keep","bookSourceComment":"owned-C-a655274bec","bookSourceType":0,"customOrder":3}]"""
    private var status = "running"
    private var error: String? = null
    private var keyboardVisible = false
    @Volatile private var confirmed: String? = null
    @Volatile private var dismissed = false

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun editor() = compose.onNode(hasSetTextAction())
    private fun value(): String = editor().fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text ?: error("No editable text")

    @After fun saveDiagnostics() {
        File(context.cacheDir, "long-native-input.json").writeText(
            JSONObject().put("status", status).put("error", error ?: JSONObject.NULL)
                .put("api", Build.VERSION.SDK_INT).put("payload", payload)
                .put("keyboard_visible_before_input", keyboardVisible)
                .put("native_input_method", "instrumentation_send_string_sync")
                .put("samples", samples).put("confirmed", confirmed ?: JSONObject.NULL)
                .put("dismissed", dismissed).toString(2),
        )
    }

    @Test fun longNativeInputKeepsEveryCharacterThroughConfirmation() {
        assertEquals(571, payload.length)
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                scenario.onActivity { host ->
                    host.setContent {
                        MaterialTheme {
                            var open by remember { mutableStateOf(true) }
                            if (open) {
                                LegadoTextInputDialog(
                                    title = "Import online", initialValue = "", hint = "url",
                                    onDismiss = { dismissed = true; open = false }, onConfirm = { confirmed = it },
                                )
                            }
                        }
                    }
                }
                compose.waitForIdle()
                editor().performClick().assertIsFocused()
                var stableSince = SystemClock.elapsedRealtime()
                compose.waitUntil(15_000) {
                    val visible = shell("dumpsys input_method").contains("mInputShown=true")
                    if (!visible) stableSince = SystemClock.elapsedRealtime()
                    visible && SystemClock.elapsedRealtime() - stableSince >= 400
                }
                keyboardVisible = true
                for (offset in payload.indices step 96) {
                    val end = minOf(payload.length, offset + 96)
                    val expected = payload.substring(0, end)
                    instrumentation.sendStringSync(payload.substring(offset, end))
                    try {
                        compose.waitUntil(10_000) { runCatching { value() == expected }.getOrDefault(false) }
                    } finally {
                        samples.put(JSONObject().put("offset", offset).put("expected", expected)
                            .put("observed", runCatching { value() }.getOrDefault("")))
                    }
                    assertEquals(expected, value())
                }
                compose.onNode(hasText("OK") and hasClickAction()).performTouchInput { click() }
                compose.waitUntil(10_000) { confirmed != null && dismissed }
                assertEquals(payload, confirmed)
            }
            status = "passed"
        } catch (failure: Throwable) {
            status = "failed"
            error = failure.toString()
            throw failure
        }
    }
}
