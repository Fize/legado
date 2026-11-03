package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.HighlightRuleDraft

@Dao
interface HighlightRuleDraftDao {
    @Query("SELECT * FROM highlight_rule_drafts WHERE id=:id")
    fun get(id: String): HighlightRuleDraft?
    @Insert(onConflict=OnConflictStrategy.ABORT)
    fun insert(draft: HighlightRuleDraft)
    @Update
    fun update(draft: HighlightRuleDraft)
    @Query("DELETE FROM highlight_rule_drafts WHERE id=:id")
    fun delete(id: String)
}
