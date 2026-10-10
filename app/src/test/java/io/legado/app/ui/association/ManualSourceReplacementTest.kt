package io.legado.app.ui.association

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import org.junit.Assert.*
import org.junit.Test

class ManualSourceReplacementTest {
    private val change = ReplaceRule(id = 1, pattern = "Seed", replacement = "Seed+",
        scopeSource = true, scopeTitle = true, scopeContent = true, isRegex = false)
    private val noOp = change.copy(id = 2, replacement = "Seed")
    private val disabled = change.copy(id = 3, isEnabled = false)
    private val readerOnly = change.copy(id = 4, scopeSource = false)
    private val rules = listOf(noOp, change, disabled, readerOnly)

    @Test fun bookManualSelectionUsesRawSourceAndTracksChangesOnly() {
        val source = BookSource(bookSourceUrl = "https://example.invalid/one", bookSourceName = "Seed")
        val original = prepareBookSourceImportCandidate(source, emptyList())
        val selected = mapOf(0 to rules.map { it.id })
        val applied = refreshBookSourceImportCandidates(listOf(original), -1, null, rules, selected)
        assertEquals("Seed+", applied.single().replaced!!.bookSourceName)
        assertEquals(listOf(1L), applied.single().effectiveRuleIds)
        val repeated = refreshBookSourceImportCandidates(applied, -1, null, rules, selected)
        assertEquals("Seed+", repeated.single().replaced!!.bookSourceName)
        val cleared = refreshBookSourceImportCandidates(repeated, -1, null, rules, emptyMap())
        assertEquals("Seed", cleared.single().source(true).bookSourceName)
        assertTrue(cleared.single().effectiveRuleIds.isEmpty())
    }

    @Test fun invalidReplacementStillIdentifiesTheRuleThatChangedIt() {
        val removeUrl = change.copy(pattern = "https://example.invalid/one", replacement = "")
        val book = prepareBookSourceImportCandidate(BookSource(bookSourceUrl = removeUrl.pattern), listOf(removeUrl))
        assertFalse(book.canImport(true))
        assertEquals(listOf(1L), book.effectiveRuleIds)
        assertEquals(removeUrl.pattern, book.original.bookSourceUrl)
    }
}
