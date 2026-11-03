package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderTypographyTest {
    @Test fun invalidMetricsCannotReachTheLayout() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 3f).forEach {
            assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(letterSpacing = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(bodyWeight = 0) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(titleWeight = 1001) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(paddingLeft = -1) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(paddingRight = 257) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(lineHeight = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(titleLineHeight = 0f) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(paragraphSpacing = -1f) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(titleTopSpacing = -1) }
        assertThrows(IllegalArgumentException::class.java) { TextReaderTypography(titleBottomSpacing = 1025) }
    }
    @Test fun asymmetricPaddingAndFontIdentitiesAreIndependentValues() {
        val appearance = TextReaderTypography(bodyFont = "body.ttf", titleFont = "title.ttf", paddingLeft = 12, paddingRight = 36)
        assertEquals(12, appearance.paddingLeft); assertEquals(36, appearance.paddingRight)
        assertEquals("title.ttf", appearance.copy(bodyWeight = 700).titleFont)
        assertEquals(400, appearance.bodyWeight)
    }
}
