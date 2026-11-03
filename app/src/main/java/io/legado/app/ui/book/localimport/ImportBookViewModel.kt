package io.legado.app.ui.book.localimport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.LocalBookImportRepository
import io.legado.app.data.repository.LocalImportDirectory
import io.legado.app.data.repository.LocalImportEntry
import io.legado.app.utils.AlphanumComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class LocalImportGroupPrompt(val name: String, val allowed: Boolean, val checked: Boolean = false)
data class LocalImportArchive(val entry: LocalImportEntry, val names: List<String>, val confirmName: String? = null)
data class LocalBookImportUiState(
    val entries: List<LocalImportEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val path: String = "",
    val canGoBack: Boolean = false,
    val loading: Boolean = true,
    val working: Boolean = false,
    val query: String = "",
    val sort: Int = 0,
    val recursive: Boolean = false,
    val saveDirectory: String = "",
    val storageHelp: String = "",
    val storageSetup: Boolean = false,
    val showSaveDirectory: Boolean = false,
    val picker: String? = null,
    val fileNameScript: String = "",
    val editingScript: Boolean = false,
    val groupPrompt: LocalImportGroupPrompt? = null,
    val archive: LocalImportArchive? = null,
    val openBook: Book? = null,
    val message: String? = null,
)

@HiltViewModel
class ImportBookViewModel @Inject constructor(
    private val repository: LocalBookImportRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocalBookImportUiState(
        query = savedState["query"] ?: "",
        selected = savedState.get<ArrayList<String>>("selection")?.toSet().orEmpty(),
        picker = savedState["picker"],
        editingScript = savedState["editingScript"] ?: false,
        fileNameScript = savedState["scriptDraft"] ?: "",
    ))
    val uiState = _uiState.asStateFlow()
    private var initialized = false
    private var configuredScript = ""
    private var directories: List<LocalImportDirectory> = savedState.get<String>("directories")?.let {
        runCatching { Json.decodeFromString<List<LocalImportDirectory>>(it) }.getOrNull()
    }.orEmpty()
    private var allEntries: List<LocalImportEntry> = emptyList()
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var incompleteUris = savedState.get<ArrayList<String>>("incompleteUris")?.toSet().orEmpty()

    fun initialize() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                val config = repository.configuration()
                configuredScript = config.fileNameScript
                _uiState.update { it.copy(sort = config.sort, saveDirectory = config.saveDirectory,
                    fileNameScript = if (it.editingScript) it.fileNameScript else config.fileNameScript, storageHelp = config.storageHelp) }
                if (directories.isNotEmpty()) refresh(resetSelection = false, recursive = savedState["recursive"] ?: false)
                else if (config.needsStorageSetup && uiState.value.picker == null) {
                    _uiState.update { it.copy(loading = false, storageSetup = true) }
                } else if (uiState.value.picker == null) {
                    if (config.directory == null) requestPicker("browse") else setRoot(config.directory)
                }
                if (uiState.value.picker != null && directories.isEmpty()) _uiState.update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error); _uiState.update { it.copy(loading = false) } }
        }
    }

    private fun saveDirectories() {
        savedState["directories"] = Json.encodeToString(directories)
        _uiState.update { it.copy(path = directories.joinToString("/", postfix = "/") { d -> d.name }, canGoBack = directories.size > 1) }
    }
    private suspend fun setRoot(uri: String) {
        val directory = repository.resolveDirectory(uri)
        if (directory == null) { requestPicker("browse"); return }
        repository.saveRoot(uri)
        directories = listOf(directory)
        refresh()
    }
    private fun refresh(resetSelection: Boolean = true, recursive: Boolean = false) {
        loadJob?.cancel()
        filterJob?.cancel()
        val directory = directories.lastOrNull() ?: return
        saveDirectories()
        savedState["recursive"] = recursive
        if (resetSelection) select(emptySet())
        allEntries = emptyList()
        _uiState.update { it.copy(entries = emptyList(), loading = true, recursive = recursive) }
        loadJob = viewModelScope.launch {
            try {
                if (recursive) repository.scan(directory).conflate().collect { publish(it) }
                else publish(repository.list(directory))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally { if (coroutineContext[Job]?.isActive == true) _uiState.update { it.copy(loading = false) } }
        }
    }
    private suspend fun publish(entries: List<LocalImportEntry>) {
        allEntries = entries.map { if (it.uri in incompleteUris) it.copy(isOnBookShelf = false) else it }
        applyFilter().join()
    }
    private fun applyFilter(): Job {
        filterJob?.cancel()
        val state = uiState.value
        val entries = allEntries
        return viewModelScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val comparator = when (state.sort) {
                    2 -> compareBy<LocalImportEntry>({ !it.isDir }, { -it.lastModified })
                    1 -> compareBy({ !it.isDir }, { -it.size })
                    else -> compareBy { !it.isDir }
                } then compareBy(AlphanumComparator) { it.name }
                entries.filter { it.name.contains(state.query) }.sortedWith(comparator)
            }
            _uiState.update { it.copy(entries = filtered) }
        }.also { filterJob = it }
    }
    private fun select(uris: Set<String>) {
        savedState["selection"] = ArrayList(uris)
        _uiState.update { it.copy(selected = uris) }
    }
    fun toggle(entry: LocalImportEntry) {
        if (uiState.value.working || entry.isDir || entry.isOnBookShelf) return
        select(if (entry.uri in uiState.value.selected) uiState.value.selected - entry.uri else uiState.value.selected + entry.uri)
    }
    private fun selectable() = uiState.value.entries.filter { !it.isDir && !it.isOnBookShelf }
    private fun selected() = selectable().filter { it.uri in uiState.value.selected }
    fun selectAll(checked: Boolean) {
        if (!uiState.value.working) select(if (checked) selectable().mapTo(linkedSetOf()) { it.uri } else emptySet())
    }
    fun invertSelection() {
        if (!uiState.value.working) select(selectable().filter { it.uri !in uiState.value.selected }.mapTo(linkedSetOf()) { it.uri })
    }
    fun query(query: String) { savedState["query"] = query; _uiState.update { it.copy(query = query) }; applyFilter() }
    fun sort(sort: Int) {
        _uiState.update { it.copy(sort = sort) }; applyFilter()
        launchOperation { repository.saveSort(sort) }
    }
    fun enter(entry: LocalImportEntry) {
        if (!entry.isDir || uiState.value.working) return
        directories = directories + LocalImportDirectory(entry.uri, entry.name)
        refresh()
    }
    fun goBack(): Boolean {
        if (uiState.value.working) return true
        if (directories.size < 2) return false
        directories = directories.dropLast(1); refresh(); return true
    }
    fun scan() { if (!uiState.value.working) refresh(recursive = true) }
    fun requestPicker(kind: String = "browse") {
        if (uiState.value.working) return
        savedState["pickerLaunched"] = false
        savedState["picker"] = kind
        _uiState.update { it.copy(picker = kind, loading = false, showSaveDirectory = false) }
    }
    fun claimPicker(): Boolean {
        if (savedState.get<Boolean>("pickerLaunched") == true) return false
        savedState["pickerLaunched"] = true
        return true
    }
    fun picked(uri: String?) {
        val kind = uiState.value.picker ?: return
        savedState["picker"] = null
        savedState["pickerLaunched"] = false
        _uiState.update { it.copy(picker = null) }
        if (uri == null) return
        launchOperation {
            if (kind == "browse") setRoot(uri)
            else {
                repository.saveDirectory(uri)
                _uiState.update { it.copy(saveDirectory = uri, storageSetup = false) }
                if (kind == "setup" && directories.isEmpty()) {
                    val root = repository.configuration().directory ?: uri
                    setRoot(root)
                }
            }
        }
    }
    fun dismissStorageSetup() {
        _uiState.update { it.copy(storageSetup = false) }
        launchOperation { repository.configuration().directory?.let { setRoot(it) } ?: requestPicker() }
    }
    fun usePrivateDirectory() {
        val setup = uiState.value.storageSetup
        _uiState.update { it.copy(showSaveDirectory = false) }
        launchOperation {
            val uri = repository.privateDirectory()
            repository.saveDirectory(uri)
            _uiState.update { it.copy(saveDirectory = uri, storageSetup = false) }
            if (setup && directories.isEmpty()) setRoot(repository.configuration().directory ?: uri)
        }
    }
    fun showSaveDirectory(show: Boolean) = _uiState.update { it.copy(showSaveDirectory = show) }
    fun editScript(show: Boolean) {
        savedState["editingScript"] = show
        savedState["scriptDraft"] = configuredScript
        _uiState.update { it.copy(editingScript = show, fileNameScript = configuredScript) }
    }
    fun changeScript(script: String) {
        savedState["scriptDraft"] = script
        _uiState.update { it.copy(fileNameScript = script) }
    }
    fun saveScript() {
        val script = uiState.value.fileNameScript
        launchOperation { repository.saveFileNameScript(script); configuredScript = script; editScript(false) }
    }
    fun requestImport() {
        if (uiState.value.working || uiState.value.loading) return
        val selected = selected()
        if (selected.isEmpty()) return
        val name = directories.lastOrNull()?.name
        if (selected.size < 2 || uiState.value.recursive || name.isNullOrBlank()) importBooks(null)
        else {
            _uiState.update { it.copy(working = true) }
            launchOperation {
                try {
                    val allowed = repository.canAddGroup()
                    _uiState.update { it.copy(groupPrompt = LocalImportGroupPrompt(name, allowed)) }
                } finally { _uiState.update { it.copy(working = false) } }
            }
        }
    }
    fun dismissGroupPrompt() = _uiState.update { it.copy(groupPrompt = null) }
    fun checkGroup(checked: Boolean) = _uiState.update { it.copy(groupPrompt = it.groupPrompt?.copy(checked = checked)) }
    fun confirmGroup(withGroup: Boolean) {
        val prompt = uiState.value.groupPrompt ?: return
        _uiState.update { it.copy(groupPrompt = null) }
        importBooks(prompt.name.takeIf { withGroup && prompt.allowed && prompt.checked })
    }
    private fun importBooks(group: String?) {
        val selected = selected()
        if (selected.isEmpty() || uiState.value.working) return
        incompleteUris = incompleteUris + selected.map { it.uri }
        savedState["incompleteUris"] = ArrayList(incompleteUris)
        _uiState.update { it.copy(working = true) }
        launchOperation {
            try {
                val result = repository.importBooks(selected, group)
                incompleteUris = incompleteUris - result.completeUris
                savedState["incompleteUris"] = ArrayList(incompleteUris)
                publish(allEntries.map { if (it.uri in result.completeUris) it.copy(isOnBookShelf = true) else it })
                select(emptySet())
                _uiState.update { it.copy(message = result.message) }
            } finally { _uiState.update { it.copy(working = false) } }
        }
    }
    fun deleteSelected() {
        val selected = selected()
        if (selected.isEmpty() || uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        launchOperation {
            try { repository.delete(selected); publish(allEntries.filterNot { it in selected }); select(emptySet()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { refresh(recursive = uiState.value.recursive); throw error }
            finally { _uiState.update { it.copy(working = false) } }
        }
    }
    fun open(entry: LocalImportEntry) {
        if (uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        launchOperation {
            try {
                if (!repository.isArchive(entry)) {
                    val book = repository.rebind(entry)
                    _uiState.update { it.copy(openBook = book) }
                }
                else {
                    val names = repository.archiveNames(entry)
                    if (names.isEmpty()) _uiState.update { it.copy(message = repository.emptyArchiveMessage()) }
                    else if (names.size == 1) chooseArchive(entry, names.single())
                    else _uiState.update { it.copy(archive = LocalImportArchive(entry, names)) }
                }
            } finally { _uiState.update { it.copy(working = false) } }
        }
    }
    private suspend fun chooseArchive(entry: LocalImportEntry, name: String) {
        val book = repository.existingArchiveBook(name)
        _uiState.update { if (book != null) it.copy(openBook = book, archive = null)
            else it.copy(archive = LocalImportArchive(entry, emptyList(), name)) }
    }
    fun chooseArchive(name: String) {
        val archive = uiState.value.archive ?: return
        if (uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        launchOperation {
            try { chooseArchive(archive.entry, name) }
            finally { _uiState.update { it.copy(working = false) } }
        }
    }
    fun confirmArchive() {
        val archive = uiState.value.archive ?: return
        val name = archive.confirmName ?: return
        _uiState.update { it.copy(working = true, archive = null) }
        launchOperation {
            try {
                val book = repository.importArchive(archive.entry, name)
                _uiState.update { it.copy(openBook = book) }
            }
            finally { _uiState.update { it.copy(working = false) } }
        }
    }
    fun dismissArchive() = _uiState.update { it.copy(archive = null) }
    fun consumeOpenBook() = _uiState.update { it.copy(openBook = null) }
    fun consumeMessage() = _uiState.update { it.copy(message = null) }
    private fun launchOperation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail(error) }
    }
    private fun fail(error: Throwable) {
        AppLog.put("本地书籍导入失败", error)
        _uiState.update { it.copy(message = repository.errorMessage(error)) }
    }
}
