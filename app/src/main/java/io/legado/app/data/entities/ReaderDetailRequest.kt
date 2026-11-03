package io.legado.app.data.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ReaderDetailPhase { Requested, Displayed, Returned, Consumed, Cancelled }

/** A reader's detail round trip is tied to its session revision, never a name/author lookup. */
@Entity(tableName = "reader_detail_requests", foreignKeys = [
    ForeignKey(entity = ReaderSessionState::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("sessionId"), Index("bookUrl")])
data class ReaderDetailRequest(
    @PrimaryKey val id: String,
    val sessionId: String,
    val bookUrl: String,
    val revision: Long,
    val phase: ReaderDetailPhase = ReaderDetailPhase.Requested,
    val producerToken: String = "",
    val returnBookUrl: String? = null,
    val deleted: Boolean = false,
    @Embedded(prefix = "position_") val position: BookInfoNavigationPosition? = null,
    val updatedAt: Long,
)

/** Losing a row must not let an old detail entry adopt a same-URL reimport. */
@Entity(tableName = "reader_detail_book_leases", foreignKeys = [
    ForeignKey(entity = ReaderDetailRequest::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Book::class, parentColumns = ["bookUrl"], childColumns = ["bookUrl"], onDelete = ForeignKey.CASCADE),
], indices = [Index("bookUrl")])
data class ReaderDetailBookLease(@PrimaryKey val requestId: String, val bookUrl: String)
