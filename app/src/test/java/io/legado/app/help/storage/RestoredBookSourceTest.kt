package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredBookSourceTest {
    @Test fun legacyAudioTypeAndSearchRuleAreConvertedTogether() {
        val source = parseRestoredBookSources("""[{
            "bookSourceUrl":"https://legacy.invalid", "bookSourceType":"AUDIO",
            "ruleSearchUrl":"https://legacy.invalid/searchKey", "ruleSearchName":".title"
        }]""").single()
        assertEquals(1, source.bookSourceType)
        assertEquals("https://legacy.invalid/{{key}}", source.searchUrl)
        assertEquals(".title", source.ruleSearch?.name)
    }

    @Test fun mixedCurrentAndLegacySourcesKeepCurrentRules() {
        val sources = parseRestoredBookSources("""[
            {"bookSourceUrl":"https://legacy.invalid","bookSourceType":"AUDIO"},
            {"bookSourceUrl":"https://current.invalid","bookSourceType":4,
             "ruleSearch":{"name":"current-title"},"enabled":false}
        ]""")
        assertEquals(listOf(1, 4), sources.map { it.bookSourceType })
        assertEquals("current-title", sources[1].ruleSearch?.name)
        assertEquals(false, sources[1].enabled)
    }
}
