package io.legado.app.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderProviderRoutesTest {

    @Test
    fun `registered paths resolve to their public request semantics`() {
        val expected = mapOf(
            "bookSource/insert" to ReaderProviderRequestCode.SaveBookSource,
            "bookSources/insert" to ReaderProviderRequestCode.SaveBookSources,
            "bookSources/delete" to ReaderProviderRequestCode.DeleteBookSources,
            "bookSource/query" to ReaderProviderRequestCode.GetBookSource,
            "bookSources/query" to ReaderProviderRequestCode.GetBookSources,
            "book/insert" to ReaderProviderRequestCode.SaveBook,
            "books/query" to ReaderProviderRequestCode.GetBookshelf,
            "book/refreshToc/query" to ReaderProviderRequestCode.RefreshToc,
            "book/chapter/query" to ReaderProviderRequestCode.GetChapterList,
            "book/content/query" to ReaderProviderRequestCode.GetBookContent,
            "book/cover/query" to ReaderProviderRequestCode.GetBookCover,
        )

        assertEquals(expected, ReaderProviderRoutes.all.associate { it.path to it.requestCode })
        expected.forEach { (path, requestCode) ->
            assertEquals(requestCode, ReaderProviderRoutes.requestForPath(path))
        }
    }

    @Test
    fun `registered paths and request codes are one to one`() {
        val routes = ReaderProviderRoutes.all

        assertEquals(routes.size, routes.map { it.path }.distinct().size)
        assertEquals(routes.size, routes.map { it.requestCode }.distinct().size)
        routes.forEach { route ->
            assertEquals(
                route.requestCode,
                ReaderProviderRoutes.requestForMatcherCode(route.requestCode.ordinal),
            )
        }
    }

    @Test
    fun `book delete dispatch remains unchanged`() {
        var bookSelection: String? = null

        dispatchReaderProviderDelete(
            ReaderProviderRequestCode.DeleteBookSources,
            "book-payload",
            deleteBookSources = { bookSelection = it },
        )

        assertEquals("book-payload", bookSelection)
    }

    @Test
    fun `book insert dispatches only to book handlers`() = runBlocking {
        val calls = mutableListOf<String>()

        dispatchReaderProviderInsert(
            ReaderProviderRequestCode.SaveBookSource,
            "single-book",
            saveBookSource = { calls.add("single:$it") },
        )
        dispatchReaderProviderInsert(
            ReaderProviderRequestCode.SaveBookSources,
            "multiple-book",
            saveBookSources = { calls.add("multiple:$it") },
        )

        assertEquals(listOf("single:single-book", "multiple:multiple-book"), calls)
    }

    @Test
    fun `valid insert without values does not invoke its handler`() = runBlocking {
        var called = false

        dispatchReaderProviderInsert(
            ReaderProviderRequestCode.SaveBookSource,
            null,
            valuesPresent = false,
            saveBookSource = { called = true },
        )

        assertFalse(called)
    }

    @Test
    fun `invalid insert request still fails without values`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                dispatchReaderProviderInsert(
                    ReaderProviderRequestCode.GetBookSources,
                    null,
                    valuesPresent = false,
                )
            }
        }
    }

    @Test
    fun `book query dispatches only to book handlers`() {
        val parameters = mapOf("url" to listOf("book-url"))

        val source = dispatchReaderProviderQuery(
            ReaderProviderRequestCode.GetBookSource,
            parameters,
            getBookSource = {
                assertEquals(parameters, it)
                "single-book"
            },
        )
        val sources = dispatchReaderProviderQuery(
            ReaderProviderRequestCode.GetBookSources,
            parameters,
            getBookSources = { "multiple-book" },
        )

        assertEquals("single-book", source)
        assertEquals("multiple-book", sources)
    }
}
