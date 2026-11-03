package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A detail entry may own a preview before the book exists in the library. */
@Entity(tableName = "book_info_requests", primaryKeys = ["id"], indices = [Index("bookUrl")])
data class BookInfoRequest(
    val id: String,
    val initialBookUrl: String,
    val bookUrl: String,
    val ownerToken: String,
    val revision: Long = 0,
    val deleted: Boolean = false,
    val updatedAt: Long,
    val previewKey: String? = null,
    @ColumnInfo(defaultValue = "NULL") val previewCommitRevision: Long? = null,
    @ColumnInfo(defaultValue = "0") val returnToReader: Boolean = false,
)

enum class BookInfoChildKind { Catalog, Edit, Reader }
enum class BookInfoChildPhase { Requested, Displayed, Returned, Cancelled, Consumed }

/** Locator data lives in Room, never in an Intent or a saved navigation stack. */
data class BookInfoNavigationPosition(
    val chapterIndex: Int,
    val chapterPosition: Int = 0,
    val chapterChanged: Boolean = false,
    val volumeIndex: Int? = null,
    val chapterInVolumeIndex: Int? = null,
    val highlightLayoutTitleLength: Int? = null,
    val highlightAnchorText: String? = null,
    val pdfPageIndex: Int? = null,
) {
    fun isValid() = chapterIndex >= 0 && chapterPosition >= 0 && (pdfPageIndex == null || pdfPageIndex >= 0)
}

/** No book FK: a reader changing source or deleting a preview must not erase its return result. */
@Entity(tableName = "book_info_child_requests", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = BookInfoRequest::class, parentColumns = ["id"],
        childColumns = ["requestId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("requestId"), Index("bookUrl")])
data class BookInfoChildRequest(
    val id: String,
    val requestId: String,
    val bookUrl: String,
    val revision: Long,
    val kind: BookInfoChildKind,
    val phase: BookInfoChildPhase = BookInfoChildPhase.Requested,
    val producerToken: String = "",
    @Embedded(prefix = "position_") val position: BookInfoNavigationPosition? = null,
    val returnBookUrl: String? = null,
    val readerDeleted: Boolean = false,
    val readerInBookshelf: Boolean = false,
    val updatedAt: Long,
)
