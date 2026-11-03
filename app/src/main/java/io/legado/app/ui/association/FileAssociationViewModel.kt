package io.legado.app.ui.association

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.FileAssociationRepository
import io.legado.app.data.repository.FileAssociationResult
import io.legado.app.data.repository.FileAssociationRequest
import io.legado.app.data.repository.FileAssociationAction
import io.legado.app.data.repository.isSupportedSharedImportMimeType
import io.legado.app.ui.book.import.local.ImportBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class FileAssociationUiState(
    val pendingRequest: FileAssociationRequest? = null,
    val storagePermissionRequired: Boolean = false,
    val loading: Boolean = true,
    val localBooks: List<ImportBook>? = null,
    val selectedUris: Set<Uri> = emptySet(),
    val directoryRequested: Boolean = false,
    val directoryPickerOpen: Boolean = false,
    val folderChoice: Boolean = false,
    val directoryDescription: String = "",
    val importingBooks: Boolean = false,
    val importingData: Boolean = false,
    val confirmation: FileAssociationResult.Data? = null,
    val result: FileAssociationResult? = null,
    val errorMessage: String? = null,
    val mixedTypes: Boolean = false,
    val finished: Boolean = false,
    val copiedBooks: Pair<Int, Int>? = null,
)

@HiltViewModel
class FileAssociationViewModel @Inject constructor(
    private val repository: FileAssociationRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val confirmation = savedState.get<String>("confirmationType")?.let { type ->
        savedState.get<String>("confirmationSource")?.let { FileAssociationResult.Data(type, it) }
    }
    private val _uiState = MutableStateFlow(FileAssociationUiState(
        loading = confirmation == null,
        confirmation = confirmation,
        directoryPickerOpen = savedState["choosingLocalBookDirectory"] ?: false,
        directoryDescription = repository.bookDirectoryDescription(),
    ))
    val uiState = _uiState.asStateFlow()
    private var items: List<ImportBook> = emptyList()
    private var stagingPath: String? = null
    private var openSingleLocalBook = false
    private var initialIntentDispatched = confirmation != null
    val selectedLocalBooks: Set<Uri> get() = uiState.value.selectedUris
    val pendingLocalBooks: List<ImportBook> get() = items.filter { it.file.uri in selectedLocalBooks }
    var importAfterDirectorySelection: Boolean
        get() = savedState["importAfterDirectorySelection"] ?: true
        private set(value) { savedState["importAfterDirectorySelection"] = value }
    var choosingLocalBookDirectory: Boolean
        get() = uiState.value.directoryPickerOpen
        set(value) {
            savedState["choosingLocalBookDirectory"] = value
            _uiState.update { it.copy(directoryPickerOpen = value, folderChoice = false) }
        }

    init {
        savedState.get<String>("localBookStaging")?.let { path ->
            initialIntentDispatched = true
            viewModelScope.launch {
                try {
                    val preview = repository.restorePreview(path, savedState["openSingleLocalBook"] ?: false)
                    items = preview.items
                    stagingPath = path
                    openSingleLocalBook = preview.openSingle
                    _uiState.update { it.copy(
                        loading = false,
                        selectedUris = savedState.get<ArrayList<String>>("selectedLocalBooks")
                            .orEmpty().mapTo(linkedSetOf(), Uri::parse),
                        localBooks = items.takeIf { !openSingleLocalBook || savedState.get<Boolean>("localBookPreview") == true },
                        directoryRequested = savedState["localBookDestination"] ?: false,
                    ) }
                    if (uiState.value.directoryRequested && !choosingLocalBookDirectory) showDirectoryChoice()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(error)
                }
            }
        }
    }

    fun shouldDispatchInitialIntent(): Boolean {
        if (initialIntentDispatched) return false
        initialIntentDispatched = true
        return true
    }

    fun initialize(requestId: String, initial: FileAssociationRequest?) {
        if (!shouldDispatchInitialIntent()) return
        viewModelScope.launch {
            try {
                val request = repository.loadRequest(requestId, initial)
                val requiresPermission = repository.requiresStoragePermission(request)
                _uiState.update { it.copy(pendingRequest = request, storagePermissionRequired = requiresPermission) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { fail(error) }
        }
    }

    fun processPendingRequest() {
        val request = uiState.value.pendingRequest ?: return
        _uiState.update { it.copy(pendingRequest = null) }
        when (request.action) {
            FileAssociationAction.SendMultiple -> dispatchSharedUris(request.streams.map(Uri::parse))
            FileAssociationAction.Send -> when {
                !isSupportedSharedImportMimeType(request.mimeType) -> reportInvalidSharedContent()
                request.streams.isNotEmpty() -> dispatchSharedUri(Uri.parse(request.streams.first()))
                !request.text.isNullOrBlank() -> dispatchSharedText(request.text)
                else -> reportInvalidSharedContent()
            }
            FileAssociationAction.View -> request.sourceUri?.let { dispatchIntent(Uri.parse(it)) }
                ?: reportInvalidSharedContent()
            FileAssociationAction.Unsupported -> reportInvalidSharedContent()
        }
    }

    fun dispatchIntent(uri: Uri) = resolve { repository.resolveUri(uri, shared = false) }
    fun dispatchSharedUri(uri: Uri) = resolve { repository.resolveUri(uri, shared = true) }
    fun dispatchSharedUris(uris: List<Uri>) = resolve { repository.resolveUris(uris) }
    fun dispatchSharedText(text: String) = resolve { repository.resolveText(text) }
    fun reportInvalidSharedContent() {
        _uiState.update { it.copy(loading = false, errorMessage = repository.errorMessage()) }
    }
    fun reportError(message: String) {
        _uiState.update { it.copy(loading = false, errorMessage = message, pendingRequest = null) }
    }

    private fun resolve(block: suspend () -> FileAssociationResult) {
        _uiState.update { it.copy(loading = true, errorMessage = null, result = null) }
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is FileAssociationResult.Preview -> {
                        items = result.items
                        stagingPath = result.stagingPath
                        openSingleLocalBook = result.openSingle
                        savedState["localBookStaging"] = result.stagingPath
                        savedState["openSingleLocalBook"] = result.openSingle
                        updateLocalSelection(items.map { it.file.uri })
                        _uiState.update { it.copy(loading = false, localBooks = items.takeUnless { openSingleLocalBook }) }
                        if (openSingleLocalBook) requestLocalBookDirectory(true)
                    }
                    is FileAssociationResult.Data -> if (result.type in setOf("bookshelf", "backup")) {
                        savedState["confirmationType"] = result.type
                        savedState["confirmationSource"] = result.source
                        _uiState.update { it.copy(loading = false, confirmation = result) }
                    } else _uiState.update { it.copy(loading = false, result = result) }
                    FileAssociationResult.MixedTypes -> _uiState.update { it.copy(loading = false, mixedTypes = true) }
                    else -> _uiState.update { it.copy(loading = false, result = result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun consumeResult() = _uiState.update { it.copy(result = null) }
    fun consumeCopiedBooks() = _uiState.update { it.copy(copiedBooks = null) }

    fun importData(type: String, source: String) {
        if (uiState.value.importingData || uiState.value.finished) return
        _uiState.update { it.copy(importingData = true) }
        viewModelScope.launch {
            try {
                repository.importData(type, source)
                savedState.remove<String>("confirmationType")
                savedState.remove<String>("confirmationSource")
                _uiState.update { it.copy(finished = true, confirmation = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            } finally {
                _uiState.update { it.copy(importingData = false) }
            }
        }
    }

    fun updateLocalSelection(uris: Collection<Uri>) {
        _uiState.update { it.copy(selectedUris = uris.toSet()) }
        savedState["selectedLocalBooks"] = ArrayList(uris.map(Uri::toString))
    }

    fun confirmLocalBooks() {
        if (uiState.value.importingBooks || uiState.value.directoryRequested) return
        if (pendingLocalBooks.isNotEmpty()) requestLocalBookDirectory(true)
    }

    fun requestLocalBookDirectory(importAfter: Boolean) {
        importAfterDirectorySelection = importAfter
        savedState["localBookDestination"] = true
        _uiState.update { it.copy(directoryRequested = true) }
        showDirectoryChoice()
    }

    private fun showDirectoryChoice() {
        val configured = repository.configuredBookDirectory()
        if (configured != null && importAfterDirectorySelection) selectLocalBookDirectory(configured)
        else _uiState.update { it.copy(loading = false, folderChoice = true) }
    }

    fun selectLocalBookDirectory(directory: Uri?, persist: Boolean = false) {
        choosingLocalBookDirectory = false
        savedState["localBookDestination"] = false
        if (directory != null && persist) repository.saveBookDirectory(directory)
        _uiState.update { it.copy(directoryRequested = false, folderChoice = false,
            directoryDescription = repository.bookDirectoryDescription()) }
        if (directory == null) {
            if (uiState.value.localBooks == null && pendingLocalBooks.isNotEmpty()) {
                savedState["localBookPreview"] = true
                _uiState.update { it.copy(localBooks = items, loading = false) }
            }
        } else if (importAfterDirectorySelection) importLocalBooks(directory)
    }

    fun usePrivateBookDirectory() = selectLocalBookDirectory(repository.privateBookDirectory(), persist = true)

    fun importBook(uri: Uri) {
        consumeResult()
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val book = repository.importBook(uri)
                _uiState.update { it.copy(loading = false, result = FileAssociationResult.OpenBook(book)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun importLocalBooks(directory: Uri) {
        if (uiState.value.importingBooks || uiState.value.finished) return
        savedState["localBookDestination"] = false
        _uiState.update { it.copy(directoryRequested = false, importingBooks = true,
            loading = it.localBooks == null) }
        val selected = pendingLocalBooks
        viewModelScope.launch {
            try {
                val books = repository.copyLocalBooks(selected, directory)
                _uiState.update { it.copy(loading = false, copiedBooks = books.size to (selected.size - books.size),
                    result = if (openSingleLocalBook) FileAssociationResult.OpenBook(books.single()) else null,
                    finished = !openSingleLocalBook) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            } finally {
                _uiState.update { it.copy(importingBooks = false) }
            }
        }
    }

    private fun fail(error: Throwable) {
        AppLog.put("导入分享内容失败\n${error.localizedMessage}", error)
        _uiState.update { it.copy(loading = false, errorMessage = repository.errorMessage(error)) }
    }

    override fun onCleared() {
        val preview = items
        val staging = stagingPath
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.releasePreview(preview, staging) }
                .onFailure { AppLog.put("清理分享导入缓存失败", it) }
        }
        super.onCleared()
    }
}
