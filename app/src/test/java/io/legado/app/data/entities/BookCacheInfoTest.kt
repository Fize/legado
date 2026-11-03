package io.legado.app.data.entities

import io.legado.app.constant.BookType
import io.legado.app.help.book.getFolderNameNoCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCacheInfoTest {

    @Test
    fun `cache projection keeps the existing folder naming behavior`() {
        val names = listOf(
            "",
            "0123456789abcdef",
            "书/名:*?\"<>|.",
            "三体全集",
        )

        names.forEachIndexed { index, name ->
            val cacheInfo = cacheInfo(
                bookUrl = "https://example.com/book/$index",
                name = name,
            )
            val book = Book(bookUrl = cacheInfo.bookUrl, name = cacheInfo.name)

            assertEquals(name, book.getFolderNameNoCache(), cacheInfo.getFolderName())
        }
    }

    @Test
    fun `legacy local and webdav books keep case insensitive epub detection`() {
        assertTrue(cacheInfo(origin = BookType.localTag, originName = "BOOK.EPUB").isEpub)
        assertTrue(cacheInfo(origin = "${BookType.webDavTag}folder", originName = "book.EpUb").isEpub)
        assertFalse(cacheInfo(origin = "https://example.com", originName = "book.epub").isEpub)
    }

    @Test
    fun `non legacy books require the local type flag for epub detection`() {
        assertTrue(
            cacheInfo(
                origin = "https://example.com",
                originName = "book.EPUB",
                type = BookType.text or BookType.local,
            ).isEpub,
        )
        assertFalse(
            cacheInfo(
                origin = BookType.localTag,
                originName = "book.epub",
                type = BookType.text,
            ).isEpub,
        )
    }

    private fun cacheInfo(
        bookUrl: String = "book-url",
        name: String = "book-name",
        origin: String = BookType.localTag,
        originName: String = "book.txt",
        type: Int = 0,
    ): BookCacheInfo {
        return BookCacheInfo(
            bookUrl = bookUrl,
            name = name,
            origin = origin,
            originName = originName,
            type = type,
        )
    }

}
