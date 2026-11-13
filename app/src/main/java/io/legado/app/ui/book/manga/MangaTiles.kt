package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.media.ExifInterface
import io.legado.app.utils.SvgUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.bumptech.glide.Glide
import io.legado.app.model.BookCover
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

/** Region decoder is owned by one page; all decode/close calls are serialized. */
internal class MangaTileSource(file: File) : AutoCloseable {
    @Suppress("DEPRECATION")
    private val decoder = runCatching { BitmapRegionDecoder.newInstance(file.path, false) }.getOrNull()
    // Formats without region decoding still need a bounded first frame (as in the old Drawable path).
    private val fallback: Bitmap? = if (decoder == null) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            }))
        } else null
    } else null
    private val vector = if (decoder == null && fallback == null)
        checkNotNull(file.inputStream().use(SvgUtils::createDrawable)) else null
    private val rawWidth = decoder?.width ?: fallback?.width ?: checkNotNull(vector).second.width
    private val rawHeight = decoder?.height ?: fallback?.height ?: checkNotNull(vector).second.height
    private val orientation = if (decoder != null) runCatching {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    }.getOrDefault(1) else 1
    val width = if (orientation in 5..8) rawHeight else rawWidth
    val height = if (orientation in 5..8) rawWidth else rawHeight
    private val transform = Matrix().apply {
        val w = rawWidth.toFloat(); val h = rawHeight.toFloat()
        val values = when (orientation) {
            2 -> floatArrayOf(-1f,0f,w, 0f,1f,0f, 0f,0f,1f)
            3 -> floatArrayOf(-1f,0f,w, 0f,-1f,h, 0f,0f,1f)
            4 -> floatArrayOf(1f,0f,0f, 0f,-1f,h, 0f,0f,1f)
            5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
            6 -> floatArrayOf(0f,-1f,h, 1f,0f,0f, 0f,0f,1f)
            7 -> floatArrayOf(0f,-1f,h, -1f,0f,w, 0f,0f,1f)
            8 -> floatArrayOf(0f,1f,0f, -1f,0f,w, 0f,0f,1f)
            else -> floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        }
        setValues(values)
    }
    private val inverse = Matrix().also { check(transform.invert(it)) }
    private var closed = false

    @Synchronized fun decode(rect: Rect, sample: Int, gray: Boolean = false, ink: Boolean = false, threshold: Int = 150): Bitmap {
        check(!closed)
        require(sample > 0 && rect.width() <= 512 * sample && rect.height() <= 512 * sample)
        val raw = RectF(rect).also(inverse::mapRect).let {
            Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
        }
        var bitmap = if (decoder != null) {
            checkNotNull(decoder.decodeRegion(raw, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = gray || ink
            }))
        } else {
            Bitmap.createBitmap(ceil(rect.width().toDouble() / sample).toInt(),
                ceil(rect.height().toDouble() / sample).toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.scale(1f / sample, 1f / sample)
                canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
                if (fallback != null) canvas.drawBitmap(fallback, 0f, 0f, null)
                else checkNotNull(vector).first.apply { setBounds(0, 0, width, height); draw(canvas) }
            }
        }
        if (orientation in 2..8) {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, false)
            if (oriented !== bitmap) bitmap.recycle()
            bitmap = oriented
        }
        if (gray || ink) {
            if (!bitmap.isMutable) {
                val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle(); bitmap = mutable
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                var v = ((c shr 16 and 255) * .299 + (c shr 8 and 255) * .587 + (c and 255) * .114).roundToInt()
                if (ink) v = if (v < threshold) 0 else 255
                pixels[i] = (c and -0x1000000) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        return bitmap
    }
    @Synchronized override fun close() {
        closed = true
        if (decoder?.isRecycled == false) decoder.recycle()
        if (fallback?.isRecycled == false) fallback.recycle()
    }
}

internal data class MangaSourceState(val source: MangaTileSource? = null, val error: Boolean = false)

