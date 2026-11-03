package io.legado.app.ui.book.changecover

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.CoverSourceRef
import org.junit.Assert.*
import org.junit.Test

class CoverSourceRefTest {
    @Test fun explicitlySelectedSourceCanAuthenticateACoverOnAnotherOrigin() {
        val book = Book(origin = "https://book.test", customCoverUrl = "https://images.test/cover.png",
            customCoverSource = CoverSourceRef("https://images.test/cover.png", "source-key"))
        assertEquals("source-key", book.getCoverSourceOrigin())
    }
    @Test fun changingTheUrlDoesNotForwardCredentialsFromAnOldSelection() {
        val book = Book(origin = "https://book.test", customCoverUrl = "https://other.test/cover.png",
            customCoverSource = CoverSourceRef("https://images.test/cover.png", "source-key"))
        assertNull(book.getCoverSourceOrigin())
    }
    @Test fun aPersistedLocalCoverDoesNotNeedNetworkCredentials() {
        val book = Book(customCoverUrl = "https://images.test/cover.png", persistedCoverUrl = "/covers/local.png",
            customCoverSource = CoverSourceRef("https://images.test/cover.png", "source-key"))
        assertNull(book.getCoverSourceOrigin())
    }
}
