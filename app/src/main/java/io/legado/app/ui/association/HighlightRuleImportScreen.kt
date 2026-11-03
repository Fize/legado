package io.legado.app.ui.association

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.repository.HighlightRuleImportStatus

@Composable
fun HighlightRuleImportScreen(
    source: String,
    requestId: String,
    parentManagerId: String?=null,
    onDismiss: () -> Unit,
    viewModel: ImportHighlightRuleViewModel = viewModel(key = "highlight-import:$requestId"),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(requestId) { viewModel.load(requestId,source,parentManagerId) }
    BackHandler { viewModel.cancel() }
    LaunchedEffect(state.completed) { if (state.completed) onDismiss() }

    AlertDialog(
        modifier = Modifier.testTag("highlightRuleImportDialog"),
        onDismissRequest = viewModel::cancel,
        title = { Text(stringResource(R.string.highlight_rule)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if(state.error) Text(stringResource(R.string.highlight_rule_import_failed))
                when {
                    state.loading || state.importing -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) { CircularProgressIndicator() }
                    state.items.isEmpty() -> if (!state.error) {
                        Text(stringResource(R.string.wrong_format))
                    }
                    else -> {
                        Text("${state.selectedCount}/${state.items.size}")
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(state.items, key = { it.uuid }) { item ->
                                val status = when (HighlightRuleImportStatus.valueOf(item.status)) {
                                    HighlightRuleImportStatus.NEW -> R.string.import_status_new
                                    HighlightRuleImportStatus.UPDATE -> R.string.import_status_update
                                    HighlightRuleImportStatus.EXISTING -> R.string.import_status_exist
                                }
                                ImportSourceItemRow(
                                    name = item.name,
                                    checked = item.selected,
                                    statusText = stringResource(status),
                                    showOpen = false,
                                    testTag = "highlightImportChoice:${item.uuid}",
                                    onCheckedChange = { viewModel.toggle(item.uuid, it) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if(state.error) TextButton(onClick=viewModel::retry,enabled=!state.loading && !state.importing,modifier=Modifier.testTag("highlightRuleImportRetry")) { Text(stringResource(R.string.retry)) }
                TextButton(
                    onClick = { viewModel.selectAll(!state.isSelectAll) },
                    enabled = !state.loading && !state.importing && state.items.isNotEmpty(),
                ) {
                    Text(
                        stringResource(
                            if (state.isSelectAll) R.string.select_cancel_count else R.string.select_all_count,
                            state.selectedCount,
                            state.items.size,
                        )
                    )
                }
                TextButton(
                    onClick = viewModel::importSelected,
                    enabled = state.selectedCount > 0 && !state.importing,
                    modifier = Modifier.testTag("highlightRuleImportConfirm"),
                ) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancel, enabled = !state.importing) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