@Composable
internal fun rememberMangaSource(path: String, origin: String?, book: String?, version: Int, retry: Int): State<MangaSourceState> {
    val context = LocalContext.current.applicationContext
    return produceState(MangaSourceState(), path, origin, book, version, retry) {
        value = MangaSourceState()
        var target: com.bumptech.glide.request.FutureTarget<File>? = null
        var owned: MangaTileSource? = null
        try {
            target = if (File(path).isFile) null else BookCover.preloadManga(context, path, sourceOrigin = origin, bookUrl = book).submit()
            val request = target
            withContext(Dispatchers.IO) {
                val file = if (request == null) File(path) else runInterruptible { request.get() }
                owned = MangaTileSource(file)
            }
            value = MangaSourceState(owned)
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = MangaSourceState(error = true) }
        finally {
            withContext(NonCancellable + Dispatchers.IO) { owned?.close() }
            target?.let { Glide.with(context).clear(it) }
        }
    }
}

private data class MangaTile(val rect: Rect, val bitmap: androidx.compose.ui.graphics.ImageBitmap)
private data class TileRequest(val rect: Rect, val sample: Int)

@Composable
internal fun MangaTiledImage(source: MangaTileSource, horizontal: Boolean, scale: Float,
    offsetX: Float, offsetY: Float, gray: Boolean, ink: Boolean, threshold: Int,
    filter: ColorFilter, modifier: Modifier = Modifier, onError: () -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var visible by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val fit = if (size.width == 0) 1f else if (horizontal)
        min(size.width.toFloat() / source.width, size.height.toFloat() / source.height)
        else size.width.toFloat() / source.width
    val factor = (fit * scale).coerceAtLeast(.000001f)
    val left = (size.width - source.width * factor) / 2f + offsetX
    val top = (size.height - source.height * factor) / 2f + offsetY
    val requests = remember(source, visible, size, factor, left, top) {
        var sample = 1
        while (sample < 1024 && sample * 2 * factor <= 1f) sample *= 2
        val step = 512 * sample
        val x0 = floor((visible.left - left) / factor).toInt().coerceIn(0, source.width)
        val y0 = floor((visible.top - top) / factor).toInt().coerceIn(0, source.height)
        val x1 = ceil((visible.right - left) / factor).toInt().coerceIn(0, source.width)
        val y1 = ceil((visible.bottom - top) / factor).toInt().coerceIn(0, source.height)
        buildList {
            if (visible.width > 0 && visible.height > 0 && x1 > x0 && y1 > y0)
                for (y in y0 / step * step until y1 step step)
                    for (x in x0 / step * step until x1 step step)
                        add(TileRequest(Rect(x, y, min(x + step, source.width), min(y + step, source.height)), sample))
        }
    }
    val currentError by rememberUpdatedState(onError)
    val tiles by produceState<List<MangaTile>>(emptyList(), source, requests, gray, ink, threshold) {
        value = emptyList()
        try {
            val result = withContext(Dispatchers.IO) {
                requests.map { request ->
                    ensureActive()
                    MangaTile(request.rect, source.decode(request.rect, request.sample, gray, ink, threshold).asImageBitmap())
                }
            }
            value = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentError() }
    }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        size = coordinates.size
        val bounds = coordinates.boundsInWindow()
        val start = coordinates.windowToLocal(Offset(bounds.left, bounds.top))
        val end = coordinates.windowToLocal(Offset(bounds.right, bounds.bottom))
        visible = androidx.compose.ui.geometry.Rect(start, end)
    }) {
        tiles.forEach { tile ->
            val x = (left + tile.rect.left * factor).roundToInt()
            val y = (top + tile.rect.top * factor).roundToInt()
            val right = (left + tile.rect.right * factor).roundToInt()
            val bottom = (top + tile.rect.bottom * factor).roundToInt()
            if (right > x && bottom > y) drawImage(tile.bitmap, dstOffset = IntOffset(x, y),
                dstSize = IntSize(right - x, bottom - y), colorFilter = filter)
        }
    }
}
