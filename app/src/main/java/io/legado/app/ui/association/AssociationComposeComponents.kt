package io.legado.app.ui.association

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
fun TranslucenceLoadingScreen(isLoading: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x80000000)),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun ImportEmptyOrErrorState(
    message: String,
    showIcon: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showIcon) {
            Icon(
                painter = painterResource(R.drawable.ic_description),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ImportSourceItemRow(
    name: String,
    checked: Boolean,
    statusText: String,
    enabled: Boolean = true,
    comment: String? = null,
    showOpen: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onOpenClick: (() -> Unit)? = null
) {
    var commentExpanded by remember(name, comment) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            if (showOpen && onOpenClick != null) {
                TextButton(
                    onClick = onOpenClick,
                    enabled = enabled
                ) {
                    Text(
                        text = stringResource(R.string.open),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!comment.isNullOrBlank()) {
            Text(
                text = comment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (commentExpanded) 39 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { commentExpanded = !commentExpanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun ImportDialogFooter(
    selectAllText: String,
    selectAllEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    okEnabled: Boolean = true,
    onSelectAllClick: () -> Unit,
    onCancelClick: () -> Unit,
    onOkClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onSelectAllClick,
            enabled = selectAllEnabled
        ) {
            Text(selectAllText)
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = onCancelClick,
            enabled = cancelEnabled
        ) {
            Text(stringResource(R.string.cancel))
        }
        TextButton(
            onClick = onOkClick,
            enabled = okEnabled
        ) {
            Text(stringResource(R.string.ok))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCustomGroupDialog(
    initialGroupName: String,
    initialIsAddGroup: Boolean,
    groups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (groupName: String?, isAddGroup: Boolean) -> Unit
) {
    var groupName by remember { mutableStateOf(initialGroupName) }
    var isAddGroup by remember { mutableStateOf(initialIsAddGroup) }
    var expanded by remember { mutableStateOf(false) }
    val filteredGroups = remember(groupName, groups) {
        if (groupName.isBlank()) groups
        else groups.filter { it.contains(groupName, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diy_edit_source_group)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.add_group),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.custom_group_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAddGroup,
                        onCheckedChange = { isAddGroup = it }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                ExposedDropdownMenuBox(
                    expanded = expanded && filteredGroups.isNotEmpty(),
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = {
                            groupName = it
                            expanded = true
                        },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        trailingIcon = {
                            if (groups.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    )
                    if (filteredGroups.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            filteredGroups.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        groupName = item
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(groupName, isAddGroup) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
