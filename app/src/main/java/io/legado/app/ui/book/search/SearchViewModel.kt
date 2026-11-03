package io.legado.app.ui.book.search

import androidx.lifecycle.SavedStateHandle
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.SearchScreenDraft
import io.legado.app.data.entities.SearchSession
import io.legado.app.data.entities.SearchSessionResult
import io.legado.app.data.entities.SearchSessionStatus
import io.legado.app.data.repository.*
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.ui.navigation.SearchRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class SearchResultUi(
    val row: SearchSessionResult,
    val originCount: Int,
    val inBookshelf: Boolean,
    val hasReadRecord: Boolean,
)

data class SearchScreenState(
    val draft: SearchScreenDraft? = null,
    val session: SearchSession? = null,
    val preferences: SearchPreferences? = null,
    val results: List<SearchResultUi> = emptyList(),
    val groups: List<String> = emptyList(),
    val sources: List<SearchSourceOption> = emptyList(),
    val history: List<SearchHistoryItem> = emptyList(),
    val shelfMatches: List<SearchShelfMatch> = emptyList(),
    val creating: Boolean = false,
    val opening: Boolean = false,
    val loadError: String? = null,
    val notice: String? = null,
    val pendingOpen: BookInfoOpenRequest? = null,
) {
    val ready get() = draft != null && preferences != null
    val searching get() = creating || session?.status == SearchSessionStatus.Running

}

