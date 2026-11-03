package io.legado.app.ui.photo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.help.glide.ImageLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoDecodeBoundsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun decoded(width: Int, height: Int, options: RequestOptions): Pair<Int, Int> {
        val file = File.createTempFile("photo-decode-bounds-", ".png", context.cacheDir)
        try {
            val input = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            try {
                input.eraseColor(Color.rgb(35, 80, 230))
                file.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { input.recycle() }
            val target = ImageLoader.load(context, file).apply(options).submit()
            try {
                val bitmap = (target.get(30, TimeUnit.SECONDS) as BitmapDrawable).bitmap
                return bitmap.width to bitmap.height
            } finally { Glide.with(context).clear(target) }
        } finally { assertTrue(file.delete() || !file.exists()) }
    }

    @Test fun legacyDefaultCanExceedTheRequestedSquareForATallImage() {
        val (width,height) = decoded(256, 1024, RequestOptions().override(512,512).dontTransform())
        assertTrue("Expected the old default's aspect-ratio overflow: $width x $height", height > 512)
    }

    @Test fun portraitAndLandscapeStayInsideBothRequestedEdges() {
        for ((sourceWidth,sourceHeight) in listOf(256 to 1024, 1024 to 256)) {
            val (width,height) = decoded(sourceWidth,sourceHeight,photoDecodeOptions(512))
            assertTrue(width in 1..512 && height in 1..512)
            assertEquals(sourceWidth.toDouble()/sourceHeight, width.toDouble()/height, .01)
        }
    }

    @Test fun smallerImageIsNotEnlarged() {
        assertEquals(128 to 64, decoded(128,64,photoDecodeOptions(512)))
    }

    @Test fun productionCapBoundsTheActual8192PixelTallInput() {
        val (width,height) = decoded(2048,8192,photoDecodeOptions())
        assertTrue("Decoded image exceeded the production cap: $width x $height",
            width in 1..PHOTO_DECODE_EDGE && height in 1..PHOTO_DECODE_EDGE)
        assertEquals(.25,width.toDouble()/height,.01)
    }
}
