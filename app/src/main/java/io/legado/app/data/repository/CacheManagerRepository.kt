package io.legado.app.data.repository

import androidx.annotation.Keep
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.CacheManagerRecord
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.getExportFileName
import io.legado.app.model.parseExportScope
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Keep data class CacheManagerDialog(val id: String=UUID.randomUUID().toString(),val kind: String,
    val refs: List<CacheBookRef> = emptyList(),val afterCurrent: Boolean=true,
    val options: CacheExportOptions=CacheExportOptions(),val split: Boolean=false,
    val scope: String="",val chunk: String="1",val text: String="",val directoryEdited: Boolean=false,val episodeEdited: Boolean=false)
@Keep data class CacheFolderAttempt(val id: String=UUID.randomUUID().toString(),val phase: String="awaiting")
@Keep data class CacheManagerDraft(val groupId: Long=-1,val dialog: CacheManagerDialog?=null,
    val folder: CacheFolderAttempt?=null,val pendingTasks: List<String> = emptyList())
data class CacheManagerOwned(val draft: CacheManagerDraft,val revision: Long,val closed: Boolean)
internal object CacheManagerOwnership {
    val process=UUID.randomUUID().toString()
    private val sequence=AtomicLong()
    fun next()=sequence.incrementAndGet()
}

