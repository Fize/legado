package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class SearchSessionStatus { Ready, Running, Stopped, Interrupted, Partial, Error }

@Entity(tableName = "search_sessions", primaryKeys = ["id"])
data class SearchSession(
    val id: String,
    val query: String,
    val scope: String,
    val precision: Boolean,
    val generation: Long,
    val revision: Long,
    val completedPage: Int,
    val requestedPage: Int,
    val status: SearchSessionStatus,
    val hasMore: Boolean,
    val done: Int,
    val total: Int,
    val failures: Int,
    val error: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "search_session_results",
    primaryKeys = ["sessionId", "resultKey", "origin", "bookUrl"],
    foreignKeys = [ForeignKey(entity = SearchSession::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "position"])],
)
data class SearchSessionResult(
    val sessionId: String,
    val resultKey: String,
    val position: Int,
    val bookUrl: String,
    val origin: String,
    val originName: String,
    val type: Int,
    val name: String,
    val author: String,
    val kind: String?,
    val coverUrl: String?,
    val intro: String?,
    val wordCount: String?,
    val latestChapterTitle: String?,
    val tocUrl: String,
    val variable: String?,
    val chapterWordCountText: String?,
    val chapterWordCount: Int,
    val respondTime: Int,
    val originOrder: Int,
    val arrival: Long,
)

@Entity(tableName = "search_screen_drafts", primaryKeys = ["id"])
data class SearchScreenDraft(
    val id: String,
    val revision: Long,
    val sessionId: String?,
    val query: String,
    val scope: String,
    val showInputHelp: Boolean,
    val manuallyStopped: Boolean,
    val scopeDialogOpen: Boolean,
    val scopeSourceMode: Boolean,
    val scopeSourceQuery: String,
    val scopeGroupDraft: String,
    val scopeSourceDraft: String?,
    val filterDialogOpen: Boolean,
    val filterDraft: String,
    val clearHistoryDialogOpen: Boolean,
    val emptyPromptOpen: Boolean,
    val emptyPromptSessionId: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "search_session_sources", primaryKeys = ["sessionId", "sourceUrl"],
    foreignKeys = [ForeignKey(entity = SearchSession::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SearchSessionSource(val sessionId: String, val sourceUrl: String, val position: Int)
