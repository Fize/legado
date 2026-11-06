package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookSourceImportJson
import io.legado.app.data.repository.parseBookSourceJson
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.ui.compose.component.unifiedSearchTextStyle
import io.legado.app.ui.compose.component.CompactSearchTextField
import io.legado.app.ui.compose.component.compactSearchHeight
import io.legado.app.ui.compose.component.unifiedSearchColors
import io.legado.app.ui.compose.component.unifiedSearchOutline
import io.legado.app.ui.compose.component.unifiedSearchShape
import io.legado.app.ui.compose.component.LegadoSearchActionLayout
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.navigation.ReplaceRuleRoute
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.first

/** Full screen Compose destination for adding or updating book sources. */
@Composable
fun BookSourceImportScreen(
    route: BookSourceImportRoute,
    onNavigate: (AppRoute) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ImportBookSourceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current as? io.legado.app.ui.AppActivity
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val successCount = state.successCount
    val onNavigateState by rememberUpdatedState(onNavigate)
    val sourceListReady = successCount != null && successCount > 0
    val presetLabels = SourceImportPreset.entries.associateWith { stringResource(it.labelId) }
    val displayedQuery = state.searchPreset?.let(presetLabels::get) ?: state.searchQuery
    var searchInput by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(displayedQuery, selection = TextRange(displayedQuery.length)))
    }
    LaunchedEffect(displayedQuery) {
        // Ignore an older collected value if another edit has already reached the model.
        val latest = viewModel.uiState.value
        val latestQuery = latest.searchPreset?.let(presetLabels::get) ?: latest.searchQuery
        if (displayedQuery == latestQuery && searchInput.text != displayedQuery) {
            searchInput = TextFieldValue(displayedQuery, selection = TextRange(displayedQuery.length))
        }
    }
    val filteredIndices = remember(state.sourceRevision, state.searchQuery, state.searchPreset) {
        state.sources.indices.filter { index ->
            val source = state.sources[index]
            state.searchPreset?.matches(source) ?: matchesSourceImportSearch(
                state.searchQuery, source.bookSourceName, source.bookSourceUrl,
                source.bookSourceGroup, source.bookSourceComment)
        }
    }
    var showMenu by remember { mutableStateOf(false) }
    var showCustomGroupDialog by remember { mutableStateOf(false) }
    var keepOriginalName by remember { mutableStateOf(AppConfig.importKeepName) }
    var keepGroup by remember { mutableStateOf(AppConfig.importKeepGroup) }
    var keepEnable by remember { mutableStateOf(AppConfig.importKeepEnable) }
    var showComment by remember { mutableStateOf(AppConfig.importShowComment) }
    var rememberGroup by remember { mutableStateOf(AppConfig.importRememberGroup) }
    var pendingReplacementRefresh by rememberSaveable { mutableStateOf<Pair<String, String?>?>(null) }
    var wasReplaceRulesOpened by rememberSaveable { mutableStateOf(false) }

    val callbacks = remember(viewModel, activity) {
        object : BookSourceImportCallbacks {
            override fun onCodeSave(code: String, requestId: String?) {
                if (viewModel.uiState.value.isUpdating) return
                val index = requestId?.toIntOrNull() ?: return
                if (index !in viewModel.uiState.value.sources.indices) return
                val source = GSON.fromJsonObject<BookSource>(code).getOrNull() ?: return
                viewModel.updateSource(index, source)
            }

            override fun onOpenReplaceRules() {
                val dialog = activity?.supportFragmentManager
                    ?.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog
                val code = dialog?.currentOriginalCode()
                if (code != null) pendingReplacementRefresh = code to dialog.requestId
                dialog?.dismissAllowingStateLoss()
                wasReplaceRulesOpened = true
                onNavigateState(ReplaceRuleRoute)
            }

            override fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) {
                val index = requestId?.toIntOrNull() ?: return
                val source = parseDraftSource(code) ?: run {
                    activity?.toastOnUi(R.string.wrong_format)
                    return
                }
                viewModel.refreshSourceReplacements(index, source, openDialog = manual)
            }

            override fun isManualSourceReplacementEnabled(): Boolean =
                !viewModel.automaticSourceReplacement

            override fun getCodeAlternate(requestId: String?): String? =
                requestId?.toIntOrNull()?.let(viewModel::replacedSourceJson)

            override fun isReplaceRuleRefreshPending(): Boolean =
                pendingReplacementRefresh != null || viewModel.uiState.value.isUpdating

            override fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?) {
                val index = requestId?.toIntOrNull() ?: -1
                val dialog = activity?.supportFragmentManager
                    ?.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog
                val matchingDialog = dialog?.takeIf { it.requestId?.toIntOrNull() == index }
                val source = matchingDialog?.let { parseDraftSource(it.currentOriginalCode()) }
                if (matchingDialog != null && source == null) {
                    activity.toastOnUi(R.string.wrong_format)
                    return
                }
                viewModel.refreshSourceReplacements(index, source, ids)
            }

            override fun onEffectiveSourceRulesChanged() {
                val dialog = activity?.supportFragmentManager
                    ?.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog
                if (dialog == null) {
                    viewModel.refreshSourceReplacements()
                } else {
                    pendingReplacementRefresh = dialog.currentOriginalCode() to dialog.requestId
                    startPendingReplacementRefresh(
                        pendingReplacementRefresh,
                        viewModel.uiState.value.successCount?.let { it > 0 } == true,
                        viewModel,
                    ) { pendingReplacementRefresh = null }
                }
            }
        }
    }

    DisposableEffect(activity, callbacks) {
        activity?.bindBookSourceImportCallbacks(callbacks)
        onDispose { activity?.unbindBookSourceImportCallbacks(callbacks) }
    }

    LaunchedEffect(route, viewModel) {
        viewModel.importSource(
            route.source,
            reimportBookUrl = route.reimportBookUrl,
            reimportSourceUrl = route.reimportSourceUrl,
        )
    }
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onDismiss()
    }
    LaunchedEffect(state.pendingReplacementDialog, state.isUpdating, activity) {
        val pending = state.pendingReplacementDialog ?: return@LaunchedEffect
        if (state.isUpdating || activity == null || activity.supportFragmentManager.isStateSaved) {
            return@LaunchedEffect
        }
        viewModel.pendingReplacementDialog = null
        val (manual, index) = pending
        if (manual) {
            if (!viewModel.automaticSourceReplacement) {
                activity.showDialogFragment(
                    ManualReplaceRulesDialog(viewModel.selectedManualRuleIds(index), index.toString())
                )
            }
        } else {
            activity.showDialogFragment(EffectiveReplacesDialog(viewModel.effectiveRuleIds(index)))
        }
    }
    // Navigation removes this screen's composition. Save the editor draft and restore it
    // after the rule manager returns, refreshing the derived preview from current rules.
    LaunchedEffect(Unit) {
        if (wasReplaceRulesOpened) {
            val pending = pendingReplacementRefresh
            viewModel.uiState.first { !it.isUpdating && !it.isLoading }
            val index = pending?.second?.toIntOrNull() ?: -1
            if (viewModel.refreshSourceReplacements(index, pending?.first?.let(::parseDraftSource))) {
                viewModel.uiState.first { !it.isUpdating }
            }
            if (pending != null && activity != null && !activity.supportFragmentManager.isStateSaved) {
                activity.showDialogFragment(
                    CodeDialog(
                        pending.first,
                        disableEdit = false,
                        requestId = pending.second,
                        alternateCode = viewModel.replacedSourceJson(index),
                        showAlternate = viewModel.useSourceReplacement,
                        showReplaceRules = true,
                    )
                )
            }
            pendingReplacementRefresh = null
            wasReplaceRulesOpened = false
        }
    }
    LaunchedEffect(state.isUpdating, state.sources, pendingReplacementRefresh) {
        val dialog = activity?.supportFragmentManager
            ?.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog ?: return@LaunchedEffect
        dialog.setReplaceRuleRefreshPending(state.isUpdating || pendingReplacementRefresh != null)
        if (!state.isUpdating && pendingReplacementRefresh == null) {
            if (parseDraftSource(dialog.currentOriginalCode()) != null) dialog.refreshAlternateCode()
            else dialog.clearAlternateCode()
        }
    }

    val groupTitle = state.groupName?.takeIf(String::isNotBlank)?.let {
        val title = resources.getString(R.string.diy_edit_source_group_title, it)
        if (state.isAddGroup) "+$title" else title
    } ?: stringResource(R.string.diy_source_group)
    val selectFooterText = if (state.searchQuery.isNotEmpty()) {
        val selected = filteredIndices.count { state.selected.getOrElse(it) { false } }
        val all = filteredIndices.all { index ->
            !viewModel.canImportSource(index) || state.selected.getOrElse(index) { false }
        }
        resources.getString(
            if (all) R.string.import_unselect_results else R.string.import_select_results,
            selected,
            filteredIndices.size,
            viewModel.selectCount,
        )
    } else {
        resources.getString(
            if (viewModel.isSelectAll) R.string.select_cancel_count else R.string.select_all_count,
            viewModel.selectCount,
            state.sources.size,
        )
    }
    val message = when {
        state.errorMessage != null -> state.errorMessage
        sourceListReady && filteredIndices.isEmpty() -> resources.getString(R.string.import_no_results)
        successCount == 0 -> resources.getString(R.string.wrong_format)
        else -> null
    }

    BackHandler(enabled = state.isUpdating) { }
    LegadoTheme {
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        val compactLayout = maxHeight < 280.dp
        LegadoSearchActionLayout(
            compact = compactLayout,
            modifier = Modifier.fillMaxSize().then(if (compactLayout) Modifier.statusBarsPadding() else Modifier),
            topBar = {
                if (!compactLayout) LegadoTopAppBar(
                    title = stringResource(R.string.import_book_source),
                    onNavigationClick = { if (!state.isUpdating) onDismiss() },
                    actions = {
                        TextButton(
                            onClick = { showCustomGroupDialog = true },
                            enabled = sourceListReady && !state.isUpdating,
                            modifier = Modifier.testTag("sourceImportGroup"),
                        ) {
                            Text(groupTitle, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                enabled = sourceListReady && !state.isUpdating,
                                modifier = Modifier.testTag("sourceImportOverflow"),
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_menu))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_new_source)) },
                                    onClick = {
                                        showMenu = false
                                        val indices = filteredIndices.filter { state.newSources.getOrElse(it) { false } }
                                        val all = indices.all { !viewModel.canImportSource(it) || state.selected.getOrElse(it) { false } }
                                        indices.forEach { viewModel.setSelection(it, !all) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_update_source)) },
                                    onClick = {
                                        showMenu = false
                                        val indices = filteredIndices.filter { state.updatedSources.getOrElse(it) { false } }
                                        val all = indices.all { !viewModel.canImportSource(it) || state.selected.getOrElse(it) { false } }
                                        indices.forEach { viewModel.setSelection(it, !all) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_original_name)) },
                                    trailingIcon = { Checkbox(checked = keepOriginalName, onCheckedChange = null) },
                                    onClick = {
                                        keepOriginalName = !keepOriginalName
                                        context.putPrefBoolean(PreferKey.importKeepName, keepOriginalName)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_group)) },
                                    trailingIcon = { Checkbox(checked = keepGroup, onCheckedChange = null) },
                                    onClick = {
                                        keepGroup = !keepGroup
                                        context.putPrefBoolean(PreferKey.importKeepGroup, keepGroup)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_enable)) },
                                    trailingIcon = { Checkbox(checked = keepEnable, onCheckedChange = null) },
                                    onClick = {
                                        keepEnable = !keepEnable
                                        AppConfig.importKeepEnable = keepEnable
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_source_comment)) },
                                    trailingIcon = { Checkbox(checked = showComment, onCheckedChange = null) },
                                    onClick = {
                                        showComment = !showComment
                                        AppConfig.importShowComment = showComment
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_remember_group)) },
                                    trailingIcon = { Checkbox(checked = rememberGroup, onCheckedChange = null) },
                                    onClick = {
                                        rememberGroup = !rememberGroup
                                        AppConfig.importRememberGroup = rememberGroup
                                        if (!rememberGroup) {
                                            viewModel.groupName = null
                                            viewModel.isAddGroup = false
                                        } else {
                                            AppConfig.importLastGroup = viewModel.groupName
                                            AppConfig.importLastGroupAdd = viewModel.isAddGroup
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.replace_source_on_import)) },
                                    modifier = Modifier.testTag("sourceImportMenu:autoReplace"),
                                    trailingIcon = { Checkbox(checked = state.automaticSourceReplacement, onCheckedChange = null) },
                                    onClick = { viewModel.setUseSourceReplacement(!state.automaticSourceReplacement) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.effective_replaces)) },
                                    modifier = Modifier.testTag("sourceImportMenu:effectiveRules"),
                                    onClick = {
                                        showMenu = false
                                        activity?.showDialogFragment(EffectiveReplacesDialog(viewModel.effectiveRuleIds()))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manual_replace_rule)) },
                                    enabled = !state.automaticSourceReplacement,
                                    modifier = Modifier.testTag("sourceImportMenu:manualRules"),
                                    onClick = {
                                        showMenu = false
                                        activity?.showDialogFragment(
                                            ManualReplaceRulesDialog(viewModel.selectedManualRuleIds(), "-1")
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_replace_rule)) },
                                    modifier = Modifier.testTag("sourceImportMenu:manageRules"),
                                    onClick = {
                                        showMenu = false
                                        callbacks.onOpenReplaceRules()
                                    },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (!compactLayout) HorizontalDivider()
                    ImportDialogFooter(
                        selectAllText = selectFooterText,
                        selectAllEnabled = sourceListReady && !state.isUpdating && filteredIndices.isNotEmpty(),
                        cancelEnabled = !state.isUpdating,
                        okEnabled = sourceListReady && !state.isUpdating,
                        onSelectAllClick = {
                            val all = filteredIndices.all { !viewModel.canImportSource(it) || state.selected.getOrElse(it) { false } }
                            filteredIndices.forEach { viewModel.setSelection(it, !all) }
                        },
                        onCancelClick = onDismiss,
                        onOkClick = viewModel::importSelect,
                        testTagPrefix = "sourceImportFooter",
                        compact = compactLayout,
                        compactSelectionText = stringResource(R.string.select_count, viewModel.selectCount, state.sources.size),
                    )
                }
            },
            searchBar = {
                if (route.modernSearch ?: (route.reimportBookUrl == null)) {
                CompactSearchTextField(
                    value = searchInput,
                    onValueChange = { value ->
                        searchInput = value
                        viewModel.updateSearch(value.text, presetLabels.entries.firstOrNull { it.value == value.text }?.key)
                    },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = if (compactLayout) null else ({ Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search)) }),
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().compactSearchHeight().unifiedSearchOutline().testTag("sourceImportSearch")
                        .padding(horizontal = if (compactLayout) 4.dp else 12.dp, vertical = if (compactLayout) 0.dp else 4.dp),
                )
                } else {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { value ->
                        searchInput = value
                        viewModel.updateSearch(value.text, presetLabels.entries.firstOrNull { it.value == value.text }?.key)
                    },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = if (compactLayout) null else ({ Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search)) }),
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sourceImportSearch")
                        .padding(horizontal = if (compactLayout) 4.dp else 12.dp, vertical = if (compactLayout) 0.dp else 4.dp),
                )
                }
            },
            content = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        sourceListReady && filteredIndices.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                            items(filteredIndices, key = { it }) { index ->
                                val source = state.sources[index]
                                val canImport = viewModel.canImportSource(index)
                                val replacementError = viewModel.sourceReplacementError(index)
                                    ?.takeIf { viewModel.useSourceReplacement }
                                val comment = replacementError?.let {
                                    resources.getString(R.string.source_replacement_error, it)
                                } ?: source.bookSourceComment?.takeIf { showComment && it.isNotBlank() }
                                val status = when {
                                    replacementError != null -> R.string.import_status_error
                                    state.newSources[index] -> R.string.import_status_new
                                    state.updatedSources[index] -> R.string.import_status_update
                                    else -> R.string.import_status_exist
                                }
                                ImportSourceItemRow(
                                    name = source.bookSourceName,
                                    checked = state.selected[index],
                                    statusText = stringResource(status),
                                    enabled = canImport && !state.isUpdating,
                                    comment = comment,
                                    showOpen = true,
                                    onCheckedChange = { viewModel.setSelection(index, it) },
                                    onOpenClick = {
                                        activity?.showDialogFragment(
                                            CodeDialog(
                                                viewModel.originalSourceJson(index) ?: GSON.toJson(source),
                                                disableEdit = false,
                                                requestId = index.toString(),
                                                alternateCode = viewModel.replacedSourceJson(index),
                                                showAlternate = viewModel.useSourceReplacement,
                                                showReplaceRules = true,
                                            )
                                        )
                                    },
                                    testTag = "sourceImportRow:$index",
                                )
                            }
                        }
                        message != null -> ImportEmptyOrErrorState(
                            message = message,
                            showIcon = state.errorMessage != null || successCount == 0,
                        )
                    }
                    if (state.isLoading || state.isUpdating) {
                        CircularProgressIndicator(Modifier.size(36.dp))
                    }
                }
            },
        )
        }

        if (showCustomGroupDialog) {
            ImportCustomGroupDialog(
                initialGroupName = state.groupName.orEmpty(),
                initialIsAddGroup = state.isAddGroup,
                groups = state.groups,
                onDismiss = { showCustomGroupDialog = false },
                onConfirm = { name, add ->
                    showCustomGroupDialog = false
                    viewModel.groupName = name
                    viewModel.isAddGroup = add
                    if (rememberGroup) {
                        AppConfig.importLastGroup = name
                        AppConfig.importLastGroupAdd = add
                    }
                },
            )
        }
    }
}

interface BookSourceImportCallbacks :
    CodeDialog.Callback,
    ManualReplaceRulesDialog.Callback,
    EffectiveReplacesDialog.Callback

private fun startPendingReplacementRefresh(
    pending: Pair<String, String?>?,
    sourceListReady: Boolean,
    viewModel: ImportBookSourceViewModel,
    onStarted: () -> Unit,
) {
    if (!sourceListReady || viewModel.uiState.value.isUpdating) return
    val (code, requestId) = pending ?: return
    val index = requestId?.toIntOrNull() ?: return
    if (viewModel.refreshSourceReplacements(index, parseDraftSource(code))) onStarted()
}

private fun parseDraftSource(code: String): BookSource? = runCatching {
    (parseBookSourceJson(code, allowSourceUrls = false) as BookSourceImportJson.Sources)
        .items.single()
}.getOrNull()