@Singleton
class CacheManagerRepository @Inject constructor(private val db: AppDatabase,private val tasks: BookTransferRepository) {
    private val dao get()=db.bookTransferDao
    private fun validId(id: String) { require(UUID.fromString(id).toString()==id) }
    private fun decode(json: String): CacheManagerDraft {
        val value=checkNotNull(GSON.fromJson(json,CacheManagerDraft::class.java))
        value.dialog?.let {
            validId(it.id)
            require(it.kind in listOf("download","export","filename","episode","charset","format","folder","recovery","forgetRecovery"))
            if(it.kind=="recovery") { validId(it.text); require(it.scope in listOf("restore","keep")); require(it.refs.isEmpty()) }
            if(it.kind=="forgetRecovery") { validId(it.text); require(it.refs.isEmpty()) }
            require(it.refs.map { ref -> ref.url }.distinct().size==it.refs.size)
            require(it.scope.length<=100_000 && it.text.length<=500_000 && it.chunk.length<=10)
            it.options.validate()
        }
        value.folder?.let { validId(it.id); require(it.phase in listOf("awaiting","failed")) }
        value.pendingTasks.forEach(::validId)
        return value.copy(pendingTasks=java.util.Collections.unmodifiableList(value.pendingTasks.toList()),
            dialog=value.dialog?.let { it.copy(refs=java.util.Collections.unmodifiableList(it.refs.toList())) })
    }
    private fun owned(id: String,owner: String)=checkNotNull(dao.manager(id)).also {
        check(it.owner==owner && !it.closed) { "Cache entry is no longer owned" }
    }
    private fun output(record: CacheManagerRecord)=CacheManagerOwned(if(record.closed) CacheManagerDraft() else decode(record.state),record.revision,record.closed)
    private fun books(refs: List<CacheBookRef>)=refs.map { ref ->
        checkNotNull(db.bookDao.getBook(ref.url)).also { check(transferBookKey(it)==ref.key) { "Book changed" } }
    }
    suspend fun open(id: String,owner: String,epoch: Long,groupId: Long): CacheManagerOwned=withContext(Dispatchers.IO) {
        validId(id); validId(owner); require(epoch>0)
        tasks.recover("download"); tasks.recover("export")
        val claimed=db.runInTransaction<CacheManagerRecord> {
            val old=dao.manager(id)
            val record=if(old==null) CacheManagerRecord(id,owner,CacheManagerOwnership.process,epoch,0,GSON.toJson(CacheManagerDraft(groupId)))
            else {
                check(old.process!=CacheManagerOwnership.process || epoch>old.epoch || epoch==old.epoch && old.owner==owner) { "Stale cache entry claim" }
                val draft=if(old.closed) CacheManagerDraft() else runCatching { decode(old.state) }.getOrNull()
                val recovered=draft?.let { value -> value.copy(pendingTasks=value.pendingTasks.filter { taskId ->
                    dao.get(taskId)?.let { it.phase=="queued" && it.process==BookTransferProcess.id }==true
                }) }
                old.copy(owner=owner,process=CacheManagerOwnership.process,epoch=epoch,state=recovered?.let(GSON::toJson) ?: old.state)
            }
            dao.manager(record); record
        }
        // Claim corrupt drafts too, so their owner can explicitly close them without erasing on load.
        output(claimed)
    }
    suspend fun persist(id: String,owner: String,revision: Long,draft: CacheManagerDraft): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val normalized=decode(GSON.toJson(draft))
            // Only command preparation and acknowledgement may change the durable dispatch receipt.
            check(normalized.pendingTasks==decode(old.state).pendingTasks)
            old.copy(revision=revision,state=GSON.toJson(normalized)).also(dao::manager).let(::output)
        }
    }
    suspend fun prepare(id: String,owner: String,revision: Long,dialog: CacheManagerDialog): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); check(draft.dialog==null && draft.folder==null)
            if(dialog.kind in listOf("download","export")) { require(dialog.refs.isNotEmpty()); books(dialog.refs) }
            val updated=decode(GSON.toJson(draft.copy(dialog=dialog)))
            old.copy(revision=revision,state=GSON.toJson(updated)).also(dao::manager).let(::output)
        }
    }
    suspend fun chooseFolder(id: String,owner: String,revision: Long): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); check(draft.dialog?.kind in listOf("export","folder","recovery"))
            old.copy(revision=revision,state=GSON.toJson(draft.copy(folder=CacheFolderAttempt()))).also(dao::manager).let(::output)
        }
    }
    suspend fun folderResult(id: String,owner: String,revision: Long,attempt: String,uri: String?): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); check(draft.folder?.id==attempt)
            val dialog=checkNotNull(draft.dialog)
            if(uri!=null) require(uri.startsWith("content://") && uri.length<=32_768)
            val updated=if(uri==null) draft.copy(folder=draft.folder.copy(phase="failed"))
                else draft.copy(folder=null,dialog=dialog.copy(options=dialog.options.copy(directory=uri),directoryEdited=true))
            old.copy(revision=revision,state=GSON.toJson(updated)).also(dao::manager).let(::output)
        }
    }
    suspend fun confirm(id: String,owner: String,revision: Long,dialogId: String,webDavKey: String?=null): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val dialog=checkNotNull(draft.dialog); check(dialog.id==dialogId && draft.folder==null)
            val current=books(dialog.refs)
            val ids=when(dialog.kind) {
                "download" -> {
                    val requests=current.filterNot { it.isLocal }.map { book ->
                        BookTransferRequest(CacheBookRef(book.bookUrl,transferBookKey(book)),"download",
                            BookTransferConfig(start=if(dialog.afterCurrent) book.durChapterIndex.coerceAtLeast(0) else 0,end=-1,threads=io.legado.app.help.config.AppConfig.threadCount.coerceIn(1,128)))
                    }
                    if(requests.isEmpty()) emptyList() else tasks.enqueueManyNow(requests)
                }
                "export" -> {
                    val chunk=dialog.chunk.toIntOrNull(); require(chunk!=null && chunk>0)
                    if(dialog.split) { require(dialog.options.format==1); current.forEach { parseExportScope(dialog.scope,db.bookChapterDao.getChapterCount(it.bookUrl)) } }
                    tasks.enqueueNow(dialog.refs,"export",BookTransferConfig(options=dialog.options,
                        section=dialog.scope.takeIf { dialog.split },chunk=chunk,webDavKey=webDavKey))
                }
                else -> error("Not a transfer confirmation")
            }
            old.copy(revision=revision,state=GSON.toJson(draft.copy(dialog=null,folder=null,pendingTasks=(draft.pendingTasks+ids).distinct())))
                .also(dao::manager).let(::output)
        }
    }
    internal suspend fun forgetRecovery(id: String,owner: String,revision: Long,dialogId: String,journal: BookExportJournal): CacheManagerOwned = withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val dialog=checkNotNull(draft.dialog)
            check(dialog.kind=="forgetRecovery" && dialog.id==dialogId && draft.folder==null)
            journal.forgetCorrupt(dialog.text)
            old.copy(revision=revision,state=GSON.toJson(draft.copy(dialog=null))).also(dao::manager).let(::output)
        }
    }
    internal suspend fun confirmRecovery(id: String,owner: String,revision: Long,dialogId: String,journal: BookExportJournal): Pair<CacheManagerOwned,ExportRecoveryCommand> = withContext(Dispatchers.IO) {
        db.runInTransaction<Pair<CacheManagerOwned,ExportRecoveryCommand>> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val dialog=checkNotNull(draft.dialog)
            check(dialog.kind=="recovery" && dialog.id==dialogId && draft.folder==null)
            val command=journal.request(dialog.text,dialog.scope,dialog.id,dialog.options.directory)
            val updated=old.copy(revision=revision,state=GSON.toJson(draft.copy(dialog=null,folder=null)))
            dao.manager(updated); output(updated) to command
        }
    }
    suspend fun requestDispatch(id: String,owner: String,revision: Long,taskId: String): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision); tasks.queuedNow(taskId)
            val draft=decode(old.state)
            old.copy(revision=revision,state=GSON.toJson(draft.copy(pendingTasks=(draft.pendingTasks+taskId).distinct())))
                .also(dao::manager).let(::output)
        }
    }
    suspend fun retryTask(id: String,owner: String,revision: Long,taskId: String): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); tasks.retryNow(taskId)
            old.copy(revision=revision,state=GSON.toJson(draft.copy(pendingTasks=(draft.pendingTasks+taskId).distinct())))
                .also(dao::manager).let(::output)
        }
    }
    suspend fun acknowledge(id: String,owner: String,revision: Long,ids: List<String>): CacheManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<CacheManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision); val draft=decode(old.state)
            old.copy(revision=revision,state=GSON.toJson(draft.copy(pendingTasks=draft.pendingTasks-ids.toSet())))
                .also(dao::manager).let(::output)
        }
    }
    suspend fun updateOptions(id: String,owner: String,preferences: io.legado.app.data.preferences.CacheExportPreferencesRepository,
        change: (CacheExportOptions) -> CacheExportOptions)=withContext(Dispatchers.IO) {
        preferences.update { value -> owned(id,owner); change(value) }
    }
    suspend fun preview(id: String,owner: String,dialogId: String): String=withContext(Dispatchers.IO) {
        val snapshot=db.runInTransaction<Pair<CacheManagerDialog,io.legado.app.data.entities.Book>> {
            val draft=decode(owned(id,owner).state); val dialog=checkNotNull(draft.dialog); check(dialog.id==dialogId)
            dialog to (books(dialog.refs).firstOrNull()?.infoSnapshot() ?: io.legado.app.data.entities.Book(name="name",author="author"))
        }
        val (dialog,book)=snapshot
        val script=if(dialog.kind in listOf("filename","episode")) dialog.text else dialog.options.episodeName
        require(script.isBlank() || io.legado.app.help.book.tryParesExportFileName(script)) { "Invalid filename script" }
        val filename=if(dialog.kind=="filename") book.getExportFileName(dialog.options.type,script)
            else book.getExportFileName("epub",1,script)
        db.runInTransaction {
            val current=decode(owned(id,owner).state).dialog
            check(current==dialog) { "Preview changed" }
        }
        filename.take(4096)
    }
    suspend fun checkOwner(id: String,owner: String)=withContext(Dispatchers.IO) { owned(id,owner); Unit }
    suspend fun close(id: String,owner: String): Unit=withContext(Dispatchers.IO) {
        db.runInTransaction { val old=owned(id,owner); dao.manager(old.copy(closed=true,state=GSON.toJson(CacheManagerDraft()))) }
    }
}
