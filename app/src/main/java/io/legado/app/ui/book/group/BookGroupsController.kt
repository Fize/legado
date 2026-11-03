package io.legado.app.ui.book.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupDraft
import io.legado.app.data.repository.BookGroupError
import io.legado.app.data.repository.BookGroupException
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.data.repository.isSelectableGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class BookGroupsUiState(
    val open: Boolean = false,
    val selection: Boolean = false,
    val bookUrl: String? = null,
    val selected: Long = 0,
    val selectedOwners: Map<Long, String> = emptyMap(),
    val ownersReady: Boolean = false,
    val groups: List<BookGroup> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val editor: BookGroupDraft? = null,
    val confirmingDelete: Boolean = false,
    val choosingCover: Boolean = false,
    val error: BookGroupError? = null,
    val errorDetail: String? = null,
)

class BookGroupsController(
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
    private val repository: BookGroupRepository,
    private val applySelection: suspend (String, Long, Map<Long, String>) -> Unit = { _, _, _ -> },
) {
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()
    private var observer: Job? = null

    init {
        observe()
        saved.get<String>("groupsCoverUri")?.let {
            _state.value = _state.value.copy(choosingCover = true)
            receiveCover(Uri.parse(it))
        }
    }

    fun openManage() { publish(_state.value.copy(open = true, selection = false, editor = null, error = null)) }
    fun openSelection(bookUrl: String, selected: Long) {
        if (_state.value.busy) return
        val state = _state.value
        publish(state.copy(open = true, selection = true, bookUrl = bookUrl, selected = selected,
            selectedOwners = owners(state.groups, selected), ownersReady = !state.loading, editor = null, error = null))
    }

    fun dismiss() {
        val state = _state.value
        if (state.busy) return
        if (state.editor != null) publish(state.copy(editor = null, confirmingDelete = false, choosingCover = false, error = null))
        else publish(state.copy(open = false, error = null))
    }

    fun newGroup() { if (!_state.value.busy) publish(_state.value.copy(editor = BookGroupDraft(), error = null)) }
    fun edit(group: BookGroup) {
        if (!_state.value.busy) publish(_state.value.copy(editor = BookGroupDraft(group.groupId, group.groupName, group.cover,
            group.bookSort, group.enableRefresh, group.onlyUpdateRead,
            group.cover?.let { !it.startsWith("http://", true) && !it.startsWith("https://", true) } == true,
            group.generation ?: UUID.randomUUID().toString()), error = null))
    }
    fun updateEditor(update: (BookGroupDraft) -> BookGroupDraft) {
        val state = _state.value
        if (!state.busy) state.editor?.let { publish(state.copy(editor = update(it), error = null)) }
    }
    fun toggle(id: Long, generation: String? = _state.value.groups.find { it.groupId == id }?.generation) {
        val state = _state.value
        if (!state.busy && state.groups.any { it.groupId == id && it.generation == generation && generation != null && it.isSelectableGroup() }) {
            val selected = if (state.selected and id != 0L) state.selected and id.inv() else state.selected or id
            publish(state.copy(selected = selected, selectedOwners = owners(state.groups, selected), ownersReady = true))
        }
    }
    fun saveEditor() {
        val draft = _state.value.editor ?: return
        action { repository.save(draft); publish(_state.value.copy(editor = null)) }
    }
    fun confirmDelete() { if (!_state.value.busy) publish(_state.value.copy(confirmingDelete = true)) }
    fun cancelDelete() { publish(_state.value.copy(confirmingDelete = false)) }
    fun delete() {
        val draft = _state.value.editor ?: return
        val id = draft.id ?: return
        action {
            try { repository.delete(id, draft.generation); publish(_state.value.copy(editor = null)) }
            finally { publish(_state.value.copy(confirmingDelete = false)) }
        }
    }
    fun setVisible(group: BookGroup, visible: Boolean) = action { repository.setVisible(group.groupId, visible, checkNotNull(group.generation)) }
    fun move(group: BookGroup, direction: Int) = action { repository.move(group.groupId, direction, _state.value.selection, checkNotNull(group.generation)) }
    fun confirmSelection() {
        val state = _state.value
        if (state.loading || !state.selection) return
        val key = state.bookUrl ?: return
        action {
            val available = _state.value.groups.filter { it.isSelectableGroup() }.fold(0L) { mask, group -> mask or group.groupId }
            applySelection(key, state.selected and available, state.selectedOwners)
            publish(_state.value.copy(open = false))
        }
    }
    fun chooseCover() { if (!_state.value.busy && _state.value.editor != null) publish(_state.value.copy(choosingCover = true)) }
    fun receiveCover(uri: Uri?) {
        if (!_state.value.choosingCover || _state.value.editor == null) return
        publish(_state.value.copy(choosingCover = false))
        if (uri == null) return
        saved["groupsCoverUri"] = uri.toString()
        action {
            try {
                updateEditorAfterImport(repository.importCover(uri))
                saved.remove<String>("groupsCoverUri")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { saved.remove<String>("groupsCoverUri"); throw error }
        }
    }
    private fun updateEditorAfterImport(path: String) {
        val state = _state.value
        state.editor?.let { publish(state.copy(editor = it.copy(cover = path, importedCover = true))) }
    }
    suspend fun coverBitmap(path: String?) = repository.coverBitmap(path)
    fun retry() = observe()

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        publish(_state.value.copy(busy = true, error = null, errorDetail = null))
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publish(_state.value.copy(error = (error as? BookGroupException)?.reason ?: BookGroupError.Storage,
                    errorDetail = error.localizedMessage))
            } finally { publish(_state.value.copy(busy = false)) }
        }
    }

    private fun observe() {
        observer?.cancel()
        observer = scope.launch {
            try {
                repository.groups.collect { groups ->
                    val state = _state.value
                    val available = groups.filter { it.isSelectableGroup() && (!state.ownersReady || state.selectedOwners[it.groupId] == it.generation) }
                        .fold(0L) { mask, group -> mask or group.groupId }
                    val selected = if (state.selection) state.selected and available else state.selected
                    publish(state.copy(groups = groups.map { it.copy() }, loading = false,
                        selected = selected, selectedOwners = if (state.selection) owners(groups, selected) else state.selectedOwners,
                        ownersReady = state.ownersReady || state.selection))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(_state.value.copy(loading = false, error = BookGroupError.Storage, errorDetail = error.localizedMessage)) }
        }
    }

    private fun owners(groups: List<BookGroup>, selected: Long): Map<Long, String> = groups
        .filter { it.isSelectableGroup() && selected and it.groupId != 0L }
        .mapNotNull { group -> group.generation?.let { group.groupId to it } }.toMap()

    private fun publish(state: BookGroupsUiState) {
        if (!state.open) {
            listOf("groupsOpen", "groupsSelection", "groupsBookUrl", "groupsSelected", "groupsOwners", "groupsOwnersReady", "groupsEditor", "groupsEditorId", "groupsGeneration", "groupsName", "groupsCover", "groupsCoverLocal", "groupsSort", "groupsRefresh", "groupsOnlyRead", "groupsDelete", "groupsChoosingCover").forEach { saved.remove<Any>(it) }
            _state.value = state
            return
        }
        saved["groupsOpen"] = state.open
        saved["groupsSelection"] = state.selection
        saved["groupsBookUrl"] = state.bookUrl
        saved["groupsSelected"] = state.selected
        saved["groupsOwners"] = ArrayList(state.selectedOwners.map { "${it.key}:${it.value}" })
        saved["groupsOwnersReady"] = state.ownersReady
        saved["groupsEditor"] = state.editor != null
        saved["groupsEditorId"] = state.editor?.id
        state.editor?.let {
            saved["groupsName"] = it.name; saved["groupsCover"] = it.cover; saved["groupsSort"] = it.sort
            saved["groupsCoverLocal"] = it.importedCover
            saved["groupsGeneration"] = it.generation
            saved["groupsRefresh"] = it.refresh; saved["groupsOnlyRead"] = it.onlyRead
        }
        saved["groupsDelete"] = state.confirmingDelete
        saved["groupsChoosingCover"] = state.choosingCover
        _state.value = state
    }
    private fun restore(): BookGroupsUiState = BookGroupsUiState(open = saved["groupsOpen"] ?: false,
        selection = saved["groupsSelection"] ?: false, bookUrl = saved["groupsBookUrl"], selected = saved["groupsSelected"] ?: 0,
        selectedOwners = saved.get<ArrayList<String>>("groupsOwners").orEmpty().mapNotNull { item ->
            val parts = item.split(':', limit = 2)
            if (parts.size == 2) parts[0].toLongOrNull()?.let { it to parts[1] } else null
        }.toMap(), ownersReady = saved["groupsOwnersReady"] ?: false,
        confirmingDelete = saved["groupsDelete"] ?: false, choosingCover = saved["groupsChoosingCover"] ?: false,
        editor = if (saved.get<Boolean>("groupsEditor") == true) BookGroupDraft(saved["groupsEditorId"], saved["groupsName"] ?: "", saved["groupsCover"],
            saved["groupsSort"] ?: -1, saved["groupsRefresh"] ?: true, saved["groupsOnlyRead"] ?: false,
            saved["groupsCoverLocal"] ?: false, saved["groupsGeneration"] ?: UUID.randomUUID().toString()) else null)
}
