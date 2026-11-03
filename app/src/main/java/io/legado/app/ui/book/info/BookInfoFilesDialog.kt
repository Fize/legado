package io.legado.app.ui.book.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R

@Composable
fun BookInfoFilesDialog(
    state: BookInfoFilesUiState,
    files: List<BookInfoWebFile>,
    controller: BookInfoFilesController,
    bookReady: Boolean,
    onUploadChanged: (Boolean) -> Unit,
) {
    if (!state.open) return
    val tag = when { state.unsupported -> "web_unsupported_dialog"; state.archiveUri != null -> "web_archive_dialog"; else -> "web_files_dialog" }
    AlertDialog(onDismissRequest = controller::dismiss,
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        modifier = Modifier.testTag(tag),
        title = { Text(stringResource(if (state.archiveUri != null) R.string.import_select_book else R.string.download_and_import_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy || !bookReady) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("web_import_progress"))
                state.error?.let {
                    val message = when (it) {
                        BookInfoFileError.Interrupted -> stringResource(R.string.file_import_interrupted)
                        BookInfoFileError.EmptyArchive -> stringResource(R.string.unsupport_archivefile_entry)
                        BookInfoFileError.Folder -> stringResource(R.string.select_book_folder)
                        BookInfoFileError.Changed -> stringResource(R.string.file_import_book_changed)
                        BookInfoFileError.Storage -> state.errorMessage ?: stringResource(R.string.error)
                    }
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("web_import_error"))
                    if (!state.busy) TextButton(onClick = controller::retry, enabled = bookReady && (state.selected != null || state.archiveUri != null), modifier = Modifier.testTag("web_files_retry")) { Text(stringResource(R.string.retry)) }
                }
                if (state.unsupported) Text(stringResource(R.string.file_not_supported, state.selectedName.orEmpty()))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (state.archiveUri != null) itemsIndexed(state.entries, key = { _, name -> name }) { index, name ->
                        Text(name, Modifier.fillMaxWidth().clickable(enabled = !state.busy && bookReady) { controller.selectEntry(name) }
                            .defaultMinSize(minHeight = 48.dp).padding(vertical = 12.dp).testTag("web_archive_$index"))
                    } else itemsIndexed(files, key = { index, file -> "$index:${file.url}" }) { index, file ->
                        Text(file.name, Modifier.fillMaxWidth().clickable(enabled = !state.busy && bookReady) { controller.select(file) }
                            .defaultMinSize(minHeight = 48.dp).padding(vertical = 12.dp).testTag("web_file_$index"))
                    }
                }
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .toggleable(state.upload, enabled = !state.busy && bookReady, role = Role.Checkbox, onValueChange = onUploadChanged)
                    .testTag("web_upload"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.upload, null)
                    Text(stringResource(R.string.upload_imported_book_to_webdav))
                }
            }
        },
        confirmButton = {
            if (state.unsupported) TextButton(onClick = controller::openUnsupported, enabled = !state.busy && bookReady && state.selected != null,
                modifier = Modifier.testTag("web_open_file")) { Text(stringResource(R.string.open_fun)) }
        },
        dismissButton = { TextButton(onClick = controller::dismiss, enabled = !state.busy,
            modifier = Modifier.testTag("web_files_cancel")) { Text(stringResource(R.string.cancel)) } })
}
