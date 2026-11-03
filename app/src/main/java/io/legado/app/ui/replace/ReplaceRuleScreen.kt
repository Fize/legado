package io.legado.app.ui.replace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import java.io.File

@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    onEdit: (Long?) -> Unit,
    onShareFile: (File) -> Unit,
    onExit: () -> Unit = {},
    viewModel: ReplaceRuleScreenViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var groupMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var deleteTargets by remember { mutableStateOf<List<ReplaceRule>>(emptyList()) }
    var groupInput by remember { mutableStateOf<GroupInput?>(null) }
    var groupEditor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var importSource by remember { mutableStateOf<String?>(null) }
    var exportUrl by remember { mutableStateOf<String?>(null) }
    val qrLauncher = rememberLauncherForActivityResult(QrCodeResult()) { it?.let { source -> importSource = source } }
    val importLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.toString()?.let { source -> importSource = source }
    }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.toString()?.let { url -> exportUrl = url }
    }

    LaunchedEffect(state.pendingShareFile) {
        state.pendingShareFile?.let { onShareFile(it); viewModel.consumeShareFile() }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.consumeError() }
    }
    DisposableEffect(Unit) { onDispose(onExit) }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.replace_purify),
                onBack = onBack,
                searchQuery = state.query,
                onSearchQueryChange = viewModel::setQuery,
                searchPlaceholder = stringResource(R.string.replace_purify_search),
                actions = {
                    IconButton({ groupMenu = true }) {
                        Icon(painterResource(R.drawable.ic_groups), stringResource(R.string.menu_action_group))
                    }
                    DropdownMenu(groupMenu, { groupMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.group_manage)) }, onClick = {
                            groupMenu = false
                            groupInput = GroupInput.Manage
                        })
                        listOf(
                            stringResource(R.string.enabled),
                            stringResource(R.string.disabled),
                            stringResource(R.string.no_group),
                        ).forEach { filter ->
                            DropdownMenuItem({ Text(filter) }, onClick = { groupMenu = false; viewModel.setQuery(filter) })
                        }
                        if (state.groups.isNotEmpty()) HorizontalDivider()
                        state.groups.forEach { group ->
                            DropdownMenuItem({ Text(group) }, onClick = {
                                groupMenu = false; viewModel.setQuery("group:$group")
                            })
                        }
                    }
                    IconButton(
                        onClick = { overflowMenu = true },
                        modifier = Modifier.testTag("replaceRuleOverflow"),
                    ) {
                        Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more_menu))
                    }
                    DropdownMenu(overflowMenu, { overflowMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.add_replace_rule)) }, onClick = {
                            overflowMenu = false; onEdit(null)
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.import_local)) }, onClick = {
                            overflowMenu = false
                            importLauncher.launch { mode = HandleFileContract.FILE; allowExtensions = arrayOf("txt", "json") }
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.import_on_line)) }, onClick = {
                            overflowMenu = false; groupInput = GroupInput.Import
                        })
                        DropdownMenuItem({ Text(stringResource(R.string.import_by_qr_code)) }, onClick = {
                            overflowMenu = false; qrLauncher.launch(null)
                        })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manual_replace_rule)) },
                            trailingIcon = { Checkbox(state.manualReplaceEnabled, null) },
                            modifier = Modifier.testTag("replaceRuleManualMode"),
                            onClick = { viewModel.setManualReplace(!state.manualReplaceEnabled) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            LegadoSelectActionBar(
                selectedCount = state.selectedRules.size,
                totalCount = state.rules.size,
                onSelectAllChange = viewModel::selectAll,
                onRevertSelection = viewModel::invertSelection,
                mainActionText = stringResource(R.string.delete),
                onMainActionClick = { deleteTargets = state.selectedRules },
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.enable_selection)) { viewModel.setEnabled(state.selectedRules, true) },
                    LegadoMenuAction(stringResource(R.string.disable_selection)) { viewModel.setEnabled(state.selectedRules, false) },
                    LegadoMenuAction(stringResource(R.string.add_group)) { groupInput = GroupInput.AddToSelection },
                    LegadoMenuAction(stringResource(R.string.remove_group)) { groupInput = GroupInput.RemoveFromSelection },
                    LegadoMenuAction(stringResource(R.string.selection_to_top)) { viewModel.moveSelection(state.selectedRules, true) },
                    LegadoMenuAction(stringResource(R.string.selection_to_bottom)) { viewModel.moveSelection(state.selectedRules, false) },
                    LegadoMenuAction(stringResource(R.string.export_selection)) {
                        exportLauncher.launch {
                            mode = HandleFileContract.EXPORT
                            fileData = HandleFileContract.FileData(
                                "exportReplaceRule.json", viewModel.exportJson(state.selectedRules), "application/json",
                            )
                        }
                    },
                    LegadoMenuAction(stringResource(R.string.share_selected_source)) { viewModel.shareSelected() },
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(state.rules, key = ReplaceRule::id) { rule ->
                ReplaceRuleRow(
                    rule = rule,
                    selected = rule.id in state.selectedIds,
                    onSelected = { viewModel.toggleSelection(rule.id, it) },
                    onEnabled = { viewModel.setEnabled(rule, it) },
                    onEdit = { onEdit(rule.id) },
                    onDelete = { deleteTargets = listOf(rule) },
                    onTop = { viewModel.moveToTop(rule) },
                    onBottom = { viewModel.moveToBottom(rule) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + deleteTargets.joinToString { it.name }) },
            confirmButton = { TextButton({ viewModel.delete(deleteTargets); deleteTargets = emptyList() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ deleteTargets = emptyList() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    groupInput?.let { input ->
        when (input) {
            GroupInput.Manage -> GroupManagementDialog(
                groups = state.groups,
                onAdd = { name -> viewModel.addGroup(name); groupInput = null },
                onEdit = { group -> groupEditor = group to group; groupInput = null },
                onDelete = { group -> viewModel.renameGroup(group, null) },
                onDismiss = { groupInput = null },
            )
            GroupInput.Import -> UrlInputDialog(
                onDismiss = { groupInput = null },
                onImport = { groupInput = null; importSource = it },
            )
            GroupInput.AddToSelection, GroupInput.RemoveFromSelection -> GroupInputDialog(
                title = stringResource(if (input == GroupInput.AddToSelection) R.string.add_group else R.string.remove_group),
                groups = state.groups,
                onDismiss = { groupInput = null },
                onConfirm = { name ->
                    if (name.isNotBlank()) viewModel.setGroups(state.selectedRules, name, input == GroupInput.AddToSelection)
                    groupInput = null
                },
            )
        }
    }
    importSource?.let { source ->
        ReplaceRuleImportDialog(source = source, onDismiss = { importSource = null })
    }
    groupEditor?.let { (old, initial) ->
        GroupInputDialog(
            title = stringResource(R.string.group_edit), initialValue = initial,
            onDismiss = { groupEditor = null },
            onConfirm = { viewModel.renameGroup(old, it); groupEditor = null },
        )
    }
    exportUrl?.let { url ->
        val passphrase = remember(url) {
            if (SourceSharePassphrase.canEncode(url)) SourceSharePassphrase.encode(
                url, SourceSharePassphrase.Type.REPLACE_RULE, DirectLinkUpload.getExpiryDate(),
            ) else null
        }
        AlertDialog(
            onDismissRequest = { exportUrl = null }, title = { Text(stringResource(R.string.export_success)) },
            text = { Text(if (url.isAbsUrl()) DirectLinkUpload.getSummary() + "\n\n" + url else url) },
            confirmButton = { TextButton({ context.sendToClip(url); exportUrl = null }) { Text(stringResource(R.string.copy_rule)) } },
            dismissButton = { passphrase?.let { TextButton({ context.sendToClip(it); exportUrl = null }) { Text(stringResource(R.string.shibboleth)) } } },
        )
    }
}

@Composable
private fun ReplaceRuleRow(
    rule: ReplaceRule,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { onSelected(!selected) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onSelected)
        Spacer(Modifier.width(8.dp))
        Text(rule.getDisplayNameGroup(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Switch(rule.isEnabled, onEnabled)
        IconButton(onEdit, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit)) }
        IconButton({ menu = true }, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.to_top)) }, onClick = { menu = false; onTop() })
            DropdownMenuItem({ Text(stringResource(R.string.to_bottom)) }, onClick = { menu = false; onBottom() })
            DropdownMenuItem({ Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
        }
    }
}

private enum class GroupInput { Manage, Import, AddToSelection, RemoveFromSelection }

@Composable
private fun GroupManagementDialog(
    groups: List<String>, onAdd: (String) -> Unit, onEdit: (String) -> Unit,
    onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_manage)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                groups.forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group, Modifier.weight(1f))
                        TextButton({ onEdit(group) }) { Text(stringResource(R.string.edit)) }
                        TextButton({ onDelete(group) }) { Text(stringResource(R.string.delete)) }
                    }
                }
                if (editing) androidx.compose.material3.OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.group_name)) })
            }
        },
        confirmButton = {
            Row {
                TextButton({ editing = true }) { Text(stringResource(R.string.add_group)) }
                if (editing) TextButton({ onAdd(value) }) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GroupInputDialog(
    title: String, groups: List<String> = emptyList(), initialValue: String = "",
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.group_name)) })
                groups.forEach { group -> TextButton({ value = group }) { Text(group) } }
            }
        },
        confirmButton = { TextButton({ onConfirm(value) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun UrlInputDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.import_on_line)) },
        text = { androidx.compose.material3.OutlinedTextField(url, { url = it }, label = { Text("URL") }) },
        confirmButton = { TextButton({ onImport(url) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
