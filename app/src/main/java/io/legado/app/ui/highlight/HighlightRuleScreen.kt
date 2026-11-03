package io.legado.app.ui.highlight

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.compose.component.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
fun HighlightRuleScreen(onBack: () -> Unit,onImport: (String,String,String) -> Unit = { _,_,_ -> },onEdit: (Long?) -> Unit,
    viewModel: HighlightRuleViewModel=viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard=LocalClipboard.current; val scope=rememberCoroutineScope()
    val back by rememberUpdatedState(onBack); val openImport by rememberUpdatedState(onImport)
    val snackbar=remember { SnackbarHostState() }
    val failure=stringResource(R.string.highlight_rule_manager_failed)
    LaunchedEffect(state.failure) { if(state.failure>0) snackbar.showSnackbar(failure) }
    LaunchedEffect(state.closed) { if(state.closed) back() }
    state.draft.importRequest?.let { request ->
        key(request.id) { HighlightImportLauncher(viewModel,request.id) }
    }
    LaunchedEffect(state.draft.importRequest?.id,state.draft.importRequest?.phase) {
        val request=state.draft.importRequest?.takeIf { it.phase=="received" } ?: return@LaunchedEffect
        viewModel.importDestination(request.id)?.let { openImport(it.id,checkNotNull(it.uri),viewModel.id) }
    }
    state.draft.transfer?.let { transfer -> transfer.attempt?.let { attempt ->
        key(attempt) { HighlightTransferLauncher(viewModel,transfer,attempt) }
    } }
    LaunchedEffect(state.draft.transfer?.id) {
        val transfer=state.draft.transfer?.takeIf { it.phase=="prepared" } ?: return@LaunchedEffect
        // The prepared state may be observed before the owning operation releases its mutex.
        val ready=viewModel.state.first { !it.busy && !it.loading || it.closed || it.draft.transfer?.id!=transfer.id }
        if(!ready.closed && ready.draft.transfer?.id==transfer.id && ready.draft.transfer.phase=="prepared") viewModel.deliver(transfer.id)
    }
    BackHandler {
        when { state.draft.dialog!=null -> viewModel.dismiss(); state.draft.groupsOpen -> viewModel.groups(false); else -> viewModel.close() }
    }
    val rows=viewModel.displayed(); val selected=viewModel.selected()
    val groups=state.rules.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    val enabled=!state.loading && !state.busy && !state.closed
    Scaffold(modifier=Modifier.testTag("highlight_rule_manager"),snackbarHost={ SnackbarHost(snackbar) },topBar={
        LegadoTopAppBar(title=stringResource(R.string.highlight_rule),onBack=viewModel::close,
            menuActions=if(!enabled) emptyList() else listOf(
                LegadoMenuAction(stringResource(R.string.highlight_rule_add),iconRes=R.drawable.ic_add,showAsAction=true,onClick={ onEdit(null) }),
                LegadoMenuAction(stringResource(R.string.import_local),onClick=viewModel::beginImport),
                LegadoMenuAction(stringResource(R.string.export_all),onClick={ viewModel.prepare(state.rules.map { it.ref }) }),
                LegadoMenuAction(stringResource(R.string.highlight_rule_group_manage),onClick={ viewModel.groups(true) }),
                LegadoMenuAction(stringResource(R.string.highlight_rule_group_filter),onClick=viewModel::filterDialog)))
    },bottomBar={
        if(enabled) LegadoSelectActionBar(selectedCount=selected.size,totalCount=rows.size,
            onSelectAllChange=viewModel::selectAll,onRevertSelection=viewModel::invert,
            mainActionText=stringResource(R.string.delete),onMainActionClick={ viewModel.delete(selected) },
            modifier=Modifier.windowInsetsPadding(WindowInsets.navigationBars),menuActions=listOf(
                LegadoMenuAction(stringResource(R.string.enable_selection),onClick={ viewModel.act(selected,"enable") }),
                LegadoMenuAction(stringResource(R.string.disable_selection),onClick={ viewModel.act(selected,"disable") }),
                LegadoMenuAction(stringResource(R.string.to_top),onClick={ viewModel.act(selected,"top") }),
                LegadoMenuAction(stringResource(R.string.to_bottom),onClick={ viewModel.act(selected,"bottom") }),
                LegadoMenuAction(stringResource(R.string.export_selection),onClick={ viewModel.prepare(selected) }),
                LegadoMenuAction(stringResource(R.string.share_selected_source),onClick={ viewModel.prepare(selected,true) })))
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        if(state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.draft.importRequest?.takeIf { it.phase in listOf("awaiting","failed") }?.let { request ->
            FlowRow {
                TextButton(onClick=viewModel::beginImport,enabled=enabled,modifier=Modifier.testTag("highlightImportChoose")) { Text(stringResource(R.string.select_file)) }
                TextButton(onClick={ viewModel.abandonImport(request.id) },enabled=enabled,modifier=Modifier.testTag("highlightImportCancel")) { Text(stringResource(R.string.cancel)) }
            }
        }
        state.draft.transfer?.takeUnless { it.phase=="prepared" || it.phase=="writing" }?.let { transfer ->
            FlowRow {
                TextButton(onClick={ scope.launch { viewModel.deliver(transfer.id) } },enabled=enabled,modifier=Modifier.testTag("highlightTransferChoose")) { Text(stringResource(R.string.select_file)) }
                if(transfer.phase=="failed" && transfer.uri!=null) TextButton(onClick={ viewModel.retry(transfer.id) },enabled=enabled,
                    modifier=Modifier.testTag("highlightTransferRetry")) { Text(stringResource(R.string.retry)) }
                TextButton(onClick={ viewModel.cancelTransfer(transfer.id) },enabled=enabled) { Text(stringResource(R.string.cancel)) }
            }
        }
        if(rows.isEmpty() && !state.loading) Text(stringResource(R.string.highlight_rule_empty),Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("highlightRuleList")) { items(rows,key={ it.ref.uuid }) { rule ->
            var menu by remember { mutableStateOf(false) }
            Column {
                Row(Modifier.fillMaxWidth().testTag("highlightRuleRow:${rule.ref.uuid}").clickable(enabled=enabled) { viewModel.select(rule.ref.uuid) }.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(rule.ref.uuid in state.draft.selected,{ viewModel.select(rule.ref.uuid) },enabled=enabled)
                    Text(rule.displayName,Modifier.weight(1f).padding(horizontal=4.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                    Switch(rule.enabled,{ viewModel.act(listOf(rule.ref),if(it) "enable" else "disable") },enabled=enabled,
                        modifier=Modifier.testTag("highlightRuleEnabled:${rule.ref.uuid}"))
                    IconButton(onClick={ onEdit(rule.ref.id) },enabled=enabled,modifier=Modifier.testTag("highlightRuleEdit:${rule.ref.uuid}")) { Icon(Icons.Default.Edit,stringResource(R.string.edit)) }
                    Box {
                        IconButton(onClick={ menu=true },enabled=enabled,modifier=Modifier.testTag("highlightRuleMore:${rule.ref.uuid}")) { Icon(Icons.Default.MoreVert,stringResource(R.string.more_menu)) }
                        DropdownMenu(menu,{ menu=false }) {
                            DropdownMenuItem(text={ Text(stringResource(R.string.to_top)) },onClick={ menu=false; viewModel.act(listOf(rule.ref),"top") })
                            DropdownMenuItem(text={ Text(stringResource(R.string.to_bottom)) },onClick={ menu=false; viewModel.act(listOf(rule.ref),"bottom") })
                            DropdownMenuItem(text={ Text(stringResource(R.string.delete)) },onClick={ menu=false; viewModel.delete(listOf(rule.ref)) })
                        }
                    }
                }
                HorizontalDivider()
            }
        } }
    } }
    if(state.draft.groupsOpen) Dialog(onDismissRequest={ viewModel.groups(false) },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.9f).fillMaxHeight(.9f).testTag("highlightGroupManager"),shape=MaterialTheme.shapes.large) {
            Scaffold(topBar={ LegadoTopAppBar(title=stringResource(R.string.highlight_rule_group_manage),onBack={ viewModel.groups(false) }) }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("highlightGroupList")) { items(groups,key={ it }) { group ->
                    Row(Modifier.fillMaxWidth().testTag("highlightGroupRow:$group").padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(group,Modifier.weight(1f))
                        TextButton(onClick={ viewModel.groupAction(group,true) },enabled=enabled,modifier=Modifier.testTag("highlightGroupEdit:$group")) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick={ viewModel.groupAction(group,false) },enabled=enabled,modifier=Modifier.testTag("highlightGroupDelete:$group")) { Text(stringResource(R.string.delete)) }
                    }
                } }
            }
        }
    }
    state.draft.dialog?.let { dialog -> key(dialog.id) {
        AlertDialog(onDismissRequest=viewModel::dismiss,modifier=Modifier.testTag("highlightManagerDialog"),
            title={ Text(stringResource(when(dialog.kind) { "rename" -> R.string.group_edit; "filter" -> R.string.highlight_rule_group_filter; "moveGroup" -> R.string.move_to_group; else -> R.string.highlight_rule_group_delete })) },
            text={ Column {
                if(state.error) Text(failure,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("highlightManagerError"))
                when(dialog.kind) {
                    "rename" -> OutlinedTextField(dialog.text,{ viewModel.renameText(dialog.id,it) },enabled=enabled,label={ Text(stringResource(R.string.group_name)) },modifier=Modifier.testTag("highlightGroupName"))
                    "filter","moveGroup" -> LazyColumn(Modifier.heightIn(max=360.dp).testTag("highlightChoiceList:${dialog.kind}")) {
                        if(dialog.kind=="filter") item { TextButton(onClick={ viewModel.filter("all") },enabled=enabled,modifier=Modifier.testTag("highlightChoice:filter:all")) { Text(stringResource(R.string.all)) } }
                        item { TextButton(onClick={ if(dialog.kind=="filter") viewModel.filter("ungrouped") else viewModel.confirm(dialog.id,null) },enabled=enabled,modifier=Modifier.testTag("highlightChoice:${dialog.kind}:ungrouped")) { Text(stringResource(R.string.no_group)) } }
                        items(groups.filterNot { dialog.kind=="moveGroup" && it==dialog.source }) { group ->
                            TextButton(onClick={ if(dialog.kind=="filter") viewModel.filter("named",group) else viewModel.confirm(dialog.id,group) },enabled=enabled,modifier=Modifier.testTag("highlightChoice:${dialog.kind}:group:$group")) { Text("[$group]") }
                        }
                    }
                    else -> Text(stringResource(if(dialog.kind=="deleteGroup") R.string.highlight_rule_group_delete_message else R.string.sure_del))
                }
            } },dismissButton={ TextButton(onClick=viewModel::dismiss,enabled=enabled) { Text(stringResource(R.string.cancel)) } },
            confirmButton={ Row {
                if(dialog.kind=="deleteGroup") TextButton(onClick={ viewModel.chooseMove(dialog.id) },enabled=enabled,modifier=Modifier.testTag("highlightGroupMove")) { Text(stringResource(R.string.move_to_group)) }
                if(dialog.kind in listOf("rename","delete","deleteGroup")) TextButton(onClick={ viewModel.confirm(dialog.id) },enabled=enabled && (dialog.kind!="rename" || dialog.text.isNotBlank()),
                    modifier=Modifier.testTag("highlightManagerConfirm")) { Text(stringResource(if(dialog.kind=="rename") R.string.ok else R.string.delete)) }
            } })
    } }
    state.draft.exportedUri?.let { uri -> AlertDialog(onDismissRequest=viewModel::dismissExport,modifier=Modifier.testTag("highlightExportSuccess"),
        title={ Text(stringResource(R.string.export_success)) },text={ Text(uri) },confirmButton={ TextButton(onClick={ scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("",uri))) }; viewModel.dismissExport() }) { Text(stringResource(R.string.ok)) } }) }
}


