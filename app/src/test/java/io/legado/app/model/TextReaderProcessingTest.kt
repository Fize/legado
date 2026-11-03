package io.legado.app.model

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.*
import org.junit.Test

class TextReaderProcessingTest {
    @Test fun processingOptionsAreImmutableAndConversionModesAreValidated() {
        val first = TextReaderProcessing("  ", true, 1, true)
        assertEquals("  ", first.copy(chineseConverterType = 2).paragraphIndent)
        assertEquals(1, first.chineseConverterType)
        listOf(-1, 3).forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) { TextReaderProcessing(chineseConverterType = mode) }
        }
    }
    @Test fun disablingTitleConversionDoesNotReadAndroidConfigurationAndTheScriptSignatureIsPreserved() {
        val chapter = BookChapter(bookUrl = "book", url = "chapter", title = "漢語")
        assertEquals("漢語", chapter.getDisplayTitle(chineseConvert = false))
        assertEquals("漢語", chapter.getDisplayTitleWithConversion(chineseConvert = false, chineseConverterType = 1))
        assertTrue(BookChapter::class.java.methods.any { it.name == "getDisplayTitle" && it.parameterCount == 4 })
    }
}
