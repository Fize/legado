package io.legado.app.ui.book.changecover

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.AppPattern
import io.legado.app.data.repository.BookCoverCandidate
import io.legado.app.data.repository.BookCoverSelection
import io.legado.app.data.repository.CoverCandidateKind
import io.legado.app.data.repository.CoverSearchData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class CoverSearchPhase { Loading, Rule, RuleReady, Searching, Paused, Ready, Complete }
data class CoverSearchUiState(
    val open: Boolean = false,
    val target: String? = null,
    val name: String = "",
    val author: String = "",
    val phase: CoverSearchPhase = CoverSearchPhase.Ready,
    val covers: List<BookCoverCandidate> = emptyList(),
    val saving: Boolean = false,
    val failures: Int = 0,
    val error: String? = null,
) { val searching get() = phase in setOf(CoverSearchPhase.Loading, CoverSearchPhase.Rule, CoverSearchPhase.Searching) }

class CoverSearchController(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val repository: CoverSearchData,
    private val apply: suspend (String, BookCoverSelection) -> Unit,
    private val reportError: (Exception) -> Unit = {},
) {
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()
    private var task: Job? = null
    private var generation = 0L
    private var pending: MutableSet<String>? = null

    init { if (state.value.open) load(auto = false) }
    fun open(target: String, name: String, author: String) {
        if (state.value.saving) return
        stop()
        publish(CoverSearchUiState(open = true, target = target, name = name.trim(), author = author.replace(AppPattern.authorRegex, "").trim()))
        pending = null
        load(auto = true)
    }
    fun dismiss() {
        if (state.value.saving) return
        stop(); publish(CoverSearchUiState())
    }
    fun refresh() { if (state.value.open && !state.value.saving) { pending = null; load(auto = true, refresh = true) } }
    fun toggleSearch() {
        val current = state.value
        if (!current.open || current.saving) return
        if (current.searching) { stop(); publish(current.copy(phase = CoverSearchPhase.Paused)) }
        else if (current.phase in setOf(CoverSearchPhase.RuleReady, CoverSearchPhase.Paused)) runSources()
        else { pending = null; load(auto = true, refresh = true) }
    }
    fun select(id: String) {
        val current = state.value
        if (current.saving || !current.open) return
        val candidate = current.covers.find { it.id == id } ?: return
        stop()
        publish(current.copy(saving = true, phase = CoverSearchPhase.Paused, error = null))
        scope.launch {
            try {
                apply(checkNotNull(current.target), BookCoverSelection(candidate.url, candidate.source))
                publish(CoverSearchUiState())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                reportError(error); publish(state.value.copy(saving = false, error = error.localizedMessage))
            }
        }
    }
    private fun stop() { generation++; task?.cancel(); task = null }
    private fun defaultCover() = BookCoverCandidate("default", "use_default_cover", order = Int.MIN_VALUE, kind = CoverCandidateKind.Default)
    private fun load(auto: Boolean, refresh: Boolean = false) {
        stop()
        val token = generation
        val current = state.value
        publish(current.copy(phase = CoverSearchPhase.Loading, error = null, failures = 0))
        task = scope.launch {
            try {
                val cached = repository.cached(current.name, current.author)
                if (token != generation) return@launch
                publish(state.value.copy(covers = (listOf(defaultCover()) + cached).distinctBy { it.id }))
                if (!refresh && cached.any { it.kind == CoverCandidateKind.Rule }) { publish(state.value.copy(phase = CoverSearchPhase.RuleReady)); return@launch }
                if (!auto) { publish(state.value.copy(phase = CoverSearchPhase.Paused)); return@launch }
                if (!refresh && cached.size > 1) { publish(state.value.copy(phase = CoverSearchPhase.Ready)); return@launch }
                publish(state.value.copy(phase = CoverSearchPhase.Rule))
                val rule = try { repository.rule(current.name, current.author) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { reportError(error); null }
                if (token != generation) return@launch
                if (rule != null) { add(rule); publish(state.value.copy(phase = CoverSearchPhase.RuleReady)) }
                else searchSources(token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                reportError(error)
                if (token == generation) publish(state.value.copy(phase = CoverSearchPhase.Paused, error = error.localizedMessage))
            }
        }
    }
    private fun runSources() {
        stop()
        val token = generation
        task = scope.launch {
            try { searchSources(token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                reportError(error)
                if (token == generation) publish(state.value.copy(phase = CoverSearchPhase.Paused, error = error.localizedMessage))
            }
        }
    }
    private suspend fun searchSources(token: Long) {
        val current = state.value
        if (pending == null) {
            val keys = repository.sourceKeys()
            if (token != generation) return
            pending = keys.toMutableSet()
        }
        if (token != generation) return
        publish(state.value.copy(phase = CoverSearchPhase.Searching))
        val semaphore = Semaphore(repository.parallelism().coerceAtLeast(1))
        coroutineScope {
            pending.orEmpty().toList().map { key -> launch {
                semaphore.withPermit {
                    if (token != generation) return@withPermit
                    currentCoroutineContext().ensureActive()
                    try {
                        repository.search(key, current.name, current.author)?.let { if (token == generation) add(it) }
                        if (token == generation) pending?.remove(key)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        reportError(error)
                        if (token == generation) { pending?.remove(key); publish(state.value.copy(failures = state.value.failures + 1)) }
                    }
                }
            } }.joinAll()
        }
        if (token == generation) publish(state.value.copy(phase = CoverSearchPhase.Complete))
    }
    private fun add(candidate: BookCoverCandidate) {
        publish(state.value.copy(covers = (state.value.covers.filterNot { it.id == candidate.id } + candidate)
            .sortedWith(compareBy<BookCoverCandidate> { it.order }.thenBy { it.id })))
    }
    private fun publish(value: CoverSearchUiState) {
        if (!value.open) saved.keys().filter { it.startsWith("coverSearch.") }.forEach { saved.remove<Any>(it) }
        else {
            saved["coverSearch.open"] = true; saved["coverSearch.target"] = value.target
            saved["coverSearch.name"] = value.name; saved["coverSearch.author"] = value.author
        }
        _state.value = value
    }
    private fun restore() = CoverSearchUiState(open = saved["coverSearch.open"] ?: false,
        target = saved["coverSearch.target"], name = saved["coverSearch.name"] ?: "", author = saved["coverSearch.author"] ?: "")
}
