package io.legado.app.ui.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import io.legado.app.help.glide.ImageLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhotoDecodeFormatsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    private fun decoded(
        format: Bitmap.CompressFormat,
        width: Int,
        height: Int,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL,
        maxEdge: Int = PHOTO_DECODE_EDGE,
        verify: (Bitmap) -> Unit,
    ) {
        val file = File.createTempFile("photo-format-", ".image", context.cacheDir)
        try {
            val input = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            try {
                val canvas = Canvas(input)
                val paint = Paint()
                colors.forEachIndexed { index, color ->
                    paint.color = color
                    val left = (index % 2) * width / 2f
                    val top = (index / 2) * height / 2f
                    canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
                }
                file.outputStream().use { assertTrue(input.compress(format, 100, it)) }
            } finally { input.recycle() }
            Log.i("PhotoDecodeFormats", "$format fixture=${width}x$height encodedBytes=${file.length()} orientation=$orientation")
            val header = file.inputStream().use { stream -> ByteArray(12).also { assertEquals(12, stream.read(it)) } }
            if (format == Bitmap.CompressFormat.JPEG) {
                assertEquals(0xff, header[0].toInt() and 0xff)
                assertEquals(0xd8, header[1].toInt() and 0xff)
                ExifInterface(file.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                assertEquals(orientation, ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            } else {
                assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
                assertEquals("WEBP", String(header, 8, 4, Charsets.US_ASCII))
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            assertEquals(width, bounds.outWidth)
            assertEquals(height, bounds.outHeight)
            // Exercise the same URI loader and sampling options as local photo-viewer requests.
            val target = ImageLoader.load(context, android.net.Uri.fromFile(file))
                .apply(photoDecodeOptions(maxEdge)).submit()
            try {
                val bitmap = (target.get(30, TimeUnit.SECONDS) as BitmapDrawable).bitmap
                assertTrue("$format/$orientation decoded ${bitmap.width}x${bitmap.height}",
                    bitmap.width in 1..maxEdge && bitmap.height in 1..maxEdge)
                // A hardware bitmap cannot be read with getPixel; copying leaves production options intact.
                val readable = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                try {
                    verify(readable)
                    Log.i("PhotoDecodeFormats", "$format source=${width}x$height orientation=$orientation decoded=${bitmap.width}x${bitmap.height} verified")
                } finally { readable.recycle() }
            } finally { Glide.with(context).clear(target) }
        } finally { assertTrue(file.delete() || !file.exists()) }
    }

    private fun assertCorners(bitmap: Bitmap, expected: List<Int>) {
        expected.forEachIndexed { index, color ->
            val actual = bitmap.getPixel(bitmap.width * (if (index % 2 == 0) 1 else 3) / 4,
                bitmap.height * (if (index / 2 == 0) 1 else 3) / 4)
            for (channel in listOf(Color::red, Color::green, Color::blue)) {
                assertTrue("Corner $index expected $color, got $actual", abs(channel(color) - channel(actual)) <= 20)
            }
        }
    }

    @Test fun jpegTallInputRespectsProductionCap() {
        decoded(Bitmap.CompressFormat.JPEG, 2048, 8192) {
            assertEquals(.25, it.width.toDouble() / it.height, .01)
            assertCorners(it, colors)
        }
    }

    @Suppress("DEPRECATION") // The same WebP encoder is available on API26 and current systems.
    @Test fun webpTallInputRespectsProductionCap() {
        decoded(Bitmap.CompressFormat.WEBP, 2048, 8192) {
            assertEquals(.25, it.width.toDouble() / it.height, .01)
            assertCorners(it, colors)
        }
    }

    @Test fun jpegExifRotationsAndMirrorsPreserveTheCornerOrder() {
        // Independent expected corner order: TL, TR, BL, BR for the eight EXIF orientations.
        val orders = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0),
            listOf(2, 3, 0, 1), listOf(0, 2, 1, 3), listOf(2, 0, 3, 1),
            listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        orders.forEachIndexed { index, order ->
            decoded(Bitmap.CompressFormat.JPEG, 256, 1024, orientation = index + 1, maxEdge = 512) {
                assertEquals(if (index < 4) .25 else 4.0, it.width.toDouble() / it.height, .01)
                assertCorners(it, order.map(colors::get))
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun smallerJpegAndWebpImagesAreNotUpscaled() {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
            decoded(format, 128, 64, maxEdge = 512) {
                assertEquals(128, it.width)
                assertEquals(64, it.height)
                assertCorners(it, colors)
            }
        }
    }
}
