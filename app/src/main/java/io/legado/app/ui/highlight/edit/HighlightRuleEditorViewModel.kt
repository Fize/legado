package io.legado.app.ui.highlight.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.HighlightRuleEditorRepository
import io.legado.app.data.repository.HighlightRuleEditorOwnership
import io.legado.app.help.HighlightStyle
import io.legado.app.model.mergeReaderHighlightStyle
import io.legado.app.ui.navigation.HighlightRuleEditRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

internal data class HighlightRuleEditorValue(
    val id: Long,val uuid: String,val name: String,val pattern: String,val isRegex: Boolean,val scope: String?,
    val isEnabled: Boolean,val style: String,val order: Int,val timeoutMillisecond: Long,val group: String?,
    val applyToTitle: Boolean,val applyToBody: Boolean,
) {
    fun entity()=HighlightRule(id,uuid,name,pattern,isRegex,scope,isEnabled,style,order,timeoutMillisecond,group,applyToTitle,applyToBody)
    companion object {
        fun from(rule: HighlightRule)=HighlightRuleEditorValue(rule.id,rule.uuid,rule.name,rule.pattern,rule.isRegex,rule.scope,
            rule.isEnabled,rule.style,rule.order,rule.timeoutMillisecond,rule.group,rule.applyToTitle,rule.applyToBody)
    }
}

internal data class HighlightRuleEditorState(val id: String?=null,val value: HighlightRuleEditorValue?=null,
    val revision: Long=0,val style: HighlightStyle=HighlightStyle(),val loading: Boolean=true,
    val busy: Boolean=false,val failed: Boolean=false,val invalid: Boolean=false,val closed: Boolean=false)

@HiltViewModel
class HighlightRuleEditorViewModel @Inject constructor(private val savedState: SavedStateHandle,
    private val repository: HighlightRuleEditorRepository): ViewModel() {
    private val mutableState=MutableStateFlow(HighlightRuleEditorState())
    internal val state=mutableState.asStateFlow()
    private val owner=UUID.randomUUID().toString()
    private val claimEpoch=HighlightRuleEditorOwnership.next()
    private val operations=Mutex()
    private var route: HighlightRuleEditRoute?=null
    internal fun initialize(request: HighlightRuleEditRoute) {
        if(route!=null) { check(route==request); return }
        route=request; savedState["highlight.editor.id"]=request.requestId
        mutableState.update { it.copy(id=request.requestId) }
        viewModelScope.launch { operations.withLock {
            try {
                val draft=repository.open(request.requestId,request.ruleId,owner,claimEpoch)
                val value=if(draft.phase=="editing") withContext(Dispatchers.Default) { HighlightRuleEditorValue.from(repository.decode(draft)) } else null
                val style=withContext(Dispatchers.Default) { value?.entity()?.styleObj() ?: HighlightStyle() }
                mutableState.value=HighlightRuleEditorState(request.requestId,value,draft.revision,style,loading=false,closed=draft.phase!="editing")
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(loading=false,failed=true) } }
        } }
    }
    internal fun edit(id: String,change: (HighlightRuleEditorValue) -> HighlightRuleEditorValue) {
        val current=state.value
        if(current.id!=id || current.loading || current.busy || current.closed) return
        val value=current.value?.let(change) ?: return
        val revision=current.revision+1
        mutableState.value=current.copy(value=value,revision=revision,failed=false,invalid=false)
        viewModelScope.launch { operations.withLock {
            try { repository.mutate(id,owner,revision) { change(HighlightRuleEditorValue.from(it)).entity() } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(failed=true) } }
        } }
    }
    internal fun style(id: String,change: (HighlightStyle) -> HighlightStyle) {
        val current=state.value
        if(current.id!=id || current.loading || current.busy || current.closed || current.value==null) return
        val next=change(current.style).normalized()
        // Preserve style extension fields; serialization and normalization run off the UI thread.
        val revision=current.revision+1
        mutableState.value=current.copy(style=next,revision=revision,failed=false,invalid=false)
        viewModelScope.launch { operations.withLock {
            try {
                val draft=repository.mutate(id,owner,revision) { value -> value.copy(style=mergeReaderHighlightStyle(value.style,next)) }
                val value=withContext(Dispatchers.Default) { HighlightRuleEditorValue.from(repository.decode(draft)) }
                mutableState.update { if(it.revision==revision) it.copy(value=value) else it }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(failed=true) } }
        } }
    }
    internal fun finish(id: String,save: Boolean) {
        val current=state.value
        if(current.id!=id || current.loading || current.busy || current.closed) return
        if(save && current.value==null) return
        mutableState.update { it.copy(busy=true,failed=false,invalid=false) }
        viewModelScope.launch { operations.withLock {
            try {
                if(save) {
                    val value=withContext(Dispatchers.Default) { checkNotNull(current.value).copy(
                        group=current.value.group?.trim()?.takeIf(String::isNotEmpty),scope=current.value.scope?.takeIf(String::isNotBlank)) }
                    if(!withContext(Dispatchers.Default) { value.entity().isValid() }) {
                        mutableState.update { it.copy(invalid=true) }; return@withLock
                    }
                    repository.mutate(id,owner,current.revision+1) { latest ->
                        value.copy(style=if(latest.styleObj()==current.style) latest.style else mergeReaderHighlightStyle(latest.style,current.style)).entity()
                    }
                }
                repository.finish(id,owner,save)
                mutableState.update { it.copy(closed=true) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(failed=true,closed=!save) } }
            finally { mutableState.update { it.copy(busy=false) } }
        } }
    }
}
