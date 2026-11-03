package io.legado.app.data.repository

import androidx.annotation.Keep
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Keep data class HighlightRuleRef(val id: Long,val uuid: String,val fingerprint: String)
data class HighlightRuleItem(val ref: HighlightRuleRef,val name: String,val group: String?,val enabled: Boolean) {
    val displayName get()=group?.takeIf(String::isNotBlank)?.let { "[$it] $name" } ?: name
}
@Keep data class HighlightManagerDialog(val id: String=UUID.randomUUID().toString(),val kind: String,
    val refs: List<HighlightRuleRef> = emptyList(),val source: String?=null,val text: String="")
@Keep data class HighlightRuleTransfer(val id: String,val kind: String,val digest: String,val phase: String="prepared",val uri: String?=null,val attempt: String?=null)
@Keep data class HighlightManagerImport(val id: String,val phase: String="awaiting",val uri: String?=null)
@Keep data class HighlightRuleManagerDraft(val filter: String="all",val group: String?=null,
    val selected: List<String> = emptyList(),val groupsOpen: Boolean=false,val dialog: HighlightManagerDialog?=null,
    val transfer: HighlightRuleTransfer?=null,val exportedUri: String?=null,val importRequest: HighlightManagerImport?=null)
data class HighlightManagerOwned(val draft: HighlightRuleManagerDraft,val revision: Long,val closed: Boolean)
internal object HighlightManagerOwnership {
    val process=UUID.randomUUID().toString()
    private val counter=java.util.concurrent.atomic.AtomicLong()
    fun next()=counter.incrementAndGet()
}

