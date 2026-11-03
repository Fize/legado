package io.legado.app.ui.photo

import io.legado.app.ui.photo.PhotoTransform
import io.legado.app.ui.photo.constrainPhotoTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoTransformTest {
    @Test fun fittedLandscapeImageHasNoPanAtOneTimes() {
        assertEquals(PhotoTransform(), constrainPhotoTransform(PhotoTransform(1f, 2f, -2f), 800f, 600f, 200f, 100f))
    }
    @Test fun fittedPortraitImageHasNoHorizontalPanUntilItFillsTheViewport() {
        assertEquals(PhotoTransform(2f, 0f, .5f), constrainPhotoTransform(PhotoTransform(2f, 2f, 2f), 800f, 600f, 100f, 200f))
    }
    @Test fun zoomedLandscapePanIsClampedToBothImageEdges() {
        assertEquals(PhotoTransform(3f, 1f, -.5f), constrainPhotoTransform(PhotoTransform(3f, 2f, -2f), 800f, 600f, 200f, 100f))
    }
    @Test fun invalidDimensionsResetTheTransform() {
        assertEquals(PhotoTransform(), constrainPhotoTransform(PhotoTransform(3f, 1f, 1f), 0f, 600f, 200f, 100f))
    }
    @Test fun invalidTransformValuesAreNormalized() {
        assertEquals(PhotoTransform(), constrainPhotoTransform(PhotoTransform(Float.NaN, Float.NaN, Float.NaN), 800f, 600f, 200f, 100f))
    }
    @Test fun resizingReclampsNormalizedPanForTheNewViewport() {
        assertEquals(PhotoTransform(2f, .5f, 0f), constrainPhotoTransform(PhotoTransform(2f, 1f, 1f), 600f, 800f, 200f, 100f))
    }
}
