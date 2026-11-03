package io.legado.app.data.repository

import androidx.annotation.Keep
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Keep data class BookTransferConfig(val options: CacheExportOptions=CacheExportOptions(),val start: Int=0,val end: Int=-1,
    val section: String?=null,val chunk: Int=1,val webDavKey: String?=null,val threads: Int=32)
data class BookTransferRequest(val ref: CacheBookRef,val kind: String,val config: BookTransferConfig)
@Keep data class CacheBookRef(val url: String,val key: String)
internal object BookTransferProcess { val id=UUID.randomUUID().toString() }
internal fun transferSourceKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
internal fun transferBookKey(book: Book): String=transferSourceKey(GSON.toJson(listOf(book.bookUrl,book.origin,
    book.originName,book.name,book.author,book.type.toString(),book.charset,book.tocUrl)))

/** Called in an ownership transaction after parsing a changed local file. */
internal fun persistLocalTransferChapters(db: AppDatabase, parsed: Book, chapters: List<BookChapter>): Book {
    val current=checkNotNull(db.bookDao.getBook(parsed.bookUrl))
    require(current.isLocal && parsed.isLocal && chapters.isNotEmpty())
    require(chapters.withIndex().all { (index,chapter) -> chapter.bookUrl==parsed.bookUrl && chapter.index==index })
    val updated=current.copy(charset=parsed.charset,tocUrl=parsed.tocUrl,wordCount=parsed.wordCount,
        totalChapterNum=chapters.size,latestChapterTitle=parsed.latestChapterTitle,latestChapterTime=parsed.latestChapterTime)
    db.bookChapterDao.delByBook(parsed.bookUrl)
    db.bookChapterDao.insert(*chapters.toTypedArray())
    db.bookDao.update(updated)
    return updated
}