@Singleton
class HighlightRuleManagerRepository @Inject constructor(private val db: AppDatabase,private val files: HighlightRuleFiles) {
    fun observe(): Flow<List<HighlightRuleItem>> = db.highlightRuleDao.flowAll().map { rows ->
        val context=currentCoroutineContext()
        java.util.Collections.unmodifiableList(rows.map { rule ->
            context.ensureActive(); HighlightRuleItem(ref(rule),rule.getDisplayName(),rule.group,rule.isEnabled)
        })
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    private fun ref(rule: HighlightRule)=HighlightRuleRef(rule.id,rule.uuid,highlightRuleIdentity(rule))
    private fun decode(json: String): HighlightRuleManagerDraft {
        val value=GSON.fromJson(json,HighlightRuleManagerDraft::class.java)
        require(value.filter in listOf("all","ungrouped","named"))
        value.transfer?.let { transfer ->
            require(UUID.fromString(transfer.id).toString()==transfer.id)
            require(transfer.kind in listOf("export","share") && transfer.phase in listOf("prepared","awaiting","writing","failed"))
            require(transfer.digest.length==64 && transfer.digest.all { it in '0'..'9' || it in 'a'..'f' })
            transfer.attempt?.let { require(UUID.fromString(it).toString()==it) }
        }
        value.importRequest?.let { request ->
            require(UUID.fromString(request.id).toString()==request.id && request.phase in listOf("awaiting","failed","received"))
            if(request.phase=="received") require(request.uri?.startsWith("content://")==true)
        }
        return value.copy(selected=java.util.Collections.unmodifiableList(value.selected.distinct()),
            dialog=value.dialog?.let { it.copy(refs=java.util.Collections.unmodifiableList(it.refs.toList())) })
    }
    private fun owned(id: String,owner: String): HighlightRuleManagerRecord = checkNotNull(db.highlightRuleManagerDao.get(id)).also {
        check(it.owner==owner && !it.closed) { "Rule manager no longer owns this entry" }
    }
    private fun output(record: HighlightRuleManagerRecord)=HighlightManagerOwned(if(record.closed) HighlightRuleManagerDraft() else decode(record.state),record.revision,record.closed)
    suspend fun open(id: String,owner: String,epoch: Long): HighlightManagerOwned=withContext(Dispatchers.IO) {
        require(UUID.fromString(id).toString()==id && UUID.fromString(owner).toString()==owner && epoch>0)
        val claimed=db.runInTransaction<HighlightRuleManagerRecord> {
            val old=db.highlightRuleManagerDao.get(id)
            val record=if(old==null) HighlightRuleManagerRecord(id,owner,HighlightManagerOwnership.process,epoch,0,GSON.toJson(HighlightRuleManagerDraft())).also(db.highlightRuleManagerDao::insert)
            else {
                check(old.process!=HighlightManagerOwnership.process || old.epoch<epoch || old.epoch==epoch && old.owner==owner) { "Stale manager claim" }
                val draft=if(old.closed) HighlightRuleManagerDraft() else runCatching { decode(old.state) }.getOrNull()
                // Keep corrupt drafts until an owned cancel; never silently erase user input on load.
                val recovered=draft?.copy(transfer=draft.transfer?.let { if(it.phase=="writing") it.copy(phase="failed") else it })
                old.copy(owner=owner,process=HighlightManagerOwnership.process,epoch=epoch,state=recovered?.let(GSON::toJson) ?: old.state).also(db.highlightRuleManagerDao::update)
            }
            record
        }
        output(claimed)
    }
    suspend fun persist(id: String,owner: String,revision: Long,draft: HighlightRuleManagerDraft): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            old.copy(revision=revision,state=GSON.toJson(draft)).also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    private fun matched(refs: List<HighlightRuleRef>): List<HighlightRule> {
        check(refs.isNotEmpty() && refs.map { it.id }.distinct().size==refs.size)
        return refs.map { expected -> checkNotNull(db.highlightRuleDao.findById(expected.id)).also { check(ref(it)==expected) { "Rule changed" } } }
    }
    suspend fun act(id: String,owner: String,revision: Long,refs: List<HighlightRuleRef>,action: String): Unit=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val old=owned(id,owner); check(revision>old.revision)
            val rows=matched(refs)
            when(action) {
                "enable","disable" -> db.highlightRuleDao.update(*rows.map { it.copy(isEnabled=action=="enable") }.toTypedArray())
                "top","bottom" -> db.highlightRuleDao.move(rows.mapTo(linkedSetOf()) { it.uuid },action=="top")
                else -> error("Unknown rule action")
            }
            db.highlightRuleManagerDao.update(old.copy(revision=revision))
        }
    }
    suspend fun confirm(id: String,owner: String,revision: Long,dialogId: String,moveTarget: String?=null): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val dialog=checkNotNull(draft.dialog); check(dialog.id==dialogId)
            val rows=matched(dialog.refs)
            if(dialog.source!=null) {
                val current=db.highlightRuleDao.all.filter { it.group==dialog.source }.map(::ref).toSet()
                check(current==dialog.refs.toSet()) { "Group membership changed" }
            }
            when(dialog.kind) {
                "rename" -> {
                    val name=dialog.text.trim(); require(name.isNotEmpty())
                    db.highlightRuleDao.update(*rows.map { it.copy(group=name) }.toTypedArray())
                }
                "delete","deleteGroup" -> db.highlightRuleDao.delete(*rows.toTypedArray())
                "moveGroup" -> {
                    check(moveTarget!=dialog.source)
                    check(moveTarget==null || db.highlightRuleDao.all.any { it.group==moveTarget }) { "Target group changed" }
                    db.highlightRuleDao.update(*rows.map { it.copy(group=moveTarget) }.toTypedArray())
                }
                else -> error("Unknown group action")
            }
            val result=draft.copy(dialog=null)
            old.copy(revision=revision,state=GSON.toJson(result)).also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    suspend fun prepare(id: String,owner: String,revision: Long,refs: List<HighlightRuleRef>,kind: String): HighlightManagerOwned=withContext(Dispatchers.IO) {
        files.withMaintenanceLock {
            require(kind in listOf("export","share"))
            val rows=db.runInTransaction<List<HighlightRule>> { val old=owned(id,owner); check(decode(old.state).transfer==null); matched(refs) }
            val key=UUID.randomUUID().toString()
            try {
                val digest=files.prepare(key,HighlightRuleFile(type=HighlightRuleFile.TYPE,rules=rows))
                db.runInTransaction<HighlightManagerOwned> {
                    val old=owned(id,owner); check(revision>old.revision)
                    val draft=decode(old.state); check(draft.transfer==null)
                    old.copy(revision=revision,state=GSON.toJson(draft.copy(transfer=HighlightRuleTransfer(key,kind,digest))))
                        .also(db.highlightRuleManagerDao::update).let(::output)
                }
            } catch(error: Exception) { files.remove(key); throw error }
        }
    }
    suspend fun transferPhase(id: String,owner: String,revision: Long,key: String,phase: String,uri: String?=null): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val transfer=checkNotNull(draft.transfer); check(transfer.id==key)
            check(phase in listOf("awaiting","writing","failed"))
            check(when(phase) { "awaiting" -> transfer.phase in listOf("prepared","awaiting","failed"); "writing" -> transfer.kind=="export" && transfer.phase in listOf("awaiting","failed"); else -> transfer.phase in listOf("writing","awaiting") })
            old.copy(revision=revision,state=GSON.toJson(draft.copy(transfer=transfer.copy(phase=phase,uri=if(phase=="awaiting") null else uri ?: transfer.uri,
                attempt=if(phase=="awaiting") UUID.randomUUID().toString() else transfer.attempt))))
                .also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    suspend fun write(id: String,owner: String,key: String): HighlightManagerOwned=withContext(Dispatchers.IO) {
        val transfer=db.runInTransaction<HighlightRuleTransfer> { checkNotNull(decode(owned(id,owner).state).transfer).also { check(it.id==key && it.phase=="writing") } }
        files.write(transfer) {
            currentCoroutineContext().ensureActive()
            val current=owned(id,owner)
            check(decode(current.state).transfer==transfer) { "Export owner changed" }
        }
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); val draft=decode(old.state); check(draft.transfer==transfer)
            old.copy(revision=old.revision+1,state=GSON.toJson(draft.copy(transfer=null,exportedUri=transfer.uri)))
                .also(db.highlightRuleManagerDao::update).let(::output)
        }.also { runCatching { files.remove(key) } }
    }
    suspend fun shareUri(transfer: HighlightRuleTransfer)=withContext(Dispatchers.IO) { files.shareUri(transfer) }
    suspend fun clearTransfer(id: String,owner: String,revision: Long,key: String): HighlightManagerOwned=withContext(Dispatchers.IO) {
        var disposable=false
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val transfer=checkNotNull(draft.transfer); check(transfer.id==key)
            if(transfer.kind=="share") files.retain(key) else disposable=true
            old.copy(revision=revision,state=GSON.toJson(draft.copy(transfer=null))).also(db.highlightRuleManagerDao::update).let(::output)
        }.also { if(disposable) runCatching { files.remove(key) } }
    }
    /** A corrupt open draft may still own a payload: preserve all files until it is resolved. */
    suspend fun cleanFiles(now: Long=System.currentTimeMillis()): Int=withContext(Dispatchers.IO) {
        files.withMaintenanceLock {
            db.runInTransaction<Int> {
                val drafts=db.highlightRuleManagerDao.openStates().map { json ->
                    runCatching { decode(json) }.getOrNull() ?: return@runInTransaction 0
                }
                files.clean(drafts.mapNotNull { it.transfer?.id }.toSet(),now)
            }
        }
    }
    suspend fun startImport(id: String,owner: String,revision: Long): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state)
            check(draft.importRequest?.phase!="received" || db.highlightRuleImportDao.get(draft.importRequest.id)?.phase in listOf("completed","cancelled")) { "An import is already pending" }
            old.copy(revision=revision,state=GSON.toJson(draft.copy(importRequest=HighlightManagerImport(UUID.randomUUID().toString()))))
                .also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    suspend fun importResult(id: String,owner: String,revision: Long,attempt: String,uri: String?,failed: Boolean=false): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val request=checkNotNull(draft.importRequest)
            check(request.id==attempt && request.phase=="awaiting")
            if(uri!=null) {
                require(uri.startsWith("content://"))
                db.highlightRuleImportDao.insert(newHighlightImportRequest(attempt,uri,id))
            }
            val next=if(failed) request.copy(phase="failed") else uri?.let { request.copy(phase="received",uri=it) }
            old.copy(revision=revision,state=GSON.toJson(draft.copy(importRequest=next))).also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    suspend fun abandonImport(id: String,owner: String,revision: Long,attempt: String): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); val request=checkNotNull(draft.importRequest)
            check(request.id==attempt && request.phase in listOf("awaiting","failed"))
            old.copy(revision=revision,state=GSON.toJson(draft.copy(importRequest=null))).also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    fun importPhase(attempt: String)=db.highlightRuleImportDao.phase(attempt)
    suspend fun importDestination(id: String,owner: String,attempt: String): HighlightManagerImport?=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerImport?> {
            val request=decode(owned(id,owner).state).importRequest
            if(request?.id!=attempt || request.phase!="received") return@runInTransaction null
            val child=checkNotNull(db.highlightRuleImportDao.get(attempt)); check(child.parent==id)
            request.takeUnless { child.phase in listOf("completed","cancelled") }
        }
    }
    suspend fun clearFinishedImport(id: String,owner: String,revision: Long,attempt: String): HighlightManagerOwned=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightManagerOwned> {
            val old=owned(id,owner); check(revision>old.revision)
            val draft=decode(old.state); check(draft.importRequest?.id==attempt)
            check(db.highlightRuleImportDao.get(attempt)?.phase in listOf("completed","cancelled"))
            old.copy(revision=revision,state=GSON.toJson(draft.copy(importRequest=null))).also(db.highlightRuleManagerDao::update).let(::output)
        }
    }
    suspend fun close(id: String,owner: String)=withContext(Dispatchers.IO) {
        var disposable: String?=null
        db.runInTransaction {
            val old=owned(id,owner)
            val transfer=runCatching { decode(old.state).transfer }.getOrNull()
            if(transfer?.kind=="share") files.retain(transfer.id)
            disposable=transfer?.takeIf { it.kind=="export" }?.id
            db.highlightRuleImportDao.children(id).forEach { child ->
                db.highlightRuleImportDao.clear(child.id)
                db.highlightRuleImportDao.update(child.copy(phase="cancelled",source="",revision=child.revision+1))
            }
            db.highlightRuleManagerDao.update(old.copy(closed=true,state=""))
        }
        disposable?.let { runCatching { files.remove(it) } }
        Unit
    }
}
