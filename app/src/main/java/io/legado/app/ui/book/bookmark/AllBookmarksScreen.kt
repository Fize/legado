package io.legado.app.ui.book.bookmark

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookmarkExportFormat
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllBookmarksScreen(
    onBack: () -> Unit,
    onOpenBook: (BookmarkOpenRequest) -> Unit,
    viewModel: AllBookmarkViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val format = if (result.requestCode == 2) {
                BookmarkExportFormat.Markdown
            } else {
                BookmarkExportFormat.Json
            }
            viewModel.export(uri, format)
        }
    }
    val messageText = when (uiState.message) {
        BookmarkMessage.ExportSuccess -> stringResource(R.string.export_success)
        BookmarkMessage.ExportFailed -> stringResource(R.string.error)
        null -> null
    }

    LaunchedEffect(uiState.pendingOpen) {
        uiState.pendingOpen?.let {
            onOpenBook(it)
            viewModel.consumeOpen()
        }
    }
    LaunchedEffect(messageText) {
        messageText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.bookmark),
                onBack = onBack,
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.export)) {
                        exportLauncher.launch { requestCode = 1 }
                    },
                    LegadoMenuAction(stringResource(R.string.export_md)) {
                        exportLauncher.launch { requestCode = 2 }
                    },
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val grouped = remember(uiState.bookmarks) {
            uiState.bookmarks.withIndex().groupBy { (_, item) ->
                "${item.bookName}(${item.bookAuthor})"
            }
        }
        if (grouped.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                grouped.forEach { (headerTitle, indexedItems) ->
                    stickyHeader(key = "header_$headerTitle") {
                        Text(
                            text = headerTitle,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    itemsIndexed(
                        items = indexedItems,
                        key = { _, (globalIndex, item) -> "${item.time}_$globalIndex" },
                    ) { _, (_, item) ->
                        BookmarkCardItem(
                            bookmark = item,
                            onClick = { viewModel.open(item) },
                            onLongClick = { viewModel.edit(item) },
                        )
                    }
                }
            }
        }
    }

    uiState.editingBookmark?.let { bookmark ->
        BookmarkEditorDialog(
            bookmark = bookmark,
            onDismiss = viewModel::dismissEditor,
            onDelete = { viewModel.delete(bookmark) },
            onSave = { bookText, content -> viewModel.save(bookmark, bookText, content) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkCardItem(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(bookmark.chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bookmark.bookText.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    bookmark.bookText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (bookmark.content.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    bookmark.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BookmarkEditorDialog(
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var bookText by remember(bookmark.time) { mutableStateOf(bookmark.bookText) }
    var content by remember(bookmark.time) { mutableStateOf(bookmark.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(bookmark.chapterName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = bookText,
                    onValueChange = { bookText = it },
                    label = { Text(stringResource(R.string.content)) },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.note_content)) },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(bookText, content) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
