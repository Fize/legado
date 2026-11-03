package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

/** Preview queries deliberately exclude the potentially large expression/style JSON. */
data class HighlightImportChoice(val uuid: String,val name: String,val status: String,val selected: Boolean)

@Dao
interface HighlightRuleImportDao {
    @Query("SELECT * FROM highlight_rule_import_sessions WHERE id=:id") fun get(id: String): HighlightRuleImportSession?
    @Query("SELECT * FROM highlight_rule_import_sessions WHERE parent=:parent AND phase NOT IN ('completed','cancelled')") fun children(parent: String): List<HighlightRuleImportSession>
    @Query("SELECT phase FROM highlight_rule_import_sessions WHERE id=:id") fun phase(id: String): Flow<String?>
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insert(value: HighlightRuleImportSession)
    @Update fun update(value: HighlightRuleImportSession)
    @Query("SELECT uuid,name,status,selected FROM highlight_rule_import_rows WHERE session=:id ORDER BY position") fun choices(id: String): List<HighlightImportChoice>
    @Query("SELECT * FROM highlight_rule_import_rows WHERE session=:id ORDER BY position") fun rows(id: String): List<HighlightRuleImportRow>
    @Query("SELECT * FROM highlight_rule_import_rows WHERE session=:id AND selected=1 ORDER BY position") fun selected(id: String): List<HighlightRuleImportRow>
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insertRows(rows: List<HighlightRuleImportRow>)
    @Query("DELETE FROM highlight_rule_import_rows WHERE session=:id") fun clear(id: String)
    @Query("DELETE FROM highlight_rule_import_sessions WHERE id=:id") fun delete(id: String)
    @Query("UPDATE highlight_rule_import_rows SET selected=:selected WHERE session=:id AND uuid=:uuid") fun select(id: String,uuid: String,selected: Boolean): Int
    @Query("UPDATE highlight_rule_import_rows SET selected=:selected WHERE session=:id") fun selectAll(id: String,selected: Boolean)
}
