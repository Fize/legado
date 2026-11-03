package io.legado.app.ui.compose.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.normalizeCoverText
import kotlinx.coroutines.delay

internal val CoverImageStatus = SemanticsPropertyKey<String>("CoverImageStatus")
internal var SemanticsPropertyReceiver.coverImageStatus by CoverImageStatus

@Composable
fun LegadoBookCover(
    path: String?, name: String?, author: String?, modifier: Modifier = Modifier,
    loadOnlyWifi: Boolean = false, sourceOrigin: String? = null,
    onLoadFinish: (() -> Unit)? = null, description: String? = null,
) {
    val appearance by BookCover.appearance.collectAsStateWithLifecycle()
    val style = appearance ?: return
    val currentPath = path?.takeIf(String::isNotBlank)
    val label = description ?: listOfNotNull(name, author).filter(String::isNotBlank).joinToString(", ").ifBlank { stringResource(R.string.img_cover) }
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(modifier.aspectRatio(3f / 4f).clip(shape).border(.5.dp, Color(0x18888888), shape)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val requestKey = listOf(currentPath, sourceOrigin, loadOnlyWifi, style.useDefault, width, height)
        val image = if (style.useDefault || currentPath == null) null else {
            rememberLegadoDrawableImageState(requestKey, 0) { context ->
                val request = if (currentPath.startsWith("file:", true) || currentPath.startsWith("android.resource://"))
                    ImageLoader.load(context, android.net.Uri.parse(currentPath)) else ImageLoader.load(context, currentPath)
                request.apply(RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)).let { builder ->
                    if (sourceOrigin == null) builder else builder.apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }.override(width, height).dontTransform()
            }.value
        }
        val status = when (image) {
            null -> "default"
            LegadoDrawableImageState.Loading -> "loading"
            LegadoDrawableImageState.Error -> "error"
            is LegadoDrawableImageState.Ready -> "ready"
        }
        val finish by rememberUpdatedState(onLoadFinish)
        // One completion per request, independent of recomposition or callback identity.
        LaunchedEffect(requestKey, status) { if (status != "loading") finish?.invoke() }
        val showText by produceState(status != "loading" && status != "ready", requestKey, status) {
            if (status == "loading") { value = false; delay(1_200); value = true }
            else value = status != "ready"
        }
        val fallback = remember(style.defaultDrawable) {
            (style.defaultDrawable.constantState?.newDrawable() ?: style.defaultDrawable).mutate()
        }
        val drawable = (image as? LegadoDrawableImageState.Ready)?.drawable ?: fallback
        Box(Modifier.fillMaxSize().semantics { contentDescription = label; role = Role.Image; coverImageStatus = status }) {
            Image(rememberLegadoDrawablePainter(drawable), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val title = normalizeCoverText(name, style.keepPunctuation)
            val writer = normalizeCoverText(author, style.keepPunctuation)
            if (style.drawName && title != null && showText && status != "ready") {
                val text = rememberCoverTextLayout(title, writer, width, height, style)
                Canvas(Modifier.fillMaxSize()) { text.draw(this) }
            }
        }
    }
}
