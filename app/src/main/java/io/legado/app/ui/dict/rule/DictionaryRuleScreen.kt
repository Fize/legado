package io.legado.app.ui.dict.rule

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.DictRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import java.io.File

@Composable
fun DictionaryRuleScreen(
    onBack: () -> Unit,
    onShareFile: (File) -> Unit,
    viewModel: DictionaryRuleViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var onlineImport by remember { mutableStateOf(false) }
    var importSource by remember { mutableStateOf<String?>(null) }
    var exportUrl by remember { mutableStateOf<String?>(null) }
    var editedField by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val qrLauncher = rememberLauncherForActivityResult(QrCodeResult()) { it?.let { source -> importSource = source } }
    val importLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.toString()?.let { source -> importSource = source }
    }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) {
        it.uri?.toString()?.let { url -> exportUrl = url }
    }
    val codeEditorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val field = editedField
        val edited = result.data?.getStringExtra("text")
        val current = viewModel.uiState.value.editor
        if (result.resultCode == Activity.RESULT_OK && field != null && edited != null && current != null) {
            viewModel.replaceEditor(when (field) {
                "name" -> current.copy(name = edited)
                "urlRule" -> current.copy(urlRule = edited)
                else -> current.copy(showRule = edited)
            })
        }
        editedField = null
    }

    LaunchedEffect(state.pendingShareFile) {
        state.pendingShareFile?.let { onShareFile(it); viewModel.consumeShareFile() }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.consumeError() }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.dict_rule),
                onBack = onBack,
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.create), R.drawable.ic_add, showAsAction = true) {
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
                totalCount = state.rules.size,
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
                                "exportDictRule.json",
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
            items(state.rules, key = DictRule::name) { rule ->
                DictionaryRuleRow(
                    rule = rule,
                    selected = rule.name in state.selectedNames,
                    onSelected = { viewModel.toggleSelection(rule.name, it) },
                    onEnabled = { viewModel.setEnabled(listOf(rule), it) },
                    onEdit = { viewModel.edit(rule) },
                    onDelete = { viewModel.requestDelete(listOf(rule)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    state.editor?.let { rule ->
        DictionaryRuleEditor(
            rule = rule,
            onSave = viewModel::save,
            onDismissRequested = { candidate ->
                if (candidate != state.originalRule) confirmDiscard = true else viewModel.dismissEditor()
            },
            onCopy = { copyRule(context, it) },
            onPaste = { pasteRule(context)?.let(viewModel::replaceEditor) },
            onFullEdit = { field, text ->
                editedField = field
                val title = when (field) {
                    "name" -> resources.getString(R.string.name)
                    "urlRule" -> resources.getString(R.string.url_rule)
                    else -> resources.getString(R.string.show_rule)
                }
                codeEditorLauncher.launch(Intent(context, CodeEditActivity::class.java).apply {
                    putExtra("text", text)
                    putExtra("title", title)
                    putExtra("cursorPosition", text.length)
                })
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = { TextButton({ confirmDiscard = false; viewModel.dismissEditor() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.no)) } },
        )
    }
    if (state.deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + state.deleteTargets.joinToString { it.name }) },
            confirmButton = { TextButton(viewModel::delete) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(viewModel::dismissDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (onlineImport) {
        OnlineDictionaryRuleImportDialog(
            onDismiss = { onlineImport = false },
            onImport = { onlineImport = false; importSource = it },
        )
    }
    importSource?.let { source ->
        DictionaryRuleImportDialog(source = source, onDismiss = { importSource = null })
    }
    exportUrl?.let { url ->
        val passphrase = remember(url) {
            if (SourceSharePassphrase.canEncode(url)) {
                SourceSharePassphrase.encode(url, SourceSharePassphrase.Type.DICT_RULE, DirectLinkUpload.getExpiryDate())
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
                passphrase?.let { TextButton({ context.sendToClip(it); exportUrl = null }) { Text(stringResource(R.string.shibboleth)) } }
            },
        )
    }
}

@Composable
private fun DictionaryRuleRow(
    rule: DictRule,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelected(!selected) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, onSelected)
        Spacer(Modifier.width(8.dp))
        Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Switch(rule.enabled, onEnabled)
        IconButton(onEdit, Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit))
        }
        IconButton(onDelete, Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete))
        }
    }
}

@Composable
private fun DictionaryRuleEditor(
    rule: DictRule,
    onSave: (DictRule) -> Unit,
    onDismissRequested: (DictRule) -> Unit,
    onCopy: (DictRule) -> Unit,
    onPaste: () -> Unit,
    onFullEdit: (String, String) -> Unit,
) {
    var name by remember(rule) { mutableStateOf(rule.name) }
    var urlRule by remember(rule) { mutableStateOf(rule.urlRule) }
    var showRule by remember(rule) { mutableStateOf(rule.showRule) }
    var focusedField by remember { mutableStateOf<String?>(null) }
    val current = rule.copy(name = name, urlRule = urlRule, showRule = showRule)
    AlertDialog(
        onDismissRequest = { onDismissRequested(current) },
        title = { Text(stringResource(R.string.dict_rule)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedField = "name" })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(urlRule, { urlRule = it }, label = { Text(stringResource(R.string.url_rule)) }, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedField = "urlRule" })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(showRule, { showRule = it }, label = { Text(stringResource(R.string.show_rule)) }, minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedField = "showRule" })
            }
        },
        confirmButton = {
            Row {
                TextButton(onPaste) { Text(stringResource(R.string.paste_rule)) }
                TextButton({ onCopy(current) }) { Text(stringResource(R.string.copy_rule)) }
                TextButton({
                    focusedField?.let { field ->
                        val text = when (field) { "name" -> name; "urlRule" -> urlRule; else -> showRule }
                        onFullEdit(field, text)
                    }
                }, enabled = focusedField != null) { Text(stringResource(R.string.edit_content)) }
                TextButton({ onSave(rule.copy(name = name, urlRule = urlRule, showRule = showRule)) }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = { TextButton({ onDismissRequested(current) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OnlineDictionaryRuleImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_on_line)) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("URL") }) },
        confirmButton = { TextButton({ onImport(value) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun copyRule(context: Context, rule: DictRule) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DictRule", GSON.toJson(rule)))
}

private fun pasteRule(context: Context): DictRule? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    return runCatching { GSON.fromJson(text, DictRule::class.java) }.getOrNull()
}
