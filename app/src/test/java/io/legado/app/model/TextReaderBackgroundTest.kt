package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderBackgroundTest {
    @Test fun invalidKindsAlphaAndRevisionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TextReaderBackground(3, "invalid") }
        assertThrows(IllegalArgumentException::class.java) { TextReaderBackground(0, "#fff", -1) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderBackground(0, "#fff", 101) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderBackground(0, "#fff", revision = -1) }
    }
    @Test fun revisionAndOpacityArePartOfTheImmutableIdentity() {
        val original = TextReaderBackground(2, "content://reader/background")
        assertNotEquals(original, original.copy(revision = 1))
        assertNotEquals(original, original.copy(alpha = 50))
        assertNotEquals(original, original.copy(fallbackColor = 0xff000000.toInt()))
        assertEquals(100, original.alpha)
    }
}
