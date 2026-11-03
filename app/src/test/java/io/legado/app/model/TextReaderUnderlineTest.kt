package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderUnderlineTest {
    @Test fun offZeroWidthAndIndependentTitleBodySwitches() {
        assertFalse(TextReaderUnderline().enabled(false))
        assertFalse(TextReaderUnderline(mode = 1, width = 0f).enabled(true))
        val body = TextReaderUnderline(mode = 6, title = false)
        assertTrue(body.enabled(false)); assertFalse(body.enabled(true))
        val title = body.copy(body = false, title = true)
        assertTrue(title.enabled(true)); assertFalse(title.enabled(false))
    }
    @Test fun invalidDimensionsAndUnknownModesAreRejected() {
        listOf(-1, 7).forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) { TextReaderUnderline(mode = mode) }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 11f).forEach { width ->
            assertThrows(IllegalArgumentException::class.java) { TextReaderUnderline(width = width) }
        }
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, -1f, 31f).forEach { distance ->
            assertThrows(IllegalArgumentException::class.java) { TextReaderUnderline(distance = distance) }
        }
    }
    @Test fun transparentColorIsExplicitAndCopiedIndependently() {
        val default = TextReaderUnderline(mode = 1)
        assertNull(default.color)
        val transparent = default.copy(color = 0)
        assertEquals(0, transparent.color)
        assertNull(default.color)
    }
}
