package io.legado.app.ui.dict.rule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.DictRule
import io.legado.app.data.repository.DictionaryRuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DictionaryRuleUiState(
    val rules: List<DictRule> = emptyList(),
    val selectedNames: Set<String> = emptySet(),
    val editor: DictRule? = null,
    val originalRule: DictRule? = null,
    val originalName: String? = null,
    val deleteTargets: List<DictRule> = emptyList(),
    val pendingShareFile: File? = null,
    val error: String? = null,
) {
    val selectedRules: List<DictRule> get() = rules.filter { it.name in selectedNames }
}

@HiltViewModel
class DictionaryRuleViewModel @Inject constructor(
    private val repository: DictionaryRuleRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DictionaryRuleUiState())
    val uiState: StateFlow<DictionaryRuleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { rules ->
                val names = rules.mapTo(hashSetOf(), DictRule::name)
                _uiState.update { it.copy(rules = rules, selectedNames = it.selectedNames.intersect(names)) }
            }
        }
    }

    fun toggleSelection(name: String, selected: Boolean) = _uiState.update {
        it.copy(selectedNames = if (selected) it.selectedNames + name else it.selectedNames - name)
    }

    fun selectAll(selected: Boolean) = _uiState.update {
        it.copy(selectedNames = if (selected) it.rules.mapTo(linkedSetOf(), DictRule::name) else emptySet())
    }

    fun invertSelection() = _uiState.update { state ->
        state.copy(selectedNames = state.rules.mapNotNullTo(linkedSetOf()) {
            if (it.name in state.selectedNames) null else it.name
        })
    }

    fun edit(rule: DictRule?) = _uiState.update {
        it.copy(editor = rule ?: DictRule(), originalRule = rule, originalName = rule?.name)
    }

    fun dismissEditor() = _uiState.update { it.copy(editor = null, originalRule = null, originalName = null) }

    fun save(rule: DictRule) {
        if (rule.name.isBlank()) {
            _uiState.update { it.copy(error = "名称不能为空") }
            return
        }
        val originalName = _uiState.value.originalName
        viewModelScope.launch {
            runCatching { repository.save(rule, originalName) }
                .onSuccess { dismissEditor() }
                .onFailure { error -> _uiState.update { it.copy(error = error.localizedMessage.orEmpty()) } }
        }
    }

    fun replaceEditor(rule: DictRule) = _uiState.update { it.copy(editor = rule) }

    fun setEnabled(rules: List<DictRule>, enabled: Boolean) = viewModelScope.launch {
        repository.update(rules.map { it.copy(enabled = enabled) })
    }

    fun requestDelete(rules: List<DictRule>) = _uiState.update { it.copy(deleteTargets = rules) }
    fun dismissDelete() = _uiState.update { it.copy(deleteTargets = emptyList()) }

    fun delete() = viewModelScope.launch {
        repository.delete(_uiState.value.deleteTargets)
        _uiState.update { it.copy(deleteTargets = emptyList()) }
    }

    fun importDefault() = viewModelScope.launch { repository.importDefault() }

    fun shareSelected() = viewModelScope.launch {
        runCatching { repository.createShareFile(_uiState.value.selectedRules) }
            .onSuccess { file -> _uiState.update { it.copy(pendingShareFile = file) } }
            .onFailure { error -> _uiState.update { it.copy(error = error.localizedMessage.orEmpty()) } }
    }

    fun consumeShareFile() = _uiState.update { it.copy(pendingShareFile = null) }
    fun consumeError() = _uiState.update { it.copy(error = null) }
}
