package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.setLayout
import kotlinx.coroutines.flow.conflate

class GroupManageDialog : BaseComposeDialogFragment() {

    private val viewModel: BookSourceViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    @Composable
    override fun Content() {
        val groups by remember {
            appDb.bookSourceDao.flowGroups().conflate()
        }.collectAsState(initial = emptyList())

        var showAddDialog by remember { mutableStateOf(false) }
        var editingGroup by remember { mutableStateOf<String?>(null) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LegadoTopAppBar(
                    title = stringResource(R.string.group_manage),
                    onBack = { dismissAllowingStateLoss() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.add_group),
                            iconRes = R.drawable.ic_add,
                            showAsAction = true,
                            onClick = { showAddDialog = true },
                        )
                    ),
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(groups, key = { it }) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { editingGroup = group }) {
                                    Text(text = stringResource(R.string.edit))
                                }
                                TextButton(onClick = { viewModel.delGroup(group) }) {
                                    Text(
                                        text = stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (showAddDialog) {
            var groupName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text(text = stringResource(R.string.add_group)) },
                text = {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text(text = stringResource(R.string.group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (groupName.isNotBlank()) {
                                viewModel.addGroup(groupName)
                            }
                            showAddDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                },
            )
        }

        editingGroup?.let { targetGroup ->
            var groupName by remember(targetGroup) { mutableStateOf(targetGroup) }
            AlertDialog(
                onDismissRequest = { editingGroup = null },
                title = { Text(text = stringResource(R.string.group_edit)) },
                text = {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text(text = stringResource(R.string.group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.upGroup(targetGroup, groupName)
                            editingGroup = null
                        }
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingGroup = null }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}