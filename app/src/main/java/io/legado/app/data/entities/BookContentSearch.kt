package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName="book_content_search_sessions",primaryKeys=["id"],
    foreignKeys=[ForeignKey(entity=Book::class,parentColumns=["bookUrl"],childColumns=["bookUrl"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("bookUrl")])
data class BookContentSearchSession(
    val id: String, val bookUrl: String, val origin: String, val owner: String, val epoch: Long,
    val generation: Long, val query: String, val draft: String,
    val regex: Boolean, val matchCase: Boolean, val replace: Boolean,
    val paragraphIndent: String, val adaptSpecialStyle: Boolean, val chineseConverterType: Int,
    val manualReplaceRules: Boolean, val titleMode: Int, val splitTitle: Boolean,
    val rulesKey: String, val chaptersKey: String,
    val chapterOrdinal: Int, val matchOffset: Int, val resultCount: Int,
    val totalChapters: Int, val searchedChapters: Int, val skippedChapters: Int,
    val running: Boolean, val complete: Boolean, val error: String?, val updatedAt: Long,
    val catalogId: String? = null, val selectedOrdinal: Int? = null,
    val selectedPosition: Int? = null, val selectedEnd: Int? = null, val selectedDocumentKey: String? = null,
    val selectedTitleLength: Int? = null, val selectedIndentLength: Int? = null,
)

@Entity(tableName="book_content_search_hits",primaryKeys=["sessionId","ordinal"],
    foreignKeys=[ForeignKey(entity=BookContentSearchSession::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["sessionId","chapterIndex","displayStart"],unique=true)])
data class BookContentSearchHit(
    val sessionId: String, val ordinal: Int, val generation: Long,
    val chapterIndex: Int, val chapterUrl: String, val chapterTitle: String,
    val documentKey: String, val displayStart: Int, val displayEnd: Int,
    val canonicalPosition: Int, val canonicalEnd: Int,
    val snippet: String, val snippetStart: Int, val snippetEnd: Int,
)
