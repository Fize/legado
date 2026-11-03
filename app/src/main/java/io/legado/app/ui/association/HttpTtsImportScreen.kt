package io.legado.app.ui.association

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.HttpTtsImportStatus
import io.legado.app.utils.GSON

@Composable
fun HttpTtsImportScreen(
    source: String,
    onDismiss: () -> Unit,
    viewModel: ImportHttpTtsViewModel = viewModel(key = "http-tts-import:$source"),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingJson by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source) { viewModel.load(source) }
    LaunchedEffect(state.completed) { if (state.completed) onDismiss() }

    AlertDialog(
        modifier = Modifier.testTag("httpTtsImportDialog"),
        onDismissRequest = { if (!state.importing) onDismiss() },
        title = { Text(stringResource(R.string.import_tts)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    state.loading || state.importing -> CircularProgressIndicator()
                    state.items.isEmpty() -> Text(
                        state.error ?: stringResource(R.string.wrong_format),
                    )
                    else -> {
                        state.error?.let { Text(it) }
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            itemsIndexed(state.items) { index, item ->
                                val status = when (item.status) {
                                    HttpTtsImportStatus.NEW -> R.string.import_status_new
                                    HttpTtsImportStatus.UPDATE -> R.string.import_status_update
                                    HttpTtsImportStatus.EXISTING -> R.string.import_status_exist
                                }
                                ImportSourceItemRow(
                                    name = item.source.name,
                                    checked = item.selected,
                                    statusText = stringResource(status),
                                    showOpen = true,
                                    testTag = "httpTtsImportItem:$index",
                                    onCheckedChange = { viewModel.toggle(index, it) },
                                    onOpenClick = {
                                        editingIndex = index
                                        editingJson = GSON.toJson(item.source)
                                        editError = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { viewModel.selectAll(!state.allSelected) },
                    enabled = !state.loading && !state.importing && state.items.isNotEmpty(),
                ) {
                    Text(stringResource(if (state.allSelected) R.string.un_select_all else R.string.select_all))
                }
                TextButton(
                    onClick = viewModel::importSelected,
                    enabled = state.selectedCount > 0 && !state.importing,
                    modifier = Modifier.testTag("httpTtsImportConfirm"),
                ) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.importing,
                modifier = Modifier.testTag("httpTtsImportCancel"),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )

    editingIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text(stringResource(R.string.edit)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editingJson,
                        onValueChange = { editingJson = it; editError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 480.dp),
                        label = { Text(stringResource(R.string.json_format)) },
                    )
                    editError?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    HttpTTS.fromJson(editingJson).onSuccess { updated ->
                        viewModel.replaceSource(index, updated)
                        editingIndex = null
                    }.onFailure { error -> editError = error.localizedMessage ?: "Invalid JSON" }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
