package io.legado.app.ui.book.manga

import android.graphics.RenderNode
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import io.legado.app.model.BookCover
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Diagnostic: existing manga loader followed by the hardware canvas used by UI rendering. */
@RunWith(AndroidJUnit4::class)
class MangaLargeBitmapReproTest {
    @Test fun longImageTilesDrawAtOriginalResolutionWithoutExceedingCanvasLimit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("manga-tiles-", ".png", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("manga-repro-long.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        var count = 0
        var maxBytes = 0
        var covered = 0L
        try {
            MangaTileSource(file).use { source ->
                assertEquals(2400, source.width); assertEquals(20000, source.height)
                for (y in 0 until source.height step 512) for (x in 0 until source.width step 512) {
                    val rect = android.graphics.Rect(x, y, minOf(x + 512, source.width), minOf(y + 512, source.height))
                    val bitmap = source.decode(rect, 1)
                    assertEquals(rect.width(), bitmap.width); assertEquals(rect.height(), bitmap.height)
                    assertEquals(android.graphics.Color.rgb(235,235,235), bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
                    maxBytes = maxOf(maxBytes, bitmap.byteCount)
                    covered += bitmap.width.toLong() * bitmap.height
                    instrumentation.runOnMainSync {
                        val node = RenderNode("bounded-manga-tile")
                        val canvas = node.beginRecording(512,512)
                        try { canvas.drawBitmap(bitmap,0f,0f,null) }
                        finally { node.endRecording(); node.discardDisplayList() }
                    }
                    bitmap.recycle(); count++
                }
            }
            assertEquals(48000000L, covered)
            assertTrue(maxBytes <= 1024 * 1024)
            Log.i("MangaLargeBitmapRepro", "FIXED tiles=$count maxTileBytes=$maxBytes originalPixels=$covered allDraws=OK")
        } finally { file.delete() }
    }

    @Test fun tilesPreservePixelDetailAndDecoderCanReopenAfterClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("manga-detail-", ".png", context.cacheDir)
        val original = android.graphics.Bitmap.createBitmap(1031, 1027, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until original.height) for (x in 0 until original.width)
            original.setPixel(x,y,android.graphics.Color.rgb(x % 256,y % 256,(x + y) % 256))
        file.outputStream().use { original.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        try {
            repeat(2) {
                val source = MangaTileSource(file)
                for (y in 0 until original.height step 512) for (x in 0 until original.width step 512) {
                    val rect = android.graphics.Rect(x,y,minOf(x+512,original.width),minOf(y+512,original.height))
                    val tile = source.decode(rect,1)
                    for (dy in 0 until tile.height) for (dx in 0 until tile.width)
                        assertEquals(original.getPixel(x+dx,y+dy), tile.getPixel(dx,dy))
                    tile.recycle()
                }
                source.close()
                assertTrue(runCatching { source.decode(android.graphics.Rect(0,0,1,1),1) }.isFailure)
            }
        } finally { original.recycle(); file.delete() }
    }

    @Test fun unsupportedRasterVectorAndDataUrlRemainReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (asset in listOf("manga-compat.gif", "manga-compat.svg")) {
            val file = File.createTempFile("manga-compat-", ".img", context.cacheDir)
            try {
                instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                MangaTileSource(file).use { source ->
                    assertEquals(16, source.width); assertEquals(24, source.height)
                    val tile = source.decode(android.graphics.Rect(0,0,16,24), 1)
                    assertEquals(android.graphics.Color.rgb(235,235,235), tile.getPixel(8,12))
                    tile.recycle()
                }
            } finally { file.delete() }
        }
        val bytes = instrumentation.context.assets.open("manga-repro-normal.png").use { it.readBytes() }
        val path = "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val target = BookCover.preloadManga(context, path).submit()
        try {
            MangaTileSource(target.get(30,TimeUnit.SECONDS)).use { source ->
                assertEquals(1200, source.width); assertEquals(1800, source.height)
                val bitmap = source.decode(android.graphics.Rect(0,0,16,24),1)
                assertEquals(android.graphics.Color.rgb(235,235,235), bitmap.getPixel(8,12))
                bitmap.recycle()
            }
        } finally { Glide.with(context).clear(target) }
    }

    @Test fun remoteImageDownloadsThroughTheSameTileSourcePath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("manga-repro-normal.png").use { it.readBytes() }
        val server = java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 15000
        val worker = kotlin.concurrent.thread(name = "manga-image-fixture") {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 15000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        val target = BookCover.preloadManga(context,"http://127.0.0.1:${server.localPort}/manga.png").submit()
        try {
            MangaTileSource(target.get(20,TimeUnit.SECONDS)).use { source ->
                assertEquals(1200,source.width); assertEquals(1800,source.height)
                val tile = source.decode(android.graphics.Rect(0,0,512,512),1)
                assertEquals(android.graphics.Color.rgb(235,235,235),tile.getPixel(256,256)); tile.recycle()
            }
        } finally { Glide.with(context).clear(target); server.close(); worker.join(1000) }
    }

    @Test fun jpegOrientationAndTileFiltersRemainCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = android.graphics.Bitmap.createBitmap(80,40,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(original)
        canvas.drawColor(android.graphics.Color.BLUE)
        canvas.drawRect(0f,0f,40f,40f,android.graphics.Paint().apply { color = android.graphics.Color.RED })
        try {
            for (orientation in listOf(2,6,8)) {
                val file = File.createTempFile("manga-exif-", ".jpg", context.cacheDir)
                try {
                    file.outputStream().use { original.compress(android.graphics.Bitmap.CompressFormat.JPEG,100,it) }
                    android.media.ExifInterface(file.path).apply {
                        setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                    }
                    MangaTileSource(file).use { source ->
                        assertEquals(if(orientation == 2) 80 else 40, source.width)
                        assertEquals(if(orientation == 2) 40 else 80, source.height)
                        val rect = android.graphics.Rect(0,0,source.width,source.height)
                        val bitmap = source.decode(rect,1)
                        val topLeft = bitmap.getPixel(8,8)
                        assertTrue(if (orientation == 6) android.graphics.Color.red(topLeft) > 240 else android.graphics.Color.blue(topLeft) > 240)
                        bitmap.recycle()
                        val gray = source.decode(rect,1,gray=true)
                        val c = gray.getPixel(8,8)
                        assertEquals(android.graphics.Color.red(c), android.graphics.Color.green(c)); gray.recycle()
                        val ink = source.decode(rect,1,ink=true)
                        assertEquals(android.graphics.Color.BLACK,ink.getPixel(8,8)); ink.recycle()
                    }
                } finally { file.delete() }
            }
        } finally { original.recycle() }
    }

    @Test fun compareNormalAndLongImageDrawing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for ((asset, expectFailure) in listOf("manga-repro-normal.png" to false, "manga-repro-long.png" to true)) {
            val file = File.createTempFile("manga-repro-", ".png", context.cacheDir)
            instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
            val target = BookCover.loadManga(context, file.absolutePath).submit()
            try {
                val bitmap = target.get(30, TimeUnit.SECONDS).toBitmap()
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    val node = RenderNode("manga-long-image-reproduction")
                    val canvas = node.beginRecording(1200, 1800)
                    try { canvas.drawBitmap(bitmap, 0f, 0f, null) }
                    catch (error: Throwable) { failure = error }
                    finally { node.endRecording(); node.discardDisplayList() }
                }
                val details = "$asset fileBytes=${file.length()} decoded=${bitmap.width}x${bitmap.height} bytes=${bitmap.byteCount} config=${bitmap.config} screenWidth=${context.resources.displayMetrics.widthPixels} result=${failure?.message ?: "DRAW_OK"}"
                Log.i("MangaLargeBitmapRepro", details)
                failure?.let { Log.i("MangaLargeBitmapRepro", "Draw failure stack", it) }
                if (expectFailure) assertTrue(details, failure?.message?.contains("trying to draw too large") == true)
                else assertNull(details, failure)
            } finally {
                Glide.with(context).clear(target)
                file.delete()
            }
        }
    }
}
