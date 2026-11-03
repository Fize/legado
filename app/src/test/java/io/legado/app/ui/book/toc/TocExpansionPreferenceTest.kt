package io.legado.app.ui.book.toc

import com.google.gson.JsonObject
import io.legado.app.data.dao.withTocExpanded
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TocExpansionPreferenceTest {

    @Test
    fun `toc expansion preference defaults and round trips`() {
        assertTrue(Book.ReadConfig().tocExpanded)
        assertTrue(GSON.fromJsonObject<Book.ReadConfig>("{}").getOrThrow().tocExpanded)

        val book = Book(readConfig = Book.ReadConfig())
        assertTrue(book.getTocExpanded())
        book.setTocExpanded(false)
        assertFalse(book.getTocExpanded())

        val restored = GSON.fromJsonObject<Book.ReadConfig>(
            GSON.toJson(book.readConfig)
        ).getOrThrow()
        assertFalse(restored.tocExpanded)
    }

    @Test
    fun `toc expansion update preserves other read config fields`() {
        val updated = GSON.fromJsonObject<JsonObject>(
            """{"futureOption":"keep","reverseToc":true}"""
        ).getOrThrow().toString().withTocExpanded(false)
        val json = GSON.fromJsonObject<JsonObject>(updated).getOrThrow()

        assertFalse(json.get("tocExpanded").asBoolean)
        assertTrue(json.get("reverseToc").asBoolean)
        assertTrue(json.get("futureOption").asString == "keep")
    }

}
