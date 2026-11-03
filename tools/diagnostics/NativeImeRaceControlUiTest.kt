package io.legado.app.ui.config

import android.app.AlertDialog
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Diagnostic comparison only; not a replacement for the production Compose input test. */
@RunWith(AndroidJUnit4::class)
class NativeImeRaceControlUiTest {
    @Test fun rapidNativeUriInput() = exercise(waitForActiveConnection = false)

    @Test fun readyNativeUriInput() = exercise(waitForActiveConnection = true)

    private fun exercise(waitForActiveConnection: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val editor = AtomicReference<EditText>()
        val dialog = AtomicReference<AlertDialog>()
        var actual = ""
        var focused = false
        var before = 0L
        var after = 0L
        var activeBeforeInput = false
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                scenario.onActivity { activity ->
                    val field = EditText(activity).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        imeOptions = EditorInfo.IME_ACTION_DONE
                    }
                    val window = AlertDialog.Builder(activity).setTitle("Import URL").setView(field)
                        .setNegativeButton("Cancel", null).create()
                    window.show()
                    field.requestFocus()
                    window.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    editor.set(field)
                    dialog.set(window)
                }
                if (waitForActiveConnection) {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    val deadline = SystemClock.uptimeMillis() + 5000
                    do {
                        instrumentation.runOnMainSync {
                            activeBeforeInput = editor.get().hasWindowFocus() && editor.get().hasFocus() &&
                                imm.isActive(editor.get()) && imm.isAcceptingText
                        }
                        if (activeBeforeInput) break
                        SystemClock.sleep(10)
                    } while (SystemClock.uptimeMillis() < deadline)
                    assertTrue("Native input connection did not become active", activeBeforeInput)
                }
                before = System.currentTimeMillis()
                instrumentation.sendStringSync("http")
                after = System.currentTimeMillis()
                val deadline = SystemClock.uptimeMillis() + 5000
                do {
                    instrumentation.runOnMainSync {
                        actual = editor.get().text.toString()
                        focused = editor.get().hasFocus()
                    }
                    if (actual == "http") break
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                assertEquals("http", actual)
            }
        } finally {
            instrumentation.runOnMainSync { dialog.get()?.dismiss() }
            File(context.cacheDir, "native-ime-race-control.json").writeText(
                JSONObject().put("expected", "http").put("actual", actual).put("focused", focused)
                    .put("wait_for_active_connection", waitForActiveConnection)
                    .put("active_before_input", activeBeforeInput)
                    .put("before_wall_ms", before).put("after_wall_ms", after).toString(2)
            )
        }
    }
}
