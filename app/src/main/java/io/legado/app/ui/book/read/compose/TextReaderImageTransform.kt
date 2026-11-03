package io.legado.app.ui.book.read.compose

import kotlin.math.min

internal data class ReaderImageTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

/** Input deltas are viewport pixels; zoom preserves the image point underneath the fingers. */
internal fun transformReaderImage(current: ReaderImageTransform, scale: Float,
    viewportWidth: Float, viewportHeight: Float, imageWidth: Float, imageHeight: Float,
    panX: Float = 0f, panY: Float = 0f,
    focalX: Float = viewportWidth / 2, focalY: Float = viewportHeight / 2): ReaderImageTransform {
    require(listOf(viewportWidth, viewportHeight, imageWidth, imageHeight, current.scale).all { it.isFinite() && it > 0 })
    require(scale.isFinite())
    require(listOf(current.x, current.y, panX, panY, focalX, focalY).all { it.isFinite() })
    val next = scale.coerceIn(1f, 8f)
    val ratio = next / current.scale
    val fit = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val boundX = ((imageWidth * fit * next - viewportWidth) / 2).coerceAtLeast(0f)
    val boundY = ((imageHeight * fit * next - viewportHeight) / 2).coerceAtLeast(0f)
    val x = current.x * ratio + (focalX - viewportWidth / 2) * (1 - ratio) + panX
    val y = current.y * ratio + (focalY - viewportHeight / 2) * (1 - ratio) + panY
    return ReaderImageTransform(next, if (boundX == 0f) 0f else x.coerceIn(-boundX, boundX),
        if (boundY == 0f) 0f else y.coerceIn(-boundY, boundY))
}