/** Each registry callback captures one immutable attempt, including across Activity recreation. */
@Composable
private fun HighlightTransferLauncher(model: HighlightRuleViewModel,transfer: HighlightRuleTransfer,attempt: String) {
    val registry=checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current).activityResultRegistry
    val registration="highlight-manager:${model.id}:$attempt"
    if(transfer.kind=="export") {
        var launcher by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<String>?>(null) }
        DisposableEffect(registry,registration) {
            val registered=registry.register(registration,ActivityResultContracts.CreateDocument("application/json")) { uri ->
                model.result(transfer.id,attempt,uri?.toString())
            }
            launcher=registered
            onDispose { registered.unregister() }
        }
        LaunchedEffect(launcher) {
            val registered=launcher ?: return@LaunchedEffect
            if(model.consumeLaunch(attempt)) {
                try { registered.launch("HighlightRules.json") }
                catch(error: Exception) { model.launchFailed(transfer.id,attempt) }
            }
        }
    } else {
        var launcher by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<Intent>?>(null) }
        DisposableEffect(registry,registration) {
            val registered=registry.register(registration,ActivityResultContracts.StartActivityForResult()) {
                model.result(transfer.id,attempt,null)
            }
            launcher=registered
            onDispose { registered.unregister() }
        }
        LaunchedEffect(launcher) {
            val registered=launcher ?: return@LaunchedEffect
            // Restoring a registration must not reopen the chooser or rewrite a document.
            if(model.state.value.launchAttempt!=attempt) return@LaunchedEffect
            try {
                val uri=model.shareUri(transfer)
                if(model.consumeLaunch(attempt)) registered.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type="application/json"; putExtra(Intent.EXTRA_STREAM,uri)
                    clipData=ClipData.newRawUri("HighlightRules.json",uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },null))
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(error: Exception) { model.launchFailed(transfer.id,attempt) }
        }
    }
}


@Composable
private fun HighlightImportLauncher(model: HighlightRuleViewModel,attempt: String) {
    val registry=checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current).activityResultRegistry
    val context=LocalContext.current
    val registration="highlight-manager-import:${model.id}:$attempt"
    var launcher by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<Array<String>>?>(null) }
    DisposableEffect(registry,registration) {
        val registered=registry.register(registration,ActivityResultContracts.OpenDocument()) { uri ->
            if(uri!=null) runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.importResult(attempt,uri?.toString())
        }
        launcher=registered
        onDispose { registered.unregister() }
    }
    LaunchedEffect(launcher) {
        val registered=launcher ?: return@LaunchedEffect
        if(model.consumeImportLaunch(attempt)) {
            try { registered.launch(arrayOf("application/json","text/plain")) }
            catch(error: Exception) { model.importLaunchFailed(attempt) }
        }
    }
}