private data class SearchPresentation(val session: SearchSession?, val preferences: SearchPreferences?, val results: List<SearchResultUi>)
private data class SearchSuggestions(val groups: List<String>, val history: List<SearchHistoryItem>, val shelf: List<SearchShelfMatch>, val sources: List<SearchSourceOption>)
private data class SearchOperationState(val creating: Boolean, val opening: Boolean, val loadError: String?, val notice: String?)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: SearchRepository,
    private val settings: SearchPreferencesRepository,
) : ViewModel() {
    private val draftId = savedState.get<String>("searchDraftId") ?: UUID.randomUUID().toString().also { savedState["searchDraftId"] = it }
    private val draft = MutableStateFlow<SearchScreenDraft?>(null)
    // Keep editing state stable across focus, IME and adaptive-layout changes.
    // Room remains the durable draft; its asynchronous flow never drives an active editor.
    val queryEditor = TextFieldState()
    val filterEditor = TextFieldState()
    val scopeQueryEditor = TextFieldState()
    private val frame = MutableStateFlow<SearchSessionFrame?>(null)
    private val preferences = MutableStateFlow<SearchPreferences?>(null)
    private val pendingOpen = MutableStateFlow<BookInfoOpenRequest?>(null)
    private val refresh = MutableStateFlow(0)
    private val active = MutableStateFlow(false)
    private val operation = MutableStateFlow(SearchOperationState(false, false, null, null))
    private val writes = Channel<SearchScreenDraft>(Channel.CONFLATED)
    private val commands = AtomicLong()
    private var initialized = false
    private var initializationJob: Job? = null
    private var creatingJob: Job? = null
    private var searchJob: Job? = null
    private var observer: Job? = null
    private var preferencesJob: Job? = null
    private var openingJob: Job? = null
    private val settingsMutex = Mutex()

    private val presentation = combine(frame, repository.libraryIndex(), repository.readRecordIndex(), preferences) { frame, library, records, prefs ->
        SearchPresentation(frame?.session, prefs, presentSearchResults(frame, library, records, prefs))
    }.flowOn(Default)

    private val suggestionQuery = draft.map { if (it?.showInputHelp == true) it.query else null }.distinctUntilChanged()
    private val sourceQuery = draft.map { if (it?.scopeDialogOpen == true && it.scopeSourceMode) it.scopeSourceQuery else null }.distinctUntilChanged()
    private val suggestions = combine(
        repository.groups(),
        suggestionQuery.flatMapLatest { query -> if (query == null) flowOf(emptyList()) else repository.history(query) },
        suggestionQuery.flatMapLatest { query -> if (query.isNullOrBlank()) flowOf(emptyList()) else repository.shelfMatches(query) },
        sourceQuery.flatMapLatest { query -> if (query == null) flowOf(emptyList()) else repository.sources(query) },
    ) { groups, history, shelf, sources -> SearchSuggestions(groups, history, shelf, sources) }

    private val screenData = combine(draft, presentation, suggestions, operation, pendingOpen) { draft, shown, suggestions, operation, pending ->
        SearchScreenState(draft, shown.session, shown.preferences, shown.results, suggestions.groups, suggestions.sources,
            suggestions.history, suggestions.shelf, operation.creating, operation.opening, operation.loadError, operation.notice, pending)
    }
    val state: StateFlow<SearchScreenState> = refresh.flatMapLatest {
        screenData.catch { error ->
            if (error is CancellationException) throw error
            AppLog.put("Search screen state failed", error)
            emit(SearchScreenState(loadError = error.localizedMessage ?: "Search data is unavailable"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchScreenState())

    init {
        observeSettings()
        viewModelScope.launch {
            snapshotFlow { queryEditor.text.toString() }.collect { value ->
                if (draft.value != null && value == queryEditor.text.toString() && value != draft.value?.query) queryChanged(value)
            }
        }
        viewModelScope.launch {
            snapshotFlow { filterEditor.text.toString() }.collect { value ->
                if (draft.value != null && value == filterEditor.text.toString() && value != draft.value?.filterDraft) filterChanged(value)
            }
        }
        viewModelScope.launch {
            snapshotFlow { scopeQueryEditor.text.toString() }.collect { value ->
                if (draft.value != null && value == scopeQueryEditor.text.toString() && value != draft.value?.scopeSourceQuery) scopeQuery(value)
            }
        }
        viewModelScope.launch {
            for (value in writes) {
                try { repository.saveDraft(value) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { report(error) }
            }
        }
    }

    private fun observeSettings() {
        preferencesJob?.cancel()
        preferencesJob = viewModelScope.launch {
            settings.observe().catch { error ->
                if (error is CancellationException) throw error
                report(error)
                operation.update { it.copy(loadError = error.localizedMessage ?: "Cannot read search settings") }
            }.collect { preferences.value = it }
        }
    }

    fun reload(route: SearchRoute) {
        initializationJob?.cancel()
        stop(manual = false)
        observer?.cancel()
        initialized = false
        observeSettings()
        refresh.value++
        initialize(route)
    }

    fun initialize(route: SearchRoute) {
        if (initialized) return
        initialized = true
        operation.update { it.copy(loadError = null) }
        initializationJob = viewModelScope.launch {
            try {
                val prefs = settings.current()
                preferences.value = prefs
                val restored = draft.value ?: repository.draft(draftId)
                val initial = restored ?: SearchScreenDraft(
                    id = draftId, revision = 0, sessionId = null, query = route.query, scope = route.scope ?: prefs.scope,
                    showInputHelp = true, manuallyStopped = false, scopeDialogOpen = false, scopeSourceMode = false,
                    scopeSourceQuery = "", scopeGroupDraft = "", scopeSourceDraft = null, filterDialogOpen = false,
                    filterDraft = "", clearHistoryDialogOpen = false, emptyPromptOpen = false, emptyPromptSessionId = null,
                    updatedAt = System.currentTimeMillis(),
                )
                if (restored == null) repository.saveDraft(initial)
                publishDraft(initial)
                initial.sessionId?.let { id ->
                    frame.value = repository.restore(id)
                    observe(id)
                }
                savedState.get<String>("searchPendingPreviewKey")?.let { key ->
                    val request = repository.pendingPreview(key)
                    if (request != null) pendingOpen.value = request
                    else { savedState.remove<String>("searchPendingPreviewKey"); operation.update { it.copy(notice = "Book preview expired; select the result again") } }
                }
                if (restored == null && route.query.isNotBlank()) submit()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                initialized = false
                operation.update { it.copy(loadError = error.localizedMessage ?: "Cannot restore search") }
            }
        }
    }

    fun setActive(value: Boolean) { active.value = value }

    private fun publishDraft(value: SearchScreenDraft) {
        val previous = draft.value
        if (previous?.query != value.query) queryEditor.replaceText(value.query)
        if (previous?.filterDraft != value.filterDraft) filterEditor.replaceText(value.filterDraft)
        if (previous?.scopeSourceQuery != value.scopeSourceQuery) scopeQueryEditor.replaceText(value.scopeSourceQuery)
        draft.value = value
    }

    private fun TextFieldState.replaceText(value: String) {
        if (text.toString() != value) setTextAndPlaceCursorAtEnd(value)
    }

    private fun change(transform: (SearchScreenDraft) -> SearchScreenDraft) {
        val current = draft.value ?: return
        val next = transform(current).copy(revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        publishDraft(next)
        writes.trySend(next)
    }

    private fun observe(id: String) {
        observer?.cancel()
        observer = viewModelScope.launch {
            active.flatMapLatest { visible -> if (visible) repository.observe(id) else emptyFlow() }.collect { value ->
                if (draft.value?.sessionId != id) return@collect
                frame.value = value
                val current = draft.value ?: return@collect
                if (value?.session?.status == SearchSessionStatus.Ready && value.session.completedPage > 0 && value.results.isEmpty() &&
                    (value.session.precision || !SearchScopeSpec(value.session.scope).isAll) && !current.showInputHelp && current.emptyPromptSessionId != id) {
                    change { it.copy(emptyPromptOpen = true, emptyPromptSessionId = id) }
                }
            }
        }
    }

    fun queryChanged(value: String) {
        stop(manual = false)
        change { it.copy(query = value, showInputHelp = true, emptyPromptOpen = false) }
    }

    fun hideSuggestions() = change { it.copy(showInputHelp = false) }

    fun focusChanged(focused: Boolean) {
        val current = draft.value ?: return
        val session = frame.value?.session
        if (focused) change { it.copy(showInputHelp = true) }
        else if (session != null && current.query.trim() == session.query && frame.value?.results?.isNotEmpty() == true) {
            change { it.copy(showInputHelp = false) }
        }
    }

    fun submit() {
        // An IME action can arrive before snapshotFlow delivers the last keystroke.
        val entered = queryEditor.text.toString()
        if (draft.value?.query != entered) queryChanged(entered)
        val current = draft.value ?: return
        val query = current.query.trim()
        if (query.isBlank()) return
        stop(manual = false)
        val command = commands.incrementAndGet()
        operation.update { it.copy(creating = true) }
        observer?.cancel()
        frame.value = null
        creatingJob = viewModelScope.launch {
            try {
                val prefs = settingsMutex.withLock { settings.current() }
                val created = repository.create(query, SearchScopeSpec(current.scope), prefs.precision)
                if (commands.get() != command || queryEditor.text.toString() != entered) return@launch
                repository.saveHistory(query)
                if (commands.get() != command || queryEditor.text.toString() != entered) return@launch
                frame.value = created
                change { it.copy(query = query, sessionId = created.session.id, scope = created.session.scope,
                    showInputHelp = false, manuallyStopped = false, emptyPromptOpen = false, emptyPromptSessionId = null) }
                observe(created.session.id)
                operation.update { it.copy(creating = false) }
                startPage(retry = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
            finally { if (commands.get() == command) operation.update { it.copy(creating = false) } }
        }
    }

    private fun startPage(retry: Boolean): Boolean {
        val id = draft.value?.sessionId ?: return false
        if (searchJob?.isActive == true) return false
        searchJob = viewModelScope.launch {
            try { repository.searchPage(id, retry, active) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
        }
        return true
    }

    fun next(automatic: Boolean = false): Boolean {
        val current = draft.value ?: return false
        val session = frame.value?.session ?: return false
        if (current.showInputHelp || current.manuallyStopped || !session.hasMore || session.status == SearchSessionStatus.Running) return false
        if (automatic && session.status != SearchSessionStatus.Ready) return false
        return startPage(retry = false)
    }

    fun retry() {
        val session = frame.value?.session
        if (session == null || session.total == 0) submit()
        else {
            change { it.copy(manuallyStopped = false) }
            startPage(retry = true)
        }
    }

    private fun stopSearchWork() {
        val id = draft.value?.sessionId
        searchJob?.cancel()
        searchJob = null
        if (id != null) viewModelScope.launch { try { repository.stop(id) } catch (error: Exception) { if (error is CancellationException) throw error; report(error) } }
    }

    fun stop(manual: Boolean = true) {
        commands.incrementAndGet()
        creatingJob?.cancel()
        openingJob?.cancel()
        pendingOpen.value = null
        savedState.remove<String>("searchPendingPreviewKey")
        operation.update { it.copy(creating = false, opening = false) }
        stopSearchWork()
        change { it.copy(manuallyStopped = manual) }
    }

    fun historySelected(word: String) {
        queryChanged(word)
        val command = commands.get()
        viewModelScope.launch {
            try { if (!repository.hasShelfName(word) && command == commands.get()) submit() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
        }
    }

    fun deleteHistory(word: String) = task { repository.deleteHistory(word) }
    fun showClearHistory() = change { it.copy(clearHistoryDialogOpen = true) }
    fun dismissClearHistory() = change { it.copy(clearHistoryDialogOpen = false) }
    fun confirmClearHistory() { dismissClearHistory(); task { repository.clearHistory() } }

    fun setPrecision(value: Boolean) {
        stop(manual = false)
        val command = commands.get()
        task { settingsMutex.withLock { settings.setPrecision(value) }; if (commands.get() == command && draft.value?.query?.isNotBlank() == true) submit() }
    }
    fun setShowReadRecord(value: Boolean) = task { settingsMutex.withLock { settings.setShowReadRecord(value) } }
    fun showFilter() {
        val value = preferences.value?.resultFilter.orEmpty()
        filterEditor.replaceText(value)
        change { it.copy(filterDialogOpen = true, filterDraft = value) }
    }
    fun filterChanged(value: String) = change { it.copy(filterDraft = value) }
    fun dismissFilter() = change { it.copy(filterDialogOpen = false) }
    fun confirmFilter() {
        if (draft.value == null) return
        val entered = filterEditor.text.toString()
        if (draft.value?.filterDraft != entered) filterChanged(entered)
        dismissFilter()
        task { settingsMutex.withLock { settings.setFilter(entered.trim()) } }
    }

    fun setScope(scope: SearchScopeSpec) {
        stop(manual = false)
        val command = commands.get()
        change { it.copy(scope = scope.raw, scopeDialogOpen = false) }
        task {
            settingsMutex.withLock { settings.setScope(scope) }
            if (commands.get() == command && draft.value?.showInputHelp == false && draft.value?.query?.isNotBlank() == true) submit()
        }
    }
    fun toggleGroup(group: String) {
        val scope = SearchScopeSpec(draft.value?.scope.orEmpty())
        setScope(if (group in scope.groups) SearchScopeSpec.groups(scope.groups - group) else SearchScopeSpec.groups(listOf(group)))
    }
    fun showScope() {
        scopeQueryEditor.replaceText("")
        change {
        val scope = SearchScopeSpec(it.scope)
        it.copy(scopeDialogOpen = true, scopeSourceMode = scope.isSource, scopeSourceQuery = "",
            scopeGroupDraft = if (scope.isSource) preferences.value?.scope.orEmpty() else scope.raw,
            scopeSourceDraft = scope.raw.takeIf { scope.isSource })
        }
    }
    fun dismissScope() = change { it.copy(scopeDialogOpen = false) }
    fun scopeMode(source: Boolean) = change { it.copy(scopeSourceMode = source) }
    fun scopeQuery(value: String) = change { it.copy(scopeSourceQuery = value) }
    fun scopeGroup(group: String) = change {
        val groups = SearchScopeSpec(it.scopeGroupDraft).groups
        it.copy(scopeGroupDraft = SearchScopeSpec.groups(if (group in groups) groups - group else groups + group).raw)
    }
    fun scopeSource(source: SearchSourceOption) = change { it.copy(scopeSourceDraft = SearchScopeSpec.source(source.name, source.url).raw) }
    fun confirmScope() {
        val current = draft.value ?: return
        setScope(SearchScopeSpec(if (current.scopeSourceMode) current.scopeSourceDraft.orEmpty() else current.scopeGroupDraft))
    }
    fun dismissEmptyPrompt() = change { it.copy(emptyPromptOpen = false) }
    fun acceptEmptyFallback() {
        dismissEmptyPrompt()
        if (frame.value?.session?.precision == true) setPrecision(false)
        else setScope(SearchScopeSpec(""))
    }

    fun openResult(key: String) {
        val id = draft.value?.sessionId ?: return
        if (operation.value.creating) return
        prepareOpen { repository.prepareSelection(id, key) }
    }
    fun openShelf(book: SearchShelfMatch) {
        stop(manual = false)
        prepareOpen { repository.prepareShelfSelection(book.url) }
    }
    private fun prepareOpen(work: suspend () -> BookInfoOpenRequest) {
        if (operation.value.opening) return
        val command = commands.incrementAndGet()
        operation.update { it.copy(opening = true) }
        openingJob = viewModelScope.launch {
            try {
                val request = work()
                if (commands.get() != command) return@launch
                savedState["searchPendingPreviewKey"] = request.previewKey
                pendingOpen.value = request
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { report(error) }
            finally { if (commands.get() == command) operation.update { it.copy(opening = false) } }
        }
    }
    fun consumeOpen(id: String) {
        if (pendingOpen.value?.id == id) {
            savedState.remove<String>("searchPendingPreviewKey")
            pendingOpen.value = null
        }
    }
    fun consumeNotice() { operation.update { it.copy(notice = null) } }

    private fun task(work: suspend () -> Unit) { viewModelScope.launch {
        try { work() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { report(error) }
    } }
    private fun report(error: Throwable) { AppLog.put("Search operation failed", error); operation.update { it.copy(notice = error.localizedMessage ?: "Search operation failed") } }
}

internal fun presentSearchResults(
    frame: SearchSessionFrame?, library: SearchLibraryIndex, records: ReadRecordIndex, preferences: SearchPreferences?,
): List<SearchResultUi> {
    val words = preferences?.resultFilter.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    return frame?.results.orEmpty().filterNot { result ->
        words.any { word -> result.row.name.contains(word, true) || result.row.author.contains(word, true) || result.row.kind?.contains(word, true) == true }
    }.map { result ->
        val inShelf = library.contains(result.row)
        SearchResultUi(result.row, result.origins.size, inShelf,
            !inShelf && preferences?.showReadRecord == true && records.contains(result.row.name, result.row.author))
    }
}
