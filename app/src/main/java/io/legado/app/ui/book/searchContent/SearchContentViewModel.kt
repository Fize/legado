package io.legado.app.ui.book.searchContent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.ui.navigation.SearchContentRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

data class SearchContentUiState(val query: String="",val session: BookContentSearchSession?=null,
    val hits: List<BookContentSearchHit> = emptyList(),val page: Int=0,val loading: Boolean=true,
    val error: String?=null,val selection: BookContentSearchSelection?=null)

@HiltViewModel
class SearchContentViewModel @Inject constructor(private val saved: SavedStateHandle,
    private val repository: BookContentSearchRepository): ViewModel() {
    private val mutable=MutableStateFlow(SearchContentUiState())
    val state=mutable.asStateFlow()
    private val operations=Mutex()
    private val hadSavedPage=saved.contains("contentSearch.page")
    private val page=saved.getStateFlow("contentSearch.page",0)
    private var current: BookContentSearchSession?=null
    private val ownerToken=UUID.randomUUID().toString()
    private var boundId: String?=null
    private var job: Job?=null
    private var initialized=false
    private var actionError: String?=null
    private var catalogId: String?=null
    private var initialRoute: SearchContentRoute?=null

    fun initialize(route: SearchContentRoute) {
        if(initialized) return
        initialized=true; initialRoute=route; catalogId=route.readerCatalogRequestId
        mutable.update { it.copy(query=route.query.take(2049)) }
        if(!hadSavedPage) saved["contentSearch.page"]=route.initialIndex.coerceAtLeast(0)/100
        val id=route.sessionId ?: saved.get<String>("contentSearch.id") ?: UUID.randomUUID().toString()
        saved["contentSearch.id"]=id; boundId=id
        viewModelScope.launch {
            try {
                val session=repository.open(id,route.bookUrl,ownerToken,route.query,catalogId)
                current=session
                mutable.update { it.copy(query=session.draft,session=session,loading=false) }
                launch {
                    combine(repository.observe(id),page) { value,p -> value to p }.collectLatest { (value,p) ->
                        if(value==null) { mutable.update { it.copy(loading=false,session=null,hits=emptyList(),error="Book does not exist") }; return@collectLatest }
                        val owner=current ?: return@collectLatest
                        if(value.owner!=owner.owner || value.epoch!=owner.epoch || value.generation!=owner.generation) {
                            if(value.owner!=ownerToken) mutable.update { it.copy(session=null,hits=emptyList(),error="Search session expired") }
                            return@collectLatest
                        }
                        current=value
                        val index=p.coerceIn(0,((value.resultCount-1)/100).coerceAtLeast(0))
                        val hits=repository.page(id,index*100)
                        val active=current
                        if(active?.owner!=value.owner || active.epoch!=value.epoch || active.generation!=value.generation) return@collectLatest
                        mutable.update { it.copy(session=value,hits=java.util.Collections.unmodifiableList(ArrayList(hits)),
                            page=index,loading=false,error=actionError ?: value.error) }
                    }
                }
                if(session.selectedOrdinal!=null && catalogId!=null) mutable.update {
                    it.copy(selection=BookContentSearchSelection(id,session.selectedOrdinal,true))
                } else if(session.running && session.query.isNotEmpty()) launchSearch(session)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(loading=false,error=error.message ?: "Search failed") } }
        }
    }
    private fun launchSearch(session: BookContentSearchSession) {
        job?.cancel(); job=viewModelScope.launch { repository.run(session) }
    }
    fun editQuery(value: String) {
        val query=value.take(2049)
        actionError=if(value.length>2048) "Query is too long" else null
        mutable.update { it.copy(query=query,error=actionError) }
        if(value.length<=2048) viewModelScope.launch { operations.withLock {
            try { current?.let { repository.draft(it,query) } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { fail(error) }
        } }
    }
    fun submit(regex: Boolean?=null,matchCase: Boolean?=null,replace: Boolean?=null) {
        if(state.value.query.length>2048) return
        if(current==null) {
            if(!state.value.loading) retry()
            return
        }
        viewModelScope.launch { operations.withLock {
            val session=current ?: return@withLock
            try {
                actionError=null
                val next=repository.restart(session,state.value.query,regex ?: session.regex,
                    matchCase ?: session.matchCase,replace ?: session.replace)
                current=next; saved["contentSearch.page"]=0
                mutable.update { it.copy(session=next,hits=emptyList(),error=null,selection=null) }
                if(next.running) launchSearch(next) else job?.cancel()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { fail(error) }
        } }
    }
    fun retry() {
        if(current==null) {
            if(state.value.loading) return
            initialized=false
            mutable.update { it.copy(loading=true,error=null) }
            initialRoute?.copy(query=state.value.query)?.let(::initialize)
        } else viewModelScope.launch { operations.withLock {
            try {
                val session=checkNotNull(current)
                job?.cancel()
                current=repository.open(session.id,session.bookUrl,ownerToken,state.value.query,catalogId)
                submit()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { fail(error) }
        } }
    }
    fun stop() { viewModelScope.launch { operations.withLock {
        try { current?.let { current=repository.stop(it); job?.cancel()
            mutable.update { value -> value.copy(session=current) } } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { fail(error) }
    } } }
    fun resume() { viewModelScope.launch { operations.withLock {
        try { current?.let { current=repository.resume(it)
            mutable.update { value -> value.copy(session=current) }
            launchSearch(checkNotNull(current)) } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { fail(error) }
    } } }
    fun page(direction: Int) {
        val value=state.value
        saved["contentSearch.page"]=(value.page+direction).coerceIn(0,(((value.session?.resultCount ?: 0)-1)/100).coerceAtLeast(0))
    }
    fun select(hit: BookContentSearchHit) { viewModelScope.launch { operations.withLock {
        val session=current ?: return@withLock
        if(state.value.error!=null || hit !in state.value.hits || hit.generation!=session.generation) return@withLock
        try {
            val stopped=repository.stop(session); current=stopped; job?.cancel()
            mutable.update { it.copy(session=stopped) }
            val selected=repository.select(stopped,hit.ordinal,catalogId)
            mutable.update { it.copy(selection=selected,error=null) }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { fail(error) }
    } } }
    override fun onCleared() {
        boundId?.let { repository.release(it,ownerToken) }
    }
    private fun fail(error: Exception) {
        actionError=error.message ?: "Search failed"
        mutable.update { it.copy(error=actionError) }
    }
    fun selectionHandled(value: BookContentSearchSelection) { mutable.update { if(it.selection==value) it.copy(selection=null) else it } }
}
