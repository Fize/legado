package io.legado.app.ui.association

import io.legado.app.data.entities.BookSource
import org.junit.Assert.*
import org.junit.Test

class SourceImportPresetTest {
    private val source=BookSource("https://example.com","Literal source")
    @Test fun enabledStateIsIndependentOfItsTranslatedLabel() {
        assertTrue(SourceImportPreset.Enabled.matches(source))
        assertFalse(SourceImportPreset.Disabled.matches(source))
        source.enabled=false
        assertFalse(SourceImportPreset.Enabled.matches(source));assertTrue(SourceImportPreset.Disabled.matches(source))
    }
    @Test fun missingAndLegacyUngroupedValuesKeepTheirMeaning() {
        listOf(null,""," ","未分组").forEach { source.bookSourceGroup=it;assertTrue(SourceImportPreset.NoGroup.matches(source)) }
        source.bookSourceGroup="Literal group";assertFalse(SourceImportPreset.NoGroup.matches(source))
    }
    @Test fun exploreAndLoginPresetsUseSourceMetadata() {
        source.enabledExplore=true;assertTrue(SourceImportPreset.ExploreEnabled.matches(source))
        source.enabledExplore=false;assertTrue(SourceImportPreset.ExploreDisabled.matches(source))
        assertFalse(SourceImportPreset.NeedsLogin.matches(source))
        source.loginUrl="https://example.com/login";assertTrue(SourceImportPreset.NeedsLogin.matches(source))
    }
}
