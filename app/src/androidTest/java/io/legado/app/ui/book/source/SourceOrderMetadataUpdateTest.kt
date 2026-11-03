package io.legado.app.ui.book.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceRuleRepository
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Repository updates from a stale row must preserve the latest committed rule metadata. */
@RunWith(AndroidJUnit4::class)
class SourceOrderMetadataUpdateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun individualAndSelectionTogglesPreserveOrderAndExternallyUpdatedMetadata() = runBlocking {
        val repository = ReplaceRuleRepository(context, appDb)
        val before = appDb.replaceRuleDao.all
        val group = "Enable ${UUID.randomUUID()}"
        val firstId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
        val stale = (0..2).map { index ->
            ReplaceRule(
                id = firstId + index,
                name = "Rule $index $group",
                group = group,
                pattern = "Before $index",
                replacement = "Before",
                order = index * 100,
                isEnabled = false,
            )
        }
        val keys = stale.mapTo(linkedSetOf(), ReplaceRule::id)
        try {
            appDb.replaceRuleDao.insert(*stale.toTypedArray())
            val expectedOrder = appDb.replaceRuleDao.all.map(ReplaceRule::id).toMutableList().apply {
                remove(stale[0].id)
                add(indexOf(stale[2].id) + 1, stale[0].id)
            }
            repository.moveRelative(stale[0].id, stale[2].id, after = true)
            assertEquals(expectedOrder, appDb.replaceRuleDao.all.map(ReplaceRule::id))

            appDb.replaceRuleDao.update(*appDb.replaceRuleDao.findByIds(*keys.toLongArray()).mapIndexed { index, rule ->
                rule.copy(
                    pattern = "Updated pattern $index",
                    replacement = "Updated replacement $index",
                    scope = "Updated scope $index",
                )
            }.toTypedArray())
            val committed = appDb.replaceRuleDao.all.associateBy(ReplaceRule::id)

            repository.setEnabled(stale.first(), true)
            assertCommittedState(committed, keys, setOf(stale.first().id))

            repository.setEnabled(stale, true)
            assertCommittedState(committed, keys, keys)

            repository.setEnabled(stale, false)
            assertCommittedState(committed, keys, emptySet())
        } finally {
            appDb.replaceRuleDao.delete(*stale.toTypedArray())
            val orders = before.associate { it.id to it.order }
            appDb.replaceRuleDao.update(*appDb.replaceRuleDao.all.map { rule ->
                rule.copy(order = orders[rule.id] ?: rule.order)
            }.toTypedArray())
        }
    }

    private fun assertCommittedState(
        committed: Map<Long, ReplaceRule>,
        changedIds: Set<Long>,
        enabledIds: Set<Long>,
    ) {
        val actual = appDb.replaceRuleDao.all.associateBy(ReplaceRule::id)
        val expected = committed.mapValues { (id, rule) ->
            if (id in changedIds) rule.copy(isEnabled = id in enabledIds) else rule
        }
        assertEquals(expected.mapValues { GSON.toJson(it.value) }, actual.mapValues { GSON.toJson(it.value) })
    }
}
