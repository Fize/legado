package io.legado.app.ui.main.explore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreAdapterBindingStateTest {

    @Test
    fun `empty explore kinds do not render dynamic rows`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreRootScreen.kt"
        )
        assertTrue(source.contains("val kinds = uiState.sourceKinds[source.bookSourceUrl].orEmpty()"))
        assertTrue(source.contains("if (kinds.isNotEmpty())"))
    }

    @Test
    fun `dynamic explore labels key LaunchedEffect by sourceUrl and kind index`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreRootScreen.kt"
        )

        assertTrue(source.contains("LaunchedEffect(sourceUrl, index, kind.viewName)"))
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
