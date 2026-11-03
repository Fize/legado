package io.legado.app.ui.replace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.association.ImportCustomGroupDialog
import io.legado.app.utils.GSON

@Composable
fun ReplaceRuleImportDialog(
    source: String,
    onDismiss: () -> Unit,
    viewModel: ReplaceRuleImportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editingJson by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(source) { viewModel.load(source) }
    LaunchedEffect(state.completed) { if (state.completed) onDismiss() }

    AlertDialog(
        modifier = Modifier.testTag("replaceRuleImportDialog"),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.import_replace_rule), modifier = Modifier.weight(1f))
                TextButton(onClick = { showGroupDialog = true }) {
                    val groupName = state.groupName?.takeIf { it.isNotBlank() }
                    val label = if (groupName == null) stringResource(R.string.diy_source_group)
                    else stringResource(R.string.diy_edit_source_group_title, groupName)
                    Text(if (state.addGroup && groupName != null) "+$label" else label)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    state.loading || state.importing -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                    state.rules.isEmpty() -> Text(
                        state.error ?: stringResource(R.string.wrong_format),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        Text("${state.selectedCount}/${state.rules.size}")
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            itemsIndexed(state.rules, key = { index, rule -> "$index:${rule.id}" }) { index, rule ->
                                val existing = state.existingRules.getOrNull(index)
                                val status = when {
                                    existing == null -> R.string.import_status_new
                                    rule.pattern != existing.pattern
                                        || rule.replacement != existing.replacement
                                        || rule.isRegex != existing.isRegex
                                        || rule.scope != existing.scope -> R.string.import_status_update
                                    else -> R.string.import_status_exist
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(index in state.selectedIndexes, { viewModel.toggle(index, it) })
                                    Column(Modifier.weight(1f)) {
                                        Text(rule.getDisplayNameGroup(), maxLines = 1)
                                        Text(
                                            stringResource(status),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconButton({
                                        editingIndex = index
                                        editingJson = GSON.toJson(rule)
                                        editError = null
                                    }) {
                                        Icon(painterResource(R.drawable.ic_code), stringResource(R.string.edit))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { viewModel.selectAll(!state.isSelectAll) },
                    enabled = !state.loading && !state.importing && state.rules.isNotEmpty(),
                ) { Text(stringResource(if (state.isSelectAll) R.string.un_select_all else R.string.select_all)) }
                TextButton(
                    viewModel::importSelected,
                    enabled = state.selectedCount > 0 && !state.importing,
                    modifier = Modifier.testTag("replaceRuleImportConfirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.importing) { Text(stringResource(R.string.cancel)) } },
    )

    if (showGroupDialog) {
        ImportCustomGroupDialog(
            initialGroupName = state.groupName.orEmpty(),
            initialIsAddGroup = state.addGroup,
            groups = state.groups,
            onDismiss = { showGroupDialog = false },
            onConfirm = { groupName, add ->
                viewModel.setGroup(groupName, add)
                showGroupDialog = false
            },
        )
    }

    if (editingIndex >= 0) {
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            title = { Text(stringResource(R.string.edit)) },
            text = {
                Column {
                    OutlinedTextField(
                        editingJson,
                        { editingJson = it; editError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp),
                        label = { Text(stringResource(R.string.json_format)) },
                    )
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton({
                    runCatching { GSON.fromJson(editingJson, ReplaceRule::class.java) }
                        .onSuccess { rule ->
                            if (rule == null || rule.pattern.isBlank()) editError = "替换规则不能为空"
                            else { viewModel.replaceRule(editingIndex, rule); editingIndex = -1 }
                        }
                        .onFailure { editError = it.localizedMessage ?: "JSON 格式错误" }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton({ editingIndex = -1 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
