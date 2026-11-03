package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Large expressions/styles stay out of navigation and SavedState. Terminal rows prevent duplicate saves. */
@Entity(tableName="highlight_rule_drafts")
data class HighlightRuleDraft(
    @PrimaryKey val id: String,val ruleId: Long?,val owner: String,val revision: Long,
    val originalKey: String?,val value: String,val phase: String="editing",val savedId: Long?=null,
    val updatedAt: Long=System.currentTimeMillis(),val process: String="",val epoch: Long=0,
)
