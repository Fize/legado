package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaReaderStateTest {

    private fun page(chapter: Int, index: Int) = MangaPage(
        chapterIndex = chapter,
        chapterSize = 2,
        mImageUrl = "$chapter/$index",
        index = index,
        imageCount = 4,
        mChapterName = "chapter $chapter",
    )

    @Test
    fun `double page spreads never pair pages across chapter boundaries`() {
        val loading = ReaderLoading(chapterIndex = 1, index = -1, mMessage = "next")
        val items = listOf(page(0, 0), page(0, 1), page(0, 2), loading, page(1, 0), page(1, 1))

        val spreads = buildMangaSpreads(items, doublePage = true)

        assertEquals(listOf("0:0+1", "0:2", "1:-1", "1:0+1"), spreads.map { it.key })
        assertNull(spreads[1].second)
        assertNull(spreads[2].second)
    }

    @Test
    fun `single page mode keeps one stable spread per content item`() {
        val items = listOf(page(0, 0), page(0, 1), page(0, 2))
        val spreads = buildMangaSpreads(items, doublePage = false)

        assertEquals(3, spreads.size)
        assertEquals(listOf(0, 1, 2), spreads.map { it.firstItemIndex })
        assertEquals(1, findMangaSpreadIndex(spreads, 1))
    }

    @Test
    fun `odd restored page resolves to its containing double spread`() {
        val spreads = buildMangaSpreads((0..3).map { page(0, it) }, doublePage = true)

        assertEquals(0, findMangaSpreadIndex(spreads, 1))
        assertEquals(1, findMangaSpreadIndex(spreads, 3))
    }
}
