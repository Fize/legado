package io.legado.app.ui.test

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runners.model.MultipleFailureException

/** Each cleanup runs even if an earlier screenshot, close or reset fails. Failures stay failures. */
fun runCleanupSteps(vararg steps: () -> Unit) {
    val failures = mutableListOf<Throwable>()
    steps.forEach { step -> try { step() } catch (failure: Throwable) { failures += failure } }
    MultipleFailureException.assertEmpty(failures)
}

fun captureCleanupDisplayScreenshot(automation: UiAutomation): Bitmap {
    check(InstrumentationRegistry.getArguments().getString("compatFailCleanupScreenshot") != "true") {
        "Injected cleanup screenshot failure"
    }
    return captureDisplayScreenshot(automation)
}
