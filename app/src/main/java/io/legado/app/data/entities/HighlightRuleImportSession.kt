package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName="highlight_rule_import_sessions",primaryKeys=["id"],
    foreignKeys=[ForeignKey(entity=HighlightRuleManagerRecord::class,parentColumns=["id"],childColumns=["parent"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["parent"])])
data class HighlightRuleImportSession(
    val id: String,val source: String,val sourceKey: String,val owner: String="",val process: String="",
    val epoch: Long=0,val revision: Long=0,val phase: String="loading",val parent: String?=null,
)

@Entity(tableName="highlight_rule_import_rows",primaryKeys=["session","uuid"],
    foreignKeys=[ForeignKey(entity=HighlightRuleImportSession::class,parentColumns=["id"],childColumns=["session"],onDelete=ForeignKey.CASCADE)])
data class HighlightRuleImportRow(
    val session: String,val uuid: String,val position: Int,val name: String,val status: String,
    val original: String?,val value: String,val selected: Boolean,
)
