package io.legado.app.ui.photo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.ui.compose.component.LegadoDrawableImageState
import io.legado.app.ui.compose.component.rememberLegadoDrawableImageState
import io.legado.app.ui.compose.component.rememberLegadoDrawablePainter
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader

@Composable
fun PhotoViewerScreen(requestKey: String, onBack: () -> Unit, viewModel: PhotoViewerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestKey) { focus.requestFocus() }
    LaunchedEffect(requestKey) { viewModel.load(requestKey) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    val close = { viewModel.close(requestKey) }
    BackHandler(onBack = close)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val request = state.request
    val image by rememberLegadoDrawableImageState(requestKey to request, state.retry) { context ->
        val local = request?.localPath
        val path = local ?: request?.url
        val loader = when {
            request?.url == "use_default_cover" -> ImageLoader.load(context, io.legado.app.model.BookCover.defaultDrawable)
            path?.startsWith("file://") == true || path?.startsWith("android.resource://") == true -> ImageLoader.load(context, android.net.Uri.parse(path))
            else -> ImageLoader.load(context, path)
        }
        loader.let { loader ->
            request?.sourceOrigin?.let { loader.apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it)) } ?: loader
        }.apply(RequestOptions().set(OkHttpModelLoader.retryFailedImagesOption, state.retry > 0))
            .apply(photoDecodeOptions()).skipMemoryCache(state.retry > 0)
            .let { if (local != null || state.retry > 0) it.diskCacheStrategy(DiskCacheStrategy.NONE) else it }
    }
    val drawable = (image as? LegadoDrawableImageState.Ready)?.drawable
    val transform = constrainPhotoTransform(state.transform, viewport.width.toFloat(), viewport.height.toFloat(), drawable?.intrinsicWidth?.toFloat() ?: 0f, drawable?.intrinsicHeight?.toFloat() ?: 0f)
    val latestTransform by rememberUpdatedState(transform)
    val latestViewport by rememberUpdatedState(viewport)
    val latestDrawable by rememberUpdatedState(drawable)
    fun update(next: PhotoTransform) = viewModel.transform(constrainPhotoTransform(next, viewport.width.toFloat(), viewport.height.toFloat(), drawable?.intrinsicWidth?.toFloat() ?: 0f, drawable?.intrinsicHeight?.toFloat() ?: 0f))
    fun zoom(factor: Float) = update(transform.copy(scale = transform.scale * factor))
    val zoomIn = stringResource(R.string.photo_zoom_in)
    val zoomOut = stringResource(R.string.photo_zoom_out)
    val reset = stringResource(R.string.reset)
    val zoomState = stringResource(R.string.photo_zoom_percent, (transform.scale * 100).toInt())
    val background = if (AppConfig.isEInkMode) MaterialTheme.colorScheme.background else Color.Black
    val foreground = if (AppConfig.isEInkMode) MaterialTheme.colorScheme.onBackground else Color.White
    Box(Modifier.fillMaxSize().background(background).testTag("photo_viewer").onSizeChanged { viewport = it }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Escape, Key.Back -> { close(); true }
                Key.Plus, Key.Equals, Key.NumPadAdd -> { zoom(1.5f); true }
                Key.Minus, Key.NumPadSubtract -> { zoom(1 / 1.5f); true }
                Key.Zero -> { update(PhotoTransform()); true }
                Key.DirectionLeft -> { update(transform.copy(x = transform.x + .1f)); true }
                Key.DirectionRight -> { update(transform.copy(x = transform.x - .1f)); true }
                Key.DirectionUp -> { update(transform.copy(y = transform.y + .1f)); true }
                Key.DirectionDown -> { update(transform.copy(y = transform.y - .1f)); true }
                else -> false
            }
        }.focusRequester(focus).focusable(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clipToBounds().testTag("photo_gestures")
            .semantics { stateDescription = zoomState; customActions = listOf(
                CustomAccessibilityAction(zoomIn) { zoom(1.5f); true },
                CustomAccessibilityAction(zoomOut) { zoom(1 / 1.5f); true },
                CustomAccessibilityAction(reset) { update(PhotoTransform()); true }) }
            .pointerInput(requestKey) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val current = latestTransform; val size = latestViewport; val picture = latestDrawable
                    if (size.width > 0 && size.height > 0 && picture != null) {
                        val scale = (current.scale * zoom).coerceIn(1f, 5f); val ratio = scale / current.scale
                        viewModel.transform(constrainPhotoTransform(PhotoTransform(scale,
                            current.x * ratio + (1 - ratio) * (centroid.x / size.width - .5f) + pan.x / size.width,
                            current.y * ratio + (1 - ratio) * (centroid.y / size.height - .5f) + pan.y / size.height),
                            size.width.toFloat(), size.height.toFloat(), picture.intrinsicWidth.toFloat(), picture.intrinsicHeight.toFloat()))
                    }
                }
            }.pointerInput(requestKey) {
                detectTapGestures(onTap = { close() }, onDoubleTap = {
                    val current = latestTransform
                    viewModel.transform(if (current.scale > 1f) PhotoTransform() else PhotoTransform(2f))
                })
            }, contentAlignment = Alignment.Center) {
            when {
                state.failed -> Button(onClick = { viewModel.load(requestKey, retry = true) }, modifier = Modifier.testTag("photo_retry")) { Text(stringResource(R.string.retry)) }
                state.loading || request == null -> CircularProgressIndicator(color = foreground)
                image == LegadoDrawableImageState.Error -> Button(onClick = { viewModel.load(requestKey, retry = true) }, modifier = Modifier.testTag("photo_retry")) { Text(stringResource(R.string.retry)) }
                drawable != null -> Image(rememberLegadoDrawablePainter(drawable), stringResource(R.string.photo_viewer_title), contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("photo_image").graphicsLayer {
                        scaleX = transform.scale; scaleY = transform.scale
                        translationX = transform.x * viewport.width; translationY = transform.y * viewport.height
                    })
                else -> CircularProgressIndicator(color = foreground)
            }
        }
        IconButton(onClick = close, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).background(if (AppConfig.isEInkMode) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = .72f), CircleShape).testTag("photo_close")) {
            Icon(Icons.Default.Close, stringResource(R.string.close), tint = foreground)
        }
    }
}
