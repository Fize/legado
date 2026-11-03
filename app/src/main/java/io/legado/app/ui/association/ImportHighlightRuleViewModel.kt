package io.legado.app.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.dao.HighlightImportChoice
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ImportHighlightRuleViewModel @Inject constructor(private val repository: HighlightRuleImportRepository): ViewModel() {
    private val mutableState=MutableStateFlow(HighlightRuleImportUiState())
    val uiState=mutableState.asStateFlow()
    private val owner=UUID.randomUUID().toString()
    private val epoch=HighlightManagerOwnership.next()
    private val mutex=Mutex()
    private var id: String?=null
    private var revision=0L
    private var source: String?=null
    private var parentManagerId: String?=null
    private var loadJob: Job?=null
    private fun accept(value: HighlightImportOwned) {
        revision=value.session.revision
        mutableState.update { it.copy(items=value.choices,loading=false,completed=value.session.phase in listOf("completed","cancelled"),
            error=if(value.session.phase=="failed") true else it.error) }
    }
    fun load(requestId: String,source: String,parentManagerId: String?=null) {
        if(id!=null) return
        id=requestId; this.source=source; this.parentManagerId=parentManagerId
        loadJob=viewModelScope.launch { mutex.withLock {
            try { accept(repository.open(requestId,source,owner,epoch,parentManagerId)) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(loading=false,error=true) } }
        } }
    }
    private fun operation(block: suspend (String) -> Unit) {
        val key=id ?: return
        if(uiState.value.loading || uiState.value.importing || uiState.value.completed) return
        mutableState.update { it.copy(importing=true,error=false) }
        viewModelScope.launch { mutex.withLock {
            try { block(key) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(error=true) } }
            finally { mutableState.update { it.copy(importing=false) } }
        } }
    }
    fun toggle(uuid: String,selected: Boolean)=operation { accept(repository.select(it,owner,++revision,uuid,selected)) }
    fun selectAll(selected: Boolean)=operation { accept(repository.select(it,owner,++revision,null,selected)) }
    fun retry()=operation { accept(repository.retry(it,owner)) }
    fun importSelected() {
        if(uiState.value.selectedCount==0) return
        operation { accept(repository.finish(it,owner,true)) }
    }
    fun cancel() {
        val key=id ?: return
        val input=source ?: return
        if(uiState.value.completed) return
        loadJob?.cancel()
        mutableState.update { it.copy(importing=true) }
        viewModelScope.launch {
            try { accept(repository.cancel(key,input,owner,epoch,parentManagerId)) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(error=true) } }
            finally { mutableState.update { it.copy(completed=true,importing=false,loading=false) } }
        }
    }
}

data class HighlightRuleImportUiState(val items: List<HighlightImportChoice> = emptyList(),
    val loading: Boolean=true,val importing: Boolean=false,val error: Boolean=false,val completed: Boolean=false) {
    val selectedCount get()=items.count { it.selected }
    val isSelectAll get()=items.isNotEmpty() && selectedCount==items.size
}
