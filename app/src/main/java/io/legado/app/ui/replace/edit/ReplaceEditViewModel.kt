package io.legado.app.ui.replace.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceRuleRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReplaceEditViewModel @Inject constructor(
    private val repository: ReplaceRuleRepository,
) : ViewModel() {

    private var loadedKey: String? = null
    private val _initialRule = MutableStateFlow<ReplaceRule?>(null)
    val initialRule = _initialRule.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()

    fun initData(
        id: Long,
        pattern: String?,
        isRegex: Boolean,
        scope: String?,
    ) {
        val key = "$id|$pattern|$isRegex|$scope"
        if (loadedKey == key) return
        loadedKey = key
        viewModelScope.launch {
            try {
                val rule = if (id > 0) repository.getRule(id)
                    ?: error("Replacement rule no longer exists")
                else ReplaceRule(
                    name = pattern.orEmpty(),
                    pattern = pattern.orEmpty(),
                    isRegex = isRegex,
                    scope = scope,
                )
                _initialRule.value = rule
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _loadError.value = error.localizedMessage ?: "Rule could not be loaded"
            }
        }
    }

    fun consumeLoadError() {
        _loadError.value = null
    }

    fun pasteRule(text: String?, onLoaded: (ReplaceRule) -> Unit, onError: (Throwable) -> Unit) {
        runCatching {
            require(!text.isNullOrBlank()) { "剪贴板为空" }
            GSON.fromJsonObject<ReplaceRule>(text).getOrThrow()
        }.onSuccess(onLoaded).onFailure(onError)
    }

    fun sampleFor(ruleId: Long): String = repository.sampleFor(ruleId)

    fun save(
        rule: ReplaceRule,
        sample: String,
        onSaved: (ReplaceRule) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val saved = repository.saveRule(rule)
                repository.saveSample(saved.id, ReplacePreview.normalizeSample(sample))
                onSaved(saved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
            }
        }
    }
}
