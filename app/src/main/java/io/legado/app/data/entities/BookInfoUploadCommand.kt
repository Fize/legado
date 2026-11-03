package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookInfoUploadStatus { Queued, Running, Failed, Completed, Cancelled }
enum class BookInfoUploadPhase { Prepared, Transferring, Transferred, Completed }

/** A confirmed upload owns immutable bytes and survives the detail entry. */
@Entity(tableName = "book_info_upload_commands", indices = [Index("bookUrl"), Index("requestId"), Index("status")])
data class BookInfoUploadCommand(
    @PrimaryKey val id: String,
    val requestId: String,
    val bookUrl: String,
    val sourceUri: String,
    val originalOrigin: String,
    val originName: String,
    val fileName: String,
    val payloadKey: String,
    val sha256: String,
    val size: Long,
    val target: String,
    val overwrite: Boolean,
    val installationId: String,
    val status: BookInfoUploadStatus = BookInfoUploadStatus.Queued,
    val phase: BookInfoUploadPhase = BookInfoUploadPhase.Prepared,
    val executionToken: String = "",
    val resultOrigin: String? = null,
    val error: String? = null,
    val updatedAt: Long,
)

/** A delete/reimport of the same URL must not inherit an upload's metadata write. */
@Entity(tableName = "book_info_upload_leases", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["bookUrl"], childColumns = ["bookUrl"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BookInfoUploadCommand::class, parentColumns = ["id"], childColumns = ["commandId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["commandId"], unique = true)])
data class BookInfoUploadLease(@PrimaryKey val bookUrl: String, val commandId: String)
