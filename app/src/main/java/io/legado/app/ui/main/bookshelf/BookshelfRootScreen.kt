package io.legado.app.ui.main.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.isSelectableGroup
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manage.BookshelfManageViewModel
import io.legado.app.ui.book.manage.ShelfSelectionOperation
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.readText
import io.legado.app.utils.resolveBookshelfContentColumns
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

@Composable
fun BookshelfRootScreen(
    onMenuAction: (BookshelfMenuAction, BookshelfRootUiState) -> Unit,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    bookshelfViewModel: BookshelfRootViewModel = viewModel(),
    actionsViewModel: BookshelfViewModel = viewModel(),
    isCurrent: Boolean = true,
) {
    val uiState by bookshelfViewModel.uiState.collectAsStateWithLifecycle()
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedUrls by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var groupPicker by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var groupMask by rememberSaveable { mutableStateOf(0L) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val manageViewModel: BookshelfManageViewModel = viewModel()
    val manageState by manageViewModel.uiState.collectAsStateWithLifecycle()
    val selectionOperation by manageViewModel.selectionOperation.collectAsStateWithLifecycle()
    val selectionBusy = selectionOperation == ShelfSelectionOperation.Running
    LaunchedEffect(Unit) { manageViewModel.initialize(BookGroup.IdRoot) }
    val selectedBooks = uiState.books.filter { it.bookUrl in selectedUrls }
    fun exitSelection() { selectionMode = false; selectedUrls = emptySet() }
    fun toggleBook(book: Book) {
        if (selectionBusy) return
        selectedUrls = if (book.bookUrl in selectedUrls) selectedUrls - book.bookUrl else selectedUrls + book.bookUrl
    }
    BackHandler(enabled = isCurrent && (selectionMode || uiState.groupId != BookGroup.IdRoot)) {
        if (selectionMode) { if (!selectionBusy) exitSelection() } else bookshelfViewModel.goBack()
    }
    val addBookProgress by actionsViewModel.addBookProgress.collectAsStateWithLifecycle()
    val fontScale = LocalDensity.current.fontScale
    val shelfScrollState = rememberLazyGridState()
    val context = LocalContext.current
    LaunchedEffect(selectionOperation) {
        when (selectionOperation) {
            ShelfSelectionOperation.Succeeded -> { exitSelection(); manageViewModel.consumeSelectionOperation() }
            ShelfSelectionOperation.Failed -> { context.toastOnUi(R.string.error); manageViewModel.consumeSelectionOperation() }
            else -> Unit
        }
    }
    val resources = LocalResources.current
    val spacing = (uiState.margin / 2).coerceAtLeast(2).dp
    var showAddUrlDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf("") }
    var exportResultMessage by remember { mutableStateOf<String?>(null) }
    var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }

    val importLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        runCatching { result.uri?.readText(context) }
            .getOrNull()
            ?.let { actionsViewModel.importBookshelf(it, uiState.groupId) }
    }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            exportResultMessage = if (uri.toString().isAbsUrl()) {
                DirectLinkUpload.getSummary() + "\n\n" + uri
            } else {
                uri.toString()
            }
        }
    }

    fun handleMenuAction(action: BookshelfMenuAction) {
        when (action) {
            BookshelfMenuAction.SELECT_BOOKS -> {
                selectedUrls = emptySet()
                selectionMode = true
            }
            BookshelfMenuAction.ADD_URL -> {
                dialogText = ""
                showAddUrlDialog = true
            }
            BookshelfMenuAction.BOOKSHELF_LAYOUT -> showConfigDialog = true
            BookshelfMenuAction.EXPORT_BOOKSHELF -> {
                actionsViewModel.exportBookshelf(uiState.books) { file ->
                    exportLauncher.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "bookshelf.json",
                            file,
                            "application/json",
                        )
                    }
                }
            }
            BookshelfMenuAction.IMPORT_BOOKSHELF -> {
                dialogText = ""
                showImportDialog = true
            }
            else -> onMenuAction(action, uiState)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val spanCount = resolveBookshelfContentColumns(
            configuredLayout = uiState.layout,
            contentWidthDp = maxWidth.value,
            fontScale = fontScale,
            horizontalPaddingDp = 2 * (8f + spacing.value),
            spacingDp = spacing.value,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uiState.groupId, uiState.groups, selectionMode) {
                    detectHorizontalDragGestures(
                        onDragStart = { totalHorizontalDrag = 0f },
                        onHorizontalDrag = { _, amount -> totalHorizontalDrag += amount },
                        onDragEnd = {
                            when {
                                selectionMode -> Unit
                                totalHorizontalDrag > 96f -> bookshelfViewModel.switchGroup(-1)
                                totalHorizontalDrag < -96f -> bookshelfViewModel.switchGroup(1)
                            }
                            totalHorizontalDrag = 0f
                        },
                        onDragCancel = { totalHorizontalDrag = 0f },
                    )
                },
        ) {
            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.bookshelf_selection_count, selectedBooks.size),
                            modifier = Modifier.weight(1f).testTag("shelf-selection-count"), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = ::exitSelection, enabled = !selectionBusy, modifier = Modifier.testTag("shelf-selection-cancel")) { Text(stringResource(R.string.cancel)) }
                    }
                }
            } else BookshelfTopBar(
                title = uiState.currentGroup?.let {
                    stringResource(R.string.bookshelf) + "(" + it.groupName + ")"
                } ?: stringResource(R.string.bookshelf),
                onMenuAction = ::handleMenuAction,
                onBackClick = if (uiState.groupId == BookGroup.IdRoot) null else {
                    { bookshelfViewModel.goBack() }
                },
            )

            if (!selectionMode) BookshelfHeaderSection(
                showStats = uiState.showBookshelfStats,
                statsText = if (uiState.showBookshelfStats) {
                    resources.getString(R.string.bookshelf_stats, uiState.bookCount, uiState.readingCount)
                } else {
                    ""
                },
                continueBook = uiState.continueBook,
                onContinueClick = onBookClick,
                onContinueLongClick = onBookLongClick,
            )

            if (uiState.items.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.bookshelf_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (uiState.groupId == BookGroup.IdRoot && !selectionMode) {
                            Button(onClick = { handleMenuAction(BookshelfMenuAction.ADD_LOCAL) }) {
                                Text(stringResource(R.string.book_local))
                            }
                            TextButton(onClick = { handleMenuAction(BookshelfMenuAction.FIND_BOOKS) }) {
                                Text(stringResource(R.string.search))
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spanCount),
                    state = shelfScrollState,
                    contentPadding = PaddingValues(8.dp + spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("bookshelf_items"),
                ) {
                    items(uiState.items, key = ::bookshelfItemKey) { item ->
                        BookshelfRootItem(
                            item = item,
                            uiState = uiState,
                            selectionMode = selectionMode,
                            selected = item is Book && item.bookUrl in selectedUrls,
                            selectionEnabled = !selectionBusy,
                            onBookClick = { if (selectionMode) toggleBook(it) else onBookClick(it) },
                            onBookLongClick = { if (selectionMode) toggleBook(it) else onBookLongClick(it) },
                            onGroupClick = { if (!selectionMode) bookshelfViewModel.openGroup(it) },
                            onGroupLongClick = { if (!selectionMode) onGroupLongClick(it) },
                        )
                    }
                }

            }
            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(modifier = Modifier.fillMaxWidth().testTag("shelf-selection-add"), enabled = selectedBooks.isNotEmpty() && !selectionBusy, onClick = { groupMask = 0; groupPicker = false }) {
                            Text(stringResource(R.string.add_to_group))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(modifier = Modifier.weight(1f).testTag("shelf-selection-remove-group"), enabled = selectedBooks.isNotEmpty() && !selectionBusy, onClick = { groupMask = 0; groupPicker = true }) {
                                Text(stringResource(R.string.bookshelf_remove_from_group))
                            }
                            TextButton(modifier = Modifier.weight(1f).testTag("shelf-selection-remove-shelf"), enabled = selectedBooks.isNotEmpty() && !selectionBusy, onClick = { confirmRemove = true }) {
                                Text(stringResource(R.string.bookshelf_remove_selected))
                            }
                        }
                    }
                }
            }
        }
    }

    groupPicker?.let { remove ->
        val groups = manageState.groups.filter { it.isSelectableGroup() }
        val validMask = groupMask and groups.fold(0L) { mask, group -> mask or group.groupId }
        AlertDialog(onDismissRequest = { groupPicker = null },
            title = { Text(stringResource(if (remove) R.string.bookshelf_remove_from_group else R.string.add_to_group)) },
            text = { if (groups.isEmpty()) Text(stringResource(R.string.bookshelf_no_custom_groups)) else LazyColumn { items(groups, key = { it.groupId }) { group ->
                val checked = (groupMask and group.groupId) != 0L
                Row(Modifier.fillMaxWidth().testTag("shelf-group-choice:${group.groupId}").clickable {
                    groupMask = if (checked) groupMask and group.groupId.inv() else groupMask or group.groupId
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(group.groupName)
                }
            } } },
            confirmButton = { TextButton(modifier = Modifier.testTag("shelf-group-confirm"), enabled = validMask != 0L && selectedBooks.isNotEmpty() && !selectionBusy, onClick = {
                groupPicker = null
                manageViewModel.changeSelectedGroups(selectedBooks.map { it.bookUrl }, validMask, remove)
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { groupPicker = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.bookshelf_remove_selected)) },
            text = { Text(stringResource(R.string.bookshelf_remove_confirmation, selectedBooks.size)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("shelf-remove-confirm"), enabled = selectedBooks.isNotEmpty() && !selectionBusy, onClick = {
                confirmRemove = false
                manageViewModel.removeSelectedFromShelf(selectedBooks)
            }) { Text(stringResource(R.string.bookshelf_remove_selected)) } },
            dismissButton = { TextButton(modifier = Modifier.testTag("shelf-remove-cancel"), onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } })
    }

    if (showAddUrlDialog) {
        BookshelfTextInputDialog(
            title = stringResource(R.string.add_book_url),
            value = dialogText,
            onValueChange = { dialogText = it },
            onDismiss = { showAddUrlDialog = false },
            onConfirm = {
                actionsViewModel.addBookByUrl(dialogText, uiState.groupId)
                showAddUrlDialog = false
            },
        )
    }
    if (showImportDialog) {
        BookshelfTextInputDialog(
            title = stringResource(R.string.import_bookshelf),
            value = dialogText,
            onValueChange = { dialogText = it },
            onDismiss = { showImportDialog = false },
            onSelectFile = {
                showImportDialog = false
                importLauncher.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            onConfirm = {
                actionsViewModel.importBookshelf(dialogText, uiState.groupId)
                showImportDialog = false
            },
        )
    }
    if (showConfigDialog) {
        BookshelfConfigDialog(
            initialState = BookshelfConfigState(
                groupStyle = AppConfig.bookGroupStyle,
                readProgressMode = AppConfig.bookshelfReadProgressMode,
                showUnread = AppConfig.showUnread,
                showLastUpdateTime = AppConfig.showLastUpdateTime,
                showWaitUpBooks = AppConfig.showWaitUpCount,
                showFastScroller = AppConfig.showBookshelfFastScroller,
                showRecentReading = AppConfig.showBookshelfRecentReading,
                showBookshelfStats = AppConfig.showBookshelfStats,
                bookshelfLayout = AppConfig.bookshelfLayout,
                bookshelfSort = AppConfig.bookshelfSort,
                showBookname = AppConfig.showBookname,
                margin = AppConfig.bookshelfMargin,
            ),
            onDismiss = { showConfigDialog = false },
            onConfirm = bookshelfViewModel::applyConfig,
        )
    }
    exportResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { exportResultMessage = null },
            text = { Text(message) },
            dismissButton = {
                TextButton(onClick = {
                    context.sendToClip(message.substringAfterLast("\n\n"))
                    exportResultMessage = null
                }) { Text(stringResource(R.string.copy_text)) }
            },
            confirmButton = {
                TextButton(onClick = { exportResultMessage = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
    if (addBookProgress >= 0) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text("添加中... ($addBookProgress)") },
            confirmButton = {},
        )
    }
}

@Composable
private fun BookshelfTextInputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onSelectFile: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("url/json") },
                minLines = 3,
            )
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                onSelectFile?.let { select ->
                    TextButton(onClick = select) { Text(stringResource(R.string.select_file)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

private fun bookshelfItemKey(item: Any): String = when (item) {
    is BookshelfGroupItem -> "group_${item.group.groupId}"
    is Book -> "book_${item.name}_${item.author}_${item.bookUrl}"
    else -> item.hashCode().toString()
}

@Composable
internal fun BookshelfRootItem(
    item: Any,
    uiState: BookshelfRootUiState,
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGroupClick: (BookGroup) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
) {
    when (item) {
        is BookshelfGroupItem -> {
            if (uiState.layout < 2) {
                BookshelfGroupListItem(
                    item = item,
                    compact = uiState.layout == 1,
                    onClick = { onGroupClick(item.group) },
                    onLongClick = { onGroupLongClick(item.group) },
                )
            } else {
                BookshelfGroupGridItem(
                    item = item,
                    showBooknameMode = uiState.showBooknameMode,
                    onClick = { onGroupClick(item.group) },
                    onLongClick = { onGroupLongClick(item.group) },
                )
            }
        }
        is Book -> {
            val selectionControl: @Composable () -> Unit = {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onBookClick(item) },
                    enabled = selectionEnabled,
                    modifier = Modifier
                        .testTag("shelf-book-selection:${item.bookUrl}")
                        .semantics { contentDescription = item.name },
                )
            }
            if (uiState.layout < 2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) selectionControl()
                    BookshelfBookListItem(
                        modifier = Modifier.weight(1f),
                        book = item,
                        compact = uiState.layout == 1,
                        isUpdating = false,
                        showUnread = uiState.showUnread,
                        showLastUpdateTime = uiState.showLastUpdateTime,
                        timeAgoVersion = 0,
                        onClick = { onBookClick(item) },
                        onLongClick = { onBookLongClick(item) },
                    )
                }
            } else {
                Column {
                    if (selectionMode) selectionControl()
                    BookshelfBookGridItem(
                        book = item,
                        showBooknameMode = uiState.showBooknameMode,
                        isUpdating = false,
                        showUnread = uiState.showUnread,
                        onClick = { onBookClick(item) },
                        onLongClick = { onBookLongClick(item) },
                    )
                }
            }
        }
    }
}
