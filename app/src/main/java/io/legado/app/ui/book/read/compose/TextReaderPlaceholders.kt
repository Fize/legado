package io.legado.app.ui.book.read.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import io.legado.app.model.TextReaderImage

internal fun textReaderPlaceholders(images: List<TextReaderImage>, dimensions: Map<Int, Pair<Int, Int>>,
    width: Int, height: Int, fontSize: Int, density: Density): List<AnnotatedString.Range<Placeholder>> = images.map { image ->
    val size = dimensions[image.offset]
    val ratio = size?.let { it.first.toFloat() / it.second } ?: (4f / 3f)
    val imageHeight: Float
    val imageWidth: Float
    if (image.inline) {
        imageHeight = with(density) { fontSize.coerceAtLeast(1).sp.toPx() }.coerceAtMost(height.toFloat())
        imageWidth = (imageHeight * ratio).coerceAtMost(width.toFloat())
    } else {
        val availableHeight = (height - with(density) { fontSize.coerceAtLeast(1).sp.toPx() }).coerceAtLeast(1f)
        imageWidth = minOf(width.toFloat(), availableHeight * ratio)
        imageHeight = imageWidth / ratio
    }
    AnnotatedString.Range(Placeholder(with(density) { imageWidth.coerceAtLeast(1f).toSp() },
        with(density) { imageHeight.coerceAtLeast(1f).toSp() }, PlaceholderVerticalAlign.Center), image.offset, image.offset + 1)
}
