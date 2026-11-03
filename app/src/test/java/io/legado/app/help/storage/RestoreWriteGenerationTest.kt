package io.legado.app.help.storage

import org.junit.Assert.*
import org.junit.Test

class RestoreWriteGenerationTest {
    @Test fun advanceInvalidatesAlreadyCapturedWork() {
        val generation = RestoreWriteGeneration()
        val old = generation.capture()
        old.requireCurrent()
        generation.advance()
        assertFalse(old.isCurrent)
        assertTrue(runCatching { old.requireCurrent() }.exceptionOrNull() is RestoreWorkInvalidatedException)
    }

    @Test fun freshWorkUsesTheNewGeneration() {
        val generation = RestoreWriteGeneration()
        generation.advance()
        val fresh = generation.capture()
        fresh.requireCurrent()
        assertTrue(fresh.isCurrent)
        assertEquals(1L, fresh.sequence)
    }

    @Test fun repeatedRestoresNeverRevalidateOldWork() {
        val generation = RestoreWriteGeneration()
        val first = generation.capture()
        generation.advance()
        val second = generation.capture()
        generation.advance()
        assertFalse(first.isCurrent)
        assertFalse(second.isCurrent)
        assertTrue(generation.capture().isCurrent)
    }

    @Test fun IndependentProcessesCannotShareIntentIdentity() {
        val first = RestoreWriteGeneration()
        val second = RestoreWriteGeneration()
        assertNotEquals(first.processId, second.processId)
        val token = second.capture()
        first.advance()
        token.requireCurrent()
    }
}
