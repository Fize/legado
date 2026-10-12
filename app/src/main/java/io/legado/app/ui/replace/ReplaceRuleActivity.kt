package io.legado.app.ui.replace

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 替换规则管理
 */
class ReplaceRuleActivity : VMBaseComposeActivity<ReplaceRuleViewModel>() {

    override val viewModel by viewModels<ReplaceRuleViewModel>()
    private val importRecordKey = "replaceRuleRecordKey"

    private var rulesState by mutableStateOf<List<ReplaceRule>>(emptyList())
    private var groupsState by mutableStateOf<List<String>>(emptyList())
    private var selectedIdsState by mutableStateOf<Set<Long>>(emptySet())
    private var searchQueryState by mutableStateOf("")
    private var manualReplaceRuleState by mutableStateOf(AppConfig.manualReplaceRule)

    private var replaceRuleFlowJob: Job? = null
    private var dataInit = false

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportReplaceRuleDialog(it))
    }
    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
        }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportReplaceRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                    sourceSharePassphraseButton(
                        layoutInflater,
                        url,
                        SourceSharePassphrase.Type.REPLACE_RULE,
                    )
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(url)
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(url)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        observeReplaceRuleData()
        observeGroupData()
    }

    @Composable
    override fun Content() {
        var groupMenuExpanded by remember { mutableStateOf(false) }
        var overflowExpanded by remember { mutableStateOf(false) }

        val selectedRules = remember(rulesState, selectedIdsState) {
            rulesState.filter { it.id in selectedIdsState }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.replace_purify),
                    onBack = { finish() },
                    searchQuery = searchQueryState,
                    onSearchQueryChange = { newText ->
                        updateSearchQuery(newText)
                    },
                    searchPlaceholder = stringResource(R.string.replace_purify_search),
                    actions = {
                        Box {
                            IconButton(onClick = { groupMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_groups),
                                    contentDescription = stringResource(R.string.menu_action_group),
                                )
                            }
                            DropdownMenu(
                                expanded = groupMenuExpanded,
                                onDismissRequest = { groupMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_manage)) },
                                    onClick = {
                                        groupMenuExpanded = false
                                        showDialogFragment<GroupManageDialog>()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.enabled)) },
                                    onClick = {
                                        groupMenuExpanded = false
                                        updateSearchQuery(getString(R.string.enabled))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disabled)) },
                                    onClick = {
                                        groupMenuExpanded = false
                                        updateSearchQuery(getString(R.string.disabled))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.no_group)) },
                                    onClick = {
                                        groupMenuExpanded = false
                                        updateSearchQuery(getString(R.string.no_group))
                                    },
                                )
                                if (groupsState.isNotEmpty()) {
                                    HorizontalDivider()
                                    groupsState.forEach { group ->
                                        DropdownMenuItem(
                                            text = { Text(group) },
                                            onClick = {
                                                groupMenuExpanded = false
                                                updateSearchQuery("group:$group")
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more),
                                    contentDescription = stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                            ) {
                                // menu_add_replace_rule
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_replace_rule)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_add),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        editActivity.launch(ReplaceEditActivity.startIntent(this@ReplaceRuleActivity))
                                    },
                                )
                                // menu_import_local
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_local)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_import),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        importDoc.launch {
                                            mode = HandleFileContract.FILE
                                            allowExtensions = arrayOf("txt", "json")
                                        }
                                    },
                                )
                                // menu_import_onLine
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_on_line)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_import),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        showImportDialog()
                                    },
                                )
                                // menu_import_qr
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_by_qr_code)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_import),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        qrCodeResult.launch()
                                    },
                                )
                                // menu_manual_replace_rule
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manual_replace_rule)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_find_replace),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = manualReplaceRuleState,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        val next = !manualReplaceRuleState
                                        manualReplaceRuleState = next
                                        AppConfig.manualReplaceRule = next
                                        setResult(RESULT_OK)
                                    },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                LegadoSelectActionBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    selectedCount = selectedRules.size,
                    totalCount = rulesState.size,
                    onSelectAllChange = { selectAll ->
                        selectedIdsState = if (selectAll) {
                            rulesState.mapTo(linkedSetOf()) { it.id }
                        } else {
                            emptySet()
                        }
                    },
                    onRevertSelection = {
                        selectedIdsState = rulesState.mapNotNullTo(linkedSetOf()) {
                            if (it.id in selectedIdsState) null else it.id
                        }
                    },
                    mainActionText = stringResource(R.string.delete),
                    onMainActionClick = {
                        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
                            yesButton { viewModel.delSelection(selectedRules) }
                            noButton()
                        }
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.enable_selection),
                            onClick = { viewModel.enableSelection(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.disable_selection),
                            onClick = { viewModel.disableSelection(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.add_group),
                            onClick = { selectionAddToGroups(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.remove_group),
                            onClick = { selectionRemoveFromGroups(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.selection_to_top),
                            onClick = { viewModel.topSelect(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.selection_to_bottom),
                            onClick = { viewModel.bottomSelect(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.export_selection),
                            onClick = {
                                exportResult.launch {
                                    mode = HandleFileContract.EXPORT
                                    fileData = HandleFileContract.FileData(
                                        "exportReplaceRule.json",
                                        GSON.toJson(ReplacePreviewConfig.withSamples(selectedRules)).toByteArray(),
                                        "application/json",
                                    )
                                }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.share_selected_source),
                            onClick = { shareSelection(selectedRules) },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = rulesState,
                    key = { it.id },
                ) { item ->
                    val isSelected = item.id in selectedIdsState
                    var rowMenuExpanded by remember { mutableStateOf(false) }

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIdsState = if (isSelected) {
                                        selectedIdsState - item.id
                                    } else {
                                        selectedIdsState + item.id
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedIdsState = if (checked) {
                                        selectedIdsState + item.id
                                    } else {
                                        selectedIdsState - item.id
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.getDisplayNameGroup(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = item.isEnabled,
                                onCheckedChange = { checked ->
                                    enable(checked, item)
                                },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { edit(item) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.edit),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { rowMenuExpanded = true },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more_vert),
                                        contentDescription = stringResource(R.string.more_menu),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = rowMenuExpanded,
                                    onDismissRequest = { rowMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.to_top)) },
                                        onClick = {
                                            rowMenuExpanded = false
                                            toTop(item)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.to_bottom)) },
                                        onClick = {
                                            rowMenuExpanded = false
                                            toBottom(item)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            rowMenuExpanded = false
                                            delete(item)
                                            selectedIdsState = selectedIdsState - item.id
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    private fun updateSearchQuery(query: String) {
        searchQueryState = query
        observeReplaceRuleData(query)
    }

    private fun observeReplaceRuleData(searchKey: String? = null) {
        dataInit = false
        replaceRuleFlowJob?.cancel()
        replaceRuleFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.replaceRuleDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.replaceRuleDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.replaceRuleDao.flowDisabled()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.replaceRuleDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val groupName = searchKey.substringAfter("group:")
                    appDb.replaceRuleDao.flowGroupSearch(groupName)
                }

                else -> {
                    appDb.replaceRuleDao.flowSearch("%$searchKey%")
                }
            }.catch {
                AppLog.put("替换规则管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect { list ->
                if (dataInit) {
                    setResult(RESULT_OK)
                }
                rulesState = list
                val validIds = list.mapTo(hashSetOf()) { it.id }
                selectedIdsState = selectedIdsState.intersect(validIds)
                dataInit = true
                delay(100)
            }
        }
    }

    private fun observeGroupData() {
        lifecycleScope.launch {
            appDb.replaceRuleDao.flowGroups().collect {
                groupsState = it
            }
        }
    }

    private fun shareSelection(rules: List<ReplaceRule>) {
        if (rules.isEmpty()) return
        viewModel.execute(scope = lifecycleScope) {
            File.createTempFile("replaceRule_", ".json", cacheDir).apply {
                writeText(GSON.toJson(ReplacePreviewConfig.withSamples(rules)))
            }
        }.onSuccess {
            share(it)
        }.onError {
            toastOnUi(it.stackTraceStr)
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionAddToGroups(selectedRules: List<ReplaceRule>) {
        alert(titleResource = R.string.add_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groupsState)
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionAddToGroups(selectedRules, it)
                    }
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionRemoveFromGroups(selectedRules: List<ReplaceRule>) {
        alert(titleResource = R.string.remove_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groupsState)
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionRemoveFromGroups(selectedRules, it)
                    }
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(
                        ImportReplaceRuleDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async { ContentProcessor.upReplaceRules() }
    }

    private fun enable(enable: Boolean, rule: ReplaceRule) {
        setResult(RESULT_OK)
        viewModel.enable(rule.id, enable)
    }

    private fun delete(rule: ReplaceRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton {
                setResult(RESULT_OK)
                viewModel.delete(rule)
            }
        }
    }

    private fun edit(rule: ReplaceRule) {
        setResult(RESULT_OK)
        editActivity.launch(ReplaceEditActivity.startIntent(this, rule.id))
    }

    private fun toTop(rule: ReplaceRule) {
        setResult(RESULT_OK)
        viewModel.toTop(rule)
    }

    private fun toBottom(rule: ReplaceRule) {
        setResult(RESULT_OK)
        viewModel.toBottom(rule)
    }

}
