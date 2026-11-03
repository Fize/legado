package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.TextTocRuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.inject.Inject

data class TextTocRuleUiState(
    val rules: List<TxtTocRule> = emptyList(),
    val query: String = "",
    val selectedIds: Set<Long> = emptySet(),
    val editor: TxtTocRule? = null,
    val deleteTargets: List<TxtTocRule> = emptyList(),
    val pendingShareFile: File? = null,
    val error: String? = null,
) {
    val visibleRules: List<TxtTocRule> get() = rules.filterByKeyword(query)
    val selectedRules: List<TxtTocRule> get() = visibleRules.filter { it.id in selectedIds }
}

@HiltViewModel
class TextTocRuleViewModel @Inject constructor(
    private val repository: TextTocRuleRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TextTocRuleUiState())
    val uiState: StateFlow<TextTocRuleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { rules ->
                val ids = rules.mapTo(hashSetOf(), TxtTocRule::id)
                _uiState.update { it.copy(rules = rules, selectedIds = it.selectedIds.intersect(ids)) }
            }
        }
    }

    fun setQuery(query: String) = _uiState.update {
        it.copy(query = query, selectedIds = if (query == it.query) it.selectedIds else emptySet())
    }

    fun toggleSelection(id: Long, selected: Boolean) = _uiState.update {
        it.copy(selectedIds = if (selected) it.selectedIds + id else it.selectedIds - id)
    }

    fun selectAll(selected: Boolean) = _uiState.update {
        it.copy(selectedIds = if (selected) it.visibleRules.mapTo(linkedSetOf(), TxtTocRule::id) else emptySet())
    }

    fun invertSelection() = _uiState.update { state ->
        state.copy(selectedIds = state.visibleRules.mapNotNullTo(linkedSetOf()) {
            if (it.id in state.selectedIds) null else it.id
        })
    }

    fun edit(rule: TxtTocRule?) = _uiState.update {
        it.copy(editor = rule ?: TxtTocRule())
    }

    fun dismissEditor() = _uiState.update { it.copy(editor = null) }

    fun save(rule: TxtTocRule) {
        val error = validate(rule)
        if (error != null) {
            _uiState.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            repository.save(rule)
            dismissEditor()
        }
    }

    fun setEnabled(rules: List<TxtTocRule>, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(rules, enabled)
    }

    fun moveToTop(rule: TxtTocRule) = viewModelScope.launch { repository.moveToTop(rule) }
    fun moveToBottom(rule: TxtTocRule) = viewModelScope.launch { repository.moveToBottom(rule) }
    fun requestDelete(rules: List<TxtTocRule>) = _uiState.update { it.copy(deleteTargets = rules) }
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

    private fun validate(rule: TxtTocRule): String? {
        if (rule.name.isBlank()) return "名称不能为空"
        return try {
            Pattern.compile(rule.rule, Pattern.MULTILINE)
            null
        } catch (error: PatternSyntaxException) {
            "正则语法错误或不支持(txt)：${error.localizedMessage}"
        }
    }
}
