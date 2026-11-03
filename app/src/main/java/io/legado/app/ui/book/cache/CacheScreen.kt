package io.legado.app.ui.book.cache

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.BookTransferDisplay
import io.legado.app.data.repository.*
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.CacheRoute

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CacheDestination(route: CacheRoute,onBack: () -> Unit,onLogs: () -> Unit,onWebDavSetup: () -> Unit={},model: CacheManagerViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val snackbar=remember { SnackbarHostState() }
    val failure=stringResource(R.string.cache_operation_failed)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model,route.groupId) { model.initialize(route.groupId) }
    LaunchedEffect(state.closed) { if(state.closed) back() }
    LaunchedEffect(state.failure) { if(state.failure>0) snackbar.showSnackbar(failure) }
    LaunchedEffect(state.draft.pendingTasks) {
        val ids=state.draft.pendingTasks
        if(ids.isNotEmpty()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val ready=model.state.first { !it.busy && !it.loading || it.closed || it.draft.pendingTasks!=ids }
            if(!ready.closed && ready.draft.pendingTasks==ids) model.dispatch()
        }
    }
    state.draft.folder?.let { attempt -> key(attempt.id) { CacheFolderLauncher(model,attempt.id,state.draft.dialog?.options?.directory) } }
    BackHandler { if(state.draft.dialog!=null) model.dismiss() else model.close() }
    val enabled=!state.loading && !state.busy && !state.closed
    val booksReady=enabled && !state.booksLoading && !state.booksFailed
    val downloading=state.transfers.filter { it.kind=="download" && it.phase in listOf("queued","running") }
    val remote=state.books.filterNot { it.local }.map { it.ref }
    var downloadMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    val downloadDescription=stringResource(if(downloading.isEmpty()) R.string.cache_download_title else R.string.cache_stop_all)
    Scaffold(modifier=Modifier.testTag("cache_manager"),snackbarHost={ SnackbarHost(snackbar) },topBar={
        LegadoTopAppBar(title=stringResource(R.string.cache_export),onBack=model::close,
            subtitle=state.groups.firstOrNull { it.id==state.draft.groupId }?.name ?: stringResource(if(state.draft.groupId==-1L) R.string.all else R.string.no_group),
            actions={
                Box {
                    Box(Modifier.size(48.dp).testTag("cacheDownloadMenu")
                        .semantics { contentDescription=downloadDescription }
                        .combinedClickable(enabled=booksReady && (remote.isNotEmpty() || downloading.isNotEmpty()),role=Role.Button,
                            onClick={ if(downloading.isEmpty()) model.download(remote,true) else model.stop(downloading.map { it.id }) },
                            onLongClick={ downloadMenu=true }),contentAlignment=Alignment.Center) {
                        Icon(painterResource(if(downloading.isEmpty()) R.drawable.ic_download else R.drawable.ic_stop_black_24dp),null)
                    }
                    DropdownMenu(downloadMenu,{ downloadMenu=false }) {
                        DropdownMenuItem(text={ Text(stringResource(R.string.menu_download_after)) },enabled=booksReady && remote.isNotEmpty(),
                            onClick={ downloadMenu=false; model.download(remote,true) })
                        DropdownMenuItem(text={ Text(stringResource(R.string.menu_download_all)) },enabled=booksReady && remote.isNotEmpty(),
                            onClick={ downloadMenu=false; model.download(remote,false) })
                        if(downloading.isNotEmpty()) DropdownMenuItem(text={ Text(stringResource(R.string.cache_stop_all)) },enabled=enabled,
                            onClick={ downloadMenu=false; model.stop(downloading.map { it.id }) })
                    }
                }
                Box {
                    IconButton(onClick={ groupMenu=true },enabled=enabled,modifier=Modifier.testTag("cacheGroupMenu")) {
                        Icon(painterResource(R.drawable.ic_groups),stringResource(R.string.group))
                    }
                    DropdownMenu(groupMenu,{ groupMenu=false }) { state.groups.forEach { group ->
                        DropdownMenuItem(text={ Text(group.name) },onClick={ groupMenu=false; model.group(group.id) })
                    } }
                }
            },menuActions=if(!enabled) emptyList() else listOf(
                LegadoMenuAction(stringResource(R.string.export_all),onClick={ if(booksReady && state.books.isNotEmpty()) model.export(state.books.map { it.ref }) }),
                LegadoMenuAction(stringResource(R.string.replace_purify),checked=state.options.useReplace,onClick={ model.toggle("replace",!state.options.useReplace) }),
                LegadoMenuAction(stringResource(R.string.custom_export_section),checked=state.options.customSections,onClick={ model.toggle("custom",!state.options.customSections) }),
                LegadoMenuAction(stringResource(R.string.export_to_web_dav),checked=state.options.webDav,onClick={ model.toggle("webdav",!state.options.webDav) }),
                LegadoMenuAction(stringResource(R.string.export_no_chapter_name),checked=state.options.omitNames,onClick={ model.toggle("names",!state.options.omitNames) }),
                LegadoMenuAction(stringResource(R.string.export_pics_file),checked=state.options.pictures,onClick={ model.toggle("pictures",!state.options.pictures) }),
                LegadoMenuAction(stringResource(R.string.parallel_export_book),checked=state.options.parallel,onClick={ model.toggle("parallel",!state.options.parallel) }),
                LegadoMenuAction(stringResource(R.string.export_folder),onClick={ model.setting("folder") }),
                LegadoMenuAction(stringResource(R.string.export_file_name),onClick={ model.setting("filename") }),
                LegadoMenuAction("${stringResource(R.string.export_type)} (${state.options.type})",onClick={ model.setting("format") }),
                LegadoMenuAction("${stringResource(R.string.export_charset)} (${state.options.charset})",onClick={ model.setting("charset") }),
                LegadoMenuAction(stringResource(R.string.log),onClick=onLogs)))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.navigationBars)) {
            if(state.loading || state.booksLoading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.draft.pendingTasks.isNotEmpty() && !state.busy) TextButton(onClick=model::dispatch,enabled=enabled,modifier=Modifier.testTag("cacheDispatch")) {
                Text(stringResource(R.string.cache_pending_start))
            }
            if(state.booksFailed) Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.cache_books_failed),Modifier.weight(1f))
                TextButton(onClick=model::retryBooks) { Text(stringResource(R.string.retry)) }
            }
            if(state.books.isEmpty() && !state.loading && !state.booksLoading && !state.booksFailed) Text(stringResource(R.string.no_book),Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("cacheBookList")) {
                if(state.recoveries.isNotEmpty()) item("exportRecoveryTitle") {
                    Text(stringResource(R.string.cache_recovery_title),Modifier.padding(16.dp),style=MaterialTheme.typography.titleMedium)
                }
                items(state.recoveries,key={ "recovery:${it.id}" }) { recovery -> CacheRecoveryRow(recovery,enabled,model) }
                items(state.books,key={ it.ref.url }) { book ->
                    val transfers=state.transfers.filter { it.bookUrl==book.ref.url }
                    CacheBookRow(book,transfers,booksReady,model)
                }
            }
        }
    }
    state.draft.dialog?.let { dialog -> CacheDialog(dialog,state,model,onWebDavSetup) }
}