@Singleton
class BookTransferRepository @Inject constructor(private val db: AppDatabase) {
    val tasks get()=db.bookTransferDao.observe()
    private val dao get()=db.bookTransferDao
    private fun book(ref: CacheBookRef)=checkNotNull(db.bookDao.getBook(ref.url)).also { check(transferBookKey(it)==ref.key) { "Book changed" } }
    private fun current(task: BookTransferTask) {
        check(dao.lease(task.bookUrl,task.kind)==task.id) { "Task no longer owns this book" }
        val identity=checkNotNull(db.bookDao.cacheIdentity(task.bookUrl)).identityBook()
        check(transferBookKey(identity)==task.bookKey) { "Book changed" }
    }
    suspend fun enqueue(refs: List<CacheBookRef>,kind: String,config: BookTransferConfig): List<String> = withContext(Dispatchers.IO) {
        enqueueNow(refs,kind,config)
    }
    internal fun enqueueNow(refs: List<CacheBookRef>,kind: String,config: BookTransferConfig): List<String> =
        enqueueManyNow(refs.map { BookTransferRequest(it,kind,config) })
    private fun lane(kind: String,config: BookTransferConfig)=if(kind=="download") "download" else if(config.options.webDav) "upload" else "local"
    private fun newBatch(kind: String)=BookTransferBatch(UUID.randomUUID().toString(),kind,BookTransferProcess.id,
        UUID.randomUUID().toString()).also(dao::insert)
    internal fun enqueueManyNow(requests: List<BookTransferRequest>): List<String> {
        require(requests.isNotEmpty() && requests.map { it.ref.url to it.kind }.distinct().size==requests.size)
        requests.forEach { (_,kind,config) ->
            require(kind in listOf("download","export"))
            if(kind=="export") { config.options.validate(); require(config.options.directory.isNotBlank()); require(config.chunk>0)
                require(!config.options.webDav || !config.webDavKey.isNullOrBlank()) }
            else require(config.start>=0 && (config.end==-1 || config.end>=config.start) && config.threads in 1..128)
        }
        return db.runInTransaction<List<String>> {
            requests.forEach { book(it.ref) }
            val batches=hashMapOf<String,BookTransferBatch>()
            requests.map { (ref,kind,config) ->
                val prior=dao.lease(ref.url,kind)?.let(dao::get)
                if(prior?.phase in listOf("queued","running") && prior?.bookKey==ref.key && prior.process==BookTransferProcess.id) {
                    check(prior.config==GSON.toJson(config)) { "A different task is already active for this book" }
                    prior.id
                } else {
                    prior?.takeIf { it.phase in listOf("queued","running") }?.let {
                        dao.update(it.copy(phase=if(it.process==BookTransferProcess.id) "cancelled" else "interrupted",owner=""))
                    }
                    val batch=batches.getOrPut(lane(kind,config)) { newBatch(lane(kind,config)) }
                    val task=BookTransferTask(UUID.randomUUID().toString(),kind,ref.url,ref.key,GSON.toJson(config),
                        process=BookTransferProcess.id,batchId=batch.id)
                    dao.insert(task); dao.lease(BookTransferLease(ref.url,kind,task.id)); task.id
                }
            }
        }
    }
    suspend fun enqueueDownload(url: String,start: Int,end: Int): List<String> = withContext(Dispatchers.IO) {
        val book=checkNotNull(db.bookDao.getBook(url))
        enqueue(listOf(CacheBookRef(url,transferBookKey(book))),"download",BookTransferConfig(start=start.coerceAtLeast(0),end=end,threads=io.legado.app.help.config.AppConfig.threadCount.coerceIn(1,128)))
    }
    suspend fun recover(kind: String,force: Boolean=false)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            dao.active(kind).filter { it.process!=BookTransferProcess.id || force && it.phase=="running" }.forEach {
                // External exports are never silently rewritten after a process death.
                dao.update(it.copy(phase="interrupted",owner=""))
            }
        }
    }
    suspend fun claim(kind: String): BookTransferTask?=withContext(Dispatchers.IO) {
        db.runInTransaction<BookTransferTask?> {
            val task=dao.next(kind) ?: return@runInTransaction null
            if(task.process!=BookTransferProcess.id) {
                return@runInTransaction task.copy(phase="interrupted",owner="").also(dao::update)
            }
            try { current(task) }
            catch(_: Exception) { dao.update(task.copy(phase="failed",error="Book changed")); return@runInTransaction task.copy(phase="failed") }
            task.copy(phase="running",process=BookTransferProcess.id,owner=UUID.randomUUID().toString()).also(dao::update)
        }
    }
    internal fun verifyNow(task: BookTransferTask) {
        db.runInTransaction {
            val value=checkNotNull(dao.get(task.id)); check(value.phase=="running" && value.owner==task.owner && value.process==task.process)
            current(value)
        }
    }
    internal fun refreshLocalNow(task: BookTransferTask, parsed: Book, chapters: List<BookChapter>) {
        db.runInTransaction {
            verifyNow(task)
            require(parsed.bookUrl==task.bookUrl)
            val updated=persistLocalTransferChapters(db,parsed,chapters)
            // The owned parser may discover charset/TOC metadata that participates in book identity.
            val owned=checkNotNull(dao.get(task.id))
            dao.update(owned.copy(bookKey=transferBookKey(updated)))
        }
    }
    internal fun refreshRemoteNow(task: BookTransferTask,parsed: Book,chapters: List<BookChapter>) {
        db.runInTransaction {
            verifyNow(task)
            require(parsed.bookUrl==task.bookUrl && chapters.isNotEmpty())
            check(db.bookChapterDao.getChapterCount(task.bookUrl)==0) { "Book directory changed" }
            require(chapters.withIndex().all { (index,chapter) -> chapter.bookUrl==task.bookUrl && chapter.index==index })
            val current=checkNotNull(db.bookDao.getBook(task.bookUrl))
            val updated=current.copy(tocUrl=parsed.tocUrl,charset=parsed.charset,type=parsed.type,
                totalChapterNum=chapters.size,latestChapterTitle=parsed.latestChapterTitle,
                latestChapterTime=parsed.latestChapterTime,wordCount=parsed.wordCount ?: current.wordCount)
            db.bookChapterDao.insert(*chapters.toTypedArray()); db.bookDao.update(updated)
            val owned=checkNotNull(dao.get(task.id)); dao.update(owned.copy(bookKey=transferBookKey(updated)))
        }
    }
    /** Keep the final short file replacement in the same ownership transaction as cancel/delete. */
    internal fun <T> publishNow(task: BookTransferTask, action: () -> T): T = db.runInTransaction<T> {
        verifyNow(task)
        action()
    }
    suspend fun verify(task: BookTransferTask)=withContext(Dispatchers.IO) { verifyNow(task) }
    internal fun progressNow(task: BookTransferTask,value: Int,total: Int) {
        db.runInTransaction { val old=checkNotNull(dao.get(task.id)); check(old.owner==task.owner && old.process==task.process && old.phase=="running"); current(old)
            dao.update(old.copy(progress=maxOf(old.progress.coerceAtMost(total.coerceAtLeast(0)),value.coerceIn(0,total.coerceAtLeast(0))),total=total.coerceAtLeast(0))) }
    }
    suspend fun progress(task: BookTransferTask,value: Int,total: Int)=withContext(Dispatchers.IO) { progressNow(task,value,total) }
    suspend fun finish(task: BookTransferTask,error: String?=null)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val old=dao.get(task.id) ?: return@runInTransaction
            if(old.owner!=task.owner || old.phase!="running") return@runInTransaction
            val valid=runCatching { current(old) }.isSuccess
            dao.update(old.copy(phase=if(error==null && valid) "completed" else "failed",error=error?.take(500) ?: if(!valid) "Book changed" else null,owner=""))
        }
    }
    suspend fun cancel(kind: String,url: String?=null)=withContext(Dispatchers.IO) {
        db.runInTransaction { dao.active(kind).filter { url==null || it.bookUrl==url }.forEach { dao.update(it.copy(phase="cancelled",owner="")) } }
    }
    suspend fun cancelIds(ids: List<String>): List<BookTransferBatch> = withContext(Dispatchers.IO) {
        db.runInTransaction<List<BookTransferBatch>> {
            val batches=linkedSetOf<String>()
            ids.forEach { id -> dao.get(id)?.takeIf { it.phase in listOf("queued","running") && dao.lease(it.bookUrl,it.kind)==id }?.let {
                dao.update(it.copy(phase="cancelled",owner="")); it.batchId?.let(batches::add)
            } }
            batches.mapNotNull { id -> dao.batch(id)?.takeIf { dao.batchSummary(id).remaining==0 } }
        }
    }
    suspend fun interrupt(task: BookTransferTask)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val current=dao.get(task.id) ?: return@runInTransaction
            if(current.phase=="running" && current.owner==task.owner && current.process==task.process) {
                dao.update(current.copy(phase="interrupted",owner=""))
            }
        }
    }
    suspend fun retry(id: String)=withContext(Dispatchers.IO) { retryNow(id) }
    internal fun retryNow(id: String) {
        db.runInTransaction { val old=checkNotNull(dao.get(id)); check(old.phase in listOf("failed","interrupted","cancelled")); current(old)
            val config=checkNotNull(GSON.fromJson(old.config,BookTransferConfig::class.java))
            val batch=newBatch(lane(old.kind,config))
            dao.update(old.copy(phase="queued",error=null,progress=0,owner="",process=BookTransferProcess.id,batchId=batch.id)) }
    }
    internal fun queuedNow(id: String) {
        val task=checkNotNull(dao.get(id)); check(task.phase=="queued" && task.process==BookTransferProcess.id && task.batchId!=null)
        current(task)
    }
    suspend fun dispatchCandidates(ids: List<String>): List<BookTransferBatch> = withContext(Dispatchers.IO) {
        db.runInTransaction<List<BookTransferBatch>> {
            ids.mapNotNull { id ->
                val task=dao.get(id) ?: return@mapNotNull null
                if(task.phase !in listOf("queued","running") || task.process!=BookTransferProcess.id) return@mapNotNull null
                current(task)
                task.batchId?.let(dao::batch)
            }.distinctBy { it.id }
        }
    }
    suspend fun scheduled(id: String,dispatch: String)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val batch=checkNotNull(dao.batch(id)); check(batch.dispatch==dispatch && batch.process==BookTransferProcess.id)
            if(batch.phase in listOf("prepared","scheduled")) dao.update(batch.copy(phase="scheduled"))
        }
    }
    suspend fun nextLocalBatch()=withContext(Dispatchers.IO) { dao.nextLocal(BookTransferProcess.id) }
    suspend fun claimBatch(id: String,dispatch: String,allowSystemDownloadResume: Boolean=false,allowProcessResume: Boolean=allowSystemDownloadResume): BookTransferBatch?=withContext(Dispatchers.IO) {
        db.runInTransaction<BookTransferBatch?> {
            val old=dao.batch(id) ?: return@runInTransaction null
            if(old.dispatch!=dispatch || old.phase in listOf("completed","cancelled","failed")) return@runInTransaction null
            val resume=old.kind=="download" && allowSystemDownloadResume && (old.process==BookTransferProcess.id || allowProcessResume)
            if(old.process!=BookTransferProcess.id && !resume) {
                dao.stopBatchTasks(id,"interrupted")
                dao.update(old.copy(phase="interrupted",owner="")); return@runInTransaction null
            }
            if(old.phase=="running" && old.process==BookTransferProcess.id || old.phase=="interrupted" && !resume) return@runInTransaction null
            if(resume) dao.resumeBatchTasks(id,BookTransferProcess.id)
            old.copy(phase="running",owner=UUID.randomUUID().toString(),process=BookTransferProcess.id).also(dao::update)
        }
    }
    private fun batchOwned(batch: BookTransferBatch)=checkNotNull(dao.batch(batch.id)).also {
        check(it.owner==batch.owner && it.dispatch==batch.dispatch && it.phase=="running" && it.process==BookTransferProcess.id)
    }
    suspend fun claimNext(batch: BookTransferBatch): BookTransferTask?=withContext(Dispatchers.IO) {
        db.runInTransaction<BookTransferTask?> {
            batchOwned(batch)
            val task=dao.nextInBatch(batch.id) ?: return@runInTransaction null
            try { check(task.process==BookTransferProcess.id); current(task) }
            catch(_: Exception) { return@runInTransaction task.copy(phase="failed",error="Book changed",owner="").also(dao::update) }
            task.copy(phase="running",owner=UUID.randomUUID().toString(),process=BookTransferProcess.id).also(dao::update)
        }
    }
    suspend fun stopBatch(batch: BookTransferBatch,resumeDownload: Boolean)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val owned=dao.batch(batch.id) ?: return@runInTransaction
            if(owned.owner!=batch.owner || owned.dispatch!=batch.dispatch || owned.phase!="running") return@runInTransaction
            val resume=resumeDownload && owned.kind=="download"
            // A cancelled runner records "interrupted" before this batch cleanup runs.
            // System retries must requeue that task as well as the unstarted books.
            if(resume) dao.resumeBatchTasks(batch.id,BookTransferProcess.id)
            else dao.stopBatchTasks(batch.id,"interrupted")
            dao.update(owned.copy(phase=if(resume) "waiting" else "interrupted",owner=""))
        }
    }
    suspend fun finishBatch(batch: BookTransferBatch)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val owned=batchOwned(batch); val summary=dao.batchSummary(batch.id)
            check(summary.remaining==0)
            dao.update(owned.copy(phase=if(summary.failed>0) "failed" else if(summary.cancelled==summary.total) "cancelled" else "completed",owner=""))
        }
    }
    suspend fun cancelBatch(id: String,dispatch: String)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val batch=dao.batch(id) ?: return@runInTransaction
            if(batch.dispatch!=dispatch) return@runInTransaction
            dao.stopBatchTasks(id,"cancelled")
            dao.update(batch.copy(phase="cancelled",owner=""))
        }
    }
    suspend fun snapshot(task: BookTransferTask)=withContext(Dispatchers.IO) { db.runInTransaction<Book> { verifyNow(task); val owned=checkNotNull(dao.get(task.id)); book(CacheBookRef(owned.bookUrl,owned.bookKey)).infoSnapshot() } }
}
