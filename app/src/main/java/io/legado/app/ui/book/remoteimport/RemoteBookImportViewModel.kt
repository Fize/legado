package io.legado.app.ui.book.remoteimport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.ArchiveUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class RemoteImportSort { Modified, Name }
data class RemoteArchivePrompt(val remote: RemoteImportEntry, val local: LocalImportEntry, val names: List<String>, val confirmName: String? = null)
data class RemoteBookImportUiState(
    val entries: List<RemoteImportEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val sort: RemoteImportSort = RemoteImportSort.Modified,
    val ascending: Boolean = false,
    val path: String = "",
    val canGoBack: Boolean = false,
    val loading: Boolean = true,
    val working: Boolean = false,
    val saveDirectory: String = "",
    val storageHelp: String = "",
    val storageSetup: Boolean = false,
    val showStorage: Boolean = false,
    val picker: Boolean = false,
    val reimport: RemoteImportEntry? = null,
    val redownload: RemoteImportEntry? = null,
    val archive: RemoteArchivePrompt? = null,
    val openBook: Book? = null,
    val message: String? = null,
    val finished: Boolean = false,
)

@HiltViewModel
class RemoteBookImportViewModel @Inject constructor(
    private val repository: RemoteBookImportRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteBookImportUiState(
        query = savedState["query"] ?: "",
        selected = savedState.get<ArrayList<String>>("selection")?.toSet().orEmpty(),
        sort = savedState.get<String>("sort")?.let { runCatching { RemoteImportSort.valueOf(it) }.getOrNull() } ?: RemoteImportSort.Modified,
        ascending = savedState["ascending"] ?: false,
        picker = savedState["picker"] ?: false,
    ))
    val uiState = _uiState.asStateFlow()
    private var initialized = false
    private var observing = false
    private var configurationJob: Job? = null
    private var connection: RemoteImportConnection? = null
    private var directories = savedState.get<String>("directories")?.let {
        runCatching { Json.decodeFromString<List<RemoteImportDirectory>>(it) }.getOrNull()
    }.orEmpty()
    private var incomplete = savedState.get<ArrayList<String>>("incomplete")?.toSet().orEmpty()
    private var allEntries: List<RemoteImportEntry> = emptyList()
    private var loadJob: Job? = null
    private var filterJob: Job? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        operation {
            val config = repository.storageConfiguration()
            _uiState.update { it.copy(saveDirectory = config.saveDirectory, storageHelp = config.storageHelp,
                storageSetup = config.needsStorageSetup, loading = !config.needsStorageSetup) }
            if (!config.needsStorageSetup) observeConfiguration()
        }
    }
    private fun observeConfiguration() {
        if (observing) return
        observing = true
        configurationJob = viewModelScope.launch {
            repository.configurationChanges().collectLatest {
                uiState.first { !it.working }
                loadJob?.cancel()
                filterJob?.cancel()
                allEntries = emptyList()
                _uiState.update { it.copy(entries = emptyList(), loading = true, archive = null, reimport = null, redownload = null) }
                try {
                    val next = repository.connection()
                    val same = savedState.get<String>("root") == next.root && savedState.get<Long>("serverId") == (next.serverId ?: DEFAULT_WEBDAV_ID)
                    connection = next
                    if (!same) {
                        directories = emptyList(); incomplete = emptySet()
                        savedState["incomplete"] = arrayListOf<String>()
                    }
                    savedState["root"] = next.root
                    savedState["serverId"] = next.serverId ?: DEFAULT_WEBDAV_ID
                    _uiState.update { it.copy(loading = true) }
                    repository.prepare(next)
                    refresh(resetSelection = !same)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    connection = null
                    _uiState.update { it.copy(loading = false, entries = emptyList(), path = "", canGoBack = false) }
                    fail(error)
                }
            }
        }
    }
    fun refresh(resetSelection: Boolean = true) {
        if (uiState.value.working) return
        val config = connection ?: run { reconnect(); return }
        loadJob?.cancel(); filterJob?.cancel()
        if (resetSelection) select(emptySet())
        savedState["directories"] = Json.encodeToString(directories)
        allEntries = emptyList()
        _uiState.update { it.copy(entries = emptyList(), loading = true, canGoBack = directories.isNotEmpty(),
            path = (if (config.isDefault) "books/" else "/") + directories.joinToString("/", postfix = if (directories.isEmpty()) "" else "/") { d -> d.name }) }
        loadJob = viewModelScope.launch {
            try { publish(repository.list(config, directories.lastOrNull()?.path)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally { if (coroutineContext[Job]?.isActive == true) _uiState.update { it.copy(loading = false) } }
        }
    }
    private fun reconnect() {
        if (uiState.value.working) return
        _uiState.update { it.copy(loading = true) }
        configurationJob?.cancel()
        observing = false
        observeConfiguration()
    }
    private suspend fun publish(entries: List<RemoteImportEntry>) {
        allEntries = entries.map { if (it.path in incomplete) it.copy(isOnBookShelf = false) else it }
        filter().join()
    }
    private fun filter(): Job {
        filterJob?.cancel()
        val snapshot = uiState.value
        val entries = allEntries
        return viewModelScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val order = if (snapshot.sort == RemoteImportSort.Name) compareBy<RemoteImportEntry, String>(AlphanumComparator) { it.filename }
                    else compareBy { it.lastModify }
                entries.filter { it.filename.contains(snapshot.query) }
                    .sortedWith(compareBy<RemoteImportEntry> { !it.isDir }.then(if (snapshot.ascending) order else order.reversed()))
            }
            _uiState.update { it.copy(entries = filtered) }
        }.also { filterJob = it }
    }
    private fun select(selected: Set<String>) { savedState["selection"] = ArrayList(selected); _uiState.update { it.copy(selected = selected) } }
    private fun selectable() = uiState.value.entries.filter { !it.isDir && !it.isOnBookShelf }
    fun toggle(entry: RemoteImportEntry) {
        if (uiState.value.loading || uiState.value.working || entry.isDir || entry.isOnBookShelf) return
        select(if (entry.path in uiState.value.selected) uiState.value.selected - entry.path else uiState.value.selected + entry.path)
    }
    fun selectAll(checked: Boolean) { if (!uiState.value.working) select(if (checked) selectable().mapTo(linkedSetOf()) { it.path } else emptySet()) }
    fun invertSelection() { if (!uiState.value.working) select(selectable().filter { it.path !in uiState.value.selected }.mapTo(linkedSetOf()) { it.path }) }
    fun query(query: String) { savedState["query"] = query; _uiState.update { it.copy(query = query) }; filter() }
    fun sort(sort: RemoteImportSort) {
        val ascending = sort != uiState.value.sort || !uiState.value.ascending
        savedState["sort"] = sort.name; savedState["ascending"] = ascending
        _uiState.update { it.copy(sort = sort, ascending = ascending) }; filter()
    }
    fun enter(entry: RemoteImportEntry) { if (entry.isDir && !uiState.value.loading && !uiState.value.working) { directories = directories + RemoteImportDirectory(entry.path, entry.filename); refresh() } }
    fun goBack(): Boolean {
        if (uiState.value.working) return true
        if (directories.isEmpty()) return false
        directories = directories.dropLast(1); refresh(); return true
    }
    fun importSelected() {
        if (uiState.value.working || uiState.value.loading) return
        importEntries(selectable().filter { it.path in uiState.value.selected })
    }
    fun requestReimport(entry: RemoteImportEntry) { if (!uiState.value.loading && !uiState.value.working) _uiState.update { it.copy(reimport = entry) } }
    fun dismissReimport() = _uiState.update { it.copy(reimport = null) }
    fun confirmReimport() { val entry = uiState.value.reimport ?: return; dismissReimport(); importEntries(listOf(entry)) }
    private fun importEntries(entries: List<RemoteImportEntry>, reopen: RemoteImportEntry? = null) {
        val config = connection ?: return
        if (entries.isEmpty() || uiState.value.working) return
        incomplete = incomplete + entries.map { it.path }; savedState["incomplete"] = ArrayList(incomplete)
        _uiState.update { it.copy(working = true) }
        operation {
            try {
                val result = repository.importBooks(config, entries)
                incomplete = incomplete - result.completePaths; savedState["incomplete"] = ArrayList(incomplete)
                publish(allEntries.map { if (it.path in result.completePaths) it.copy(isOnBookShelf = true) else it })
                select(entries.filter { it.path !in result.completePaths }.mapTo(linkedSetOf()) { it.path })
                _uiState.update { it.copy(message = result.message, showStorage = result.storageDenied) }
                if (reopen != null && reopen.path in result.completePaths) openArchive(reopen)
            } finally { _uiState.update { it.copy(working = false) } }
        }
    }
    fun open(entry: RemoteImportEntry) {
        if (uiState.value.loading || uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        operation {
            try {
                if (ArchiveUtils.isArchive(entry.filename)) openArchive(entry)
                else { val book = repository.existingBook(entry); _uiState.update { it.copy(openBook = book) } }
            } finally { _uiState.update { it.copy(working = false) } }
        }
    }
    private suspend fun openArchive(remote: RemoteImportEntry) {
        val local = repository.downloadedArchive(remote)
        if (local == null) { _uiState.update { it.copy(redownload = remote) }; return }
        val names = repository.archiveNames(local)
        when (names.size) {
            0 -> _uiState.update { it.copy(message = repository.emptyArchiveMessage()) }
            1 -> chooseArchive(RemoteArchivePrompt(remote, local, names), names.single())
            else -> _uiState.update { it.copy(archive = RemoteArchivePrompt(remote, local, names)) }
        }
    }
    fun dismissRedownload() = _uiState.update { it.copy(redownload = null) }
    fun confirmRedownload() { val entry = uiState.value.redownload ?: return; dismissRedownload(); importEntries(listOf(entry), entry) }
    private suspend fun chooseArchive(prompt: RemoteArchivePrompt, name: String) {
        val book = repository.existingArchiveBook(name)
        _uiState.update { if (book != null) it.copy(archive = null, openBook = book) else it.copy(archive = prompt.copy(confirmName = name)) }
    }
    fun chooseArchive(name: String) {
        val prompt = uiState.value.archive ?: return
        if (uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        operation { try { chooseArchive(prompt, name) } finally { _uiState.update { it.copy(working = false) } } }
    }
    fun dismissArchive() = _uiState.update { it.copy(archive = null) }
    fun confirmArchive() {
        val prompt = uiState.value.archive ?: return
        val name = prompt.confirmName ?: return
        val config = connection ?: return
        _uiState.update { it.copy(archive = null, working = true) }
        operation {
            try { val book = repository.importArchive(config, prompt.remote, prompt.local, name); _uiState.update { it.copy(openBook = book) } }
            finally { _uiState.update { it.copy(working = false) } }
        }
    }
    fun showStorage(show: Boolean) = _uiState.update { it.copy(showStorage = show) }
    fun requestStoragePicker() { savedState["picker"] = true; savedState["pickerLaunched"] = false; _uiState.update { it.copy(picker = true) } }
    fun claimPicker(): Boolean {
        if (savedState.get<Boolean>("pickerLaunched") == true) return false
        savedState["pickerLaunched"] = true; return true
    }
    fun picked(value: String?) {
        savedState["picker"] = false; savedState["pickerLaunched"] = false; _uiState.update { it.copy(picker = false) }
        if (value != null) saveStorage(value)
    }
    fun usePrivateStorage() = saveStorage(repository.privateStorageDirectory())
    private fun saveStorage(value: String) = operation {
        repository.saveStorageDirectory(value)
        _uiState.update { it.copy(saveDirectory = value, storageSetup = false, showStorage = false) }
        observeConfiguration()
    }
    fun cancelStorage() = _uiState.update { it.copy(showStorage = false, finished = it.storageSetup) }
    fun consumeOpenBook() = _uiState.update { it.copy(openBook = null) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }
    private fun operation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail(error) }
    }
    private fun fail(error: Throwable) { AppLog.put("远程导入失败", error); _uiState.update { it.copy(loading = false, message = repository.errorMessage(error)) } }
}
