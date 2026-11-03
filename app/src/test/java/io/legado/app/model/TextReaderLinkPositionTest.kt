package io.legado.app.model

import org.junit.Assert.*
import org.junit.Test

class TextReaderLinkPositionTest {
    @Test fun savedCoordinatesRoundTripWithoutResourceAddresses() {
        val position = TextReaderLinkPosition(2,textReaderLinkKey("chapter.xhtml"),517,12,2)
        assertEquals(position,TextReaderLinkPosition.decode(position.encode()))
        assertFalse(position.encode().contains("chapter.xhtml"))
    }
    @Test fun malformedAndOversizedCoordinatesAreRejected() {
        val valid = TextReaderLinkPosition(0,"a".repeat(64),0,0,0).encode()
        assertNull(TextReaderLinkPosition.decode("x".repeat(513)))
        assertNull(TextReaderLinkPosition.decode("{}"))
        assertNull(TextReaderLinkPosition.decode(valid.replace("\"chapter\":0","\"chapter\":-1")))
        assertNull(TextReaderLinkPosition.decode(valid.replace("a".repeat(64),"raw/path")))
    }
}
