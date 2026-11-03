package io.legado.app.ui.replace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceRuleImportPreview
import io.legado.app.data.repository.ReplaceRuleImportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReplaceRuleImportUiState(
    val rules: List<ReplaceRule> = emptyList(),
    val existingRules: List<ReplaceRule?> = emptyList(),
    val selectedIndexes: Set<Int> = emptySet(),
    val groups: List<String> = emptyList(),
    val groupName: String? = null,
    val addGroup: Boolean = false,
    val loading: Boolean = true,
    val importing: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
) {
    val selectedCount: Int get() = selectedIndexes.size
    val isSelectAll: Boolean get() = rules.isNotEmpty() && selectedIndexes.size == rules.size
}

@HiltViewModel
class ReplaceRuleImportViewModel @Inject constructor(
    private val repository: ReplaceRuleImportRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReplaceRuleImportUiState())
    val uiState: StateFlow<ReplaceRuleImportUiState> = _uiState.asStateFlow()
    private var loadedSource: String? = null

    init {
        viewModelScope.launch {
            repository.observeGroups().collect { groups -> _uiState.update { it.copy(groups = groups) } }
        }
    }

    fun load(source: String) {
        if (loadedSource == source) return
        loadedSource = source
        _uiState.update { ReplaceRuleImportUiState(groups = it.groups, loading = true) }
        viewModelScope.launch {
            runCatching { repository.preview(source) }
                .onSuccess(::showPreview)
                .onFailure { error ->
                    _uiState.update { it.copy(loading = false, error = error.localizedMessage ?: "导入失败") }
                }
        }
    }

    private fun showPreview(preview: ReplaceRuleImportPreview) {
        _uiState.update {
            it.copy(
                rules = preview.rules,
                existingRules = preview.existingRules,
                selectedIndexes = preview.selectedIndexes,
                loading = false,
                error = if (preview.rules.isEmpty()) "没有可导入的规则" else null,
            )
        }
    }

    fun toggle(index: Int, selected: Boolean) = _uiState.update {
        it.copy(selectedIndexes = if (selected) it.selectedIndexes + index else it.selectedIndexes - index)
    }

    fun selectAll(selected: Boolean) = _uiState.update {
        it.copy(selectedIndexes = if (selected) it.rules.indices.toSet() else emptySet())
    }

    fun replaceRule(index: Int, rule: ReplaceRule) = _uiState.update { state ->
        if (index !in state.rules.indices) state
        else state.copy(rules = state.rules.toMutableList().also { it[index] = rule })
    }

    fun setGroup(name: String?, add: Boolean) = _uiState.update {
        it.copy(groupName = name, addGroup = add)
    }

    fun importSelected() {
        val state = _uiState.value
        val selected = state.selectedIndexes.mapNotNull(state.rules::getOrNull)
        if (selected.isEmpty() || state.importing) return
        _uiState.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.import(selected, state.groupName, state.addGroup) }
                .onSuccess { _uiState.update { it.copy(importing = false, completed = true) } }
                .onFailure { error ->
                    _uiState.update { it.copy(importing = false, error = error.localizedMessage ?: "导入失败") }
                }
        }
    }
}
