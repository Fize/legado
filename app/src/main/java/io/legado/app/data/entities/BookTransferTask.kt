package io.legado.app.data.entities

import androidx.room.*

@Entity(tableName="book_transfer_tasks",indices=[Index("bookUrl"),Index("kind"),Index("phase"),Index("batchId")],foreignKeys=[
    ForeignKey(entity=BookTransferBatch::class,parentColumns=["id"],childColumns=["batchId"],onDelete=ForeignKey.SET_NULL)])
data class BookTransferTask(@PrimaryKey val id: String,val kind: String,val bookUrl: String,val bookKey: String,
    val config: String,val phase: String="queued",val progress: Int=0,val total: Int=0,val error: String?=null,
    val process: String="",val owner: String="",val createdAt: Long=System.currentTimeMillis(),@ColumnInfo(defaultValue="NULL") val batchId: String?=null)
@Entity(tableName="book_transfer_leases",primaryKeys=["bookUrl","kind"],foreignKeys=[
    ForeignKey(entity=Book::class,parentColumns=["bookUrl"],childColumns=["bookUrl"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=BookTransferTask::class,parentColumns=["id"],childColumns=["taskId"],onDelete=ForeignKey.CASCADE)],indices=[Index("taskId")])
data class BookTransferLease(val bookUrl: String,val kind: String,val taskId: String)
@Entity(tableName="cache_manager_states")
data class CacheManagerRecord(@PrimaryKey val id: String,val owner: String,val process: String,val epoch: Long,
    val revision: Long,val state: String,val closed: Boolean=false)

@Entity(tableName="book_transfer_batches",indices=[Index("kind"),Index("phase")])
data class BookTransferBatch(@PrimaryKey val id: String,val kind: String,val process: String,
    val dispatch: String,val phase: String="prepared",val owner: String="",val createdAt: Long=System.currentTimeMillis())

data class BookTransferDisplay(val id: String,val bookUrl: String,val bookKey: String,val kind: String,
    val phase: String,val progress: Int,val total: Int,val error: String?)

data class BookTransferExecution(val phase: String,val owner: String)
data class BookTransferBatchProgress(val progress: Long,val total: Long)
data class BookTransferBatchSummary(val remaining: Int,val failed: Int,val cancelled: Int,val total: Int)
