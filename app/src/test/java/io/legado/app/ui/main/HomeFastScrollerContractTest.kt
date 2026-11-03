package io.legado.app.ui.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeFastScrollerContractTest {

    @Test
    fun `folder bookshelf and discovery use independent fast scrollers`() {
        val bookshelf = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfRootViewModel.kt",
        ).readText()
        assertTrue(bookshelf.contains("EventBus.BOOKSHELF_REFRESH"))

        val config = projectFile(
            "src/main/java/io/legado/app/help/config/AppConfig.kt",
        ).readText()
        assertTrue(
            config.contains(
                "get() = appCtx.getPrefBoolean(PreferKey.showDiscoveryFastScroller, false)",
            ),
        )

        val otherConfig = projectFile(
            "src/main/java/io/legado/app/ui/config/OtherSettingsScreen.kt",
        ).readText()
        val discoverySwitch = otherConfig
            .substringAfterLast("key = PreferKey.showDiscoveryFastScroller")
            .substringBefore("}")
        assertTrue(discoverySwitch.contains("default = false"))
        assertTrue(discoverySwitch.contains("R.string.show_discovery_fast_scroller"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
