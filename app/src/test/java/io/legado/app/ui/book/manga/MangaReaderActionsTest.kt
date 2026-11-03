package io.legado.app.ui.book.manga

import io.legado.app.data.repository.MangaReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class MangaReaderActionsTest {
    @Test fun independentSettingsToggleAgainstTheLatestPersistedSnapshot() {
        val initial = MangaReaderPreferences()
        MangaReaderSetting.entries.filterNot { it == MangaReaderSetting.EInk || it == MangaReaderSetting.Gray }
            .forEach { setting ->
                val changed = initial.toggle(setting)
                assertNotEquals(setting.name, initial, changed)
                assertEquals(setting.name, initial, changed.toggle(setting))
            }
    }

    @Test fun grayAndEinkModesAreMutuallyExclusive() {
        val gray = MangaReaderPreferences(grayEnabled = true)
        val eink = gray.toggle(MangaReaderSetting.EInk)
        assertTrue(eink.eInkEnabled); assertFalse(eink.grayEnabled)
        val again = eink.toggle(MangaReaderSetting.Gray)
        assertFalse(again.eInkEnabled); assertTrue(again.grayEnabled)
    }

    @Test fun automaticReadingWaitsForTheMenuAndLoadingToFinish() {
        val state = MangaReaderUiState(autoPageEnabled = true, loadingVisible = false)
        assertEquals(MangaReaderCommand.TurnPage(1, true), automaticMangaCommand(state))
        assertNull(automaticMangaCommand(state.copy(menuVisible = true)))
        assertNull(automaticMangaCommand(state.copy(loadingVisible = true)))
        assertNull(automaticMangaCommand(state.copy(dialog = MangaReaderDialog.ColorFilter)))
        assertEquals(MangaReaderCommand.TurnPage(1, false),
            automaticMangaCommand(state.copy(pageAnimationEnabled = false)))
    }

    @Test fun continuousReadingUsesTheConfiguredSpeedWithAValidMinimum() {
        val state = MangaReaderUiState(autoScrollEnabled = true, loadingVisible = false, autoPageSpeed = 3)
        assertEquals(MangaReaderCommand.ScrollBy(3f), automaticMangaCommand(state))
        assertEquals(MangaReaderCommand.ScrollBy(1f), automaticMangaCommand(state.copy(autoPageSpeed = -1)))
        assertNull(automaticMangaCommand(state.copy(autoScrollEnabled = false)))
    }
}
