package io.legado.app.ui.book.info

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoBooksDirException
import io.legado.app.utils.UrlUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.security.MessageDigest

data class BookInfoWebFile(val url: String, val name: String) {
    val suffix: String get() = UrlUtil.getSuffix(name)
    val isSupported: Boolean get() = AppPattern.bookFileRegex.matches(name)
    val isSupportDecompress: Boolean get() = AppPattern.archiveFileRegex.matches(name)
}

sealed interface BookInfoFileOperation {
    data class Import(val file: BookInfoWebFile) : BookInfoFileOperation
    data class Download(val file: BookInfoWebFile) : BookInfoFileOperation
    data class ImportEntry(val uri: String, val entry: String) : BookInfoFileOperation
}
sealed interface BookInfoFileResult {
    data class Imported(val bookUrl: String) : BookInfoFileResult
    data class Downloaded(val uri: String) : BookInfoFileResult
}
enum class BookInfoFileError { Interrupted, EmptyArchive, Folder, Changed, Storage }
class BookInfoFileException(val reason: BookInfoFileError) : IllegalStateException(reason.name)

data class BookInfoFileCompletion(
    val id: String = UUID.randomUUID().toString(),
    val bookUrl: String? = null,
    val uri: String? = null,
    val upload: Boolean = false,
    val read: Boolean = false,
)
data class BookInfoFilesUiState(
    val open: Boolean = false,
    val bookUrl: String? = null,
    val upload: Boolean = false,
    val read: Boolean = false,
    val selected: BookInfoWebFile? = null,
    val selectedKey: String? = null,
    val selectedName: String? = null,
    val unsupported: Boolean = false,
    val archiveUri: String? = null,
    val entries: List<String> = emptyList(),
    val selectedEntry: String? = null,
    val busy: Boolean = false,
    val error: BookInfoFileError? = null,
    val errorMessage: String? = null,
    val completion: BookInfoFileCompletion? = null,
)

