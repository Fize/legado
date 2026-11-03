package io.legado.app.ui.book.read.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.TextReaderTypography
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextReaderFontsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unreadableFontFallsBackToTheSelectedSystemFamily() = runBlocking {
        val fallback = loadTextReaderFonts(context, TextReaderTypography(systemFont = 1))
        val missing = loadTextReaderFonts(context, TextReaderTypography(systemFont = 1,
            bodyFont = "/missing-${UUID.randomUUID()}.ttf", titleFont = "content://unavailable.reader/font"))
        assertEquals(fallback, missing)
    }
    @Test fun anOwnedFontFileCanSupplyOnlyTheTitleOrBothFamilies() = runBlocking {
        val file = File(context.cacheDir, "reader-font-${UUID.randomUUID()}.ttf")
        try {
            context.assets.open("font/number.ttf").use { input -> file.outputStream().use { input.copyTo(it) } }
            val separate = loadTextReaderFonts(context, TextReaderTypography(titleFont = file.path))
            assertNotEquals(separate.body, separate.title)
            val shared = loadTextReaderFonts(context, TextReaderTypography(bodyFont = file.path, titleFont = file.path))
            assertEquals(shared.body, shared.title)
        } finally { file.delete() }
    }
    @Test fun aReadableContentUriLoadsItsFontThroughTheResolver() = runBlocking {
        val file = File(context.cacheDir, "reader-uri-font-${UUID.randomUUID()}.ttf")
        try {
            context.assets.open("font/number.ttf").use { input -> file.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileProvider", file).toString()
            val loaded = loadTextReaderFonts(context, TextReaderTypography(bodyFont = uri, titleFont = uri))
            assertNotEquals(loadTextReaderFonts(context, TextReaderTypography()).body, loaded.body)
            assertSame(loaded.body, loaded.title)
        } finally { file.delete() }
    }
}
