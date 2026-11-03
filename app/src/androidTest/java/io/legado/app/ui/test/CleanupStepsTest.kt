package io.legado.app.ui.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException

@RunWith(AndroidJUnit4::class)
class CleanupStepsTest {
    @Test fun failedCaptureAndCloseStillRestoreAndRetainBothFailures() {
        val steps = mutableListOf<String>()
        val capture = IllegalStateException("capture")
        val close = IllegalStateException("close")
        try {
            runCleanupSteps(
                { steps += "capture"; throw capture },
                { steps += "close"; throw close },
                { steps += "rotation" },
                { steps += "preferences" },
            )
            fail("Failures were swallowed")
        } catch (failure: MultipleFailureException) {
            assertEquals(listOf(capture, close), failure.failures)
        }
        assertEquals(listOf("capture", "close", "rotation", "preferences"), steps)
    }

    @Test fun aSingleFailureIsRethrownAfterRestoration() {
        val original = IllegalArgumentException("capture")
        var restored = false
        try {
            runCleanupSteps({ throw original }, { restored = true })
            fail("Failure was swallowed")
        } catch (failure: IllegalArgumentException) { assertSame(original, failure) }
        assertTrue(restored)
    }

    @Test fun successfulCleanupRunsInOrder() {
        val steps = mutableListOf<Int>()
        runCleanupSteps({ steps += 1 }, { steps += 2 })
        assertEquals(listOf(1, 2), steps)
    }
}
