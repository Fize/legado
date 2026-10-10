package io.legado.app.ui.login

import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceLoginRouteTest {

    @Test
    fun `blank origin keeps current source`() {
        val current = BookSource(bookSourceUrl = "https://current.example")

        val result = resolveLoginSource("  ", current, { null })

        assertSame(current, result)
    }

    @Test
    fun `origin resolves book source`() {
        val current = BookSource(bookSourceUrl = "https://current.example")
        val book = BookSource(bookSourceUrl = "https://target.example")

        val result = resolveLoginSource(book.bookSourceUrl, current, { book })

        assertSame(book, result)
    }

    @Test
    fun `matching origin keeps current source before database lookup`() {
        val current = BookSource(bookSourceUrl = "https://same.example")
        val collidingBook = BookSource(bookSourceUrl = current.bookSourceUrl)

        val result = resolveLoginSource(current.bookSourceUrl, current, { collidingBook })

        assertSame(current, result)
    }

    @Test
    fun `missing source does not fall back`() {
        val current = BookSource(bookSourceUrl = "https://current.example")

        val missing = resolveLoginSource("https://missing.example", current, { null })

        assertNull(missing)
    }

    @Test
    fun `active book source keeps reading context`() {
        val current = BookSource(bookSourceUrl = "https://current.example")

        val route = createSourceLoginRoute(current, current, BookType.audio)

        assertEquals("bookSource", route?.type)
        assertEquals(current.bookSourceUrl, route?.key)
        assertEquals(BookType.audio, route?.bookType)
    }

    @Test
    fun `cross source routes use explicit keys`() {
        val current = BookSource(bookSourceUrl = "https://current.example")
        val target = BookSource(bookSourceUrl = "https://target.example")

        val bookRoute = createSourceLoginRoute(target, current, BookType.text)

        assertEquals("bookSource", bookRoute?.type)
        assertEquals(target.bookSourceUrl, bookRoute?.key)
        assertNull(bookRoute?.bookType)
    }
}
