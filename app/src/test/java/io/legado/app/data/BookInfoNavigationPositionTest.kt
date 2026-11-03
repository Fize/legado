package io.legado.app.data

import io.legado.app.data.entities.BookInfoNavigationPosition
import io.legado.app.data.entities.BookInfoRequest
import io.legado.app.data.repository.navigationOwner
import org.junit.Assert.*
import org.junit.Test

class BookInfoNavigationPositionTest {
    @Test fun defaultAndPdfLocatorsKeepTheirIndependentCoordinates() {
        assertTrue(BookInfoNavigationPosition(0).isValid())
        val pdf = BookInfoNavigationPosition(2, 17, true, 1, 3, 8, "anchor", 22)
        assertTrue(pdf.isValid()); assertEquals(17, pdf.chapterPosition); assertEquals(22, pdf.pdfPageIndex)
        assertEquals("anchor", pdf.highlightAnchorText)
    }
    @Test fun negativeRequiredCoordinatesAreRejected() {
        assertFalse(BookInfoNavigationPosition(-1).isValid())
        assertFalse(BookInfoNavigationPosition(0, -1).isValid())
        assertFalse(BookInfoNavigationPosition(0, pdfPageIndex = -1).isValid())
    }
    @Test fun navigationOwnerUsesTheCurrentCanonicalBookRatherThanTheLaunchKey() {
        val owner = BookInfoRequest("id", "initial", "changed-source", "token", 4, updatedAt = 100).navigationOwner()
        assertEquals("id", owner.id); assertEquals("token", owner.token)
        assertEquals(4L, owner.revision); assertEquals("changed-source", owner.bookUrl)
    }
}
