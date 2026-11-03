package io.legado.app.ui.book.localimport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.LocalImportEntry
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoDropdownMenu
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ConvertUtils

@Composable
fun LocalBookImportScreen(
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    viewModel: ImportBookViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val selectFolder = rememberLauncherForActivityResult(HandleFileContract()) { viewModel.picked(it.uri?.toString()) }
    LaunchedEffect(Unit) { viewModel.initialize() }
    LaunchedEffect(state.picker) {
        if (state.picker == null) return@LaunchedEffect
        if (viewModel.claimPicker()) selectFolder.launch {
            title = null
            mode = HandleFileContract.DIR_SYS
        }
    }
    LaunchedEffect(state.openBook) { state.openBook?.let { onOpenBook(it); viewModel.consumeOpenBook() } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    BackHandler(enabled = state.canGoBack || state.working) { viewModel.goBack() }
    if (state.showSaveDirectory || state.storageSetup) {
        ComposeAlertDialog(
            modifier = Modifier.testTag("localBookSaveFolderDialog"),
            onDismissRequest = { if (state.storageSetup) viewModel.dismissStorageSetup() else viewModel.showSaveDirectory(false) },
            title = { Text(stringResource(if (state.storageSetup) R.string.select_book_folder else R.string.local_book_save_path)) },
            text = { Text(if (state.storageSetup) state.storageHelp else state.saveDirectory) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("localBookSaveFolderSelect"),
                    onClick = { viewModel.requestPicker(if (state.storageSetup) "setup" else "save") }) {
                    Text(stringResource(R.string.select_folder))
                }
            },
            dismissButton = { Row {
                TextButton(onClick = { if (state.storageSetup) viewModel.dismissStorageSetup() else viewModel.showSaveDirectory(false) }) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(modifier = Modifier.testTag("localBookSaveFolderPrivate"), onClick = viewModel::usePrivateDirectory) {
                    Text(stringResource(R.string.shared_local_books_private))
                }
            } },
        )
    }
    if (state.editingScript) {
        ComposeAlertDialog(
            onDismissRequest = { viewModel.editScript(false) },
            title = { Text(stringResource(R.string.import_file_name)) },
            text = { Column {
                Text(stringResource(R.string.local_import_filename_help))
                OutlinedTextField(value = state.fileNameScript, onValueChange = viewModel::changeScript,
                    label = { Text("JavaScript") }, modifier = Modifier.testTag("localImportFilenameScript"))
            } },
            confirmButton = { TextButton(modifier = Modifier.testTag("localImportFilenameSave"), onClick = viewModel::saveScript) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(modifier = Modifier.testTag("localImportFilenameCancel"), onClick = { viewModel.editScript(false) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.groupPrompt?.let { prompt ->
        ComposeAlertDialog(
            modifier = Modifier.testTag("localImportGroupDialog"),
            onDismissRequest = viewModel::dismissGroupPrompt,
            title = { Text(stringResource(R.string.import_directory_group_title)) },
            text = { Column {
                if (!prompt.allowed) Text(stringResource(R.string.book_group_limit))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = prompt.checked, onCheckedChange = viewModel::checkGroup, enabled = prompt.allowed,
                        modifier = Modifier.testTag("localImportGroupCheckbox"))
                    Text(stringResource(R.string.import_directory_group))
                }
            } },
            confirmButton = { TextButton(enabled = prompt.allowed, onClick = { viewModel.confirmGroup(true) },
                modifier = Modifier.testTag("localImportGroupConfirm")) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { viewModel.confirmGroup(false) }) { Text(stringResource(R.string.local_import_without_group)) } },
        )
    }
    state.archive?.let { archive ->
        ComposeAlertDialog(
            onDismissRequest = viewModel::dismissArchive,
            title = { Text(stringResource(R.string.start_read)) },
            text = {
                if (archive.confirmName != null) Text(stringResource(R.string.no_book_found_bookshelf))
                else LazyColumn { items(archive.names) { name ->
                    TextButton(enabled = !state.working, onClick = { viewModel.chooseArchive(name) }) { Text(name) }
                } }
            },
            confirmButton = { if (archive.confirmName != null) TextButton(onClick = viewModel::confirmArchive) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissArchive) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val searchExpanded = rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val topBar = remember(viewModel) {
        movableContentOf<LocalBookImportUiState> { value ->
            LegadoTopAppBar(
                title = stringResource(R.string.local_book),
                onBack = { if (!value.working) currentOnBack() },
                searchQuery = value.query,
                onSearchQueryChange = viewModel::query,
                searchPlaceholder = stringResource(R.string.screen) + " • " + stringResource(R.string.local_book),
                searchExpandedState = searchExpanded,
                actions = {
                    IconButton(onClick = { viewModel.requestPicker() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder_open),
                            contentDescription = stringResource(R.string.select_folder),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_sort),
                                contentDescription = stringResource(R.string.sort),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        LegadoDropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.sort_by_name)) },
                                modifier = Modifier.semantics {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(value.sort == 0)
                                },
                                trailingIcon = {
                                    Checkbox(checked = value.sort == 0, onCheckedChange = null)
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    viewModel.sort(0)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.sort_by_size)) },
                                modifier = Modifier.semantics {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(value.sort == 1)
                                },
                                trailingIcon = {
                                    Checkbox(checked = value.sort == 1, onCheckedChange = null)
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    viewModel.sort(1)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.sort_by_time)) },
                                modifier = Modifier.semantics {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(value.sort == 2)
                                },
                                trailingIcon = {
                                    Checkbox(checked = value.sort == 2, onCheckedChange = null)
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    viewModel.sort(2)
                                },
                            )
                        }
                    }
                },
                menuActions = listOf(
                    LegadoMenuAction(
                        title = stringResource(R.string.scan_folder),
                        onClick = viewModel::scan,
                    ),
                    LegadoMenuAction(
                        title = stringResource(R.string.import_file_name),
                        onClick = { viewModel.editScript(true) },
                    ),
                    LegadoMenuAction(
                        title = stringResource(R.string.local_book_save_path),
                        onClick = { viewModel.showSaveDirectory(true) },
                    ),
                ),
            )
        }
    }
    val selectionActions = remember(viewModel) {
        movableContentOf<LocalBookImportUiState> { value ->
            LegadoSelectActionBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).testTag("localImportSelectionActions"),
                selectedCount = value.entries.count { !it.isDir && !it.isOnBookShelf && it.uri in value.selected },
                totalCount = value.entries.count { !it.isDir && !it.isOnBookShelf },
                onSelectAllChange = viewModel::selectAll,
                onRevertSelection = viewModel::invertSelection,
                mainActionText = stringResource(R.string.add_to_bookshelf),
                onMainActionClick = viewModel::requestImport,
                menuActions = listOf(
                    LegadoMenuAction(
                        title = stringResource(R.string.delete),
                        onClick = viewModel::deleteSelected,
                    ),
                ),
            )
        }
    }
    val directoryHeader: @Composable () -> Unit = {
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
    }
    val checkableCount = state.entries.count { !it.isDir && !it.isOnBookShelf }
    val selectedCount = state.entries.count { !it.isDir && !it.isOnBookShelf && it.uri in state.selected }
    val entryRow: @Composable (LocalImportEntry, Boolean) -> Unit = { item, showQuickActions ->
        val isSelected = item.uri in state.selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .testTag("localImportEntry_${item.name}")
                .clickable(enabled = !state.working) {
                    if (item.isDir) {
                        viewModel.enter(item)
                    } else if (!item.isOnBookShelf) {
                        viewModel.toggle(item)
                    } else {
                        viewModel.open(item)
                    }
                }
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
                    text = item.name,
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
                            text = item.name.substringAfterLast("."),
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
                            text = AppConst.dateFormat.format(item.lastModified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (showQuickActions && isSelected && !item.isDir && !item.isOnBookShelf) {
                LocalImportRowActions(selectedCount, checkableCount, state.working, viewModel, item.name)
            }
        }
        HorizontalDivider()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // At large font sizes several fixed bars can leave no file row. Keep
        // every command reachable by scrolling the whole short-window page.
        val compact = maxHeight < 240.dp + 120.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { if (!compact) topBar(state) },
            bottomBar = { if (!compact) selectionActions(state) },
        ) { innerPadding ->
            if (compact) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    item(key = "import-heading") { topBar(state) }
                    item(key = "import-directory") { directoryHeader() }
                    items(state.entries, key = { it.uri }) { entryRow(it, true) }
                    if (state.entries.isEmpty() && !state.loading) {
                        item(key = "import-empty") {
                            Text(stringResource(R.string.empty_msg_import_book),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                    item(key = "import-actions") { selectionActions(state) }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    directoryHeader()
                    Box(Modifier.fillMaxSize()) {
                        if (state.entries.isEmpty() && !state.loading) {
                            Text(stringResource(R.string.empty_msg_import_book),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp))
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(state.entries, key = { it.uri }) { entryRow(it, false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep batch commands beside the selection when fixed bars would starve the file list. */
@Composable
private fun LocalImportRowActions(count: Int, total: Int, working: Boolean, viewModel: ImportBookViewModel, fileName: String) {
    var expanded by remember(fileName) { mutableStateOf(false) }
    val allSelected = total > 0 && count == total
    Box {
        IconButton(enabled = !working, onClick = { expanded = true },
            modifier = Modifier.testTag("localImportRowActions_$fileName")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_menu))
        }
        LegadoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.select_all_count, count, total)) },
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(allSelected)
                },
                trailingIcon = { Checkbox(checked = allSelected, onCheckedChange = null) },
                enabled = !working,
                onClick = { expanded = false; viewModel.selectAll(!allSelected) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.revert_selection)) },
                enabled = !working,
                onClick = { expanded = false; viewModel.invertSelection() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_to_bookshelf)) },
                enabled = !working,
                onClick = { expanded = false; viewModel.requestImport() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                enabled = !working,
                onClick = { expanded = false; viewModel.deleteSelected() },
            )
        }
    }
}
