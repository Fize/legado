package io.legado.app.model.remote

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.archiveName
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.analyzeRule.CustomUrl
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBookWebDavUploadTest {

    @Test
    fun `archive upload keeps the archive file name`() {
        val archiveBook = Book(
            origin = "${BookType.localTag}::source.zip",
            originName = "chapter.txt",
            type = BookType.text or BookType.local or BookType.archive,
        )

        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
        assertEquals("chapter.txt", remoteBookUploadFileName(Book(originName = "chapter.txt")))
    }

    @Test
    fun `archive name ignores custom WebDAV attributes`() {
        val archiveBook = Book(
            origin = BookType.webDavTag + CustomUrl("https://example.com/books/source.zip")
                .putAttribute("serverID", 7L)
                .toString(),
            type = BookType.text or BookType.local or BookType.archive,
        )

        assertEquals("source.zip", archiveBook.archiveName)
        assertEquals("source.zip", remoteBookUploadFileName(archiveBook))
    }

    @Test
    fun `conditional upload conflicts require overwrite confirmation`() {
        assertTrue(isWebDavOverwriteConflict(409))
        assertTrue(isWebDavOverwriteConflict(412))
        assertFalse(isWebDavOverwriteConflict(null))
        assertFalse(isWebDavOverwriteConflict(401))
        assertFalse(isWebDavOverwriteConflict(500))
    }

    @Test fun `archive name decodes path characters without including the query`() {
        val book = Book(origin = BookType.webDavTag + CustomUrl("https://example.com/books/source%20%23%E6%BC%AB%E7%94%BB%2B.zip?token=value")
            .putAttribute("serverID", 7L), type = BookType.local or BookType.text or BookType.archive)
        assertEquals("source #漫画+.zip", book.archiveName)
    }

    @Test fun `archive name decodes a literal percent only once`() {
        val book = Book(origin = BookType.webDavTag + "https://example.com/books/source%2520.zip",
            type = BookType.local or BookType.text or BookType.archive)
        assertEquals("source%20.zip", book.archiveName)
    }

}