/** Owns the whole file chooser workflow; no host, callback to a screen, or file content is saved. */
class BookInfoFilesController(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val perform: suspend (String, BookInfoFileOperation) -> BookInfoFileResult,
    private val entries: suspend (String) -> List<String>,
    private val requestFolder: () -> Unit,
    private val reportError: (Exception) -> Unit = {},
) {
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()

    init {
        state.value.takeIf { it.open }?.archiveUri?.let(::loadArchive)
    }

    fun open(bookUrl: String, upload: Boolean, read: Boolean) {
        if (state.value.busy || state.value.completion != null) return
        publish(BookInfoFilesUiState(open = true, bookUrl = bookUrl, upload = upload, read = read))
    }
    fun dismiss() {
        if (!state.value.busy) publish(BookInfoFilesUiState(completion = state.value.completion))
    }
    fun setUpload(value: Boolean) { if (!state.value.busy) publish(state.value.copy(upload = value)) }
    fun syncCandidates(bookUrl: String, files: List<BookInfoWebFile>) {
        val current = state.value
        if (!current.open || current.bookUrl != bookUrl || current.selected != null) return
        files.find { candidateKey(it) == current.selectedKey }?.let { publish(current.copy(selected = it)) }
    }
    private fun candidateKey(file: BookInfoWebFile) = MessageDigest.getInstance("SHA-256")
        .digest((file.url + "\u0000" + file.name).toByteArray()).joinToString("") { "%02x".format(it) }

    fun select(file: BookInfoWebFile) {
        if (!state.value.open || state.value.busy) return
        publish(state.value.copy(selected = file, selectedKey = candidateKey(file), selectedName = file.name, unsupported = !file.isSupported && !file.isSupportDecompress,
            archiveUri = null, entries = emptyList(), selectedEntry = null, error = null, errorMessage = null))
        if (!state.value.unsupported) importSelected()
    }
    fun openUnsupported() { if (state.value.unsupported) importSelected() }
    fun selectEntry(entry: String) {
        val current = state.value
        val uri = current.archiveUri ?: return
        if (entry !in current.entries || current.busy) return
        publish(current.copy(selectedEntry = entry))
        action { finish(perform(checkNotNull(current.bookUrl), BookInfoFileOperation.ImportEntry(uri, entry))) }
    }
    fun retry() {
        val current = state.value
        if (current.archiveUri != null && current.entries.isEmpty()) loadArchive(current.archiveUri)
        else if (current.selectedEntry != null) selectEntry(current.selectedEntry)
        else if (current.selected != null) importSelected()
    }
    fun consumeCompletion(id: String) {
        if (state.value.completion?.id == id) publish(state.value.copy(completion = null))
    }

    private fun importSelected() {
        val current = state.value
        val file = current.selected ?: return
        val bookUrl = current.bookUrl ?: return
        action {
            if (file.isSupported) finish(perform(bookUrl, BookInfoFileOperation.Import(file)))
            else {
                val result = perform(bookUrl, BookInfoFileOperation.Download(file)) as? BookInfoFileResult.Downloaded
                    ?: error("Expected downloaded file")
                if (file.isSupportDecompress) {
                    publish(state.value.copy(archiveUri = result.uri))
                    val files = entries(result.uri)
                    if (files.isEmpty()) throw BookInfoFileException(BookInfoFileError.EmptyArchive)
                    publish(state.value.copy(entries = files.toList()))
                    if (files.size == 1) {
                        publish(state.value.copy(selectedEntry = files.single()))
                        finish(perform(bookUrl, BookInfoFileOperation.ImportEntry(result.uri, files.single())))
                    }
                } else finish(result)
            }
        }
    }
    private fun loadArchive(uri: String) = action {
        val files = entries(uri)
        if (files.isEmpty()) throw BookInfoFileException(BookInfoFileError.EmptyArchive)
        publish(state.value.copy(entries = files.toList()))
    }
    private fun finish(result: BookInfoFileResult) {
        val current = state.value
        val completion = when (result) {
            is BookInfoFileResult.Imported -> BookInfoFileCompletion(bookUrl = result.bookUrl, upload = current.upload, read = current.read)
            is BookInfoFileResult.Downloaded -> BookInfoFileCompletion(uri = result.uri)
        }
        publish(BookInfoFilesUiState(busy = current.busy, completion = completion))
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        publish(state.value.copy(busy = true, error = null, errorMessage = null))
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = when (error) {
                    is NoBooksDirException -> BookInfoFileError.Folder
                    is BookInfoFileException -> error.reason
                    else -> BookInfoFileError.Storage
                }
                reportError(error)
                publish(state.value.copy(error = reason, errorMessage = error.localizedMessage))
                if (reason == BookInfoFileError.Folder) requestFolder()
            } finally { publish(state.value.copy(busy = false)) }
        }
    }

    private fun publish(value: BookInfoFilesUiState) {
        if (!value.open && value.completion == null) {
            saved.keys().filter { it.startsWith("fileImport.") }.forEach { saved.remove<Any>(it) }
        } else {
            saved["fileImport.open"] = value.open
            saved["fileImport.book"] = value.bookUrl
            saved["fileImport.upload"] = value.upload
            saved["fileImport.read"] = value.read
            saved["fileImport.selectedKey"] = value.selectedKey
            saved["fileImport.selectedName"] = value.selectedName
            saved["fileImport.unsupported"] = value.unsupported
            saved["fileImport.archive"] = value.archiveUri
            saved["fileImport.entry"] = value.selectedEntry
            saved["fileImport.busy"] = value.busy
            saved["fileImport.completionId"] = value.completion?.id
            saved["fileImport.completedBook"] = value.completion?.bookUrl
            saved["fileImport.completedUri"] = value.completion?.uri
            saved["fileImport.completedUpload"] = value.completion?.upload
            saved["fileImport.completedRead"] = value.completion?.read
        }
        _state.value = value
    }
    private fun restore(): BookInfoFilesUiState {
        val completion = saved.get<String>("fileImport.completionId")?.let { id ->
            BookInfoFileCompletion(id, saved["fileImport.completedBook"], saved["fileImport.completedUri"],
                saved["fileImport.completedUpload"] ?: false, saved["fileImport.completedRead"] ?: false)
        }
        return BookInfoFilesUiState(open = saved["fileImport.open"] ?: false, bookUrl = saved["fileImport.book"],
            upload = saved["fileImport.upload"] ?: false, read = saved["fileImport.read"] ?: false, selectedKey = saved["fileImport.selectedKey"], selectedName = saved["fileImport.selectedName"],
            unsupported = saved["fileImport.unsupported"] ?: false, archiveUri = saved["fileImport.archive"], selectedEntry = saved["fileImport.entry"],
            error = if (saved.get<Boolean>("fileImport.busy") == true && completion == null) BookInfoFileError.Interrupted else null,
            completion = completion)
    }
}
