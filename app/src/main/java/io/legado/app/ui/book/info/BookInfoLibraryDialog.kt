package io.legado.app.ui.book.info

import androidx.compose.foundation.layout.*
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
import io.legado.app.data.repository.BookInfoLibraryError

@Composable
fun BookInfoLibraryDialog(state: BookInfoLibraryUiState, controller: BookInfoLibraryController, cancelDeletion: (() -> Unit)? = null,
    cancelUpload: (() -> Unit)? = null) {
    val operation = state.operation ?: return
    val cancelWork = when (state.phase) {
        BookInfoLibraryPhase.Removing -> cancelDeletion
        BookInfoLibraryPhase.Uploading -> cancelUpload
        else -> null
    }
    AlertDialog(onDismissRequest = controller::dismiss,
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        modifier = Modifier.testTag("library_action_dialog"),
        title = { Text(stringResource(if (operation == BookInfoLibraryOperation.Upload) R.string.upload_to_remote else R.string.remove_from_bookshelf)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("library_action_progress"))
                    Text(stringResource(R.string.loading))
                }
                when (state.phase) {
                    BookInfoLibraryPhase.ConfirmOverwrite -> Text(stringResource(R.string.webdav_book_exists_confirm), Modifier.testTag("library_overwrite_message"))
                    BookInfoLibraryPhase.ConfirmDelete -> {
                        Text(stringResource(R.string.sure_del))
                        if (state.local) {
                            LibraryOption(state.deleteOriginal, R.string.delete_book_file, "library_delete_original", controller::setDeleteOriginal)
                            if (state.target != null) LibraryOption(state.deleteRemote, R.string.delete_webdav_book_file, "library_delete_remote", controller::setDeleteRemote)
                        }
                    }
                    BookInfoLibraryPhase.Error -> {
                        val message = when (state.error) {
                            BookInfoLibraryError.NotConfigured -> stringResource(R.string.webdav_not_configured)
                            BookInfoLibraryError.Changed -> stringResource(R.string.library_action_changed)
                            BookInfoLibraryError.RemoteDelete -> stringResource(R.string.delete_webdav_book_file_fail)
                            BookInfoLibraryError.RemoteUpload -> stringResource(R.string.upload_book_fail)
                            BookInfoLibraryError.Interrupted -> stringResource(R.string.book_action_interrupted)
                            else -> state.errorMessage ?: stringResource(R.string.error)
                        }
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("library_action_error"))
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                BookInfoLibraryPhase.ConfirmOverwrite -> TextButton(onClick = controller::confirmOverwrite, modifier = Modifier.testTag("library_overwrite_confirm")) { Text(stringResource(R.string.ok)) }
                BookInfoLibraryPhase.ConfirmDelete -> TextButton(onClick = controller::confirmDelete, modifier = Modifier.testTag("library_delete_confirm")) { Text(stringResource(R.string.ok)) }
                BookInfoLibraryPhase.Error -> TextButton(onClick = controller::retry, modifier = Modifier.testTag("library_action_retry")) { Text(stringResource(R.string.retry)) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = { if (cancelWork != null) cancelWork() else controller.dismiss() },
            enabled = !state.busy || cancelWork != null, modifier = Modifier.testTag("library_action_cancel")) {
            Text(stringResource(if (state.phase == BookInfoLibraryPhase.Uploading && cancelWork != null) R.string.book_upload_cancel
                else if (state.phase == BookInfoLibraryPhase.Removing && cancelWork != null) R.string.book_delete_cancel
                else if (operation == BookInfoLibraryOperation.Upload && state.read) R.string.reading else R.string.cancel))
        } })
}

@Composable
private fun LibraryOption(checked: Boolean, label: Int, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(stringResource(label))
    }
}
