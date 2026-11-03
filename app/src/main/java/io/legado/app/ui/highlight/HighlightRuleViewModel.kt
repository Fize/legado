package io.legado.app.ui.highlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

internal data class HighlightManagerUiState(val draft: HighlightRuleManagerDraft=HighlightRuleManagerDraft(),
    val rules: List<HighlightRuleItem> = emptyList(),val loading: Boolean=true,val busy: Boolean=false,
    val failure: Long=0,val error: Boolean=false,val closed: Boolean=false,val launchAttempt: String?=null,val importLaunchAttempt: String?=null)

@HiltViewModel
class HighlightRuleViewModel @Inject constructor(private val savedState: SavedStateHandle,
    private val repository: HighlightRuleManagerRepository): ViewModel() {
    internal val id: String=savedState.get<String>("highlight.manager.id") ?: UUID.randomUUID().toString().also { savedState["highlight.manager.id"]=it }
    private val owner=UUID.randomUUID().toString()
    private val epoch=HighlightManagerOwnership.next()
    private val mutex=Mutex()
    private val mutableState=MutableStateFlow(HighlightManagerUiState())
    internal val state=mutableState.asStateFlow()
    private var revision=0L

    init {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    accept(repository.open(id,owner,epoch))
                    savedState.remove<String>("highlight.manager.import")?.let { uri ->
                        if(state.value.draft.importRequest==null && !state.value.closed) {
                            val pending=repository.startImport(id,owner,++revision); accept(pending)
                            accept(repository.importResult(id,owner,++revision,pending.draft.importRequest!!.id,uri))
                        }
                    }
                }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { fail(); mutableState.update { it.copy(loading=false) } }
            }
            savedState.get<String>("highlight.manager.import.result.id")?.let { attempt ->
                importResult(attempt,savedState.get<String>("highlight.manager.import.result.uri")?.takeIf(String::isNotEmpty))
            }
            savedState.get<String>("highlight.manager.result.id")?.let { key ->
                result(key,savedState.get<String>("highlight.manager.result.attempt"),savedState.get<String>("highlight.manager.result.uri")?.takeIf(String::isNotEmpty))
            }
            try { repository.observe().collect { rows -> mutableState.update { it.copy(rules=rows) }; reconcile() } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { fail() }
        }
    }
    init {
        viewModelScope.launch {
            state.map { it.draft.importRequest?.id }.distinctUntilChanged().collectLatest { attempt ->
                if(attempt!=null) repository.importPhase(attempt).collect { phase ->
                    if(phase in listOf("completed","cancelled")) {
                        val ready=state.first { !it.busy && !it.loading || it.closed || it.draft.importRequest?.id!=attempt }
                        if(!ready.closed && ready.draft.importRequest?.id==attempt) operation {
                            accept(repository.clearFinishedImport(id,owner,++revision,attempt))
                        }
                    }
                }
            }
        }
    }
    internal fun beginImport()=operation {
        val value=repository.startImport(id,owner,++revision)
        accept(value); mutableState.update { it.copy(importLaunchAttempt=value.draft.importRequest?.id) }
    }
    internal fun abandonImport(attempt: String)=operation { accept(repository.abandonImport(id,owner,++revision,attempt)) }
    internal fun consumeImportLaunch(attempt: String): Boolean {
        if(state.value.importLaunchAttempt!=attempt || state.value.draft.importRequest?.id!=attempt || state.value.closed) return false
        mutableState.update { it.copy(importLaunchAttempt=null) }; return true
    }
    internal fun importResult(attempt: String,uri: String?)=enqueueResult(attempt,null,uri,true)
    internal fun importLaunchFailed(attempt: String) {
        if(state.value.draft.importRequest?.let { it.id==attempt && it.phase=="awaiting" }!=true) return
        operation { accept(repository.importResult(id,owner,++revision,attempt,null,true)); fail() }
    }
    internal suspend fun importDestination(attempt: String): HighlightManagerImport?=mutex.withLock {
        try { repository.importDestination(id,owner,attempt) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { fail(); null }
    }

    private fun accept(value: HighlightManagerOwned) {
        revision=value.revision
        mutableState.update { it.copy(draft=value.draft,loading=false,closed=value.closed) }
    }
    private fun fail() { mutableState.update { it.copy(failure=it.failure+1,error=true) } }
    internal fun displayed()=state.value.rules.filter { when(state.value.draft.filter) {
        "ungrouped" -> it.group.isNullOrBlank(); "named" -> it.group==state.value.draft.group; else -> true
    } }
    internal fun selected(): List<HighlightRuleRef> {
        val selected=state.value.draft.selected.toHashSet()
        return displayed().filter { it.ref.uuid in selected }.map { it.ref }
    }
    private fun canChange()=!state.value.loading && !state.value.busy && !state.value.closed
    private fun change(transform: (HighlightRuleManagerDraft) -> HighlightRuleManagerDraft) {
        if(!canChange()) return
        val draft=transform(state.value.draft); if(draft==state.value.draft) return
        val next=++revision
        mutableState.update { it.copy(draft=draft,error=false) }
        viewModelScope.launch { mutex.withLock {
            try { repository.persist(id,owner,next,draft) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { fail() }
        } }
    }
    private fun reconcile() {
        if(!canChange()) return
        val rules=state.value.rules; val uuids=rules.map { it.ref.uuid }.toSet()
        change { draft ->
            val missing=draft.filter=="named" && rules.none { it.group==draft.group }
            draft.copy(selected=draft.selected.filter { it in uuids },filter=if(missing) "all" else draft.filter,group=if(missing) null else draft.group)
        }
    }
    internal fun select(uuid: String) = change { it.copy(selected=if(uuid in it.selected) it.selected-uuid else it.selected+uuid) }
    internal fun selectAll(all: Boolean)=change { it.copy(selected=if(all) displayed().map { row -> row.ref.uuid } else emptyList()) }
    internal fun invert()=change { draft ->
        val selected=draft.selected.toHashSet()
        draft.copy(selected=displayed().filter { row -> row.ref.uuid !in selected }.map { row -> row.ref.uuid })
    }
    internal fun filter(kind: String,group: String?=null)=change { it.copy(filter=kind,group=group,dialog=null) }
    internal fun groups(open: Boolean)=change { it.copy(groupsOpen=open,dialog=null) }
    internal fun dismiss()=change { it.copy(dialog=null) }
    internal fun filterDialog()=change { it.copy(dialog=HighlightManagerDialog(kind="filter")) }
    internal fun groupAction(group: String,rename: Boolean) = change { it.copy(dialog=HighlightManagerDialog(kind=if(rename) "rename" else "deleteGroup",
        source=group,text=group,refs=state.value.rules.filter { row -> row.group==group }.map { row -> row.ref })) }
    internal fun renameText(dialogId: String,text: String)=change { draft ->
        if(draft.dialog?.id!=dialogId) draft else draft.copy(dialog=draft.dialog.copy(text=text))
    }
    internal fun chooseMove(dialogId: String)=change { draft ->
        if(draft.dialog?.id!=dialogId) draft else draft.copy(dialog=draft.dialog.copy(kind="moveGroup"))
    }
    internal fun delete(refs: List<HighlightRuleRef>) { if(refs.isNotEmpty()) change { it.copy(dialog=HighlightManagerDialog(kind="delete",refs=refs)) } }
    private fun operation(block: suspend () -> Unit) {
        if(!canChange()) return
        mutableState.update { it.copy(busy=true,error=false) }
        viewModelScope.launch { mutex.withLock {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { fail() }
            finally { mutableState.update { it.copy(busy=false) }; reconcile() }
        } }
    }
    internal fun act(refs: List<HighlightRuleRef>,action: String) {
        if(refs.isEmpty()) return
        operation { repository.act(id,owner,++revision,refs,action) }
    }
    internal fun confirm(dialogId: String,target: String?=null) {
        if(state.value.draft.dialog?.id!=dialogId) return
        operation { accept(repository.confirm(id,owner,++revision,dialogId,target)) }
    }
    internal fun prepare(refs: List<HighlightRuleRef>,share: Boolean=false) {
        if(refs.isEmpty() || state.value.draft.transfer!=null) return
        operation { accept(repository.prepare(id,owner,++revision,refs,if(share) "share" else "export")) }
    }
    internal suspend fun deliver(key: String): Boolean {
        if(!canChange() || state.value.draft.transfer?.id!=key) return false
        mutableState.update { it.copy(busy=true,error=false) }
        return try { mutex.withLock {
            val value=repository.transferPhase(id,owner,++revision,key,"awaiting")
            accept(value); mutableState.update { it.copy(launchAttempt=value.draft.transfer?.attempt) }; true
        } } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { fail(); false }
        finally { mutableState.update { it.copy(busy=false) }; reconcile() }
    }
    internal suspend fun shareUri(transfer: HighlightRuleTransfer)=repository.shareUri(transfer)
    internal fun consumeLaunch(attempt: String): Boolean {
        if(state.value.launchAttempt!=attempt || state.value.draft.transfer?.attempt!=attempt || state.value.closed) return false
        mutableState.update { it.copy(launchAttempt=null) }; return true
    }
    internal fun launchFailed(key: String,attempt: String) {
        val transfer=state.value.draft.transfer ?: return
        if(transfer.id!=key || transfer.attempt!=attempt || transfer.phase!="awaiting") return
        operation { accept(repository.transferPhase(id,owner,++revision,key,"failed")); mutableState.update { it.copy(launchAttempt=null) }; fail() }
    }
    internal fun result(key: String,attempt: String?,uri: String?)=enqueueResult(key,attempt,uri,false)
    private fun enqueueResult(key: String,attempt: String?,uri: String?,isImport: Boolean) {
        fun matches(value: HighlightManagerUiState): Boolean=if(isImport) value.draft.importRequest?.let { it.id==key && it.phase=="awaiting" }==true
            else value.draft.transfer?.let { it.id==key && attempt!=null && it.attempt==attempt && it.phase=="awaiting" }==true
        if(!state.value.loading && !matches(state.value)) return
        val prefix=if(isImport) "highlight.manager.import.result" else "highlight.manager.result"
        savedState["$prefix.id"]=key; savedState["$prefix.uri"]=uri.orEmpty()
        if(!isImport) savedState["$prefix.attempt"]=attempt
        fun clear() {
            if(savedState.get<String>("$prefix.id")==key && savedState.get<String>("$prefix.uri")==uri.orEmpty() &&
                (isImport || savedState.get<String>("$prefix.attempt")==attempt)) {
                savedState.remove<String>("$prefix.id"); savedState.remove<String>("$prefix.uri"); savedState.remove<String>("$prefix.attempt")
            }
        }
        viewModelScope.launch {
            val ready=state.first { it.closed || !it.loading && (!matches(it) || !it.busy) }
            if(ready.closed || !matches(ready)) { clear(); return@launch }
            if(isImport) operation { try { accept(repository.importResult(id,owner,++revision,key,uri)) } finally { clear() } }
            else if(ready.draft.transfer?.kind=="share" || uri==null) operation {
                try { accept(repository.clearTransfer(id,owner,++revision,key)) } finally { clear() }
            }
            else write(key,uri,::clear)
        }
    }
    internal fun retry(key: String) {
        val transfer=state.value.draft.transfer ?: return
        if(transfer.id!=key || transfer.phase!="failed" || transfer.uri==null) return
        write(key,transfer.uri)
    }
    private fun write(key: String,uri: String,finished: () -> Unit = {}) = operation {
        try {
            accept(repository.transferPhase(id,owner,++revision,key,"writing",uri))
            try { accept(repository.write(id,owner,key)) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                runCatching { accept(repository.transferPhase(id,owner,++revision,key,"failed")) }
                throw error
            }
        } finally { finished() }
    }
    internal fun cancelTransfer(key: String)=operation { accept(repository.clearTransfer(id,owner,++revision,key)) }
    internal fun dismissExport()=change { it.copy(exportedUri=null) }
    internal fun close()=operation { try { repository.close(id,owner) } finally { mutableState.update { it.copy(closed=true) } } }
}
