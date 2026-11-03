package io.legado.app.ui.main.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreGroupMenuStateTest {

    @Test
    fun `group query parsing preserves exact group names`() {
        assertNull(exploreGroupFromQuery(null))
        assertNull(exploreGroupFromQuery("keyword"))
        assertEquals("", exploreGroupFromQuery("group:"))
        assertEquals("all", exploreGroupFromQuery("group:all"))
        assertEquals("manage", exploreGroupFromQuery("group:manage"))

        assertTrue(isExploreAllQuery(null))
        assertTrue(isExploreAllQuery("keyword"))
        assertFalse(isExploreAllQuery("group:"))
        assertFalse(isExploreAllQuery("group:missing"))
    }

    @Test
    fun `only existing groups remain selected`() {
        val groups = linkedSetOf("all", "manage", "novel")

        assertNull(selectedExploreGroup("keyword", groups))
        assertNull(selectedExploreGroup("group:missing", groups))
        assertEquals("all", selectedExploreGroup("group:all", groups))
        assertEquals("manage", selectedExploreGroup("group:manage", groups))
        assertEquals("novel", selectedExploreGroup("group:novel", groups))
    }

    @Test
    fun `compose root and viewmodel wire group selection and missing group fallback`() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreRootScreen.kt")
        val viewModel = readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreViewModel.kt")

        listOf(
            "R.string.all_source",
            "isExploreAllQuery(uiState.searchQuery)",
            "selectedExploreGroup(uiState.searchQuery, uiState.groups)",
        ).forEach { expected ->
            assertTrue("ExploreRootScreen should contain $expected", source.contains(expected))
        }
        assertTrue(viewModel.contains("requestedGroup !in currentGroups"))
        assertTrue(viewModel.contains("searchQuery.value = \"\""))
    }

    @Test
    fun `discover toolbar opens book source management`() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/main/explore/ExploreRootScreen.kt")

        assertTrue(source.contains("onManageSources"))
        assertTrue(source.contains("R.drawable.ic_settings"))
        assertTrue(source.contains("R.string.book_source_manage"))
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
