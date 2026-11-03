package io.legado.app.ui.book.manage

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.contains
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.filterBooksForTocUpdate
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import java.util.Collections

private enum class GroupEditAction { Move, Add, Remove, Item }
private enum class ManageCoverRestoreAction { Network, Source }

@Composable
fun BookshelfManageDestination(
    initialGroupId: Long,
    onBack: () -> Unit,
    onManageGroups: () -> Unit,
    onOpenBookInfo: (String) -> Unit,
    viewModel: BookshelfManageViewModel = viewModel(key = "bookshelf-manage:$initialGroupId"),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedUrls by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var openBookInfoByTitle by rememberSaveable { mutableStateOf(AppConfig.openBookInfoByClickTitle) }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    var groupAction by remember { mutableStateOf<GroupEditAction?>(null) }
    var groupMask by remember { mutableStateOf(0L) }
    var groupActionBook by remember { mutableStateOf<Book?>(null) }
    var sourcePicker by remember { mutableStateOf(false) }
    var sourceSearch by rememberSaveable { mutableStateOf("") }
    var deleteBooks by remember { mutableStateOf<List<Book>?>(null) }
    var deleteOriginal by rememberSaveable { mutableStateOf(LocalConfig.deleteBookOriginal) }
    var confirmCoverRestore by remember { mutableStateOf<ManageCoverRestoreAction?>(null) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { exportResult = it.toString() }
    }

    LaunchedEffect(initialGroupId) { viewModel.initialize(initialGroupId) }

    val filteredBooks = remember(state.books, searchQuery) {
        if (searchQuery.isBlank()) state.books else state.books.filter { it.contains(searchQuery) }
    }
    val selectedBooks = remember(filteredBooks, selectedUrls) {
        filteredBooks.filter { it.bookUrl in selectedUrls }
    }

    fun showGroupPicker(action: GroupEditAction, initial: Long = 0L, book: Book? = null) {
        groupAction = action
        groupMask = initial
        groupActionBook = book
    }

    fun updateBooksToc() {
        val books = filterBooksForTocUpdate(selectedBooks)
        if (books.isEmpty()) context.toastOnUi(R.string.no_book_can_update)
        else {
            postEvent(EventBus.UP_BOOKS_TOC, books)
            context.toastOnUi(resources.getString(R.string.update_book_info_toc_submitted, books.size))
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.bookshelf_management),
                subtitle = state.groupName,
                onBack = onBack,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                searchPlaceholder = stringResource(R.string.screen) + " • " + state.groupName,
                actions = {
                    Box {
                        IconButton(onClick = { groupMenuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_groups),
                                contentDescription = stringResource(R.string.group),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(groupMenuExpanded, { groupMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_manage)) },
                                onClick = {
                                    groupMenuExpanded = false
                                    onManageGroups()
                                },
                            )
                            HorizontalDivider()
                            state.groups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.groupName) },
                                    onClick = {
                                        groupMenuExpanded = false
                                        selectedUrls = emptySet()
                                        viewModel.selectGroup(group.groupId)
                                    },
                                )
                            }
                        }
                    }
                },
                menuActions = listOf(
                    LegadoMenuAction(
                        title = stringResource(R.string.export_all_use_book_source),
                        onClick = {
                            viewModel.saveAllUseBookSourceToFile { file ->
                                exportLauncher.launch {
                                    mode = HandleFileContract.EXPORT
                                    fileData = HandleFileContract.FileData(
                                        "bookSource.json", file, "application/json",
                                    )
                                }
                            }
                        },
                    ),
                    LegadoMenuAction(
                        title = stringResource(R.string.open_book_info_by_click_title),
                        checked = openBookInfoByTitle,
                        onClick = {
                            openBookInfoByTitle = !openBookInfoByTitle
                            AppConfig.openBookInfoByClickTitle = openBookInfoByTitle
                        },
                    ),
                ),
            )
        },
        bottomBar = {
            LegadoSelectActionBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                selectedCount = selectedBooks.size,
                totalCount = filteredBooks.size,
                onSelectAllChange = { all ->
                    selectedUrls = if (all) filteredBooks.mapTo(linkedSetOf()) { it.bookUrl } else emptySet()
                },
                onRevertSelection = {
                    selectedUrls = filteredBooks.mapNotNullTo(linkedSetOf()) {
                        if (it.bookUrl in selectedUrls) null else it.bookUrl
                    }
                },
                mainActionText = stringResource(R.string.move_to_group),
                onMainActionClick = { showGroupPicker(GroupEditAction.Move) },
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.delete)) {
                        deleteOriginal = LocalConfig.deleteBookOriginal
                        deleteBooks = selectedBooks
                    },
                    LegadoMenuAction(stringResource(R.string.allow_update)) { viewModel.upCanUpdate(selectedBooks, true) },
                    LegadoMenuAction(stringResource(R.string.disable_update)) { viewModel.upCanUpdate(selectedBooks, false) },
                    LegadoMenuAction(stringResource(R.string.add_to_group)) { showGroupPicker(GroupEditAction.Add) },
                    LegadoMenuAction(stringResource(R.string.remove_group)) { showGroupPicker(GroupEditAction.Remove) },
                    LegadoMenuAction(stringResource(R.string.change_source_batch)) {
                        sourcePicker = true
                        sourceSearch = ""
                        viewModel.searchSources("")
                    },
                    LegadoMenuAction(stringResource(R.string.clear_cache)) { viewModel.clearCache(selectedBooks) },
                    LegadoMenuAction(stringResource(R.string.persist_network_covers)) { viewModel.persistNetworkCovers(selectedBooks) },
                    LegadoMenuAction(stringResource(R.string.restore_network_covers)) { confirmCoverRestore = ManageCoverRestoreAction.Network },
                    LegadoMenuAction(stringResource(R.string.restore_source_covers)) { confirmCoverRestore = ManageCoverRestoreAction.Source },
                    LegadoMenuAction(stringResource(R.string.check_selected_interval)) {
                        selectedUrls = extendBookSelectionToInterval(filteredBooks, selectedUrls)
                    },
                    LegadoMenuAction(stringResource(R.string.update_book_info_toc), onClick = ::updateBooksToc),
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(filteredBooks, key = { it.bookUrl }) { book ->
                val isSelected = book.bookUrl in selectedUrls
                val groupsText = state.groups.filter { (it.groupId > 0 || it.groupId == Long.MIN_VALUE) && (it.groupId and book.group) != 0L }
                    .joinToString(",") { it.groupName }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedUrls = if (isSelected) selectedUrls - book.bookUrl else selectedUrls + book.bookUrl
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(isSelected, onCheckedChange = { checked ->
                            selectedUrls = if (checked) selectedUrls + book.bookUrl else selectedUrls - book.bookUrl
                        })
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    book.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("bookshelf-manage-title:${book.bookUrl}").then(
                                        if (openBookInfoByTitle) Modifier.clickable { onOpenBookInfo(book.bookUrl) } else Modifier,
                                    ),
                                )
                                if (book.author.isNotEmpty()) Text(
                                    book.author,
                                    Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(if (book.isLocal) stringResource(R.string.local_book) else book.originName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(groupsText.ifEmpty { stringResource(R.string.no_group) },
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { showGroupPicker(GroupEditAction.Item, book.group, book) }) {
                                    Text(stringResource(R.string.group))
                                }
                                TextButton(onClick = {
                                    deleteOriginal = LocalConfig.deleteBookOriginal
                                    deleteBooks = listOf(book)
                                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    groupAction?.let { action ->
        AlertDialog(
            onDismissRequest = { groupAction = null },
            title = { Text(stringResource(R.string.group_select)) },
            text = {
                LazyColumn {
                    items(state.groups, key = { it.groupId }) { group ->
                        val checked = (groupMask and group.groupId) != 0L
                        Row(Modifier.fillMaxWidth().clickable {
                            groupMask = if (checked) groupMask and group.groupId.inv() else groupMask or group.groupId
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = { value ->
                                groupMask = if (value) groupMask or group.groupId else groupMask and group.groupId.inv()
                            })
                            Text(group.groupName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val books = if (action == GroupEditAction.Item) listOfNotNull(groupActionBook) else selectedBooks
                    when (action) {
                        GroupEditAction.Move, GroupEditAction.Item -> viewModel.assignGroup(books, groupMask)
                        GroupEditAction.Add -> viewModel.assignGroup(books, groupMask, add = true)
                        GroupEditAction.Remove -> viewModel.assignGroup(books, groupMask, remove = true)
                    }
                    groupAction = null
                    groupActionBook = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { groupAction = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (sourcePicker) {
        AlertDialog(
            onDismissRequest = { sourcePicker = false },
            title = { Text(stringResource(R.string.change_source_batch)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = sourceSearch,
                        onValueChange = { sourceSearch = it; viewModel.searchSources(it) },
                        label = { Text(stringResource(R.string.search_book_source)) },
                        singleLine = true,
                    )
                    LazyColumn(Modifier.fillMaxWidth().sizeIn(maxHeight = 420.dp)) {
                        items(state.sources, key = { it.bookSourceUrl }) { source ->
                            Text(
                                source.getDisPlayNameGroup(),
                                Modifier.fillMaxWidth().clickable {
                                    sourcePicker = false
                                    viewModel.changeSourceByUrl(selectedBooks, source.bookSourceUrl)
                                }.padding(12.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sourcePicker = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteBooks?.let { books ->
        AlertDialog(
            onDismissRequest = { deleteBooks = null },
            title = { Text(stringResource(R.string.draw)) },
            text = {
                Column {
                    Text(stringResource(R.string.sure_del))
                    if (books.any(Book::isLocal)) Row(Modifier.fillMaxWidth().clickable { deleteOriginal = !deleteOriginal }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteOriginal, onCheckedChange = { deleteOriginal = it })
                        Text(stringResource(R.string.delete_book_file))
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                deleteBooks = null
                LocalConfig.deleteBookOriginal = deleteOriginal
                viewModel.deleteBook(books, deleteOriginal)
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { deleteBooks = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmCoverRestore?.let { action ->
        val title = if (action == ManageCoverRestoreAction.Network) R.string.restore_network_covers else R.string.restore_source_covers
        val message = if (action == ManageCoverRestoreAction.Network) R.string.restore_network_covers_confirm else R.string.restore_source_covers_confirm
        AlertDialog(
            onDismissRequest = { confirmCoverRestore = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = {
                confirmCoverRestore = null
                if (action == ManageCoverRestoreAction.Network) viewModel.restoreNetworkCovers(selectedBooks)
                else viewModel.restoreSourceCovers(selectedBooks)
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { confirmCoverRestore = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (state.batchChangeSource || state.batchPersistCover) {
        val message = if (state.batchChangeSource) {
            stringResource(R.string.change_source_batch) + " " + state.batchChangeSourceProgress
        } else if (state.batchPersistCoverProgress.isNotBlank()) {
            state.batchPersistCoverProgress
        } else stringResource(R.string.persist_network_covers)
        AlertDialog(
            onDismissRequest = viewModel::cancelBatchOperation,
            title = { Text(message) },
            text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = viewModel::cancelBatchOperation) { Text(stringResource(R.string.cancel)) } },
        )
    }

    exportResult?.let { uri ->
        val message = if (uri.isAbsUrl()) DirectLinkUpload.getSummary() + "\n\n" + uri else uri
        AlertDialog(
            onDismissRequest = { exportResult = null },
            title = { Text(stringResource(R.string.export_success)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { context.sendToClip(uri); exportResult = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

internal fun extendBookSelectionToInterval(
    books: List<Book>,
    selectedBookUrls: Set<String>,
): Set<String> {
    val selectedPosition = books.mapIndexedNotNull { index, book ->
        index.takeIf { book.bookUrl in selectedBookUrls }
    }
    if (selectedPosition.isEmpty()) return selectedBookUrls
    val minPosition = Collections.min(selectedPosition)
    val maxPosition = Collections.max(selectedPosition)
    return selectedBookUrls + books.subList(minPosition, maxPosition + 1).map { it.bookUrl }
}
