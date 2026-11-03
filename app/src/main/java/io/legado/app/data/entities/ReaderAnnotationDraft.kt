package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Large editor text belongs in Room; navigation and SavedState only retain the reader identity. */
@Entity(tableName="reader_annotation_drafts",primaryKeys=["readerId"],
    foreignKeys=[ForeignKey(entity=ReaderSessionState::class,parentColumns=["id"],childColumns=["readerId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=Book::class,parentColumns=["bookUrl"],childColumns=["bookUrl"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("bookUrl")])
data class ReaderAnnotationDraft(
    val readerId: String,val id: String,val bookUrl: String,val origin: String,
    val bookName: String,val bookAuthor: String,val chapterIndex: Int,val chapterUrl: String,
    val chapterName: String,val position: Int,val end: Int,val titleLength: Int,
    val documentKey: String,val highlight: Boolean,val editingTime: Long?,val originalKey: String?,
    val bookText: String,val note: String,val style: String,val updatedAt: Long,
)
