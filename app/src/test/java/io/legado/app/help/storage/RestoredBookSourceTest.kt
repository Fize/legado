package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

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

    @Test fun streamingRestoresMixedSourcesAndRejectsTruncatedArray() {
        val file = File.createTempFile("restored-sources", ".json")
        try {
            file.writeText("""[{"bookSourceUrl":"https://legacy.invalid","bookSourceType":"AUDIO"},
                {"bookSourceUrl":"https://current.invalid","bookSourceType":4,"enabled":false}]""")
            val types = mutableListOf<Int>()
            forEachRestoredBookSource(file) { types.add(it.bookSourceType) }
            assertEquals(listOf(1, 4), types)
            file.writeText("""[{"bookSourceUrl":"https://valid.invalid"},""")
            assertThrows(IllegalArgumentException::class.java) {
                forEachRestoredBookSource(file) { }
            }
        } finally {
            file.delete()
        }
    }
}
