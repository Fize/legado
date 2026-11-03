package io.legado.app.ui.book.read.compose

import org.junit.Assert.*
import org.junit.Test

class TextReaderImageTransformTest {
    private fun transform(current: ReaderImageTransform = ReaderImageTransform(), scale: Float = 2f,
        panX: Float = 0f, panY: Float = 0f, focalX: Float = 200f, focalY: Float = 200f) =
        transformReaderImage(current, scale, 400f, 400f, 800f, 400f, panX, panY, focalX, focalY)
    @Test fun centeredZoomKeepsTheImageCentered() { assertEquals(ReaderImageTransform(2f, 0f, 0f), transform()) }
    @Test fun zoomKeepsThePointUnderTheFingersAtTheSameViewportPosition() {
        val next = transform(scale = 3f, focalX = 250f, focalY = 220f)
        assertEquals(-100f, next.x, 0f); assertEquals(-40f, next.y, 0f)
        assertEquals(250f, 200f + (250f - 200f) * next.scale + next.x, 0f)
    }
    @Test fun viewportPanIsNotDividedByTheCurrentScale() {
        assertEquals(80f, transform(ReaderImageTransform(4f), 4f, panX = 80f).x, 0f)
    }
    @Test fun panningCannotExposeSpaceBeyondTheFittedImageEdges() {
        val next = transform(scale = 3f, panX = 10000f, panY = -10000f)
        assertEquals(400f, next.x, 0f); assertEquals(-100f, next.y, 0f)
    }
    @Test fun resetReturnsBothScaleAndTranslationsToTheInitialViewport() {
        assertEquals(ReaderImageTransform(), transform(ReaderImageTransform(4f, 200f, -100f), 1f))
    }
    @Test fun scaleIsClampedAtBothBounds() {
        assertEquals(8f, transform(scale = 99f).scale, 0f)
        assertEquals(1f, transform(scale = 0.1f).scale, 0f)
        assertEquals(1f, transform(scale = 0f).scale, 0f)
    }
    @Test fun zoomingAnAlreadyPannedImagePreservesItsCurrentCenterAnchor() {
        assertEquals(100f, transform(ReaderImageTransform(2f, 50f), 4f).x, 0f)
    }
    @Test fun invalidGeometryCannotEnterTheViewportState() {
        assertThrows(IllegalArgumentException::class.java) { transformReaderImage(ReaderImageTransform(), 2f, 0f, 400f, 800f, 400f) }
        assertThrows(IllegalArgumentException::class.java) { transform(scale = Float.NaN) }
    }
}
