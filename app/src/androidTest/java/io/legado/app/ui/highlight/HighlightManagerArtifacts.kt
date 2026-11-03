package io.legado.app.ui.highlight

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import java.io.File

/** Track only records and private payloads created during this test; retain pre-existing app data. */
internal class HighlightManagerArtifacts {
    private val root=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"highlight-rule-transfers")
    private val before=ids()
    private val files=root.listFiles()?.map { it.name }?.toSet().orEmpty()
    private fun ids(): Set<String> = appDb.openHelper.readableDatabase.query("SELECT id FROM highlight_rule_manager_states").use { cursor ->
        buildSet { while(cursor.moveToNext()) add(cursor.getString(0)) }
    }
    fun cleanup() {
        (ids()-before).forEach(appDb.highlightRuleManagerDao::delete)
        root.listFiles()?.filter { it.name !in files }?.forEach { it.delete() }
    }
}
