package io.legado.app.ui.book.cache

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.R
import io.legado.app.data.entities.BookTransferDisplay
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.service.BookTransferScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

internal data class CacheManagerUiState(val draft: CacheManagerDraft=CacheManagerDraft(),val options: CacheExportOptions=CacheExportOptions(),
    val books: List<CacheShelfBook> = emptyList(),val groups: List<CacheShelfGroup> = emptyList(),val transfers: List<BookTransferDisplay> = emptyList(),
    val loading: Boolean=true,val booksLoading: Boolean=true,val busy: Boolean=false,val closed: Boolean=false,val failure: Long=0,val dialogError: Int=0,
    val preview: String="",val folderLaunch: String?=null,val booksFailed: Boolean=false,
    val recoveries: List<ExportRecoveryItem> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CacheManagerViewModel @Inject constructor(private val saved: SavedStateHandle,private val manager: CacheManagerRepository,
    private val shelf: CacheShelfRepository,private val tasks: BookTransferRepository,
    private val preferences: CacheExportPreferencesRepository,private val folders: CacheFolderAccess,
    private val scheduler: BookTransferScheduler,private val journal: BookExportJournal): ViewModel() {
    internal val id: String=saved.get<String>("cache.manager.id") ?: UUID.randomUUID().toString().also { saved["cache.manager.id"]=it }
    private val owner=UUID.randomUUID().toString(); private val epoch=CacheManagerOwnership.next()
    private val mutex=Mutex(); private var initialized=false; private var revision=0L
    private val mutable=MutableStateFlow(CacheManagerUiState())
    internal val state=mutable.asStateFlow()
    private val group=MutableStateFlow<Long?>(null)
    private val reload=MutableStateFlow(0)
    init {
        viewModelScope.launch { preferences.preferences.catch { fail() }.collect { value -> mutable.update { it.copy(options=value) } } }
        viewModelScope.launch { shelf.groups().catch { fail() }.collect { value -> mutable.update { it.copy(groups=value) } } }
        viewModelScope.launch { shelf.transfers().catch { fail() }.collect { value -> mutable.update { it.copy(transfers=value) } } }
        viewModelScope.launch { journal.observe().catch { fail() }.collect { value -> mutable.update { it.copy(recoveries=value) } } }
        viewModelScope.launch {
            combine(group,reload) { selected,_ -> selected }.flatMapLatest { selected ->
                if(selected==null) emptyFlow() else shelf.books(selected).map { selected to it }
                    .onStart { if(group.value==selected) mutable.update { it.copy(booksLoading=true,booksFailed=false) } }
                    .catch { if(group.value==selected) { mutable.update { it.copy(booksFailed=true,booksLoading=false) }; fail() } }
            }.collect { (selected,value) ->
                if(group.value==selected) mutable.update { it.copy(books=value,booksLoading=false,booksFailed=false) }
            }
        }
    }
    internal fun initialize(groupId: Long) {
        if(initialized) return
        initialized=true
        viewModelScope.launch {
            mutex.withLock {
                try { accept(manager.open(id,owner,epoch,groupId)) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { fail(); mutable.update { it.copy(loading=false) } }
            }
            saved.get<String>("cache.folder.result.id")?.let { folderResult(it,saved.get<String>("cache.folder.result.uri")) }
        }
    }
    private fun accept(value: CacheManagerOwned) {
        revision=maxOf(revision,value.revision)
        val changed=group.value!=value.draft.groupId
        group.value=value.draft.groupId
        mutable.update { it.copy(draft=value.draft,loading=false,closed=value.closed,
            books=if(changed) emptyList() else it.books,booksLoading=changed || it.booksLoading) }
    }
    private fun fail() { mutable.update { it.copy(failure=it.failure+1) } }
    private fun ready()=!state.value.loading && !state.value.busy && !state.value.closed
    private fun operation(force: Boolean=false,block: suspend () -> Unit) {
        if(!force && !ready()) return
        if(!force) mutable.update { it.copy(busy=true,dialogError=0) }
        viewModelScope.launch {
            mutex.withLock {
                if(state.value.closed) return@withLock
                mutable.update { it.copy(busy=true) }
                try { block() }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { fail(); mutable.update { it.copy(dialogError=R.string.cache_operation_failed) } }
                finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
    private fun change(transform: (CacheManagerDraft) -> CacheManagerDraft) {
        if(!ready()) return
        val next=transform(state.value.draft); if(next==state.value.draft) return
        val version=++revision
        val changed=group.value!=next.groupId
        mutable.update { it.copy(draft=next,dialogError=0,preview="",books=if(changed) emptyList() else it.books,
            booksLoading=changed || it.booksLoading) }
        group.value=next.groupId
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val persisted=manager.persist(id,owner,version,next)
                    if(revision==version) accept(persisted)
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { fail() }
            }
        }
    }
    internal fun group(value: Long)=change { it.copy(groupId=value) }
    internal fun retryBooks() { reload.update { it+1 } }
    internal fun count(ref: CacheBookRef)=shelf.count(ref)
    internal fun retryCount(ref: CacheBookRef)=shelf.retryCount(ref)
    internal fun download(refs: List<CacheBookRef>,after: Boolean)=operation {
        check(!state.value.booksLoading && !state.value.booksFailed)
        accept(manager.prepare(id,owner,++revision,CacheManagerDialog(kind="download",refs=refs,afterCurrent=after)))
    }
    internal fun export(refs: List<CacheBookRef>)=operation {
        check(!state.value.booksLoading && !state.value.booksFailed)
        val options=preferences.current()
        accept(manager.prepare(id,owner,++revision,CacheManagerDialog(kind="export",refs=refs,options=options,
            split=options.customSections && options.format==1)))
    }
    internal fun recover(item: ExportRecoveryItem,action: String)=operation {
        require(!item.corrupt)
        accept(manager.prepare(id,owner,++revision,CacheManagerDialog(kind="recovery",text=item.id,scope=action,
            options=CacheExportOptions(directory=item.directory))))
    }
    internal fun forgetRecovery(item: ExportRecoveryItem)=operation {
        require(item.corrupt)
        accept(manager.prepare(id,owner,++revision,CacheManagerDialog(kind="forgetRecovery",text=item.id)))
    }
    internal fun startRecovery(ref: ExportRecoveryCommand)=operation {
        manager.checkOwner(id,owner); scheduler.scheduleRecovery(ref)
    }
    internal fun cancelRecovery(ref: ExportRecoveryCommand)=operation {
        manager.checkOwner(id,owner); withContext(Dispatchers.IO) { journal.cancel(ref) }
    }
    internal fun setting(kind: String)=operation {
        val options=preferences.current()
        val text=when(kind) { "filename" -> options.fileName; "episode" -> options.episodeName; "charset" -> options.charset; else -> "" }
        accept(manager.prepare(id,owner,++revision,CacheManagerDialog(kind=kind,options=options,text=text)))
        if(kind=="folder") chooseFolderNow()
    }
    internal fun edit(transform: (CacheManagerDialog) -> CacheManagerDialog)=change { draft -> draft.copy(dialog=draft.dialog?.let(transform)) }
    internal fun dismiss()=change { it.copy(dialog=null,folder=null) }
    internal fun toggle(key: String,value: Boolean)=operation {
        manager.updateOptions(id,owner,preferences) { old -> when(key) {
            "replace" -> old.copy(useReplace=value); "custom" -> old.copy(customSections=value)
            "webdav" -> old.copy(webDav=value); "names" -> old.copy(omitNames=value)
            "pictures" -> old.copy(pictures=value); "parallel" -> old.copy(parallel=value)
            else -> error("Unknown export setting")
        } }
    }
    internal fun preview()=operation {
        val dialog=checkNotNull(state.value.draft.dialog)
        val value=manager.preview(id,owner,dialog.id)
        mutable.update { if(it.draft.dialog==dialog) it.copy(preview=value) else it }
    }
    internal fun confirm()=operation {
        val dialog=checkNotNull(state.value.draft.dialog)
        if(dialog.kind=="forgetRecovery") {
            accept(manager.forgetRecovery(id,owner,++revision,dialog.id,journal))
        } else if(dialog.kind=="recovery") {
            if(!folders.writable(dialog.options.directory)) { chooseFolderNow(); return@operation }
            val (updated,command)=manager.confirmRecovery(id,owner,++revision,dialog.id,journal)
            accept(updated); scheduler.scheduleRecovery(command)
        } else if(dialog.kind=="download" || dialog.kind=="export") {
            if(dialog.kind=="export" && !folders.writable(dialog.options.directory)) { chooseFolderNow(); return@operation }
            if(dialog.kind=="export" && (dialog.directoryEdited || dialog.episodeEdited)) {
                manager.updateOptions(id,owner,preferences) { old ->
                    if(dialog.episodeEdited) require(dialog.options.episodeName.isBlank() || tryParesExportFileName(dialog.options.episodeName))
                    old.copy(directory=if(dialog.directoryEdited) dialog.options.directory else old.directory,
                        episodeName=if(dialog.episodeEdited) dialog.options.episodeName else old.episodeName)
                }
            }
            val remote=if(dialog.kind=="export" && dialog.options.webDav) {
                try { scheduler.webDavKey() } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { mutable.update { it.copy(dialogError=R.string.cache_webdav_setup_required) }; return@operation }
            } else null
            accept(manager.confirm(id,owner,++revision,dialog.id,remote))
        } else {
            manager.updateOptions(id,owner,preferences) { old -> when(dialog.kind) {
                "filename","episode" -> {
                    require(dialog.text.isBlank() || tryParesExportFileName(dialog.text))
                    if(dialog.kind=="filename") old.copy(fileName=dialog.text) else old.copy(episodeName=dialog.text)
                }
                "charset" -> old.copy(charset=dialog.text.ifBlank { "UTF-8" })
                "format" -> old.copy(format=dialog.options.format)
                "folder" -> { require(dialog.options.directory.isNotBlank()); old.copy(directory=dialog.options.directory) }
                else -> error("Unknown setting")
            } }
            accept(manager.persist(id,owner,++revision,state.value.draft.copy(dialog=null,folder=null)))
        }
    }
    private suspend fun chooseFolderNow() {
        val value=manager.chooseFolder(id,owner,++revision); accept(value)
        mutable.update { it.copy(folderLaunch=value.draft.folder?.id) }
    }
    internal fun chooseFolder()=operation { chooseFolderNow() }
    internal fun abandonFolder()=change { it.copy(folder=null) }
    internal fun consumeFolderLaunch(attempt: String): Boolean {
        if(state.value.folderLaunch!=attempt || state.value.draft.folder?.id!=attempt || state.value.closed) return false
        mutable.update { it.copy(folderLaunch=null) }; return true
    }
    internal fun folderResult(attempt: String,uri: String?) {
        if(uri!=null && uri.length>32_768) { folderResult(attempt,null); return }
        saved["cache.folder.result.id"]=attempt; saved["cache.folder.result.uri"]=uri.orEmpty()
        if(state.value.loading) return
        operation(force=true) {
            if(state.value.draft.folder?.id==attempt) {
                val accepted=uri?.takeIf { it.isNotBlank() }?.let { runCatching { folders.accept(it) }.getOrNull() }
                accept(manager.folderResult(id,owner,++revision,attempt,accepted))
            }
            if(saved.get<String>("cache.folder.result.id")==attempt) {
                saved.remove<String>("cache.folder.result.id"); saved.remove<String>("cache.folder.result.uri")
            }
        }
    }
    internal fun dispatch()=operation {
        val ids=state.value.draft.pendingTasks
        if(ids.isNotEmpty()) { manager.checkOwner(id,owner); scheduler.schedule(ids); accept(manager.acknowledge(id,owner,++revision,ids)) }
    }
    internal fun stop(ids: List<String>)=operation { manager.checkOwner(id,owner); scheduler.cancelTasks(ids) }
    internal fun startQueued(task: String)=operation { accept(manager.requestDispatch(id,owner,++revision,task)) }
    internal fun retry(task: String)=operation { accept(manager.retryTask(id,owner,++revision,task)) }
    internal fun close()=operation {
        try { manager.close(id,owner) } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { fail() }
        mutable.update { it.copy(closed=true) }
    }
}
