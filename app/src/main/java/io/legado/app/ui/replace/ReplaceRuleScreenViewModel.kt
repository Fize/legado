package io.legado.app.ui.replace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceRuleRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ReplaceRuleScreenState(
    val rules: List<ReplaceRule> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val query: String = "",
    val manualReplaceEnabled: Boolean = false,
    val pendingShareFile: File? = null,
    val error: String? = null,
) {
    val selectedRules: List<ReplaceRule> get() = rules.filter { it.id in selectedIds }
}

@HiltViewModel
class ReplaceRuleScreenViewModel @Inject constructor(
    private val repository: ReplaceRuleRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReplaceRuleScreenState())
    val uiState: StateFlow<ReplaceRuleScreenState> = _uiState.asStateFlow()
    private var rulesJob: Job? = null

    init {
        observeRules("")
        viewModelScope.launch { repository.observeGroups().collect { groups -> _uiState.update { it.copy(groups = groups) } } }
        _uiState.update { it.copy(manualReplaceEnabled = repository.manualReplaceEnabled()) }
    }

    fun setQuery(query: String) {
        if (_uiState.value.query == query) return
        _uiState.update { it.copy(query = query, selectedIds = emptySet()) }
        observeRules(query)
    }

    private fun observeRules(query: String) {
        rulesJob?.cancel()
        rulesJob = viewModelScope.launch {
            repository.observeRules(query).collect { rules ->
                val ids = rules.mapTo(hashSetOf(), ReplaceRule::id)
                _uiState.update { it.copy(rules = rules, selectedIds = it.selectedIds.intersect(ids)) }
            }
        }
    }

    fun toggleSelection(id: Long, selected: Boolean) = _uiState.update {
        it.copy(selectedIds = if (selected) it.selectedIds + id else it.selectedIds - id)
    }

    fun selectAll(selected: Boolean) = _uiState.update {
        it.copy(selectedIds = if (selected) it.rules.mapTo(linkedSetOf(), ReplaceRule::id) else emptySet())
    }

    fun invertSelection() = _uiState.update { state ->
        state.copy(selectedIds = state.rules.mapNotNullTo(linkedSetOf()) {
            if (it.id in state.selectedIds) null else it.id
        })
    }

    fun setManualReplace(enabled: Boolean) {
        repository.setManualReplaceEnabled(enabled)
        _uiState.update { it.copy(manualReplaceEnabled = enabled) }
    }

    fun setEnabled(rule: ReplaceRule, enabled: Boolean) = launch { repository.setEnabled(rule, enabled) }
    fun setEnabled(rules: List<ReplaceRule>, enabled: Boolean) = launch { repository.setEnabled(rules, enabled) }
    fun delete(rules: List<ReplaceRule>) = launch { repository.delete(rules) }
    fun moveToTop(rule: ReplaceRule) = launch { repository.moveToTop(rule) }
    fun moveToBottom(rule: ReplaceRule) = launch { repository.moveToBottom(rule) }
    fun moveSelection(rules: List<ReplaceRule>, top: Boolean) = launch { repository.moveSelection(rules, top) }
    fun setGroups(rules: List<ReplaceRule>, group: String, add: Boolean) = launch { repository.setGroups(rules, group, add) }
    fun addGroup(group: String) = launch { repository.addGroup(group) }
    fun renameGroup(old: String, new: String?) = launch { repository.renameGroup(old, new) }

    fun shareSelected() = launch {
        runCatching { repository.createShareFile(_uiState.value.selectedRules) }
            .onSuccess { file -> _uiState.update { it.copy(pendingShareFile = file) } }
            .onFailure { error -> _uiState.update { it.copy(error = error.localizedMessage.orEmpty()) } }
    }

    fun exportJson(rules: List<ReplaceRule>): ByteArray = repository.exportJson(rules)
    fun consumeShareFile() = _uiState.update { it.copy(pendingShareFile = null) }
    fun consumeError() = _uiState.update { it.copy(error = null) }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { error -> _uiState.update { it.copy(error = error.localizedMessage.orEmpty()) } }
    }
}
