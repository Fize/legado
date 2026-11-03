package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.HighlightRuleManagerRecord

@Dao
interface HighlightRuleManagerDao {
    @Query("SELECT * FROM highlight_rule_manager_states WHERE id=:id")
    fun get(id: String): HighlightRuleManagerRecord?
    @Query("SELECT state FROM highlight_rule_manager_states WHERE closed=0")
    fun openStates(): List<String>
    @Insert(onConflict=OnConflictStrategy.ABORT)
    fun insert(value: HighlightRuleManagerRecord)
    @Update fun update(value: HighlightRuleManagerRecord)
    @Query("DELETE FROM highlight_rule_manager_states WHERE id=:id") fun delete(id: String)
}
