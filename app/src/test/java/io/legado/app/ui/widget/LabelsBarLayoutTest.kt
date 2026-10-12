package io.legado.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LabelsBarLayoutTest {

    @Test
    fun `book details wrap and center labels without a horizontal scroller`() {
        val bookInfoSource = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        ).readText()

        assertTrue(bookInfoSource.contains("FlowRow("))
        assertTrue(bookInfoSource.contains("Alignment.CenterHorizontally"))
        assertFalse(bookInfoSource.contains("horizontalScroll("))
    }

    @Test
    fun `search and explore results wrap labels within the available width`() {
        val searchSource = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/ui/book/search/SearchActivity.kt"
        ).readText()
        val exploreSource = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt"
        ).readText()

        assertTrue(searchSource.contains("FlowRow("))
        assertTrue(exploreSource.contains("FlowRow("))
    }

    @Test
    fun `labels use flexbox and logical spacing`() {
        val source = File(
            repositoryRoot,
            "app/src/main/java/io/legado/app/ui/widget/LabelsBar.kt"
        ).readText()

        assertTrue(source.contains(": FlexboxLayout(context, attrs)"))
        assertTrue(source.contains("FlexboxLayout.LayoutParams("))
        assertTrue(source.contains("marginEnd ="))
        assertTrue(source.contains("flexWrap == FlexWrap.NOWRAP"))
        assertFalse(source.contains("setMargins("))
        assertFalse(source.contains("rightMargin"))
    }

    private fun labelsBar(path: String, id: String): Element {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(repositoryRoot, path))
        val nodes = document.getElementsByTagName(LABELS_BAR)
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.androidAttribute("id").endsWith("/$id") }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private fun Element.hasAncestor(name: String): Boolean =
        generateSequence(parentNode) { it.parentNode }
            .any { it.nodeName.endsWith(name) }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val LABELS_BAR = "io.legado.app.ui.widget.LabelsBar"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
