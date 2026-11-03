package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import org.junit.Assert.*
import org.junit.Test

class RestorePreferenceTypesTest {
    @Test fun rejectsTypesThatWouldCrashImmediateReadersWithoutLoggingTheirValues() {
        val cases = listOf(
            PreferKey.readStyleSelect to "private-invalid-value",
            PreferKey.comicStyleSelect to 2L,
            PreferKey.autoReadSpeed to true,
            PreferKey.editFontScale to "private-invalid-value",
            PreferKey.userAgent to 123,
            PreferKey.customHosts to setOf("private-invalid-value"),
            PreferKey.themeMode to false,
            PreferKey.cPrimary to 0xFF000000L,
            PreferKey.coverTitleLargeSize to "private-invalid-value",
        )
        cases.forEach { (key, value) ->
            val error = runCatching { validateEagerRestorePreferenceTypes(mapOf(key to value)) }.exceptionOrNull()
            assertTrue("Must reject $key", error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().contains(key))
            assertTrue(error.message.orEmpty().contains("config.xml"))
            assertFalse(error.message.orEmpty().contains("private-invalid-value"))
        }
    }

    @Test fun validLegacyAndUnknownValuesAreNotNormalizedOrRemovedByTypeValidation() {
        val values = linkedMapOf<String, Any?>(
            PreferKey.readStyleSelect to -1,
            PreferKey.comicStyleSelect to 999,
            PreferKey.autoReadSpeed to 46,
            PreferKey.themeMode to "future-mode",
            PreferKey.userAgent to "",
            PreferKey.customHosts to "legacy unparsed string",
            PreferKey.showBoardLine to 99,
            PreferKey.doublePageHorizontal to 0,
            PreferKey.hideNavigationBar to "true",
            "future-option" to setOf("one", "two"),
            "future-number" to 5L,
        )
        val original = LinkedHashMap(values)
        validateEagerRestorePreferenceTypes(values)
        assertEquals(original, values)
    }

    @Test fun nullEntriesIgnoredByTheWriterRemainAccepted() {
        validateEagerRestorePreferenceTypes(mapOf(PreferKey.readStyleSelect to null, PreferKey.userAgent to null))
    }

    @Test fun numericStringsAreNotMistakenForFrameworkIntegerPreferences() {
        assertTrue(runCatching { validateEagerRestorePreferenceTypes(mapOf(PreferKey.readStyleSelect to "2")) }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
