package io.legado.app.ui.book.toc.rule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import java.io.File

@Composable
fun TextTocRuleScreen(
    onBack: () -> Unit,
    onShareFile: (File) -> Unit,
    viewModel: TextTocRuleViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var onlineImport by remember { mutableStateOf(false) }
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
        state.pendingShareFile?.let {
            onShareFile(it)
            viewModel.consumeShareFile()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.txt_toc_rule),
                onBack = onBack,
                searchQuery = state.query,
                onSearchQueryChange = viewModel::setQuery,
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.add), R.drawable.ic_add, showAsAction = true) {
                        viewModel.edit(null)
                    },
                    LegadoMenuAction(stringResource(R.string.import_local), R.drawable.ic_import) {
                        importLauncher.launch {
                            mode = HandleFileContract.FILE
                            allowExtensions = arrayOf("txt", "json")
                        }
                    },
                    LegadoMenuAction(stringResource(R.string.import_on_line), R.drawable.ic_import) {
                        onlineImport = true
                    },
                    LegadoMenuAction(stringResource(R.string.import_by_qr_code), R.drawable.ic_import) {
                        qrLauncher.launch(null)
                    },
                    LegadoMenuAction(stringResource(R.string.import_default_rule), R.drawable.ic_import) {
                        viewModel.importDefault()
                    },
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            LegadoSelectActionBar(
                selectedCount = state.selectedRules.size,
                totalCount = state.visibleRules.size,
                onSelectAllChange = viewModel::selectAll,
                onRevertSelection = viewModel::invertSelection,
                mainActionText = stringResource(R.string.delete),
                onMainActionClick = { viewModel.requestDelete(state.selectedRules) },
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.enable_selection)) {
                        viewModel.setEnabled(state.selectedRules, true)
                    },
                    LegadoMenuAction(stringResource(R.string.disable_selection)) {
                        viewModel.setEnabled(state.selectedRules, false)
                    },
                    LegadoMenuAction(stringResource(R.string.export_selection)) {
                        exportLauncher.launch {
                            mode = HandleFileContract.EXPORT
                            fileData = HandleFileContract.FileData(
                                "exportTxtTocRule.json",
                                GSON.toJson(state.selectedRules).toByteArray(),
                                "application/json",
                            )
                        }
                    },
                    LegadoMenuAction(stringResource(R.string.share_selected_source)) {
                        viewModel.shareSelected()
                    },
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(state.visibleRules, key = TxtTocRule::id) { rule ->
                TextTocRuleRow(
                    rule = rule,
                    selected = rule.id in state.selectedIds,
                    onSelected = { viewModel.toggleSelection(rule.id, it) },
                    onEnabled = { viewModel.setEnabled(listOf(rule), it) },
                    onEdit = { viewModel.edit(rule) },
                    onTop = { viewModel.moveToTop(rule) },
                    onBottom = { viewModel.moveToBottom(rule) },
                    onDelete = { viewModel.requestDelete(listOf(rule)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    state.editor?.let { rule ->
        TextTocRuleEditor(rule, viewModel::save, viewModel::dismissEditor)
    }
    if (state.deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.sure_del)) },
            confirmButton = { TextButton(viewModel::delete) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(viewModel::dismissDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (onlineImport) {
        OnlineImportDialog(
            onDismiss = { onlineImport = false },
            onImport = {
                onlineImport = false
                importSource = it
            },
        )
    }
    importSource?.let { source ->
        TextTocRuleImportDialog(source = source, onDismiss = { importSource = null })
    }
    exportUrl?.let { url ->
        val passphrase = remember(url) {
            if (SourceSharePassphrase.canEncode(url)) {
                SourceSharePassphrase.encode(
                    url,
                    SourceSharePassphrase.Type.TOC_RULE,
                    DirectLinkUpload.getExpiryDate(),
                )
            } else null
        }
        AlertDialog(
            onDismissRequest = { exportUrl = null },
            title = { Text(stringResource(R.string.export_success)) },
            text = { Text(if (url.isAbsUrl()) DirectLinkUpload.getSummary() + "\n\n" + url else url) },
            confirmButton = {
                TextButton({ context.sendToClip(url); exportUrl = null }) { Text(stringResource(R.string.copy_rule)) }
            },
            dismissButton = {
                passphrase?.let {
                    TextButton({ context.sendToClip(it); exportUrl = null }) {
                        Text(stringResource(R.string.shibboleth))
                    }
                }
            },
        )
    }
}

@Composable
private fun TextTocRuleRow(
    rule: TxtTocRule,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { onSelected(!selected) }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, onSelected)
            Spacer(Modifier.width(8.dp))
            Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Switch(rule.enable, onEnabled)
            IconButton(onEdit, Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit))
            }
            Box {
                IconButton({ menu = true }, Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.to_top)) }, onClick = { menu = false; onTop() })
                    DropdownMenuItem({ Text(stringResource(R.string.to_bottom)) }, onClick = { menu = false; onBottom() })
                    DropdownMenuItem(
                        { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        rule.example?.takeIf(String::isNotBlank)?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun TextTocRuleEditor(rule: TxtTocRule, onSave: (TxtTocRule) -> Unit, onDismiss: () -> Unit) {
    var name by remember(rule.id) { mutableStateOf(rule.name) }
    var regex by remember(rule.id) { mutableStateOf(rule.rule) }
    var replacement by remember(rule.id) { mutableStateOf(rule.replacement) }
    var example by remember(rule.id) { mutableStateOf(rule.example.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.txt_toc_rule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(regex, { regex = it }, label = { Text(stringResource(R.string.regex)) })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(replacement, { replacement = it }, label = { Text(stringResource(R.string.replace_to_js)) })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(example, { example = it }, label = { Text(stringResource(R.string.example)) })
            }
        },
        confirmButton = {
            TextButton({ onSave(rule.copy(name = name, rule = regex, replacement = replacement, example = example)) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OnlineImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var value by remember {
        mutableStateOf("https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_on_line)) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("URL") }) },
        confirmButton = { TextButton({ onImport(value) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
