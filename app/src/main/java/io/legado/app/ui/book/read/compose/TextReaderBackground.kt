package io.legado.app.ui.book.read.compose

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.model.TextReaderBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

internal fun textReaderBackgroundColor(spec: TextReaderBackground): Color? = if (spec.kind != 0) null else
    runCatching { Color(spec.value.toColorInt()).let { it.copy(alpha = it.alpha * spec.alpha / 100f) } }.getOrNull()

/** Every decode is owned by its composition; no shared drawable or explicitly recycled bitmap. */
internal suspend fun loadTextReaderBackground(context: Context, spec: TextReaderBackground,
    width: Int, height: Int): Drawable? = withContext(Dispatchers.IO) {
    if (width <= 0 || height <= 0 || spec.alpha == 0 || spec.kind == 0) return@withContext null
    try {
        val drawable = run {
            fun open(): InputStream = when {
                spec.kind == 1 -> context.assets.open("bg/" + spec.value)
                spec.value.startsWith("content://", true) -> checkNotNull(context.contentResolver.openInputStream(spec.value.toUri()))
                else -> {
                    val path = if (spec.value.startsWith("file:", true)) File(checkNotNull(spec.value.toUri().path))
                        else if (spec.value.contains(File.separator)) File(spec.value)
                        else File(File(context.getExternalFilesDir(null) ?: context.filesDir, "bg"), spec.value)
                    path.inputStream()
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
            val targetWidth = width.coerceIn(1, 4096)
            val targetHeight = height.coerceIn(1, 4096)
            var sample = 1
            while (options.outWidth.toLong() / sample * (options.outHeight.toLong() / sample) > 4L * 1024 * 1024 ||
                options.outWidth / sample > targetWidth * 2 || options.outHeight / sample > targetHeight * 2) sample *= 2
            options.inJustDecodeBounds = false; options.inSampleSize = sample
            val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
            val chunk = bitmap.ninePatchChunk
            if (chunk != null && NinePatch.isNinePatchChunk(chunk)) NinePatchDrawable(context.resources, bitmap, chunk, Rect(), null)
            else bitmap.toDrawable(context.resources).apply { gravity = Gravity.FILL }
        }
        drawable.mutate().apply { alpha = (spec.alpha * 255 + 50) / 100 }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    catch (_: OutOfMemoryError) { null }
}

@Composable
internal fun TextReaderBackgroundLayer(spec: TextReaderBackground?, modifier: Modifier = Modifier) {
    if (spec == null) return
    val context = LocalContext.current.applicationContext
    BoxWithConstraints(modifier.testTag("text_reader_background")) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val source = Triple(spec.kind, spec.value, spec.revision)
        val color = remember(spec) { textReaderBackgroundColor(spec) }
        val drawable = remember(source, context) { mutableStateOf<Drawable?>(null) }
        val requestWidth = if (spec.kind == 0) 1 else width
        val requestHeight = if (spec.kind == 0) 1 else height
        LaunchedEffect(source, context, requestWidth, requestHeight, spec.alpha > 0) {
            if (spec.kind != 0 && spec.alpha > 0) {
                val decoded = loadTextReaderBackground(context, spec.copy(alpha = 100), requestWidth, requestHeight)
                if (decoded != null) drawable.value = decoded
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(spec.fallbackColor))
            color?.let { drawRect(it) }
            drawable.value?.takeIf { spec.kind != 0 && spec.alpha > 0 }?.let { owned ->
                owned.alpha = (spec.alpha * 255 + 50) / 100
                owned.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawIntoCanvas { owned.draw(it.nativeCanvas) }
            }
        }
    }
}
