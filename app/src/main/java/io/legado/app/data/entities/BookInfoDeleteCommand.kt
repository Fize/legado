package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookInfoDeleteStatus { Queued, Running, Failed, Completed, Cancelled }
enum class BookInfoDeletePhase { Accepted, RemoteRemoved, OriginalRemoved, DatabaseRemoved, Completed }

/** Accepted user intent and checkpoints outlive both the detail entry and the deleted book. */
@Entity(tableName = "book_info_delete_commands", indices = [Index("bookUrl"), Index("requestId"), Index("status")])
data class BookInfoDeleteCommand(
    @PrimaryKey val id: String,
    val requestId: String,
    val bookUrl: String,
    val snapshot: String,
    val target: String?,
    val deleteOriginal: Boolean,
    val deleteRemote: Boolean,
    val status: BookInfoDeleteStatus = BookInfoDeleteStatus.Queued,
    val phase: BookInfoDeletePhase = BookInfoDeletePhase.Accepted,
    val executionToken: String = "",
    val callbackClaimed: Boolean = false,
    val error: String? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val installationId: String? = null,
)

/** Deleting/replacing a book row invalidates the command's proof, even at the same URL. */
@Entity(tableName = "book_info_deletion_leases", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["bookUrl"], childColumns = ["bookUrl"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BookInfoDeleteCommand::class, parentColumns = ["id"], childColumns = ["commandId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["commandId"], unique = true)])
data class BookInfoDeletionLease(@PrimaryKey val bookUrl: String, val commandId: String)
