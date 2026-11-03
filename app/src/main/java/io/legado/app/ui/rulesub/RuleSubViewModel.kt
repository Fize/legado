package io.legado.app.ui.rulesub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.repository.RuleSubRepository
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface RuleSubSaveResult {
    data object Saved : RuleSubSaveResult
    data object EmptyUrl : RuleSubSaveResult
    data class DuplicateUrl(val name: String) : RuleSubSaveResult
}

@HiltViewModel
class RuleSubViewModel @Inject constructor(
    private val repository: RuleSubRepository,
) : ViewModel() {

    val subscriptions: StateFlow<List<RuleSub>> = repository.observeAll()
        .catch {
            AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun newSubscription(): RuleSub = withContext(IO) {
        RuleSub(customOrder = repository.nextOrder())
    }

    suspend fun save(ruleSub: RuleSub): RuleSubSaveResult = withContext(IO) {
        if (ruleSub.url.isBlank()) return@withContext RuleSubSaveResult.EmptyUrl
        val existing = repository.findByUrl(ruleSub.url)
        if (existing != null && existing.id != ruleSub.id) {
            return@withContext RuleSubSaveResult.DuplicateUrl(existing.name)
        }
        repository.save(ruleSub)
        RuleSubSaveResult.Saved
    }

    fun delete(ruleSub: RuleSub) {
        viewModelScope.launch(IO) { repository.delete(ruleSub) }
    }
}
