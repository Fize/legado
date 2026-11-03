package io.legado.app.ui.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverConfigRefreshContractTest {
    @Test
    fun `default cover preference refreshes cover model and bookshelf`() {
        val source = File("src/main/java/io/legado/app/ui/config/CoverSettingsScreen.kt").readText()
        assertTrue(source.contains("PreferKey.useDefaultCover"))
        assertTrue(source.contains("BookCover.upDefaultCover()"))
        assertTrue(source.contains("EventBus.BOOKSHELF_REFRESH"))
        assertTrue(source.contains("DisposableEffect(context)"))
    }
}
