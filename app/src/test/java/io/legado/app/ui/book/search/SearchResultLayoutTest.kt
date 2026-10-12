package io.legado.app.ui.book.search

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchResultLayoutTest {

    @Test
    fun metadataRowsAreIndependentlyConstrained() {
        val source = searchBookGridItemSource()
        assertTrue(source.contains("fun SearchBookGridItem("))
        assertTrue(source.contains("book.author"))
        assertTrue(source.contains("kinds.isNotEmpty()"))
        assertTrue(source.contains("book.latestChapterTitle"))
        assertTrue(source.contains("book.trimIntro"))
    }

    @Test
    fun rowUsesLogicalDirectionConstraintsForRtl() {
        val source = searchBookGridItemSource()
        assertTrue(source.contains("Row("))
        assertTrue(source.contains("LegadoBookCover("))
        assertTrue(source.contains("isInBookshelf"))
        assertTrue(source.contains("hasReadRecord"))
        assertTrue(source.contains("originCount"))
    }

    @Test
    fun titleRowChainsIndicatorsBeforeName() {
        val source = searchBookGridItemSource()
        val itemStart = source.indexOf("fun SearchBookGridItem(")
        val bookshelfIndex = source.indexOf("if (isInBookshelf)", itemStart)
        val readRecordIndex = source.indexOf("else if (hasReadRecord)", itemStart)
        val nameIndex = source.indexOf("text = book.name", itemStart)
        assertTrue(bookshelfIndex in itemStart until readRecordIndex)
        assertTrue(readRecordIndex < nameIndex)
    }

    @Test
    fun coverStaysCenteredWhenMetadataGrowsBeyondItsHeight() {
        val source = searchBookGridItemSource()
        val itemStart = source.indexOf("fun SearchBookGridItem(")
        val itemCode = source.substring(itemStart)
        assertTrue(itemCode.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(itemCode.contains("maxLines = 3"))
    }

    private fun searchBookGridItemSource(): String = listOf(
        File("src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt"),
        File("app/src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt"),
    ).firstOrNull { it.isFile }?.readText() ?: error("ExploreShowActivity.kt not found")
}
