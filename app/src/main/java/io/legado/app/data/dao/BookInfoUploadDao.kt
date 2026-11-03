package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookInfoUploadDao {
    @Query("SELECT * FROM book_info_upload_commands WHERE id = :id")
    fun get(id: String): BookInfoUploadCommand?
    @Query("SELECT * FROM book_info_upload_commands WHERE requestId = :requestId ORDER BY updatedAt DESC LIMIT 1")
    fun latestForRequest(requestId: String): BookInfoUploadCommand?
    @Query("SELECT * FROM book_info_upload_commands WHERE id = :id")
    fun observe(id: String): Flow<BookInfoUploadCommand?>
    @Query("SELECT c.* FROM book_info_upload_commands c JOIN book_info_upload_leases l ON l.commandId = c.id WHERE l.bookUrl = :bookUrl AND c.status IN ('Queued','Running','Failed') LIMIT 1")
    fun observeBook(bookUrl: String): Flow<BookInfoUploadCommand?>
    @Query("SELECT * FROM book_info_upload_commands WHERE status IN ('Queued','Running','Failed')")
    fun pending(): List<BookInfoUploadCommand>
    @Query("SELECT * FROM book_info_upload_commands WHERE status IN ('Completed','Cancelled')")
    fun terminal(): List<BookInfoUploadCommand>
    @Query("DELETE FROM book_info_upload_commands WHERE status IN ('Completed','Cancelled') AND NOT EXISTS (SELECT 1 FROM book_info_requests r WHERE r.id = requestId) AND (updatedAt < :before OR id IN (SELECT c.id FROM book_info_upload_commands c WHERE c.status IN ('Completed','Cancelled') AND NOT EXISTS (SELECT 1 FROM book_info_requests r WHERE r.id = c.requestId) ORDER BY c.updatedAt DESC, c.id DESC LIMIT -1 OFFSET 128))")
    fun pruneTerminal(before: Long): Int
    @Query("SELECT * FROM book_info_upload_leases WHERE bookUrl = :bookUrl")
    fun lease(bookUrl: String): BookInfoUploadLease?
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insert(command: BookInfoUploadCommand)
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertLease(lease: BookInfoUploadLease)
    @Update fun update(command: BookInfoUploadCommand): Int
    @Query("DELETE FROM book_info_upload_leases WHERE commandId = :id")
    fun release(id: String): Int
    @Query("DELETE FROM book_info_upload_commands WHERE id = :id AND status IN ('Completed','Cancelled')")
    fun deleteTerminal(id: String): Int
}
