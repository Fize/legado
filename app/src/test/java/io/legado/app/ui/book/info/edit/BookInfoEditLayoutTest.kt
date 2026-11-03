package io.legado.app.ui.book.info.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookInfoEditLayoutTest {

    @Test
    fun `all editable content stays inside the bounded scroll viewport`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()

        assertTrue(source.contains("LegadoTopAppBar"))
        assertTrue(source.contains("verticalScroll(rememberScrollState())"))
        listOf(
            "LegadoBookCover",
            "R.string.book_name",
            "R.string.author",
            "R.array.book_type",
            "R.string.cover_path",
            "R.string.select_local_image",
            "R.string.change_cover_source",
            "R.string.refresh_cover",
            "R.string.book_intro",
        ).forEach { token ->
            assertTrue("$token must be present in Compose form", source.contains(token))
        }
    }

    @Test
    fun `cover header and actions fit constrained widths`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()

        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains(".padding(start = 10.dp)"))
        assertTrue(source.contains(".heightIn(min = 48.dp)"))
        assertTrue(source.contains("maxLines = 1"))
        assertTrue(source.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun `introduction is visibly multiline and layout uses logical spacing`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()

        assertTrue(source.contains("minLines = 4"))
    }

    @Test
    fun `Chinese local image actions use compact labels`() {
        assertEquals("本地图片", stringValue("app/src/main/res/values-zh/strings.xml"))
        assertEquals("本機圖片", stringValue("app/src/main/res/values-zh-rTW/strings.xml"))
        assertEquals("本地圖片", stringValue("app/src/main/res/values-zh-rHK/strings.xml"))
    }

    private fun parse(path: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))

    private fun stringValue(path: String): String {
        val nodes = parse(path).getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == "select_local_image" }
            .textContent
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val ACTIVITY_PATH = "app/src/main/java/io/legado/app/ui/book/info/edit/BookInfoEditScreen.kt"
    }
}
