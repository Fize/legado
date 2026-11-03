package io.legado.app.ui.book.info.edit

import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.ui.book.changecover.CoverSearchController
import io.legado.app.ui.book.changecover.CoverSearchUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BookInfoEditError { Missing, Name, Conflict, Draft, Storage }
data class BookInfoEditUiState(
    val target: String? = null,
    val book: Book? = null,
    val draft: BookInfoEditDraft = BookInfoEditDraft(),
    val dirty: Set<BookInfoEditField> = emptySet(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val coverRevision: Int = 0,
    val recoveryReady: Boolean = false,
    val error: BookInfoEditError? = null,
    val errorMessage: String? = null,
    val coverSearch: CoverSearchUiState = CoverSearchUiState(),
)

@HiltViewModel
class BookInfoEditViewModel @Inject constructor(
    private val repository: BookInfoEditRepository,
    private val savedState: SavedStateHandle,
    covers: CoverSearchRepository,
    private val files: CoverFileRepository,
    private val draftStore: BookInfoEditDraftStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(restore())
    val uiState = _uiState.asStateFlow()
    val book get() = uiState.value.book
    private var loading: Job? = null
    private var sequence = 0L
    private val draftKey = savedState.get<String>("edit.draftKey") ?: UUID.randomUUID().toString().also { savedState["edit.draftKey"] = it }
    private val lease = CompletableDeferred<EditDraftLease>()
    private val draftWrites = Channel<BookInfoEditDraftSnapshot>(Channel.CONFLATED)
    private val revision = AtomicLong(savedState["edit.revision"] ?: 0L)
    private val committedRevision = AtomicLong(-1)
    private var lastDraft: Triple<String?, BookInfoEditDraft, Set<BookInfoEditField>>? = null
    val coverController = CoverSearchController(savedState, viewModelScope, covers, { key, selection ->
        check(uiState.value.target == key && uiState.value.book != null && !uiState.value.busy)
        updateDraft { it.copy(coverUrl = selection.url, coverSource = selection.source) }
    }, { AppLog.put("封面搜索失败", it) })
    init {
        viewModelScope.launch(IO) {
            try {
                val opened = draftStore.open(draftKey)
                lease.complete(opened)
                for (write in draftWrites) {
                    if (!draftStore.save(opened, write)) break
                    committedRevision.set(write.revision)
                    _uiState.update { if (revision.get() == write.revision) it.copy(recoveryReady = true) else it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { lease.completeExceptionally(error); AppLog.put("保存书籍编辑草稿失败", error); _uiState.update { it.copy(error = BookInfoEditError.Draft) } }
        }
        viewModelScope.launch { coverController.state.collect { cover -> _uiState.update { it.copy(coverSearch = cover) } } }
    }
    fun loadBook(key: String) {
        if (uiState.value.target == key && (book != null || uiState.value.loading)) return
        loading?.cancel()
        val token = ++sequence
        val previous = uiState.value
        val same = previous.target == key
        if (!same) coverController.dismiss()
        _uiState.value = previous.copy(target = key, book = null, loading = true, error = null,
            draft = if (same) previous.draft else BookInfoEditDraft(), dirty = if (same) previous.dirty else emptySet(), saved = same && previous.saved)
        loading = viewModelScope.launch {
            try {
                val disk = try { lease.await().restored?.takeIf { it.target == key } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (token != sequence) return@launch
                val large = savedState.get<ArrayList<String>>("edit.largeFields").orEmpty().mapNotNull { name -> BookInfoEditField.entries.find { it.name == name } }.toSet()
                if (large.any { it !in disk?.dirty.orEmpty() }) _uiState.update { it.copy(dirty = it.dirty - large, error = BookInfoEditError.Draft) }
                disk?.let {
                    revision.updateAndGet { counter -> maxOf(counter, it.revision) }
                    val small = uiState.value.draft
                    val fields = uiState.value.dirty
                    val restored = it.draft.copy(
                        bookName = if (savedState.contains("edit.name")) small.bookName else it.draft.bookName,
                        bookAuthor = if (savedState.contains("edit.author")) small.bookAuthor else it.draft.bookAuthor,
                        selectedTypeIndex = if (savedState.contains("edit.type")) small.selectedTypeIndex else it.draft.selectedTypeIndex,
                        coverUrl = if (savedState.contains("edit.cover")) small.coverUrl else it.draft.coverUrl,
                        coverSource = if (savedState.contains("edit.coverSource")) small.coverSource else it.draft.coverSource,
                        bookIntro = if (savedState.contains("edit.intro")) small.bookIntro else it.draft.bookIntro)
                    _uiState.value = uiState.value.copy(draft = restored, dirty = fields + it.dirty)
                }
                val loaded = repository.getBook(key)
                if (token != sequence) return@launch
                if (loaded == null) publish(uiState.value.copy(loading = false, error = BookInfoEditError.Missing))
                else {
                    val current = uiState.value
                    val fresh = BookInfoEditDraft(loaded.name, loaded.author, when {
                        loaded.isVideo -> 4; loaded.isImage -> 2; loaded.isAudio -> 1; loaded.isWebFile -> 3; else -> 0
                    }, (loaded.customCoverUrl?.takeIf(String::isNotBlank) ?: loaded.coverUrl).orEmpty(),
                        loaded.customCoverSource?.takeIf { it.url == loaded.customCoverUrl }?.source, loaded.getDisplayIntro().orEmpty())
                    val restored = current.draft
                    val draft = fresh.copy(
                        bookName = if (BookInfoEditField.Name in current.dirty) restored.bookName else fresh.bookName,
                        bookAuthor = if (BookInfoEditField.Author in current.dirty) restored.bookAuthor else fresh.bookAuthor,
                        selectedTypeIndex = if (BookInfoEditField.Type in current.dirty) restored.selectedTypeIndex else fresh.selectedTypeIndex,
                        coverUrl = if (BookInfoEditField.Cover in current.dirty) restored.coverUrl else fresh.coverUrl,
                        coverSource = if (BookInfoEditField.Cover in current.dirty) restored.coverSource else fresh.coverSource,
                        bookIntro = if (BookInfoEditField.Intro in current.dirty) restored.bookIntro else fresh.bookIntro)
                    publish(current.copy(book = loaded, draft = draft, loading = false))
                    savedState.get<String>("edit.coverImport")?.let { importCover(Uri.parse(it)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
        }
    }
    fun updateDraft(update: (BookInfoEditDraft) -> BookInfoEditDraft) {
        val current = uiState.value
        if (current.busy || current.saved || current.book == null) return
        val draft = update(current.draft)
        val dirty = current.dirty.toMutableSet()
        if (draft.bookName != current.draft.bookName) dirty.add(BookInfoEditField.Name)
        if (draft.bookAuthor != current.draft.bookAuthor) dirty.add(BookInfoEditField.Author)
        if (draft.selectedTypeIndex != current.draft.selectedTypeIndex) dirty.add(BookInfoEditField.Type)
        if (draft.coverUrl != current.draft.coverUrl || draft.coverSource != current.draft.coverSource) dirty.add(BookInfoEditField.Cover)
        if (draft.bookIntro != current.draft.bookIntro) dirty.add(BookInfoEditField.Intro)
        publish(current.copy(draft = draft, dirty = dirty, error = null))
    }
    fun openCoverSearch() {
        val current = uiState.value
        if (!current.busy && current.book != null) coverController.open(checkNotNull(current.target), current.draft.bookName, current.draft.bookAuthor)
    }
    fun refreshCover() { _uiState.update { it.copy(coverRevision = it.coverRevision + 1) } }
    fun importCover(uri: Uri) {
        if (uiState.value.busy || book == null) return
        savedState["edit.coverImport"] = uri.toString()
        publish(uiState.value.copy(busy = true, error = null))
        viewModelScope.launch {
            try {
                val path = files.importCover(uri)
                val current = uiState.value
                publish(current.copy(draft = current.draft.copy(coverUrl = path, coverSource = null), dirty = current.dirty + BookInfoEditField.Cover,
                    coverRevision = current.coverRevision + 1))
                savedState.remove<String>("edit.coverImport")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { savedState.remove<String>("edit.coverImport"); fail(error) }
            finally { publish(uiState.value.copy(busy = false)) }
        }
    }
    fun save() {
        val current = uiState.value
        val key = current.target ?: return
        if (current.busy || current.saved || current.book == null) return
        if (current.draft.bookName.isBlank()) { publish(current.copy(error = BookInfoEditError.Name)); return }
        publish(current.copy(busy = true, error = null))
        viewModelScope.launch {
            try {
                val result = repository.saveDraft(key, current.draft, current.dirty, navigationOwner)
                try { withContext(IO) { draftStore.discard(lease.await()) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { AppLog.put("清理书籍编辑草稿失败", error) }
                publish(uiState.value.copy(book = result, busy = false, saved = true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally { publish(uiState.value.copy(busy = false)) }
        }
    }
    private var navigationOwner: io.legado.app.data.entities.BookInfoChildRequest? = null
    internal fun setNavigationOwner(owner: io.legado.app.data.entities.BookInfoChildRequest?) { navigationOwner = owner }
    fun consumeSaved() { publish(uiState.value.copy(saved = false)) }
    private fun fail(error: Exception) {
        AppLog.put("书籍信息编辑失败", error)
        publish(uiState.value.copy(loading = false, busy = false,
            error = if (error is SQLiteConstraintException) BookInfoEditError.Conflict else BookInfoEditError.Storage, errorMessage = error.localizedMessage))
    }
    private fun publish(value: BookInfoEditUiState) {
        savedState["edit.target"] = value.target
        fun small(key: String, text: String?, field: BookInfoEditField) {
            if (field in value.dirty && (text?.length ?: 0) <= 8192) savedState[key] = text else savedState.remove<String>(key)
        }
        small("edit.name", value.draft.bookName, BookInfoEditField.Name)
        small("edit.author", value.draft.bookAuthor, BookInfoEditField.Author)
        small("edit.cover", value.draft.coverUrl, BookInfoEditField.Cover)
        small("edit.coverSource", value.draft.coverSource, BookInfoEditField.Cover)
        small("edit.intro", value.draft.bookIntro, BookInfoEditField.Intro)
        if (BookInfoEditField.Type in value.dirty) savedState["edit.type"] = value.draft.selectedTypeIndex else savedState.remove<Int>("edit.type")
        savedState["edit.dirty"] = ArrayList(value.dirty.map { it.name }); savedState["edit.saved"] = value.saved
        savedState["edit.largeFields"] = ArrayList(value.dirty.filter { field -> when (field) {
            BookInfoEditField.Name -> value.draft.bookName.length > 8192
            BookInfoEditField.Author -> value.draft.bookAuthor.length > 8192
            BookInfoEditField.Cover -> value.draft.coverUrl.length > 8192 || (value.draft.coverSource?.length ?: 0) > 8192
            BookInfoEditField.Intro -> value.draft.bookIntro.length > 8192
            BookInfoEditField.Type -> false
        } }.map { it.name })
        val draftIdentity = Triple(value.target, value.draft, value.dirty)
        if (value.book != null && !value.saved && draftIdentity != lastDraft) {
            lastDraft = draftIdentity
            val next = revision.incrementAndGet()
            savedState["edit.revision"] = next
            val dirty = value.dirty
            val minimal = BookInfoEditDraft(
                if (BookInfoEditField.Name in dirty) value.draft.bookName else "",
                if (BookInfoEditField.Author in dirty) value.draft.bookAuthor else "",
                if (BookInfoEditField.Type in dirty) value.draft.selectedTypeIndex else 0,
                if (BookInfoEditField.Cover in dirty) value.draft.coverUrl else "",
                if (BookInfoEditField.Cover in dirty) value.draft.coverSource else null,
                if (BookInfoEditField.Intro in dirty) value.draft.bookIntro else "")
            draftWrites.trySend(BookInfoEditDraftSnapshot(checkNotNull(value.target), next, minimal, dirty))
            _uiState.value = value.copy(recoveryReady = false)
            return
        }
        _uiState.value = value.copy(recoveryReady = value.book != null && committedRevision.get() == revision.get())
    }
    private fun restore() = BookInfoEditUiState(target = savedState["edit.target"], saved = savedState["edit.saved"] ?: false,
        draft = BookInfoEditDraft(savedState["edit.name"] ?: "", savedState["edit.author"] ?: "", savedState["edit.type"] ?: 0,
            savedState["edit.cover"] ?: "", savedState["edit.coverSource"], savedState["edit.intro"] ?: ""),
        dirty = savedState.get<ArrayList<String>>("edit.dirty").orEmpty().mapNotNull { name -> BookInfoEditField.entries.find { it.name == name } }.toSet())
}
