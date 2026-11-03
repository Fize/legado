package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

/** A navigation entry's durable identity; it contains keys/positions, never book content. */
@Entity(tableName = "reader_sessions", primaryKeys = ["id"])
data class ReaderSessionState(
    val id: String,
    val initialBookUrl: String,
    val bookUrl: String,
    val inBookshelf: Boolean,
    val chapterChanged: Boolean,
    val chapterIndex: Int = -1,
    val pageIndex: Int = 0,
    val revision: Long = 0,
    val positionTime: Long = 0,
    val ownerToken: String = "",
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "reader_book_owners", primaryKeys = ["bookUrl"],
    foreignKeys = [ForeignKey(entity = ReaderSessionState::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Book::class, parentColumns = ["bookUrl"], childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class ReaderBookOwner(val bookUrl: String, val sessionId: String, val ownerToken: String)

@Entity(tableName = "reader_catalog_requests", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ReaderSessionState::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Book::class, parentColumns = ["bookUrl"], childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("bookUrl")])
data class ReaderCatalogRequest(
    val id: String,
    val sessionId: String,
    val bookUrl: String,
    val revision: Long,
    val chapterIndex: Int? = null,
    val pageIndex: Int? = null,
    val consumed: Boolean = false,
)
