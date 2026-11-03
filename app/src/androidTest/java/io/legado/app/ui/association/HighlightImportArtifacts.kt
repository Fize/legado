package io.legado.app.ui.association

import io.legado.app.data.appDb

/** Preserve existing imports; delete only records created during the enclosing fixture. */
internal class HighlightImportArtifacts {
    private val before=ids()
    private fun ids(): Set<String> = appDb.openHelper.readableDatabase.query("SELECT id FROM highlight_rule_import_sessions").use { cursor ->
        buildSet { while(cursor.moveToNext()) add(cursor.getString(0)) }
    }
    fun cleanup() { (ids()-before).forEach(appDb.highlightRuleImportDao::delete) }
}
