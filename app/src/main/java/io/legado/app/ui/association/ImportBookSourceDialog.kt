package io.legado.app.ui.association

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 导入书源弹出窗口
 */
class ImportBookSourceDialog() : BaseComposeDialogFragment(),
    CodeDialog.Callback, ManualReplaceRulesDialog.Callback, EffectiveReplacesDialog.Callback {

    constructor(
        source: String,
        finishOnDismiss: Boolean = false,
        reimportBookUrl: String? = null,
        reimportSourceUrl: String? = null,
    ) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
            putString("reimportBookUrl", reimportBookUrl)
            putString("reimportSourceUrl", reimportSourceUrl)
        }
    }

    private val viewModel by viewModels<ImportBookSourceViewModel>()
    private var sourceListReady by mutableStateOf(false)
    private var isLoading by mutableStateOf(true)
    private var showEmptyIcon by mutableStateOf(false)
    private var messageText by mutableStateOf<String?>(null)
    private var filteredIndices by mutableStateOf<List<Int>>(emptyList())
    private var searchQueryState by mutableStateOf("")
    private var selectFooterText by mutableStateOf("")
    private var importEnabledState by mutableStateOf(false)
    private var cancelEnabledState by mutableStateOf(true)
    private var groupMenuTitle by mutableStateOf("")
    private var showCustomGroupDialog by mutableStateOf(false)
    private var keepOriginalName by mutableStateOf(AppConfig.importKeepName)
    private var keepGroup by mutableStateOf(AppConfig.importKeepGroup)
    private var keepEnable by mutableStateOf(AppConfig.importKeepEnable)
    private var showComment by mutableStateOf(AppConfig.importShowComment)
    private var rememberGroup by mutableStateOf(AppConfig.importRememberGroup)
    private var autoReplaceSource by mutableStateOf(false)
    private var listVersion by mutableIntStateOf(0)

    private var pendingReplacementRefresh: Pair<String, String?>? = null
    private val replaceRuleActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                openCodeDialog()?.let { dialog ->
                    pendingReplacementRefresh = dialog.currentOriginalCode() to dialog.requestId
                    dialog.setReplaceRuleRefreshPending(true)
                    if (!startPendingReplacementRefresh() && pendingReplacementRefresh == null) {
                        syncOpenCodeDialog()
                    }
                } ?: viewModel.refreshSourceReplacements()
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isLoading = true
        searchQueryState = viewModel.searchQuery
        autoReplaceSource = viewModel.automaticSourceReplacement
        updateGroupMenu()
        updateInteractionState()

        viewModel.importFinished.observe(viewLifecycleOwner) { finished ->
            if (finished) dismissAllowingStateLoss()
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            isLoading = false
            showEmptyIcon = true
            messageText = it
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            isLoading = false
            if (it > 0) {
                sourceListReady = true
                showEmptyIcon = false
                messageText = null
                refreshSources()
                if (viewModel.sourceUpdatePending.value != true &&
                    !startPendingReplacementRefresh() &&
                    pendingReplacementRefresh == null
                ) {
                    syncOpenCodeDialog()
                }
            } else {
                showEmptyIcon = true
                messageText = getString(R.string.wrong_format)
            }
        }
        viewModel.sourceUpdatePending.observe(viewLifecycleOwner) {
            if (it == true) isLoading = true
            else if (sourceListReady) isLoading = false
            listVersion++
            updateInteractionState()
            openCodeDialog()?.setReplaceRuleRefreshPending(
                it == true || pendingReplacementRefresh != null
            )
            if (it != true &&
                !startPendingReplacementRefresh() &&
                pendingReplacementRefresh == null
            ) {
                syncOpenCodeDialog()
                showPendingReplacementDialog()
            }
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(
            source,
            reimportBookUrl = arguments?.getString("reimportBookUrl"),
            reimportSourceUrl = arguments?.getString("reimportSourceUrl")
        )
    }

    @Composable
    override fun DialogContent() {
        val currentVersion = listVersion
        var showMenu by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LegadoTopAppBar(
                    title = stringResource(R.string.import_book_source),
                    actions = {
                        TextButton(onClick = { showCustomGroupDialog = true }) {
                            Text(
                                text = groupMenuTitle,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_new_source)) },
                                    enabled = importEnabledState,
                                    onClick = {
                                        showMenu = false
                                        val indices = filteredIndices.filter { viewModel.newSourceStatus[it] }
                                        val selectAll = indices.all { !viewModel.canImportSource(it) || viewModel.selectStatus[it] }
                                        indices.forEach { viewModel.setSelection(it, !selectAll) }
                                        listVersion++
                                        upSelectText()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_update_source)) },
                                    enabled = importEnabledState,
                                    onClick = {
                                        showMenu = false
                                        val indices = filteredIndices.filter { viewModel.updateSourceStatus[it] }
                                        val selectAll = indices.all { !viewModel.canImportSource(it) || viewModel.selectStatus[it] }
                                        indices.forEach { viewModel.setSelection(it, !selectAll) }
                                        listVersion++
                                        upSelectText()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_original_name)) },
                                    trailingIcon = { Checkbox(checked = keepOriginalName, onCheckedChange = null) },
                                    onClick = {
                                        keepOriginalName = !keepOriginalName
                                        putPrefBoolean(PreferKey.importKeepName, keepOriginalName)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_group)) },
                                    trailingIcon = { Checkbox(checked = keepGroup, onCheckedChange = null) },
                                    onClick = {
                                        keepGroup = !keepGroup
                                        putPrefBoolean(PreferKey.importKeepGroup, keepGroup)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_enable)) },
                                    trailingIcon = { Checkbox(checked = keepEnable, onCheckedChange = null) },
                                    onClick = {
                                        keepEnable = !keepEnable
                                        AppConfig.importKeepEnable = keepEnable
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_source_comment)) },
                                    trailingIcon = { Checkbox(checked = showComment, onCheckedChange = null) },
                                    onClick = {
                                        showComment = !showComment
                                        AppConfig.importShowComment = showComment
                                        listVersion++
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_remember_group)) },
                                    trailingIcon = { Checkbox(checked = rememberGroup, onCheckedChange = null) },
                                    onClick = {
                                        rememberGroup = !rememberGroup
                                        AppConfig.importRememberGroup = rememberGroup
                                        if (rememberGroup) {
                                            AppConfig.importLastGroup = viewModel.groupName
                                            AppConfig.importLastGroupAdd = viewModel.isAddGroup
                                        } else {
                                            viewModel.groupName = null
                                            viewModel.isAddGroup = false
                                        }
                                        updateGroupMenu()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.replace_source_on_import)) },
                                    enabled = importEnabledState,
                                    trailingIcon = { Checkbox(checked = autoReplaceSource, onCheckedChange = null) },
                                    onClick = {
                                        autoReplaceSource = !autoReplaceSource
                                        viewModel.setUseSourceReplacement(autoReplaceSource)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.effective_replaces)) },
                                    enabled = importEnabledState,
                                    onClick = {
                                        showMenu = false
                                        showSourceReplacements(false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manual_replace_rule)) },
                                    enabled = importEnabledState && !autoReplaceSource,
                                    onClick = {
                                        showMenu = false
                                        showSourceReplacements(true)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_replace_rule)) },
                                    enabled = importEnabledState,
                                    onClick = {
                                        showMenu = false
                                        onOpenReplaceRules()
                                    }
                                )
                            }
                        }
                    }
                )

                OutlinedTextField(
                    value = searchQueryState,
                    onValueChange = { newText ->
                        searchQueryState = newText
                        viewModel.searchQuery = newText
                        if (sourceListReady) refreshSources()
                    },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.search)
                        )
                    },
                    trailingIcon = {
                        if (searchQueryState.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    searchQueryState = ""
                                    viewModel.searchQuery = ""
                                    if (sourceListReady) refreshSources()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val msg = messageText
                    if (sourceListReady && filteredIndices.isNotEmpty() && currentVersion >= 0) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filteredIndices, key = { it }) { position ->
                                val source = viewModel.allSources.getOrNull(position) ?: return@items
                                val canImport = viewModel.canImportSource(position)
                                val interactionEnabled = viewModel.sourceUpdatePending.value != true
                                val replacementError = viewModel.sourceReplacementError(position)
                                    ?.takeIf { viewModel.useSourceReplacement }
                                val comment = replacementError?.let {
                                    getString(R.string.source_replacement_error, it)
                                } ?: source.bookSourceComment?.takeIf {
                                    showComment && it.isNotBlank()
                                }
                                val statusRes = when {
                                    replacementError != null -> R.string.import_status_error
                                    viewModel.newSourceStatus[position] -> R.string.import_status_new
                                    viewModel.updateSourceStatus[position] -> R.string.import_status_update
                                    else -> R.string.import_status_exist
                                }
                                ImportSourceItemRow(
                                    name = source.bookSourceName,
                                    checked = viewModel.selectStatus[position],
                                    statusText = stringResource(statusRes),
                                    enabled = canImport && interactionEnabled,
                                    comment = comment,
                                    showOpen = true,
                                    onCheckedChange = { isChecked ->
                                        if (viewModel.sourceUpdatePending.value != true) {
                                            viewModel.setSelection(position, isChecked)
                                            listVersion++
                                            upSelectText()
                                        }
                                    },
                                    onOpenClick = {
                                        if (viewModel.sourceUpdatePending.value != true) {
                                            showDialogFragment(
                                                CodeDialog(
                                                    viewModel.originalSourceJson(position) ?: GSON.toJson(source),
                                                    disableEdit = false,
                                                    requestId = position.toString(),
                                                    alternateCode = viewModel.replacedSourceJson(position),
                                                    showAlternate = viewModel.useSourceReplacement,
                                                    showReplaceRules = true,
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    } else if (msg != null) {
                        ImportEmptyOrErrorState(
                            message = msg,
                            showIcon = showEmptyIcon
                        )
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }

                HorizontalDivider()
                ImportDialogFooter(
                    selectAllText = selectFooterText,
                    selectAllEnabled = importEnabledState && filteredIndices.isNotEmpty(),
                    cancelEnabled = cancelEnabledState,
                    okEnabled = importEnabledState,
                    onSelectAllClick = {
                        val indices = filteredIndices
                        val selectAll = indices.all { !viewModel.canImportSource(it) || viewModel.selectStatus[it] }
                        indices.forEach { viewModel.setSelection(it, !selectAll) }
                        listVersion++
                        upSelectText()
                    },
                    onCancelClick = { dismissAllowingStateLoss() },
                    onOkClick = {
                        if (viewModel.sourceUpdatePending.value == true) return@ImportDialogFooter
                        if (arguments?.getString("reimportBookUrl") != null) {
                            viewModel.importSelect()
                        } else {
                            val waitDialog = WaitDialog(requireContext())
                            waitDialog.show()
                            viewModel.importSelect {
                                waitDialog.dismiss()
                                dismissAllowingStateLoss()
                            }
                        }
                    }
                )
            }
        }

        if (showCustomGroupDialog) {
            val groups = remember { appDb.bookSourceDao.allGroups().toList() }
            ImportCustomGroupDialog(
                initialGroupName = viewModel.groupName.orEmpty(),
                initialIsAddGroup = viewModel.isAddGroup,
                groups = groups,
                onDismiss = { showCustomGroupDialog = false },
                onConfirm = { groupName, isAddGroup ->
                    showCustomGroupDialog = false
                    viewModel.isAddGroup = isAddGroup
                    viewModel.groupName = groupName
                    if (AppConfig.importRememberGroup) {
                        AppConfig.importLastGroup = viewModel.groupName
                        AppConfig.importLastGroupAdd = viewModel.isAddGroup
                    }
                    updateGroupMenu()
                }
            )
        }
    }

    private fun refreshSources() {
        filteredIndices = viewModel.allSources.indices.filter { index ->
            val source = viewModel.allSources[index]
            when (viewModel.searchQuery) {
                getString(R.string.enabled) -> source.enabled
                getString(R.string.disabled) -> !source.enabled
                getString(R.string.need_login) -> !source.loginUrl.isNullOrBlank() ||
                    (source.isJsSource() && source.hasLoginForm())
                getString(R.string.no_group) -> source.bookSourceGroup.isNullOrBlank() ||
                    source.bookSourceGroup?.trim() == "未分组"
                getString(R.string.enabled_explore) -> source.enabledExplore
                getString(R.string.disabled_explore) -> !source.enabledExplore
                else -> matchesSourceImportSearch(
                    viewModel.searchQuery,
                    source.bookSourceName,
                    source.bookSourceUrl,
                    source.bookSourceGroup,
                    source.bookSourceComment
                )
            }
        }
        messageText = if (filteredIndices.isEmpty()) {
            getString(R.string.import_no_results)
        } else {
            null
        }
        listVersion++
        upSelectText()
        updateInteractionState()
    }

    private fun upSelectText() {
        if (viewModel.searchQuery.isNotEmpty()) {
            val indices = filteredIndices
            val selected = indices.count { viewModel.selectStatus[it] }
            val all = indices.all { !viewModel.canImportSource(it) || viewModel.selectStatus[it] }
            selectFooterText = getString(
                if (all) R.string.import_unselect_results else R.string.import_select_results,
                selected,
                indices.size,
                viewModel.selectCount,
            )
            return
        }
        selectFooterText = if (viewModel.isSelectAll) {
            getString(
                R.string.select_cancel_count,
                viewModel.selectCount,
                viewModel.allSources.size
            )
        } else {
            getString(
                R.string.select_all_count,
                viewModel.selectCount,
                viewModel.allSources.size
            )
        }
    }

    private fun updateGroupMenu() {
        val name = viewModel.groupName
        groupMenuTitle = if (name.isNullOrBlank()) {
            getString(R.string.diy_source_group)
        } else {
            val title = getString(R.string.diy_edit_source_group_title, name)
            if (viewModel.isAddGroup) "+$title" else title
        }
    }

    private fun updateInteractionState() {
        val sourceUpdatePending = viewModel.sourceUpdatePending.value == true
        val importEnabled = sourceListReady && !sourceUpdatePending
        importEnabledState = importEnabled
        cancelEnabledState = !sourceUpdatePending
        isCancelable = !sourceUpdatePending
        autoReplaceSource = viewModel.automaticSourceReplacement
    }

    override fun onCodeSave(code: String, requestId: String?) {
        if (viewModel.sourceUpdatePending.value == true) return
        val index = requestId?.toIntOrNull() ?: return
        if (index !in viewModel.allSources.indices) return
        val source = GSON.fromJsonObject<BookSource>(code).getOrNull() ?: return
        viewModel.updateSource(index, source)
    }

    override fun onResume() {
        super.onResume()
        showPendingReplacementDialog()
    }

    private fun showPendingReplacementDialog() {
        if (!isResumed || childFragmentManager.isStateSaved || viewModel.sourceUpdatePending.value == true) return
        val (manual, index) = viewModel.pendingReplacementDialog ?: return
        viewModel.pendingReplacementDialog = null
        showSourceReplacements(manual, index)
    }

    private fun showSourceReplacements(manual: Boolean, index: Int = -1) {
        if (!isAdded || childFragmentManager.isStateSaved || viewModel.sourceUpdatePending.value == true) return
        if (manual) {
            if (viewModel.automaticSourceReplacement) return
            showDialogFragment(ManualReplaceRulesDialog(viewModel.selectedManualRuleIds(index), index.toString()))
        } else {
            showDialogFragment(EffectiveReplacesDialog(viewModel.effectiveRuleIds(index)))
        }
    }

    override fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) {
        val index = requestId?.toIntOrNull() ?: return
        val source = parseDraftSource(code) ?: run {
            toastOnUi(R.string.wrong_format)
            return
        }
        viewModel.refreshSourceReplacements(index, source, openDialog = manual)
    }

    override fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?) {
        val index = requestId?.toIntOrNull() ?: -1
        val dialog = openCodeDialog()?.takeIf { it.requestId?.toIntOrNull() == index }
        val source = dialog?.let { parseDraftSource(it.currentOriginalCode()) }
        if (dialog != null && source == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        viewModel.refreshSourceReplacements(index, source, ids)
    }

    override fun onEffectiveSourceRulesChanged() {
        val dialog = openCodeDialog()
        if (dialog == null) viewModel.refreshSourceReplacements()
        else {
            pendingReplacementRefresh = dialog.currentOriginalCode() to dialog.requestId
            startPendingReplacementRefresh()
        }
    }

    override fun onOpenReplaceRules() {
        replaceRuleActivity.launch(Intent(requireContext(), ReplaceRuleActivity::class.java))
    }

    private fun startPendingReplacementRefresh(): Boolean {
        if (!sourceListReady || viewModel.sourceUpdatePending.value == true) return false
        val (code, requestId) = pendingReplacementRefresh ?: return false
        val index = requestId?.toIntOrNull()
        if (index == null || index !in viewModel.allSources.indices) {
            pendingReplacementRefresh = null
            return false
        }
        val source = parseDraftSource(code)
        val started = viewModel.refreshSourceReplacements(index, source)
        if (started) pendingReplacementRefresh = null
        return started
    }

    private fun openCodeDialog(): CodeDialog? =
        childFragmentManager.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog

    private fun syncOpenCodeDialog() {
        openCodeDialog()?.let { dialog ->
            dialog.setReplaceRuleRefreshPending(false)
            if (parseDraftSource(dialog.currentOriginalCode()) != null) {
                dialog.refreshAlternateCode()
            } else {
                dialog.clearAlternateCode()
            }
        }
    }

    private fun parseDraftSource(code: String): BookSource? = runCatching {
        (parseBookSourceJson(code, allowSourceUrls = false) as BookSourceImportJson.Sources)
            .items.single()
    }.getOrNull()

    override fun isReplaceRuleRefreshPending(): Boolean =
        pendingReplacementRefresh != null || viewModel.sourceUpdatePending.value == true

    override fun isManualSourceReplacementEnabled(): Boolean = !viewModel.automaticSourceReplacement

    override fun getCodeAlternate(requestId: String?): String? {
        val index = requestId?.toIntOrNull() ?: return null
        return viewModel.replacedSourceJson(index)
    }

}
