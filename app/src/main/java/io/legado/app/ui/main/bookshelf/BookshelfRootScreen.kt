package io.legado.app.ui.main.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.readText
import io.legado.app.utils.resolveBookshelfContentColumns
import io.legado.app.utils.sendToClip

@Composable
fun BookshelfRootScreen(
    onMenuAction: (BookshelfMenuAction, BookshelfRootUiState) -> Unit,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    bookshelfViewModel: BookshelfRootViewModel = viewModel(),
    actionsViewModel: BookshelfViewModel = viewModel(),
) {
    val uiState by bookshelfViewModel.uiState.collectAsStateWithLifecycle()
    val addBookProgress by actionsViewModel.addBookProgress.collectAsStateWithLifecycle()
    val fontScale = LocalDensity.current.fontScale
    val shelfScrollState = rememberLazyGridState()
    val context = LocalContext.current
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
                .pointerInput(uiState.groupId, uiState.groups) {
                    detectHorizontalDragGestures(
                        onDragStart = { totalHorizontalDrag = 0f },
                        onHorizontalDrag = { _, amount -> totalHorizontalDrag += amount },
                        onDragEnd = {
                            when {
                                totalHorizontalDrag > 96f -> bookshelfViewModel.switchGroup(-1)
                                totalHorizontalDrag < -96f -> bookshelfViewModel.switchGroup(1)
                            }
                            totalHorizontalDrag = 0f
                        },
                        onDragCancel = { totalHorizontalDrag = 0f },
                    )
                },
        ) {
            BookshelfTopBar(
                title = uiState.currentGroup?.let {
                    stringResource(R.string.bookshelf) + "(" + it.groupName + ")"
                } ?: stringResource(R.string.bookshelf),
                onMenuAction = ::handleMenuAction,
                onBackClick = if (uiState.groupId == BookGroup.IdRoot) null else {
                    { bookshelfViewModel.goBack() }
                },
            )

            BookshelfHeaderSection(
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.bookshelf_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spanCount),
                    state = shelfScrollState,
                    contentPadding = PaddingValues(8.dp + spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxSize().testTag("bookshelf_items"),
                ) {
                    items(uiState.items, key = ::bookshelfItemKey) { item ->
                        BookshelfRootItem(
                            item = item,
                            uiState = uiState,
                            onBookClick = onBookClick,
                            onBookLongClick = onBookLongClick,
                            onGroupClick = bookshelfViewModel::openGroup,
                            onGroupLongClick = onGroupLongClick,
                        )
                    }
                }

            }
        }
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
private fun BookshelfRootItem(
    item: Any,
    uiState: BookshelfRootUiState,
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
            if (uiState.layout < 2) {
                BookshelfBookListItem(
                    book = item,
                    compact = uiState.layout == 1,
                    isUpdating = false,
                    showUnread = uiState.showUnread,
                    showLastUpdateTime = uiState.showLastUpdateTime,
                    timeAgoVersion = 0,
                    onClick = { onBookClick(item) },
                    onLongClick = { onBookLongClick(item) },
                )
            } else {
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