@Composable
private fun CacheRecoveryRow(item: ExportRecoveryItem,enabled: Boolean,model: CacheManagerViewModel) {
    Column(Modifier.fillMaxWidth().testTag("cacheRecovery:${item.id}").padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(item.corrupt) stringResource(R.string.cache_recovery_corrupt) else item.name,style=MaterialTheme.typography.bodyLarge)
        if(item.corrupt) TextButton(onClick={ model.forgetRecovery(item) },enabled=enabled,
            modifier=Modifier.testTag("cacheRecoveryForget:${item.id}")) { Text(stringResource(R.string.cache_recovery_forget)) }
        if(!item.corrupt) {
            Text(stringResource(when(item.phase) {
                "queued" -> R.string.cache_task_queued; "running" -> R.string.cache_recovery_running
                "failed" -> R.string.cache_recovery_failed; "cancelled" -> R.string.cache_task_cancelled
                "interrupted" -> R.string.cache_task_interrupted; else -> R.string.cache_recovery_pending
            }),style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                val command=item.command
                if(command!=null) {
                    if(item.phase=="queued") TextButton(onClick={ model.startRecovery(command) },enabled=enabled,
                        modifier=Modifier.testTag("cacheRecoveryStart:${item.id}")) { Text(stringResource(R.string.cache_pending_start)) }
                    TextButton(onClick={ model.cancelRecovery(command) },enabled=enabled,
                        modifier=Modifier.testTag("cacheRecoveryCancel:${item.id}")) { Text(stringResource(R.string.cancel)) }
                } else {
                    TextButton(onClick={ model.recover(item,"restore") },enabled=enabled,
                        modifier=Modifier.testTag("cacheRecoveryRestore:${item.id}")) {
                        Text(stringResource(if(item.hadOriginal) R.string.cache_recovery_restore else R.string.cache_recovery_discard))
                    }
                    TextButton(onClick={ model.recover(item,"keep") },enabled=enabled,
                        modifier=Modifier.testTag("cacheRecoveryKeep:${item.id}")) { Text(stringResource(R.string.cache_recovery_keep)) }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun CacheBookRow(book: CacheShelfBook,transfers: List<BookTransferDisplay>,enabled: Boolean,model: CacheManagerViewModel) {
    val countFlow=remember(book.ref) { model.count(book.ref) }
    val count by countFlow.collectAsStateWithLifecycle(initialValue=null)
    val download=transfers.firstOrNull { it.kind=="download" }
    val running=download?.phase in listOf("queued","running")
    val title=book.title.ifBlank { stringResource(R.string.no_book_name) }
    Column(Modifier.fillMaxWidth().testTag("cacheBook:${book.ref.url}").padding(horizontal=16.dp,vertical=8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title,style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(stringResource(R.string.author_show,book.author),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(when { book.local -> stringResource(R.string.local_book); count==null -> stringResource(R.string.loading)
                    count?.failed==true -> stringResource(R.string.cache_books_failed)
                    else -> stringResource(R.string.download_count,count!!.cached,count!!.total) },style=MaterialTheme.typography.bodySmall)
            }
            if(!book.local) IconButton(enabled=enabled,onClick={
                if(running) model.stop(listOf(checkNotNull(download).id)) else model.download(listOf(book.ref),false)
            },modifier=Modifier.testTag("cacheDownload:${book.ref.url}")) {
                Icon(painterResource(if(running) R.drawable.ic_stop_black_24dp else R.drawable.ic_download),
                    stringResource(if(running) R.string.cache_stop_book else R.string.cache_download_book,title))
            }
            IconButton(enabled=enabled,onClick={ model.export(listOf(book.ref)) },modifier=Modifier.testTag("cacheExport:${book.ref.url}")) {
                Icon(painterResource(R.drawable.ic_export),stringResource(R.string.cache_export_book,title))
            }
        }
        if(count?.failed==true) TextButton(onClick={ model.retryCount(book.ref) },modifier=Modifier.testTag("cacheCountRetry:${book.ref.url}")) { Text(stringResource(R.string.retry)) }
        transfers.forEach { transfer ->
            val matches=transfer.bookKey==book.ref.key
            val status=when {
                !matches -> stringResource(R.string.cache_task_changed)
                transfer.phase=="queued" -> stringResource(R.string.cache_task_queued)
                transfer.phase=="running" -> stringResource(R.string.cache_task_running,transfer.progress,transfer.total)
                transfer.phase=="completed" -> stringResource(R.string.cache_task_completed)
                transfer.phase=="cancelled" -> stringResource(R.string.cache_task_cancelled)
                transfer.phase=="interrupted" -> stringResource(R.string.cache_task_interrupted)
                else -> stringResource(R.string.cache_task_failed)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("${stringResource(if(transfer.kind=="download") R.string.cache_download_title else R.string.export_book)} · $status",
                    Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                if(matches && transfer.phase=="queued") IconButton(onClick={ model.startQueued(transfer.id) },enabled=enabled,
                    modifier=Modifier.testTag("cacheStart:${transfer.id}")) { Icon(Icons.Default.PlayArrow,stringResource(R.string.cache_pending_start)) }
                if(transfer.phase in listOf("queued","running")) IconButton(onClick={ model.stop(listOf(transfer.id)) },enabled=enabled,
                    modifier=Modifier.testTag("cacheStop:${transfer.id}")) { Icon(painterResource(R.drawable.ic_stop_black_24dp),stringResource(R.string.cancel)) }
                if(matches && transfer.phase in listOf("failed","interrupted","cancelled")) IconButton(onClick={ model.retry(transfer.id) },enabled=enabled,
                    modifier=Modifier.testTag("cacheRetry:${transfer.id}")) { Icon(Icons.Default.Refresh,stringResource(R.string.retry)) }
            }
            if(matches && transfer.phase=="running") LinearProgressIndicator(progress={
                if(transfer.total>0) (transfer.progress.toFloat()/transfer.total).coerceIn(0f,1f) else 0f
            },modifier=Modifier.fillMaxWidth())
        }
    }
    HorizontalDivider()
}

@Composable
private fun CacheDialog(dialog: CacheManagerDialog,state: CacheManagerUiState,model: CacheManagerViewModel,onWebDavSetup: () -> Unit) {
    val enabled=!state.busy && !state.closed
    val title=when(dialog.kind) {
        "download" -> stringResource(R.string.cache_download_confirm,dialog.refs.size)
        "export" -> stringResource(R.string.cache_export_confirm,dialog.refs.size)
        "folder" -> stringResource(R.string.export_folder)
        "format" -> stringResource(R.string.export_type)
        "charset" -> stringResource(R.string.set_charset)
        "recovery","forgetRecovery" -> stringResource(R.string.cache_recovery_title)
        else -> stringResource(R.string.export_file_name)
    }
    AlertDialog(onDismissRequest=model::dismiss,title={ Text(title) },modifier=Modifier.testTag("cacheDialog:${dialog.kind}"),text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            when(dialog.kind) {
                "download" -> Text(stringResource(if(dialog.afterCurrent) R.string.cache_download_after_description else R.string.cache_download_all_description))
                "forgetRecovery" -> Text(stringResource(R.string.cache_recovery_forget_description))
                "export","folder","recovery" -> {
                    if(dialog.kind=="recovery") Text(stringResource(if(dialog.scope=="restore") R.string.cache_recovery_restore_description else R.string.cache_recovery_keep_description))
                    Text(dialog.options.directory.ifBlank { stringResource(R.string.cache_folder_waiting) },maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("cacheExportDirectory"))
                    TextButton(onClick=model::chooseFolder,enabled=enabled,modifier=Modifier.testTag("cacheChooseFolder")) { Text(stringResource(R.string.select_folder)) }
                    if(dialog.kind=="export") {
                        Text("${dialog.options.type} · ${dialog.options.charset}")
                        if(dialog.options.format==1 && dialog.options.customSections) {
                            Row(Modifier.fillMaxWidth().toggleable(dialog.split,enabled=enabled,role=Role.Checkbox,
                                onValueChange={ value -> model.edit { it.copy(split=value) } }),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(dialog.split,null,enabled=enabled)
                                Text(stringResource(R.string.custom_export))
                            }
                            if(dialog.split) {
                                OutlinedTextField(dialog.scope,{ value -> model.edit { it.copy(scope=value.take(100_000)) } },enabled=enabled,
                                    label={ Text(stringResource(R.string.cache_scope)) },modifier=Modifier.testTag("cacheScope"))
                                OutlinedTextField(dialog.chunk,{ value -> model.edit { it.copy(chunk=value.take(10)) } },enabled=enabled,
                                    label={ Text(stringResource(R.string.cache_part_size)) },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("cachePartSize"))
                                OutlinedTextField(dialog.options.episodeName,{ value -> model.edit { it.copy(options=it.options.copy(episodeName=value.take(500_000)),episodeEdited=true) } },enabled=enabled,
                                    label={ Text(stringResource(R.string.export_file_name)) },supportingText={ Text(stringResource(R.string.cache_filename_script_hint)) },modifier=Modifier.testTag("cacheEpisodeName"))
                                TextButton(onClick=model::preview,enabled=enabled) { Text(stringResource(R.string.cache_preview)) }
                            }
                        }
                    }
                }
                "format" -> listOf("txt","epub","pdf").forEachIndexed { index,name ->
                    Row(Modifier.fillMaxWidth().selectable(dialog.options.format==index,enabled=enabled,role=Role.RadioButton,
                        onClick={ model.edit { it.copy(options=it.options.copy(format=index)) } }),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(dialog.options.format==index,null,enabled=enabled)
                        Text(name)
                    }
                }
                else -> {
                    OutlinedTextField(dialog.text,{ value -> model.edit { it.copy(text=value.take(if(dialog.kind=="charset") 128 else 500_000)) } },enabled=enabled,
                        label={ Text(title) },modifier=Modifier.testTag("cacheSettingText"))
                    if(dialog.kind!="charset") {
                        Text(stringResource(R.string.cache_filename_script_hint))
                        TextButton(onClick=model::preview,enabled=enabled) { Text(stringResource(R.string.cache_preview)) }
                    }
                }
            }
            if(state.preview.isNotBlank()) Text(state.preview,modifier=Modifier.testTag("cacheFilenamePreview"))
            if(state.draft.folder!=null) Row(verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.cache_folder_waiting),Modifier.weight(1f))
                TextButton(onClick=model::chooseFolder,enabled=enabled) { Text(stringResource(R.string.retry)) }
                TextButton(onClick=model::abandonFolder,enabled=enabled) { Text(stringResource(R.string.cancel)) }
            }
            if(state.dialogError!=0) Text(stringResource(state.dialogError),color=MaterialTheme.colorScheme.error)
            if(state.dialogError==R.string.cache_webdav_setup_required) TextButton(onClick=onWebDavSetup) { Text(stringResource(R.string.cache_webdav_setup_action)) }
        }
    },confirmButton={ TextButton(onClick=model::confirm,enabled=enabled && state.draft.folder==null,modifier=Modifier.testTag("cacheConfirm")) { Text(stringResource(R.string.ok)) } },
        dismissButton={ TextButton(onClick=model::dismiss,enabled=enabled) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CacheFolderLauncher(model: CacheManagerViewModel,attempt: String,directory: String?) {
    val registry=checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val registration="cache-folder:${model.id}:$attempt"
    var launcher by remember(registry,registration,model) { mutableStateOf<ActivityResultLauncher<Uri?>?>(null) }
    DisposableEffect(registry,registration,model) {
        val registered=registry.register(registration,ActivityResultContracts.OpenDocumentTree()) { uri -> model.folderResult(attempt,uri?.toString()) }
        launcher=registered
        onDispose { registered.unregister() }
    }
    LaunchedEffect(launcher) {
        val registered=launcher ?: return@LaunchedEffect
        if(model.consumeFolderLaunch(attempt)) {
            try { registered.launch(cacheFolderInitialUri(directory)) }
            catch(_: Exception) { model.folderResult(attempt,null) }
        }
    }
}

/** EXTRA_INITIAL_URI requires a document URI, including when reopening a granted tree. */
internal fun cacheFolderInitialUri(directory: String?): Uri? = runCatching {
    val uri=directory?.takeIf { it.startsWith("content://") }?.let(Uri::parse) ?: return@runCatching null
    if(DocumentsContract.isTreeUri(uri)) {
        val documentId=runCatching { DocumentsContract.getDocumentId(uri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(uri) }
        DocumentsContract.buildDocumentUriUsingTree(uri,documentId)
    } else uri
}.getOrNull()
