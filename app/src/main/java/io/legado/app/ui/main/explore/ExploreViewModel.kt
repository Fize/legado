package io.legado.app.ui.main.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.ExploreRepository
import io.legado.app.utils.InfoMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExploreUiState(
    val groups: Set<String> = emptySet(),
    val sources: List<BookSourcePart> = emptyList(),
    val searchQuery: String = "",
    val expandedSourceUrl: String? = null,
    val loadingSourceUrl: String? = null,
    val sourceKinds: Map<String, List<ExploreKind>> = emptyMap(),
    val failedSourceErrors: Map<String, String> = emptyMap(),
)

private data class ExploreContentState(
    val groups: List<String>,
    val sources: List<BookSourcePart>,
    val query: String,
)

private data class ExploreExpansionState(
    val expandedSourceUrl: String?,
    val loadingSourceUrl: String?,
    val sourceKinds: Map<String, List<ExploreKind>>,
    val failedSourceErrors: Map<String, String>,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel @Inject constructor(
    private val repository: ExploreRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val expandedSourceUrl = MutableStateFlow<String?>(null)
    private val loadingSourceUrl = MutableStateFlow<String?>(null)
    private val sourceKinds = MutableStateFlow<Map<String, List<ExploreKind>>>(emptyMap())
    private val failedSourceErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private var kindsJob: Job? = null

    private val groups = repository.observeGroups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val sources = searchQuery
        .flatMapLatest(repository::observeSources)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val contentState = combine(groups, sources, searchQuery, ::ExploreContentState)
    private val expansionState = combine(
        expandedSourceUrl,
        loadingSourceUrl,
        sourceKinds,
        failedSourceErrors,
        ::ExploreExpansionState,
    )

    val uiState: StateFlow<ExploreUiState> = combine(
        contentState,
        expansionState,
    ) { content, expansion ->
        ExploreUiState(
            groups = content.groups.toSet(),
            sources = content.sources,
            searchQuery = content.query,
            expandedSourceUrl = expansion.expandedSourceUrl,
            loadingSourceUrl = expansion.loadingSourceUrl,
            sourceKinds = expansion.sourceKinds,
            failedSourceErrors = expansion.failedSourceErrors,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExploreUiState())

    init {
        viewModelScope.launch {
            groups.collect { currentGroups ->
                val requestedGroup = exploreGroupFromQuery(searchQuery.value)
                if (requestedGroup != null && requestedGroup !in currentGroups) {
                    searchQuery.value = ""
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun toggleSource(source: BookSourcePart) {
        val sourceUrl = source.bookSourceUrl
        if (expandedSourceUrl.value == sourceUrl) {
            expandedSourceUrl.value = null
            return
        }
        expandedSourceUrl.value = sourceUrl
        if (sourceUrl !in sourceKinds.value) loadKinds(source, refresh = false)
    }

    fun refreshSource(source: BookSourcePart) {
        loadKinds(source, refresh = true)
    }

    fun infoMap(sourceUrl: String): InfoMap = repository.infoMap(sourceUrl)

    fun saveInfoMap(infoMap: InfoMap) = repository.saveInfoMap(infoMap)

    fun saveNeededInfoMaps() {
        viewModelScope.launch(IO) { repository.saveNeededInfoMaps() }
    }

    suspend fun source(sourceUrl: String) = repository.source(sourceUrl)

    suspend fun resolveKindLabel(sourceUrl: String, expression: String, infoMap: InfoMap): String? =
        repository.resolveKindLabel(sourceUrl, expression, infoMap)

    fun runKindAction(
        sourceUrl: String,
        action: String,
        infoMap: InfoMap,
        java: Any,
        name: String,
    ) {
        viewModelScope.launch {
            repository.runKindAction(sourceUrl, action, infoMap, java, name)
        }
    }

    private fun loadKinds(source: BookSourcePart, refresh: Boolean) {
        kindsJob?.cancel()
        val sourceUrl = source.bookSourceUrl
        loadingSourceUrl.value = sourceUrl
        failedSourceErrors.update { it - sourceUrl }
        kindsJob = viewModelScope.launch {
            try {
                val kinds = repository.loadKinds(source, refresh)
                currentCoroutineContext().ensureActive()
                val error = kinds.firstOrNull { it.title.startsWith("ERROR:") }
                if (error != null) {
                    failedSourceErrors.update { it + (sourceUrl to (error.url ?: error.title)) }
                } else if (expandedSourceUrl.value == sourceUrl || refresh) {
                    sourceKinds.update { it + (sourceUrl to kinds) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failedSourceErrors.update { it + (sourceUrl to error.stackTraceToString()) }
            }
            if (loadingSourceUrl.value == sourceUrl) loadingSourceUrl.value = null
        }
    }

    fun collapseSource(): Boolean {
        if (expandedSourceUrl.value == null) return false
        expandedSourceUrl.value = null
        return true
    }

    fun clearTransientKinds() {
        kindsJob?.cancel()
        sourceKinds.value = emptyMap()
        failedSourceErrors.value = emptyMap()
        loadingSourceUrl.value = null
    }

    fun topSource(bookSource: BookSourcePart) {
        viewModelScope.launch { repository.topSource(bookSource) }
    }

    fun deleteSource(source: BookSourcePart) {
        viewModelScope.launch { repository.deleteSource(source) }
    }
}
