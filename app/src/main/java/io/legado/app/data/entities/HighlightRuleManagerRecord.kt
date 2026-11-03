package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName="highlight_rule_manager_states")
data class HighlightRuleManagerRecord(@PrimaryKey val id: String,val owner: String,val process: String,
    val epoch: Long,val revision: Long,val state: String,val closed: Boolean=false)
