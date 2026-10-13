package io.legado.app.ui.highlight

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHighlightRuleDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

class HighlightRuleActivity : VMBaseComposeActivity<HighlightRuleViewModel>() {

    override val viewModel by viewModels<HighlightRuleViewModel>()

    private var allRulesState by mutableStateOf<List<HighlightRule>>(emptyList())
    private var activeGroupState by mutableStateOf<String?>(null)
    private var selectedUuidsState by mutableStateOf<Set<String>>(emptySet())

    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> showDialogFragment(ImportHighlightRuleDialog(uri.toString())) }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary() + "\n\n" + url)
                }
                if (!url.isAbsUrl()) setMessage(url)
                okButton { sendToClip(url) }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        observeData()
    }

    private fun observeData() {
        lifecycleScope.launch {
            appDb.highlightRuleDao.flowAll()
                .catch { AppLog.put("高亮规则界面更新数据出错", it) }
                .flowOn(IO)
                .conflate()
                .collect { rules ->
                    allRulesState = rules
                    if (activeGroupState != null && activeGroupState != UNGROUPED &&
                        rules.none { it.group == activeGroupState }
                    ) {
                        activeGroupState = null
                    }
                    val validUuids = rules.mapTo(hashSetOf()) { it.uuid }
                    selectedUuidsState = selectedUuidsState.intersect(validUuids)
                }
        }
    }

    @Composable
    override fun Content() {
        val displayedRules = remember(allRulesState, activeGroupState) {
            when (val group = activeGroupState) {
                null -> allRulesState
                UNGROUPED -> allRulesState.filter { it.group.isNullOrBlank() }
                else -> allRulesState.filter { it.group == group }
            }
        }
        val selectedRules = remember(displayedRules, selectedUuidsState) {
            displayedRules.filter { it.uuid in selectedUuidsState }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.highlight_rule),
                    onBack = { finish() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.highlight_rule_add),
                            iconRes = R.drawable.ic_add,
                            showAsAction = true,
                            onClick = {
                                showDialogFragment(HighlightRuleEditDialog.create(pattern = ""))
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.import_local),
                            onClick = {
                                importDoc.launch {
                                    mode = HandleFileContract.FILE
                                    allowExtensions = arrayOf("json")
                                }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.export_all),
                            onClick = { exportRules(allRulesState) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.highlight_rule_group_manage),
                            onClick = { showDialogFragment<HighlightGroupManageDialog>() },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.highlight_rule_group_filter),
                            onClick = { showGroupFilter() },
                        ),
                    ),
                )
            },
            bottomBar = {
                LegadoSelectActionBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    selectedCount = selectedRules.size,
                    totalCount = displayedRules.size,
                    onSelectAllChange = { selectAll ->
                        selectedUuidsState = if (selectAll) {
                            displayedRules.mapTo(linkedSetOf()) { it.uuid }
                        } else {
                            emptySet()
                        }
                    },
                    onRevertSelection = {
                        selectedUuidsState = displayedRules.mapNotNullTo(linkedSetOf()) {
                            if (it.uuid in selectedUuidsState) null else it.uuid
                        }
                    },
                    mainActionText = stringResource(R.string.delete),
                    onMainActionClick = {
                        alert(titleResource = R.string.highlight_rule, messageResource = R.string.sure_del) {
                            noButton()
                            yesButton {
                                viewModel.delete(*selectedRules.toTypedArray())
                                    .onSuccess { ReadBook.upHighlightRules() }
                            }
                        }
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.enable_selection),
                            onClick = {
                                viewModel.enableSelection(selectedRules, true)
                                    .onSuccess { ReadBook.upHighlightRules() }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.disable_selection),
                            onClick = {
                                viewModel.enableSelection(selectedRules, false)
                                    .onSuccess { ReadBook.upHighlightRules() }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.to_top),
                            onClick = {
                                viewModel.moveSelection(selectedRules, true)
                                    .onSuccess { ReadBook.upHighlightRules() }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.to_bottom),
                            onClick = {
                                viewModel.moveSelection(selectedRules, false)
                                    .onSuccess { ReadBook.upHighlightRules() }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.export_selection),
                            onClick = { exportRules(selectedRules) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.share_selected_source),
                            onClick = { shareRules(selectedRules) },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (displayedRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.highlight_rule_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = displayedRules,
                            key = { it.uuid },
                        ) { rule ->
                            val isSelected = rule.uuid in selectedUuidsState
                            var menuExpanded by remember { mutableStateOf(false) }

                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedUuidsState = if (isSelected) {
                                                selectedUuidsState - rule.uuid
                                            } else {
                                                selectedUuidsState + rule.uuid
                                            }
                                        }
                                        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedUuidsState = if (checked) {
                                                selectedUuidsState + rule.uuid
                                            } else {
                                                selectedUuidsState - rule.uuid
                                            }
                                        },
                                    )
                                    Text(
                                        text = rule.displayNameWithGroup(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 4.dp),
                                    )
                                    Switch(
                                        checked = rule.isEnabled,
                                        onCheckedChange = { checked ->
                                            rule.isEnabled = checked
                                            update(rule)
                                        },
                                    )
                                    IconButton(onClick = { edit(rule) }) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.edit),
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = stringResource(R.string.more_menu),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(R.string.to_top)) },
                                                onClick = {
                                                    menuExpanded = false
                                                    toTop(rule)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(R.string.to_bottom)) },
                                                onClick = {
                                                    menuExpanded = false
                                                    toBottom(rule)
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
                                                    menuExpanded = false
                                                    delete(rule)
                                                },
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun HighlightRule.displayNameWithGroup() =
        group?.takeIf { it.isNotBlank() }?.let { "[$it] ${getDisplayName()}" } ?: getDisplayName()

    private fun exportRules(rules: List<HighlightRule>) {
        if (rules.isEmpty()) {
            toastOnUi(R.string.highlight_rule_empty)
            return
        }
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "HighlightRules.json",
                GSON.toJson(
                    HighlightRuleFile(type = HighlightRuleFile.TYPE, rules = rules)
                ).toByteArray(),
                "application/json"
            )
        }
    }

    private fun shareRules(rules: List<HighlightRule>) {
        if (rules.isEmpty()) return
        val selected = rules.toList()
        viewModel.execute(scope = lifecycleScope) {
            File.createTempFile("highlightRules_", ".json", cacheDir).apply {
                writeText(GSON.toJson(HighlightRuleFile(type = HighlightRuleFile.TYPE, rules = selected)))
            }
        }.onSuccess {
            share(it)
        }.onError {
            toastOnUi(it.stackTraceStr)
        }
    }

    private fun showGroupFilter() {
        lifecycleScope.launch(IO) {
            val groups = appDb.highlightRuleDao.flowGroups().first()
            val choices = listOf(
                GroupChoice(getString(R.string.all), null),
                GroupChoice(getString(R.string.no_group), UNGROUPED)
            ) + groups.map { GroupChoice("[$it]", it) }
            launch(kotlinx.coroutines.Dispatchers.Main) {
                alert(titleResource = R.string.highlight_rule_group_filter) {
                    items(choices) { _, choice, _ ->
                        activeGroupState = choice.value
                    }
                }
            }
        }
    }

    fun update(vararg rule: HighlightRule) {
        viewModel.update(*rule).onSuccess { ReadBook.upHighlightRules() }
    }

    fun delete(rule: HighlightRule) {
        alert(R.string.highlight_rule) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.getDisplayName())
            noButton()
            yesButton {
                viewModel.delete(rule).onSuccess { ReadBook.upHighlightRules() }
            }
        }
    }

    fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog.edit(rule.id))
    }

    fun toTop(rule: HighlightRule) {
        viewModel.toTop(rule).onSuccess { ReadBook.upHighlightRules() }
    }

    fun toBottom(rule: HighlightRule) {
        viewModel.toBottom(rule).onSuccess { ReadBook.upHighlightRules() }
    }

    private data class GroupChoice(val title: String, val value: String?) {
        override fun toString() = title
    }

    private companion object {
        const val UNGROUPED = "\u0000"
    }
}
