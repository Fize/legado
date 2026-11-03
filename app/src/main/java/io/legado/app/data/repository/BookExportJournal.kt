package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.annotation.Keep
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.legado.app.utils.*
import io.legado.app.service.replaceStagedExport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Keep internal data class ExportRecoveryCommand(val id: String,val dispatch: String)
internal data class ExportRecoveryItem(val id: String,val name: String,val directory: String,val hadOriginal: Boolean,
    val phase: String,val command: ExportRecoveryCommand?,val corrupt: Boolean=false)
@Keep internal data class ExportJournalCommand(val dispatch: String,val process: String,val action: String,val phase: String)
@Keep internal data class ExportJournalRecord(val id: String,val directory: String,val filename: String,val staging: String,
    val backup: String,val stagingUri: String,val originalDigest: String?,val digest: String,
    val version: Int=1,val phase: String="prepared",val command: ExportJournalCommand?=null)

/** Independent of Room: a killed publication transaction must not roll its recovery record back. */
@Singleton
class BookExportJournal internal constructor(private val root: File,private val process: String,
    private val afterCheckpoint: (ExportJournalRecord) -> Unit = {}) {
    @Inject constructor(@ApplicationContext context: Context): this(File(context.filesDir,"book-export-journal"),BookTransferProcess.id)
    private val metadata=ReentrantLock()
    private val targets=Array(32) { ReentrantLock() }
    private val publishing=hashSetOf<String>()
    private val revision=MutableStateFlow(0L)
    private fun changed() { revision.update { it+1 } }
    private fun validId(id: String) { require(UUID.fromString(id).toString()==id) }
    private fun file(id: String): AtomicFile { validId(id); return AtomicFile(File(root,"$id.json")) }
    private fun read(id: String): ExportJournalRecord {
        val record=file(id).openRead().use { input ->
            require(input.channel.size()<=131_072)
            checkNotNull(GSON.fromJson(input.reader(Charsets.UTF_8),ExportJournalRecord::class.java))
        }
        validate(record,id)
        return record
    }
    private fun validate(record: ExportJournalRecord,id: String=record.id) {
        validId(id)
        require(record.version==1 && record.id==id && record.directory.length<=32_768)
        require(record.phase in listOf("prepared","backed_up","installed","rolled_back","published","recovering"))
        require(record.directory.startsWith("content://") || File(record.directory).isAbsolute)
        listOf(record.filename,record.staging,record.backup).forEach { name ->
            require(name.isNotBlank() && name.length<=4096 && '/' !in name && '\\' !in name && name!="." && name!="..")
        }
        require(record.filename!=record.staging && record.filename!=record.backup && record.staging!=record.backup)
        require(record.digest.matches(Regex("[0-9a-f]{64}")))
        require(record.originalDigest==null || record.originalDigest.matches(Regex("[0-9a-f]{64}")))
        record.command?.let { validId(it.dispatch); require(it.action in listOf("restore","keep")); require(it.phase in listOf("queued","running","cancelled","interrupted","failed")) }
    }
    private fun write(record: ExportJournalRecord) {
        validate(record)
        check(root.isDirectory || root.mkdirs())
        val atomic=file(record.id); val output=atomic.startWrite()
        try { val writer=output.writer(Charsets.UTF_8); GSON.toJson(record,writer); writer.flush(); atomic.finishWrite(output) }
        catch(error: Throwable) { atomic.failWrite(output); throw error }
        changed()
    }
    private fun ids(): List<String> = root.listFiles().orEmpty().mapNotNull { entry ->
        val id=entry.name.substringBefore('.')
        id.takeIf { runCatching { validId(it) }.isSuccess && entry.name.removePrefix(id) in listOf(".json",".json.bak",".json.new") }
    }.distinct().sorted()
    private fun directoryKey(directory: String): String {
        val uri=Uri.parse(directory)
        return if(uri.scheme=="content") uri.authority+":"+runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse { DocumentsContract.getTreeDocumentId(uri) }
            else File(if(uri.scheme=="file") checkNotNull(uri.path) else directory).canonicalPath
    }
    private fun target(record: ExportJournalRecord)=targets[(directoryKey(record.directory)+"\n"+record.filename).hashCode().and(Int.MAX_VALUE)%targets.size]
    private fun remove(id: String) {
        val atomic=file(id); atomic.delete()
        check(listOf("",".bak",".new").none { File(atomic.baseFile.path+it).exists() })
        changed()
    }
    private fun digest(doc: FileDoc,verify: () -> Unit): String {
        val hash=MessageDigest.getInstance("SHA-256")
        doc.openInputStream().getOrThrow().use { input ->
            val buffer=ByteArray(64*1024)
            while(true) { verify(); val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) }
        }
        verify()
        return hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    }
    private fun matching(doc: FileDoc?,expected: String?,verify: () -> Unit): Boolean =
        doc!=null && expected!=null && digest(doc,verify)==expected
    private fun delete(doc: FileDoc) { doc.delete(); check(!doc.exists()) { "Cannot remove owned export fragment" } }
    private fun move(doc: FileDoc,folder: FileDoc,name: String): FileDoc {
        check(folder.find(name)==null) { "Export destination already exists" }
        val moved=doc.asFile()?.let { source ->
            val destination=checkNotNull(source.parentFile).resolve(name)
            check(source.renameTo(destination)) { "Cannot rename export file" }; FileDoc.fromFile(destination)
        } ?: DocumentsContract.renameDocument(splitties.init.appCtx.contentResolver,doc.uri,name)
            ?.let { FileDoc.fromUri(it,false) }
        return checkNotNull(moved).also { check(it.name==name) { "Provider changed the export name" } }
    }
    private fun checkpoint(id: String,phase: String) {
        val value=metadata.withLock { read(id).copy(phase=phase).also(::write) }
        afterCheckpoint(value)
    }
    internal fun isRetained(staging: FileDoc)=metadata.withLock {
        ids().any { id -> runCatching { read(id).stagingUri==staging.uri.toString() }.getOrDefault(true) }
    }
    internal fun items(): List<ExportRecoveryItem> = metadata.withLock {
        java.util.Collections.unmodifiableList(ids().filterNot(publishing::contains).map { id ->
            runCatching {
                val record=read(id); val command=record.command
                val phase=if(command==null) "pending" else if(command.process!=process) "interrupted" else command.phase
                ExportRecoveryItem(id,record.filename,record.directory,record.originalDigest!=null,phase,
                    command?.takeIf { it.process==process && it.phase in listOf("queued","running") }?.let { ExportRecoveryCommand(id,it.dispatch) })
            }.getOrElse { ExportRecoveryItem(id,"","",false,"failed",null,true) }
        })
    }
    internal fun observe(): Flow<List<ExportRecoveryItem>> = revision.map { items() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    internal fun checkDestination(folder: FileDoc,filename: String)=metadata.withLock {
        check(ids().none { id -> runCatching { read(id).let { directoryKey(it.directory)==directoryKey(folder.toString()) && it.filename==filename } }.getOrDefault(true) }) {
            "An interrupted export must be recovered first"
        }
    }
    internal fun forgetCorrupt(id: String)=metadata.withLock {
        check(id !in publishing && runCatching { read(id) }.isFailure)
        remove(id)
    }

    /** The record is synced before publication enters the task ownership transaction. */
    internal fun publish(folder: FileDoc,staging: FileDoc,filename: String,backupName: String,
        verify: () -> Unit,publication: (() -> FileDoc) -> FileDoc): FileDoc {
        val seed=ExportJournalRecord(UUID.randomUUID().toString(),folder.toString(),filename,staging.name,backupName,staging.uri.toString(),null,"")
        return target(seed).withLock {
            verify()
            checkDestination(folder,filename)
            val original=folder.find(filename)
            val record=seed.copy(originalDigest=original?.let { digest(it,verify) },digest=digest(staging,verify))
            metadata.withLock { write(record); publishing.add(record.id) }
            var published=false
            try {
                val installed=publication {
                    verify()
                    check(if(original==null) folder.find(filename)==null else matching(folder.find(filename),record.originalDigest,verify)) { "Existing export changed" }
                    replaceStagedExport(current=original,
                        backupCurrent={ move(it,folder,backupName).also { checkpoint(record.id,"backed_up") } },
                        activateStaged={ verify(); move(staging,folder,filename).also { checkpoint(record.id,"installed") } },
                        restoreCurrent={ move(it,folder,filename).also { checkpoint(record.id,"rolled_back") } },
                        deleteBackup={})
                }
                // Keep the original backup until the ownership transaction has committed.
                published=true; checkpoint(record.id,"published")
                folder.find(backupName)?.let { check(matching(it,record.originalDigest,{})); delete(it) }
                installed
            } finally {
                // Resolve by name: a provider may retain the URI after a rename.
                try {
                    val current=folder.find(filename); val backup=folder.find(backupName)
                    val settled=backup==null && (published && matching(current,record.digest,{}) ||
                        !published && record.originalDigest!=null && matching(current,record.originalDigest,{}) ||
                        !published && record.originalDigest==null && current==null)
                    if(settled) metadata.withLock { remove(record.id) }
                } catch(_: Exception) { /* Preserve the journal if publication cannot be reconciled. */ }
                finally { metadata.withLock { publishing.remove(record.id); changed() } }
            }
        }
    }

    internal fun request(id: String,action: String,dispatch: String,directory: String): ExportRecoveryCommand = metadata.withLock {
        validId(dispatch); require(action in listOf("restore","keep")); check(id !in publishing)
        val record=read(id); require(directoryKey(directory)==directoryKey(record.directory)) { "Choose the original export directory" }
        val old=record.command
        check(old?.let { it.process==process && it.phase=="running" }!=true) { "Recovery is already running" }
        write(record.copy(directory=directory,command=ExportJournalCommand(dispatch,process,action,"queued")))
        ExportRecoveryCommand(id,dispatch)
    }
    internal fun next(): ExportRecoveryCommand? = metadata.withLock { ids().firstNotNullOfOrNull { id ->
        runCatching { read(id).command?.takeIf { it.process==process && it.phase=="queued" }?.let { ExportRecoveryCommand(id,it.dispatch) } }.getOrNull()
    } }
    internal fun pending(ref: ExportRecoveryCommand): Boolean = metadata.withLock {
        runCatching { read(ref.id).command?.let { it.dispatch==ref.dispatch && it.process==process && it.phase=="queued" }==true }.getOrDefault(false)
    }
    internal fun cancel(ref: ExportRecoveryCommand)=metadata.withLock {
        val record=runCatching { read(ref.id) }.getOrNull() ?: return@withLock
        val command=record.command ?: return@withLock
        if(command.dispatch==ref.dispatch && command.process==process && command.phase in listOf("queued","running"))
            write(record.copy(command=command.copy(phase="cancelled")))
    }
    internal fun running(ref: ExportRecoveryCommand): Flow<Boolean> = revision.map { metadata.withLock {
        runCatching { read(ref.id).command?.let { it.dispatch==ref.dispatch && it.process==process && it.phase=="running" }==true }.getOrDefault(false)
    } }.distinctUntilChanged().flowOn(Dispatchers.IO)
    internal suspend fun execute(ref: ExportRecoveryCommand)=withContext(Dispatchers.IO) {
        val execution=currentCoroutineContext()
        val record=metadata.withLock {
            val record=read(ref.id); val command=checkNotNull(record.command)
            if(command.dispatch!=ref.dispatch || command.process!=process || command.phase!="queued") return@withContext
            record.copy(command=command.copy(phase="running")).also(::write)
        }
        fun verify() {
            execution.ensureActive()
            val active=metadata.withLock { val command=read(ref.id).command; command?.dispatch==ref.dispatch && command.process==process && command.phase=="running" }
            if(!active) throw CancellationException("Recovery was cancelled")
        }
        try {
            target(record).withLock {
                val folder=FileDoc.fromDir(record.directory)
                fun find(name: String)=folder.find(name)
                fun checked(doc: FileDoc?,hash: String?): FileDoc = checkNotNull(doc).also { check(matching(it,hash,::verify)) { "Export file changed outside this task" } }
                fun removeOwned(name: String,hash: String?) { find(name)?.let { checked(it,hash); verify(); delete(it) } }
                fun moveOwned(name: String,newName: String,hash: String?) { val source=checked(find(name),hash); verify(); move(source,folder,newName); checkpoint(ref.id,"recovering") }
                verify()
                if(record.command!!.action=="restore") {
                    val current=find(record.filename)
                    if(record.originalDigest!=null) {
                        if(!matching(current,record.originalDigest,::verify)) {
                            checked(find(record.backup),record.originalDigest)
                            if(current!=null) {
                                checked(current,record.digest)
                                if(find(record.staging)==null) moveOwned(record.filename,record.staging,record.digest)
                                else { checked(find(record.staging),record.digest); removeOwned(record.filename,record.digest) }
                            }
                            moveOwned(record.backup,record.filename,record.originalDigest)
                        }
                        checked(find(record.filename),record.originalDigest)
                        removeOwned(record.backup,record.originalDigest)
                    } else {
                        check(find(record.backup)==null) { "Unexpected export backup" }
                        removeOwned(record.filename,record.digest)
                    }
                    removeOwned(record.staging,record.digest)
                } else {
                    if(!matching(find(record.filename),record.digest,::verify)) {
                        checked(find(record.staging),record.digest)
                        find(record.filename)?.let {
                            checked(it,record.originalDigest)
                            if(find(record.backup)==null) moveOwned(record.filename,record.backup,record.originalDigest)
                            else { checked(find(record.backup),record.originalDigest); removeOwned(record.filename,record.originalDigest) }
                        }
                        moveOwned(record.staging,record.filename,record.digest)
                    }
                    checked(find(record.filename),record.digest)
                    removeOwned(record.staging,record.digest); removeOwned(record.backup,record.originalDigest)
                }
                verify(); metadata.withLock { verify(); remove(ref.id) }
            }
        } catch(error: Throwable) {
            metadata.withLock {
                runCatching { val latest=read(ref.id); val command=latest.command
                    if(command?.dispatch==ref.dispatch && command.phase=="running") write(latest.copy(command=command.copy(phase=if(error is CancellationException) "interrupted" else "failed"))) }
            }
            throw error
        }
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
internal interface BookExportJournalEntryPoint { fun exportJournal(): BookExportJournal }
internal fun exportJournal(context: Context): BookExportJournal = EntryPointAccessors.fromApplication(context.applicationContext,BookExportJournalEntryPoint::class.java).exportJournal()
