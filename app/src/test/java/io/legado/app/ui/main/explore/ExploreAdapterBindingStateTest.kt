package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreAdapterBindingStateTest {

    @Test
    fun `only the latest matching explore binding accepts async results`() {
        assertTrue(isExploreBindingCurrent(2, 2, 3, 3, "source", "source"))
        assertFalse(isExploreBindingCurrent(1, 2, 3, 3, "source", "source"))
        assertFalse(isExploreBindingCurrent(2, 2, 3, 4, "source", "source"))
        assertFalse(isExploreBindingCurrent(2, 2, 3, 3, "source", "other"))
        assertFalse(isExploreBindingCurrent(2, 2, 3, 3, "source", null))
    }

    @Test
    fun `empty explore kinds do not render ExploreKindsSection`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt"
        )
        assertTrue(source.contains("val kinds = sourceKindsMap[item.bookSourceUrl].orEmpty()"))
        assertTrue(source.contains("if (kinds.isNotEmpty())"))
    }

    @Test
    fun `dynamic explore labels key LaunchedEffect by sourceUrl and kindIdx`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt"
        )

        assertTrue(source.contains("LaunchedEffect(sourceUrl, kindIdx, kind.viewName)"))
        assertTrue(source.contains("isExploreBindingCurrent("))
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
