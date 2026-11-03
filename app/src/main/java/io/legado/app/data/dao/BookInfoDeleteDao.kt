package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookInfoDeleteDao {
    @Query("SELECT * FROM book_info_delete_commands WHERE id = :id")
    fun get(id: String): BookInfoDeleteCommand?
    @Query("SELECT * FROM book_info_delete_commands WHERE id = :id")
    fun observe(id: String): Flow<BookInfoDeleteCommand?>
    @Query("SELECT c.* FROM book_info_delete_commands c JOIN book_info_deletion_leases l ON l.commandId = c.id WHERE l.bookUrl = :bookUrl AND c.status IN ('Queued','Running','Failed') LIMIT 1")
    fun observeBook(bookUrl: String): Flow<BookInfoDeleteCommand?>
    @Query("SELECT * FROM book_info_delete_commands WHERE status IN ('Queued','Running','Failed')")
    fun pending(): List<BookInfoDeleteCommand>
    @Query("SELECT * FROM book_info_deletion_leases WHERE bookUrl = :bookUrl")
    fun lease(bookUrl: String): BookInfoDeletionLease?
    @Query("SELECT * FROM book_info_delete_commands WHERE requestId = :requestId ORDER BY updatedAt DESC LIMIT 1")
    fun latestForRequest(requestId: String): BookInfoDeleteCommand?
    @Query("DELETE FROM book_info_delete_commands WHERE id = :id AND status IN ('Completed','Cancelled')")
    fun deleteTerminal(id: String): Int
    @Query("UPDATE book_info_delete_commands SET snapshot = '', target = NULL WHERE status IN ('Completed','Cancelled') AND (snapshot != '' OR target IS NOT NULL)")
    fun minimizeTerminal(): Int
    // A live detail entry may still acknowledge/retry its command: retain its tombstone.
    @Query("DELETE FROM book_info_delete_commands WHERE status IN ('Completed','Cancelled') AND NOT EXISTS (SELECT 1 FROM book_info_requests r WHERE r.id = requestId) AND (updatedAt < :before OR id IN (SELECT c.id FROM book_info_delete_commands c WHERE c.status IN ('Completed','Cancelled') AND NOT EXISTS (SELECT 1 FROM book_info_requests r WHERE r.id = c.requestId) ORDER BY c.updatedAt DESC, c.id DESC LIMIT -1 OFFSET 128))")
    fun pruneTerminal(before: Long): Int
    @Query("SELECT EXISTS(SELECT 1 FROM book_info_deletion_leases l JOIN book_info_delete_commands c ON c.id = l.commandId WHERE l.bookUrl = :bookUrl AND c.status IN ('Queued','Running'))")
    fun blocksBook(bookUrl: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(command: BookInfoDeleteCommand)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertLease(lease: BookInfoDeletionLease)
    @Update fun update(command: BookInfoDeleteCommand): Int
    @Query("DELETE FROM book_info_deletion_leases WHERE commandId = :id")
    fun release(id: String): Int
    @Query("UPDATE book_info_requests SET deleted = 1, revision = revision + 1, updatedAt = MAX(updatedAt, :now) WHERE bookUrl = :bookUrl AND deleted = 0")
    fun publishDeleted(bookUrl: String, now: Long): Int
}
