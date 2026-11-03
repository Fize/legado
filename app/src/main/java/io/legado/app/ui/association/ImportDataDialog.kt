package io.legado.app.ui.association

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.utils.FileDoc

@Composable
internal fun ImportDataConfirmationDialog(
    type: String,
    source: String,
    importing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val backup = type == "backup"
    val name = remember(source) { FileDoc.fromUri(Uri.parse(source), false).name }
    val message = when {
        importing -> stringResource(R.string.importing)
        backup -> "$name\n${stringResource(R.string.restore_message)}"
        else -> name
    }
    AlertDialog(
        modifier = Modifier.testTag("sharedImportDataDialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (backup) R.string.restore_confirmation else R.string.import_bookshelf))
        },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !importing,
                modifier = Modifier.testTag("sharedImportDataConfirm"),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !importing,
                modifier = Modifier.testTag("sharedImportDataCancel"),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}
