package io.legado.app.data.repository

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderPreferencesTest {

    @Test
    fun `backup compatibility map round trips every manga preference`() {
        val expected = MangaReaderPreferences(
            scaleDisabled = false,
            longClickSaveEnabled = false,
            pageAnimationDisabled = true,
            preDownloadCount = 23,
            clickScrollDisabled = true,
            autoPageSpeed = 7,
            footerConfigJson = "footer",
            horizontalScrollEnabled = true,
            doublePageEnabled = false,
            rightToLeftEnabled = true,
            colorFilterJson = "filter",
            titleHidden = true,
            eInkEnabled = true,
            eInkThreshold = 177,
            horizontalPageSnapDisabled = true,
            grayEnabled = true,
        )

        val restored = MangaReaderPreferences().restoreLegacyPreferenceMap(
            expected.toLegacyPreferenceMap()
        )

        assertEquals(expected, restored)
    }

    @Test
    fun `partial legacy restore changes only provided values`() {
        val restored = MangaReaderPreferences().restoreLegacyPreferenceMap(
            mapOf(
                PreferKey.mangaPreDownloadNum to 4,
                PreferKey.enableMangaGray to true,
            )
        )

        assertEquals(4, restored.preDownloadCount)
        assertTrue(restored.grayEnabled)
        assertTrue(restored.scaleDisabled)
        assertFalse(restored.eInkEnabled)
    }
}
