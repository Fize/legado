package io.legado.app.ui.book.read.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.TextReaderChapterKey
import io.legado.app.model.TextReaderImage
import io.legado.app.ui.compose.component.LegadoImageState
import io.legado.app.ui.compose.component.rememberLegadoImageState
import kotlinx.coroutines.CancellationException

private sealed interface ImageBytes {
    data object Loading : ImageBytes
    data object Failed : ImageBytes
    class Ready(val bytes: ByteArray) : ImageBytes
}

/** The byte read is cancellable and the Glide target remains pinned for as long as it is drawn. */
@Composable
internal fun TextReaderVisibleImage(
    key: TextReaderChapterKey, image: TextReaderImage, ordinal: Int, rect: Rect, pageTop: Float,
    width: Int, height: Int, model: TextReaderViewModel,
    onBitmap: (ImageBitmap?) -> Unit, onDimensions: (Int, Int) -> Unit,
    canInteract: () -> Boolean = { true },
) {
    var retry by remember(key, image) { mutableIntStateOf(0) }
    var preview by remember(key, image) { mutableStateOf(false) }
    val bytes by androidx.compose.runtime.key(key, image, retry) {
        produceState<ImageBytes>(ImageBytes.Loading) {
            value = try { ImageBytes.Ready(model.image(key, image, force = retry > 0)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ImageBytes.Failed }
        }
    }
    val state = (bytes as? ImageBytes.Ready)?.let { loaded ->
        rememberLegadoImageState(listOf(key, image, retry, width, height)) { context ->
            ImageLoader.load(context, loaded.bytes).override(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }.value
    }
    val bitmap = (state as? LegadoImageState.Success)?.bitmap
    val failed = bytes == ImageBytes.Failed || state == LegadoImageState.Error
    val label = stringResource(R.string.text_reader_image, ordinal)
    val currentBitmap by rememberUpdatedState(onBitmap)
    val currentDimensions by rememberUpdatedState(onDimensions)
    DisposableEffect(bitmap) {
        currentBitmap(bitmap)
        onDispose { currentBitmap(null) }
    }
    LaunchedEffect(bitmap) { bitmap?.let { currentDimensions(it.width, it.height) } }
    val density = LocalDensity.current
    val loading = stringResource(R.string.loading)
    val error = stringResource(R.string.photo_open_failed)
    val open = stringResource(R.string.preview_image_by_click)
    Box(Modifier.offset(with(density) { rect.left.toDp() }, with(density) { (rect.top - pageTop).toDp() })
        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
        .testTag("text_reader_image_${image.offset}")
        .clickable(role = Role.Button, onClickLabel = if (failed) stringResource(R.string.retry) else open) {
            if (canInteract()) { if (failed) retry++ else if (bitmap != null) preview = true }
        }.semantics { contentDescription = label; stateDescription = if (failed) error else if (bitmap == null) loading else open },
        contentAlignment = Alignment.Center) {
        if (failed) Text(stringResource(R.string.retry))
    }
    if (preview && bitmap != null) ReaderImagePreview(bitmap, label) { preview = false }
}

@Composable
private fun ReaderImagePreview(bitmap: ImageBitmap, description: String, onClose: () -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var x by remember(bitmap) { mutableFloatStateOf(0f) }
    var y by remember(bitmap) { mutableFloatStateOf(0f) }
    val zoomIn = stringResource(R.string.text_reader_zoom_in)
    val zoomOut = stringResource(R.string.text_reader_zoom_out)
    val reset = stringResource(R.string.text_reader_zoom_reset)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("text_reader_image_preview")) {
            if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) return@BoxWithConstraints
            fun transform(next: Float, dx: Float = 0f, dy: Float = 0f,
                focalX: Float = constraints.maxWidth / 2f, focalY: Float = constraints.maxHeight / 2f) {
                val result = transformReaderImage(ReaderImageTransform(scale, x, y), next,
                    constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(),
                    dx, dy, focalX, focalY)
                scale = result.scale; x = result.x; y = result.y
            }
            Image(bitmap, description, Modifier.fillMaxSize()
                .pointerInput(bitmap, constraints.maxWidth, constraints.maxHeight) {
                    detectTransformGestures { centroid, pan, zoom, _ -> transform(scale * zoom, pan.x, pan.y, centroid.x, centroid.y) }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = x, translationY = y)
                .semantics {
                    stateDescription = "${(scale * 100).toInt()}%"
                    customActions = listOf(CustomAccessibilityAction(zoomIn) { transform(scale * 1.5f); true },
                        CustomAccessibilityAction(zoomOut) { transform(scale / 1.5f); true },
                        CustomAccessibilityAction(reset) { transform(1f); true })
                }, contentScale = ContentScale.Fit)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Text(stringResource(R.string.close), color = Color.White)
            }
        }
    }
}
