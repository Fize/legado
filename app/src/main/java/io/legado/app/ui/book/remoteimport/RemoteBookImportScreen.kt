package io.legado.app.ui.book.remoteimport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ConvertUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteBookImportScreen(
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onServers: () -> Unit,
    onLogs: () -> Unit,
    viewModel: RemoteBookImportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val checkableItems = state.entries.filter { !it.isDir && !it.isOnBookShelf }
    val selectedItems = checkableItems.filter { it.path in state.selected }
    val picker = rememberLauncherForActivityResult(HandleFileContract()) { viewModel.picked(it.uri?.toString()) }
    LaunchedEffect(Unit) { viewModel.initialize() }
    LaunchedEffect(state.picker) { if (state.picker && viewModel.claimPicker()) picker.launch { mode = HandleFileContract.DIR_SYS } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    LaunchedEffect(state.openBook) { state.openBook?.let { onOpenBook(it); viewModel.consumeOpenBook() } }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    BackHandler(enabled = state.canGoBack || state.working) { viewModel.goBack() }
    if (state.storageSetup || state.showStorage) {
        AlertDialog(
            modifier = Modifier.testTag("remoteImportStorageDialog"),
            onDismissRequest = { viewModel.cancelStorage() },
            title = { Text(stringResource(R.string.select_book_folder)) },
            text = { Text(if (state.storageSetup) state.storageHelp else state.saveDirectory) },
            confirmButton = { TextButton(onClick = viewModel::requestStoragePicker) { Text(stringResource(R.string.select_folder)) } },
            dismissButton = { Row {
                TextButton(onClick = { viewModel.cancelStorage() }) { Text(stringResource(R.string.cancel)) }
                TextButton(modifier = Modifier.testTag("remoteImportPrivateStorage"), onClick = { viewModel.usePrivateStorage() }) {
                    Text(stringResource(R.string.shared_local_books_private))
                }
            } },
        )
    }
    if (state.reimport != null) {
        AlertDialog(onDismissRequest = viewModel::dismissReimport, title = { Text(stringResource(R.string.sure)) },
            text = { Text(stringResource(R.string.remote_import_again)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("remoteImportAgainConfirm"), onClick = viewModel::confirmReimport) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissReimport) { Text(stringResource(R.string.cancel)) } })
    }
    if (state.redownload != null) {
        AlertDialog(onDismissRequest = viewModel::dismissRedownload, title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.archive_not_found)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("remoteImportDownloadConfirm"), onClick = viewModel::confirmRedownload) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissRedownload) { Text(stringResource(R.string.cancel)) } })
    }
    state.archive?.let { prompt ->
        AlertDialog(onDismissRequest = viewModel::dismissArchive, title = { Text(stringResource(R.string.start_read)) },
            text = {
                if (prompt.confirmName != null) Text(stringResource(R.string.no_book_found_bookshelf))
                else LazyColumn { items(prompt.names) { name -> TextButton(enabled = !state.working, onClick = { viewModel.chooseArchive(name) }) { Text(name) } } }
            },
            confirmButton = { if (prompt.confirmName != null) TextButton(modifier = Modifier.testTag("remoteArchiveImportConfirm"), onClick = viewModel::confirmArchive) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissArchive) { Text(stringResource(R.string.cancel)) } })
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.remote_book),
                onBack = { if (!state.working) onBack() },
                searchQuery = state.query,
                onSearchQueryChange = viewModel::query,
                searchPlaceholder = stringResource(R.string.screen) + " • " + stringResource(R.string.remote_book),
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh_black_24dp),
                            contentDescription = stringResource(R.string.refresh),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_sort_24),
                                contentDescription = stringResource(R.string.sort),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.sort_by_name)) },
                                trailingIcon = {
                                    Checkbox(
                                        checked = state.sort == RemoteImportSort.Name,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    viewModel.sort(RemoteImportSort.Name)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.sort_by_lastUpdateTime)) },
                                trailingIcon = {
                                    Checkbox(
                                        checked = state.sort == RemoteImportSort.Modified,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    viewModel.sort(RemoteImportSort.Modified)
                                },
                            )
                        }
                    }
                },
                menuActions = listOf(
                    LegadoMenuAction(
                        title = stringResource(R.string.server_config),
                        iconRes = R.drawable.ic_cfg_other,
                        onClick = onServers,
                    ),
                    LegadoMenuAction(
                        title = stringResource(R.string.log),
                        iconRes = R.drawable.ic_cfg_about,
                        onClick = onLogs,
                    ),
                ),
            )
        },
        bottomBar = {
            LegadoSelectActionBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                selectedCount = selectedItems.size,
                totalCount = checkableItems.size,
                onSelectAllChange = viewModel::selectAll,
                onRevertSelection = viewModel::invertSelection,
                mainActionText = stringResource(R.string.add_to_bookshelf),
                onMainActionClick = viewModel::importSelected,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.goBack() },
                        enabled = state.canGoBack,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(text = stringResource(R.string.go_back), fontSize = 13.sp)
                    }
                }
            }
            if ((state.loading || state.working)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                if (state.entries.isEmpty() && !state.loading) {
                    Text(
                        text = stringResource(R.string.empty),
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
                            items = state.entries,
                            key = { it.path },
                        ) { item ->
                            val isSelected = item.path in state.selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .testTag("remoteImportEntry_${item.filename}")
                                    .combinedClickable(
                                        enabled = !state.working,
                                        onClick = {
                                            if (item.isDir) {
                                                viewModel.enter(item)
                                            } else if (!item.isOnBookShelf) {
                                                viewModel.toggle(item)
                                            } else {
                                                viewModel.open(item)
                                            }
                                        },
                                        onLongClick = {
                                            if (item.isOnBookShelf) {
                                                viewModel.requestReimport(item)
                                            }
                                        },
                                    )
                                    .padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.width(60.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when {
                                        item.isDir -> Icon(
                                            painter = painterResource(R.drawable.ic_folder),
                                            contentDescription = null,
                                        )
                                        item.isOnBookShelf -> Icon(
                                            painter = painterResource(R.drawable.ic_book_has),
                                            contentDescription = null,
                                        )
                                        else -> Checkbox(
                                            checked = isSelected,
                                            enabled = !state.working,
                                            onCheckedChange = { viewModel.toggle(item) },
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = item.filename,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.isDir) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Text(
                                                text = item.contentType,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        RoundedCornerShape(2.dp)
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = ConvertUtils.formatFileSize(item.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = AppConst.dateFormat.format(item.lastModify),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
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
