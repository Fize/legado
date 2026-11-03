package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfZoomGeometryTest {
    // Actual API 37 phone layout: both fitted pages are narrower than their half-screen slots.
    private val spread = listOf(393.5f..818.5f, 1605.5f..2030.5f)

    @Test fun `pinching the empty spread gutter uses the nearest paper instead of magnifying blank space`() {
        assertEquals(606f, resolvePdfSpreadFocusX(1212f, 1f, 0f, spread), 0.01f)
        assertEquals(1818f, resolvePdfSpreadFocusX(1500f, 1f, 0f, spread), 0.01f)
        assertEquals(612f, resolvePdfSpreadFocusX(1212f, 2f, -600f, spread), 0.01f)
    }

    @Test fun `pinches over an actual page or outside the spread keep the users focal point`() {
        for (focus in listOf(600f, 1800f, 100f, 2300f)) {
            assertEquals(focus, resolvePdfSpreadFocusX(focus, 1f, 0f, spread), 0.01f)
        }
        assertEquals(600f, resolvePdfSpreadFocusX(600f, 2f, -600f, spread), 0.01f)
    }

    @Test fun `single page and incomplete layouts do not invent a different gesture anchor`() {
        assertEquals(1212f, resolvePdfSpreadFocusX(1212f, 1f, 0f, spread.take(1)), 0.01f)
        assertEquals(1212f, resolvePdfSpreadFocusX(1212f, 1f, 0f, emptyList()), 0.01f)
        assertEquals(1212f, resolvePdfSpreadFocusX(1212f, 0f, 0f, spread), 0.01f)
    }
}
