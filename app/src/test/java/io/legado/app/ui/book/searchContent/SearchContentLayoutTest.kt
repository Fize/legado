package io.legado.app.ui.book.searchContent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchContentLayoutTest {

    @Test
    fun searchResultTextIsConstrainedAcrossParentWidth() {
        val source = searchContentSource()
            .substringAfter("internal fun SearchContentItemCard(")

        assertTrue(source.contains("Text("))
        assertTrue(source.contains("fontSize = 14.sp"))
        assertTrue(source.contains(".fillMaxWidth()"))
        assertTrue(source.contains(".padding(12.dp)"))
    }

    @Test
    fun searchResultItemUsesFlatClickableCard() {
        val source = searchContentSource()
            .substringAfter("internal fun SearchContentItemCard(")

        assertTrue(source.contains("Surface("))
        assertTrue(source.contains("shape = RoundedCornerShape(8.dp)"))
        assertTrue(source.contains("color = colorResource(R.color.background_card)"))
        assertTrue(source.contains("tonalElevation = 0.dp"))
        assertTrue(source.contains("shadowElevation = 0.dp"))
        assertTrue(source.contains(".padding(horizontal = 10.dp, vertical = 3.dp)"))
        assertTrue(source.contains(".clickable(onClick = onClick)"))
    }

    private fun searchContentSource(): String {
        val file = listOf(
            File("src/main/java/io/legado/app/ui/book/searchContent/SearchContentActivity.kt"),
            File("app/src/main/java/io/legado/app/ui/book/searchContent/SearchContentActivity.kt"),
        ).firstOrNull { it.isFile } ?: error("SearchContentActivity.kt not found")
        return file.readText()
    }
}
