package io.legado.app.ui.book.read.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.NinePatchDrawable
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.TextReaderBackground
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextReaderBackgroundTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun file() = File(context.cacheDir, "reader-background-${UUID.randomUUID()}.png")
    private fun png(file: File, width: Int = 32, height: Int = 16) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(0xff00cc22.toInt()); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    @Test fun aColorRetainsItsArgbAndAppliesTheConfiguredOpacity() {
        listOf(1, 25, 50, 75, 100).forEach { opacity ->
            val color = textReaderBackgroundColor(TextReaderBackground(0, "#00cc22", opacity))!!.toArgb()
            assertEquals((opacity * 255 + 50) / 100, color ushr 24)
            assertEquals(0x00cc22, color and 0xffffff)
        }
    }
    @Test fun sourceColorAlphaAndConfiguredOpacityAreCompositedOnlyOnce() {
        val color = textReaderBackgroundColor(TextReaderBackground(0, "#8000cc22", 25))!!.toArgb()
        assertEquals(32, color ushr 24)
    }
    @Test fun aFileIsDecodedIntoAnOwnedDrawable() = runBlocking {
        val file = file()
        try {
            png(file)
            val first = loadTextReaderBackground(context, TextReaderBackground(2, file.path), 100, 100) as BitmapDrawable
            val second = loadTextReaderBackground(context, TextReaderBackground(2, file.path), 100, 100) as BitmapDrawable
            assertNotSame(first, second); assertNotSame(first.bitmap, second.bitmap)
            first.alpha = 0
            assertEquals(255, second.alpha); assertFalse(second.bitmap.isRecycled)
        } finally { file.delete() }
    }
    @Test fun contentAndFileUrisCanLoadTheSameLocalImage() = runBlocking {
        val file = file()
        try {
            png(file)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileProvider", file)
            val content = loadTextReaderBackground(context, TextReaderBackground(2, uri.toString()), 100, 100) as BitmapDrawable
            val local = loadTextReaderBackground(context, TextReaderBackground(2, file.toURI().toString()), 100, 100) as BitmapDrawable
            assertEquals(0xff00cc22.toInt(), content.bitmap.getPixel(0, 0))
            assertEquals(content.bitmap.getPixel(0, 0), local.bitmap.getPixel(0, 0))
        } finally { file.delete() }
    }
    @Test fun bundledBackgroundAssetsUseTheAssetPath() = runBlocking {
        val name = context.assets.list("bg")!!.first { it.endsWith(".jpg") }
        assertNotNull(loadTextReaderBackground(context, TextReaderBackground(1, name), 200, 100))
    }
    @Test fun oversizedImagesAreSampledBeforeAllocatingTheirPixels() = runBlocking {
        val file = file()
        try {
            png(file, 3000, 3000)
            val drawable = loadTextReaderBackground(context, TextReaderBackground(2, file.path), 1000, 1000) as BitmapDrawable
            assertTrue(drawable.bitmap.width.toLong() * drawable.bitmap.height <= 4L * 1024 * 1024)
            assertTrue(drawable.bitmap.width < 3000)
        } finally { file.delete() }
    }
    @Test fun aCompiledNinePatchRetainsItsStretchChunkWhenDrawn() = runBlocking {
        val file = file()
        val canvasBitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            // 8 x 8 green content, compiled with SDK 36 aapt2 from a marked 10 x 10 PNG.
            InstrumentationRegistry.getInstrumentation().context.assets.open("reader_background_fixture.9.png").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val drawable = loadTextReaderBackground(context, TextReaderBackground(2, file.path), 200, 100)
            assertTrue(drawable is NinePatchDrawable)
            drawable!!.setBounds(0, 0, 200, 100); drawable.draw(Canvas(canvasBitmap))
            assertEquals(0xff00cc22.toInt(), canvasBitmap.getPixel(100, 50))
        } finally { canvasBitmap.recycle(); file.delete() }
    }
    @Test fun unavailableCorruptAndInvalidSourcesFallBackWithoutAReaderError() = runBlocking {
        val file = file()
        try {
            file.writeText("not an image")
            listOf(TextReaderBackground(2, file.path), TextReaderBackground(2, file.path + "-missing"),
                TextReaderBackground(2, "content://unavailable.reader/background"))
                .forEach { assertNull(loadTextReaderBackground(context, it, 100, 100)) }
            assertNull(textReaderBackgroundColor(TextReaderBackground(0, "invalid-color")))
        } finally { file.delete() }
    }
    @Test fun transparentAndUnmeasuredBackgroundsDoNotAllocateADrawable() = runBlocking {
        assertNull(loadTextReaderBackground(context, TextReaderBackground(2, "content://should-not-open/background", 0), 100, 100))
        assertNull(loadTextReaderBackground(context, TextReaderBackground(2, "content://should-not-open/background"), 0, 100))
    }
}
