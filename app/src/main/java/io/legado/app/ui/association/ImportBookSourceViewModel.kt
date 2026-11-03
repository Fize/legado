package io.legado.app.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.repository.BookSourceImportRepository
import io.legado.app.data.repository.*
import javax.inject.Inject
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class ImportBookSourceUiState(
    val sources: List<BookSource> = emptyList(),
    val localSources: List<BookSourcePart?> = emptyList(),
    val selected: List<Boolean> = emptyList(),
    val newSources: List<Boolean> = emptyList(),
    val updatedSources: List<Boolean> = emptyList(),
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
    val successCount: Int? = null,
    val searchQuery: String = "",
    val searchPreset: SourceImportPreset? = null,
    val sourceRevision: Long = 0,
    val groupName: String? = null,
    val isAddGroup: Boolean = false,
    val automaticSourceReplacement: Boolean = false,
    val pendingReplacementDialog: Pair<Boolean, Int>? = null,
    val groups: List<String> = emptyList(),
)


@HiltViewModel
class ImportBookSourceViewModel @Inject constructor(
    private val repository: BookSourceImportRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ImportBookSourceUiState(
            groupName = AppConfig.importLastGroup.takeIf { AppConfig.importRememberGroup },
            isAddGroup = AppConfig.importRememberGroup && AppConfig.importLastGroupAdd,
            automaticSourceReplacement = AppConfig.importReplaceSource,
        )
    )
    val uiState: StateFlow<ImportBookSourceUiState> = _uiState.asStateFlow()
    val allSources: List<BookSource> get() = uiState.value.sources
    val checkSources: List<BookSourcePart?> get() = uiState.value.localSources
    val selectStatus: List<Boolean> get() = uiState.value.selected
    val newSourceStatus: List<Boolean> get() = uiState.value.newSources
    val updateSourceStatus: List<Boolean> get() = uiState.value.updatedSources
    var isAddGroup: Boolean
        get() = uiState.value.isAddGroup
        set(value) { _uiState.update { it.copy(isAddGroup = value) } }
    var groupName: String?
        get() = uiState.value.groupName
        set(value) { _uiState.update { it.copy(groupName = value) } }
    var searchQuery: String
        get() = uiState.value.searchQuery
        set(value) { updateSearch(value) }
    fun updateSearch(value: String, preset: SourceImportPreset? = null) {
        _uiState.update { it.copy(searchQuery = value, searchPreset = preset) }
    }
    private val sourceItems = arrayListOf<BookSource>()
    private val localSourceItems = arrayListOf<BookSourcePart?>()
    private val selectedItems = arrayListOf<Boolean>()
    private val newSourceItems = arrayListOf<Boolean>()
    private val updatedSourceItems = arrayListOf<Boolean>()
    private var reimportBookUrl: String? = null
    private var reimportSourceUrl: String? = null

    private val sourceCandidates = arrayListOf<BookSourceImportCandidate>()
    private val sourceGroupItems = arrayListOf<String>()
    private val manualSelections = arrayListOf<Boolean?>()
    private var importStarted = false
    var automaticSourceReplacement: Boolean
        get() = uiState.value.automaticSourceReplacement
        private set(value) { updateUi { copy(automaticSourceReplacement = value) } }
    private val manualRuleIds = hashMapOf<Int, List<Long>>()
    val useSourceReplacement: Boolean
        get() = automaticSourceReplacement || manualRuleIds.isNotEmpty()

    var pendingReplacementDialog: Pair<Boolean, Int>?
        get() = uiState.value.pendingReplacementDialog
        set(value) { updateUi { copy(pendingReplacementDialog = value) } }

    private fun updateUi(
        transform: ImportBookSourceUiState.() -> ImportBookSourceUiState = { this },
    ) {
        _uiState.update { current ->
            transform(
                current.copy(
                    sources = sourceItems.map { it.copy() },
                    sourceRevision = current.sourceRevision + 1,
                    localSources = localSourceItems.map { it?.copy() },
                    selected = selectedItems.toList(),
                    newSources = newSourceItems.toList(),
                    updatedSources = updatedSourceItems.toList(),
                    searchQuery = searchQuery,
                    groupName = groupName,
                    isAddGroup = isAddGroup,
                    automaticSourceReplacement = automaticSourceReplacement,
                    pendingReplacementDialog = pendingReplacementDialog,
                    groups = sourceGroupItems.toList(),
                )
            )
        }
    }

    private fun <T> launchIo(
        block: suspend () -> T,
        onSuccess: (T) -> Unit = {},
        onError: (Throwable) -> Unit = {},
        onFinally: () -> Unit = {},
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val result = block()
            withContext(Main) { onSuccess(result) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(Main) { onError(error) }
        } finally {
            withContext(Main) { onFinally() }
        }
    }

    val isSelectAll: Boolean
        get() {
            selectedItems.forEachIndexed { index, selected ->
                if (canImportSource(index) && !selected) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceItems.forEachIndexed { index, b ->
                if (b && canImportSource(index) && !selectedItems[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updatedSourceItems.forEachIndexed { index, b ->
                if (b && canImportSource(index) && !selectedItems[index]) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectedItems.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(finally: () -> Unit = {}) {
        if (uiState.value.isUpdating || uiState.value.isFinished) return
        updateUi { copy(isUpdating = true, errorMessage = null) }
        launchIo(
            block = {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectedItems.forEachIndexed { index, b ->
                if (b && canImportSource(index)) {
                    val source = sourceItems[index]
                    localSourceItems[index]?.let {
                        if (keepName) {
                            source.bookSourceName = it.bookSourceName
                        }
                        if (keepGroup) {
                            source.bookSourceGroup = it.bookSourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.bookSourceGroup = groups.joinToString(",")
                        } else {
                            source.bookSourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            // Once confirmed, finish publication even if the dialog is destroyed after the DB write.
            withContext(NonCancellable) {
                val source = repository.publishSources(
                    selectSource,
                    reimportSourceUrl?.takeIf { url -> selectSource.any { it.bookSourceUrl == url } },
                )
                withContext(Main) {
                    val current = ReadBook.book
                    if (source != null && current != null && current.bookUrl == reimportBookUrl &&
                        current.origin == reimportSourceUrl) {
                        ReadBook.bookSource = source
                    }
                    updateUi { copy(isFinished = true) }
                }
            }
            },
            onError = {
                updateUi { copy(errorMessage = "ImportError:${it.localizedMessage}", isLoading = false) }
                AppLog.put("ImportError:${it.localizedMessage}", it)
            },
            onFinally = {
            updateUi { copy(isUpdating = false) }
            finally.invoke()
            },
        )
    }

    fun importSource(text: String, reimportBookUrl: String? = null, reimportSourceUrl: String? = null) {
        if (importStarted) return
        importStarted = true
        this.reimportBookUrl = reimportBookUrl
        this.reimportSourceUrl = reimportSourceUrl
        launchIo(
            block = { repository.sourceGroups() },
            onSuccess = { groups ->
                sourceGroupItems.clear()
                sourceGroupItems.addAll(groups)
                updateUi()
            },
        )
        launchIo(
            block = { repository.readSources(text) },
            onSuccess = {
                sourceItems.clear()
                sourceItems.addAll(it)
                prepareSourceCandidates()
            },
            onError = {
                updateUi { copy(errorMessage = "ImportError:${it.localizedMessage}", isLoading = false) }
                AppLog.put("ImportError:${it.localizedMessage}", it)
            },
        )
    }

    private fun prepareSourceCandidates() {
        launchIo(
            block = {
            repository.prepareCandidates(sourceItems, automaticSourceReplacement, manualRuleIds)
            },
            onSuccess = { candidates ->
            sourceCandidates.clear()
            sourceCandidates.addAll(candidates)
            applyCandidateSources()
            comparisonSource()
            },
            onError = {
                updateUi { copy(errorMessage = "ImportError:${it.localizedMessage}", isLoading = false) }
                AppLog.put("ImportError:${it.localizedMessage}", it)
            },
        )
    }

    private fun applyCandidateSources() {
        sourceItems.clear()
        sourceItems.addAll(sourceCandidates.map { it.source(useSourceReplacement) })
    }

    fun setUseSourceReplacement(enabled: Boolean) {
        if (enabled == automaticSourceReplacement) return
        refreshSourceReplacements(automatic = enabled)
    }

    private fun comparisonSource(
        preserveManualSelections: Boolean = false,
        onError: () -> Unit = {},
        finally: () -> Unit = {},
    ) {
        val savedManualSelections = manualSelections.toList()
        launchIo(
            block = {
            repository.compareSources(sourceItems)
            },
            onSuccess = { comparisons ->
            localSourceItems.clear()
            selectedItems.clear()
            newSourceItems.clear()
            updatedSourceItems.clear()
            manualSelections.clear()
            comparisons.forEachIndexed { index, (localSource, status) ->
                val manualSelection = if (preserveManualSelections) {
                    savedManualSelections.getOrNull(index)
                } else {
                    null
                }
                localSourceItems.add(localSource)
                selectedItems.add(
                    canImportSource(index) &&
                        resolveImportSourceSelection(status, manualSelection,
                            selectExisting = reimportSourceUrl == sourceItems[index].bookSourceUrl)
                )
                newSourceItems.add(status.isNew)
                updatedSourceItems.add(status.isUpdate)
                manualSelections.add(manualSelection)
            }
            updateUi { copy(successCount = sourceItems.size, isLoading = false, errorMessage = null) }
            },
            onError = {
                onError()
                updateUi { copy(errorMessage = "ImportError:${it.localizedMessage}", isLoading = false) }
                AppLog.put("ImportError:${it.localizedMessage}", it)
            },
            onFinally = finally,
        )
    }

    fun setSelection(index: Int, selected: Boolean) {
        if (index !in selectedItems.indices || index !in manualSelections.indices) return
        if (uiState.value.isUpdating || !canImportSource(index)) return
        selectedItems[index] = selected
        manualSelections[index] = selected
        updateUi()
    }

    fun updateSource(index: Int, source: BookSource) {
        if (uiState.value.isUpdating) return
        updateUi { copy(isUpdating = true, errorMessage = null) }
        launchIo(
            block = {
            repository.prepareEditedCandidate(
                source,
                automaticSourceReplacement,
                selectedManualRuleIds(index),
            )
            },
            onSuccess = { (candidate, localSource, editedStatus) ->
            if (index !in sourceItems.indices) return@launchIo
            sourceCandidates[index] = candidate
            sourceItems[index] = candidate.source(useSourceReplacement)
            localSourceItems[index] = localSource
            selectedItems[index] = canImportSource(index) &&
                resolveImportSourceSelection(editedStatus, manualSelections[index],
                    selectExisting = reimportSourceUrl == sourceItems[index].bookSourceUrl)
            newSourceItems[index] = editedStatus.isNew
            updatedSourceItems[index] = editedStatus.isUpdate
            updateUi { copy(successCount = sourceItems.size) }
            },
            onError = {
                updateUi { copy(errorMessage = "ImportError:${it.localizedMessage}") }
                AppLog.put("ImportError:${it.localizedMessage}", it)
            },
            onFinally = { updateUi { copy(isUpdating = false) } },
        )
    }

    fun refreshSourceReplacements(
        index: Int = -1,
        source: BookSource? = null,
        ids: List<Long>? = null,
        openDialog: Boolean? = null,
        automatic: Boolean = automaticSourceReplacement,
    ): Boolean {
        if (uiState.value.isUpdating || (index != -1 && index !in sourceCandidates.indices)) return false
        if (automatic && (ids != null || openDialog == true)) return false
        val previousCandidates = sourceCandidates.toList()
        val previousIds = manualRuleIds.toMap()
        val previousMode = automaticSourceReplacement
        automaticSourceReplacement = automatic
        fun restorePreviousState() {
            automaticSourceReplacement = previousMode
            sourceCandidates.clear()
            sourceCandidates.addAll(previousCandidates)
            manualRuleIds.clear()
            manualRuleIds.putAll(previousIds)
            applyCandidateSources()
        }
        if (ids != null) {
            if (index == -1) sourceCandidates.indices.forEach { manualRuleIds[it] = ids }
            else manualRuleIds[index] = ids
        }
        val selectedIds = manualRuleIds.toMap().takeUnless { automatic }
        updateUi { copy(isUpdating = true, errorMessage = null) }
        launchIo(
            block = {
            repository.refreshCandidates(
                previousCandidates,
                index,
                source,
                selectedIds,
            )
            },
            onSuccess = { candidates ->
            var comparisonSucceeded = true
            sourceCandidates.clear()
            sourceCandidates.addAll(candidates)
            applyCandidateSources()
            comparisonSource(
                preserveManualSelections = true,
                onError = {
                    comparisonSucceeded = false
                    restorePreviousState()
                },
            ) {
                if (comparisonSucceeded) {
                    AppConfig.importReplaceSource = automatic
                    if (openDialog != null) pendingReplacementDialog = openDialog to index
                }
                updateUi { copy(isUpdating = false) }
            }
            },
            onError = {
                updateUi { copy(errorMessage = "ImportError:${it.localizedMessage}") }
                AppLog.put("ImportError:${it.localizedMessage}", it)
                restorePreviousState()
                updateUi { copy(isUpdating = false) }
            },
        )
        return true
    }

    fun selectedManualRuleIds(index: Int = -1): List<Long> = if (index >= 0) manualRuleIds[index].orEmpty()
        else sourceCandidates.indices.map { manualRuleIds[it].orEmpty().toSet() }
            .reduceOrNull { all, ids -> all.intersect(ids) }?.toList().orEmpty()

    fun effectiveRuleIds(index: Int = -1): List<Long> = if (!useSourceReplacement) emptyList() else
        (if (index >= 0) listOfNotNull(sourceCandidates.getOrNull(index)) else sourceCandidates)
            .flatMap { it.effectiveRuleIds }.distinct()

    fun canImportSource(index: Int): Boolean =
        sourceCandidates.getOrNull(index)?.canImport(useSourceReplacement) != false

    fun originalSourceJson(index: Int): String? =
        sourceCandidates.getOrNull(index)?.originalJson

    fun replacedSourceJson(index: Int): String? =
        sourceCandidates.getOrNull(index)?.replacedJson

    fun sourceReplacementError(index: Int): String? =
        sourceCandidates.getOrNull(index)?.replacementError

}
