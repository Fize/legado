package io.legado.app.ui.association

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.DictionaryRuleRepository
import io.legado.app.data.repository.ReplaceRuleRepository
import io.legado.app.data.repository.TextTocRuleRepository
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Verifies the importable payloads produced for the selected rules on the Compose routes. */
@RunWith(AndroidJUnit4::class)
class RuleSelectionShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun highlightSelectionUsesTheImportEnvelopeAndPreservesFullStyle() {
        val id = UUID.randomUUID().toString()
        val selected = HighlightRule(
            name = "Share highlight $id", pattern = "甲乙[?&]", isRegex = true,
            scope = "Fixture", group = "Share group", isEnabled = false,
            applyToTitle = true, applyToBody = false, timeoutMillisecond = 4567,
            style = "{\"textColor\":123456}",
        )

        val restored = checkNotNull(GSON.fromJson(
            GSON.toJson(HighlightRuleFile(type = HighlightRuleFile.TYPE, rules = listOf(selected))),
            HighlightRuleFile::class.java,
        ))

        assertEquals(HighlightRuleFile.TYPE, restored.type)
        assertEquals(GSON.toJson(selected), GSON.toJson(restored.rules!!.single()))
        assertEquals(1, restored.rules!!.size)
    }

    @Test
    fun replacementSelectionSharesImportableRuleAndItsPreviewSample() = runBlocking {
        val id = UUID.randomUUID().toString()
        val selected = ReplaceRule(
            id = 0, name = "Share replacement $id", pattern = "target", replacement = "changed",
            group = "Sample group", scope = "Fixture", scopeTitle = true, isEnabled = false,
        )
        val unselected = ReplaceRule(id = 1, name = "Unselected replacement $id", pattern = "other")
        val inserted = appDb.replaceRuleDao.insert(selected, unselected)
        selected.id = inserted[0]
        unselected.id = inserted[1]
        val sample = "Title\ntarget sample & ? query"
        ReplacePreviewConfig.saveSample(selected.id, sample)
        try {
            val file = ReplaceRuleRepository(context, appDb).createShareFile(listOf(selected))
            try {
                val restored = GSON.fromJsonArray<ReplaceRule>(file.readText()).getOrThrow().single()
                assertEquals(selected.id, restored.id)
                assertEquals(selected.name, restored.name)
                assertEquals(selected.pattern, restored.pattern)
                assertEquals(selected.replacement, restored.replacement)
                assertEquals(selected.group, restored.group)
                assertEquals(selected.scope, restored.scope)
                assertTrue(restored.scopeTitle)
                assertFalse(restored.isEnabled)
                assertEquals(sample, restored.previewText)
                assertNull(selected.previewText)
            } finally {
                file.delete()
            }
            assertEquals(sample, ReplacePreviewConfig.sample(selected.id))
        } finally {
            ReplacePreviewConfig.removeSample(selected.id)
            appDb.replaceRuleDao.delete(selected, unselected)
        }
    }

    @Test
    fun textTocSelectionSharesEveryRuleFieldWithoutUnselectedRules() = runBlocking {
        val id = System.currentTimeMillis()
        val selected = TxtTocRule(
            id = id, name = "Share toc $id", rule = "^Chapter (.+)$", replacement = "$1",
            example = "Chapter One", serialNumber = 42, enable = false,
        )
        val unselected = TxtTocRule(id = id + 1, name = "Unselected toc $id", rule = "Other")
        appDb.txtTocRuleDao.insert(selected, unselected)
        try {
            val file = TextTocRuleRepository(context, appDb).createShareFile(listOf(selected))
            try {
                val restored = GSON.fromJsonArray<TxtTocRule>(file.readText()).getOrThrow().single()
                assertEquals(GSON.toJson(selected), GSON.toJson(restored))
                assertFalse(restored.id == unselected.id)
            } finally {
                file.delete()
            }
        } finally {
            appDb.txtTocRuleDao.delete(selected, unselected)
        }
    }

    @Test
    fun dictionarySelectionPreservesCompleteUrlAndDisplayRule() = runBlocking {
        val id = UUID.randomUUID().toString()
        val selected = DictRule(
            name = "Share dictionary $id",
            urlRule = "https://example.invalid/dictionary?q={{key}}&lang=zh#result",
            showRule = "@js:result + ' & ? # '", enabled = false, sortNumber = 42,
        )
        val unselected = DictRule(name = "Unselected dictionary $id", urlRule = "https://example.invalid/other")
        appDb.dictRuleDao.insert(selected, unselected)
        try {
            val file = DictionaryRuleRepository(context, appDb).createShareFile(listOf(selected))
            try {
                val restored = GSON.fromJsonArray<DictRule>(file.readText()).getOrThrow().single()
                assertEquals(GSON.toJson(selected), GSON.toJson(restored))
                assertFalse(restored.name == unselected.name)
            } finally {
                file.delete()
            }
        } finally {
            appDb.dictRuleDao.delete(selected, unselected)
        }
    }
}
