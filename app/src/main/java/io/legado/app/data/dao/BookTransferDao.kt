package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookTransferDao {
    @Query("SELECT * FROM book_transfer_tasks ORDER BY createdAt,id") fun observe(): Flow<List<BookTransferTask>>
    @Query("SELECT * FROM book_transfer_tasks WHERE kind=:kind AND phase='queued' ORDER BY createdAt,id LIMIT 1") fun next(kind: String): BookTransferTask?
    @Query("SELECT * FROM book_transfer_tasks WHERE id=:id") fun get(id: String): BookTransferTask?
    @Query("SELECT * FROM book_transfer_tasks WHERE kind=:kind AND phase IN ('queued','running')") fun active(kind: String): List<BookTransferTask>
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insert(task: BookTransferTask)
    @Update fun update(task: BookTransferTask)
    @Query("SELECT taskId FROM book_transfer_leases WHERE bookUrl=:url AND kind=:kind") fun lease(url: String,kind: String): String?
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun lease(value: BookTransferLease)
    @Query("SELECT * FROM cache_manager_states WHERE id=:id") fun manager(id: String): CacheManagerRecord?
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun manager(record: CacheManagerRecord)
    @Query("DELETE FROM cache_manager_states WHERE id=:id") fun deleteManager(id: String)
    @Query("DELETE FROM book_transfer_tasks WHERE id=:id") fun deleteTask(id: String)
    @Query("SELECT t.id,t.bookUrl,t.bookKey,t.kind,t.phase,t.progress,t.total,t.error FROM book_transfer_tasks t INNER JOIN book_transfer_leases l ON l.taskId=t.id ORDER BY t.createdAt,t.id")
    fun observeCurrent(): Flow<List<BookTransferDisplay>>
    @Query("SELECT * FROM book_transfer_batches WHERE id=:id") fun batch(id: String): BookTransferBatch?
    @Query("SELECT * FROM book_transfer_batches WHERE kind='download' AND phase NOT IN ('completed','failed','cancelled')")
    fun unfinishedDownloadBatches(): List<BookTransferBatch>
    @Query("SELECT * FROM book_transfer_batches WHERE phase NOT IN ('completed','failed','cancelled')")
    fun unfinishedTransferBatches(): List<BookTransferBatch>
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insert(batch: BookTransferBatch)
    @Update fun update(batch: BookTransferBatch)
    @Query("SELECT * FROM book_transfer_tasks WHERE batchId=:id ORDER BY rowid") fun batchTasks(id: String): List<BookTransferTask>
    @Query("SELECT * FROM book_transfer_tasks WHERE batchId=:id AND phase='queued' ORDER BY rowid LIMIT 1")
    fun nextInBatch(id: String): BookTransferTask?
    @Query("SELECT coalesce(sum(phase IN ('queued','running')),0) AS remaining,coalesce(sum(phase IN ('failed','interrupted')),0) AS failed,coalesce(sum(phase='cancelled'),0) AS cancelled,count(*) AS total FROM book_transfer_tasks WHERE batchId=:id")
    fun batchSummary(id: String): BookTransferBatchSummary
    @Query("UPDATE book_transfer_tasks SET phase=:phase,owner='' WHERE batchId=:id AND phase IN ('queued','running')")
    fun stopBatchTasks(id: String,phase: String)
    @Query("UPDATE book_transfer_tasks SET phase='queued',owner='',process=:process WHERE batchId=:id AND phase IN ('queued','running','interrupted')")
    fun resumeBatchTasks(id: String,process: String)
    @Query("SELECT * FROM book_transfer_batches WHERE kind='local' AND process=:process AND phase='scheduled' ORDER BY rowid LIMIT 1")
    fun nextLocal(process: String): BookTransferBatch?
    @Query("SELECT phase,owner FROM book_transfer_tasks WHERE id=:id")
    fun execution(id: String): Flow<BookTransferExecution?>
    @Query("SELECT coalesce(sum(CASE WHEN phase='completed' THEN 100 WHEN total>0 THEN min(progress*100/total,100) ELSE 0 END),0) AS progress,count(*)*100 AS total FROM book_transfer_tasks WHERE batchId=:id")
    fun batchProgress(id: String): Flow<BookTransferBatchProgress>
}
