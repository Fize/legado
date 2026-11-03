package io.legado.app.ui.book.remoteimport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar

@Composable
fun RemoteServersScreen(
    onBack: () -> Unit,
    onEdit: (Long?) -> Unit,
    viewModel: RemoteServersViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.initialize() }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    Scaffold(
        topBar = { LegadoTopAppBar(title = stringResource(R.string.server_config), onBack = onBack,
            menuActions = listOf(LegadoMenuAction(title = stringResource(R.string.add), iconRes = R.drawable.ic_add,
                showAsAction = true, onClick = { onEdit(null) }))) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(modifier = Modifier.testTag("remoteServerDefault"), onClick = viewModel::useDefault) { Text(stringResource(R.string.text_default)) }
            Row {
                TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
                TextButton(modifier = Modifier.testTag("remoteServerConfirm"), onClick = { viewModel.confirm() }) { Text(stringResource(R.string.ok)) }
            }
        } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.servers, key = { it.id }) { server ->
                    Row(Modifier.fillMaxWidth().testTag("remoteServer_${server.id}").clickable { viewModel.select(server.id) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = server.id == state.selected, onClick = { viewModel.select(server.id) })
                        Text(server.name, Modifier.weight(1f).padding(horizontal = 8.dp))
                        IconButton(modifier = Modifier.testTag("remoteServerEdit_${server.id}"), onClick = { onEdit(server.id) }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.edit))
                        }
                        IconButton(modifier = Modifier.testTag("remoteServerDelete_${server.id}"), onClick = { viewModel.requestDelete(server) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    state.deleteTarget?.let { server ->
        AlertDialog(onDismissRequest = { viewModel.requestDelete(null) }, title = { Text(stringResource(R.string.sure_del)) },
            text = { Text(server.name) }, confirmButton = {
                TextButton(modifier = Modifier.testTag("remoteServerDeleteConfirm"), onClick = viewModel::confirmDelete) { Text(stringResource(R.string.ok)) }
            }, dismissButton = { TextButton(onClick = { viewModel.requestDelete(null) }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun RemoteServerEditScreen(
    serverId: Long?,
    onBack: () -> Unit,
    viewModel: RemoteServerEditViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(serverId) { viewModel.initialize(serverId) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    BackHandler(enabled = state.saving) { }
    Scaffold(
        topBar = { LegadoTopAppBar(title = stringResource(R.string.server_config), onBack = { if (!state.saving) onBack() },
            menuActions = listOf(LegadoMenuAction(title = stringResource(R.string.action_save), iconRes = R.drawable.ic_save,
                showAsAction = true, onClick = viewModel::save))) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            val draft = state.draft
            OutlinedTextField(value = draft.name, onValueChange = { viewModel.changeDraft(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.name)) }, singleLine = true, enabled = state.ready && !state.saving,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("remoteServerName"))
            OutlinedTextField(value = draft.url, onValueChange = { viewModel.changeDraft(draft.copy(url = it)) },
                label = { Text(stringResource(R.string.web_dav_url)) }, singleLine = true, enabled = state.ready && !state.saving,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("remoteServerUrl"))
            OutlinedTextField(value = draft.username, onValueChange = { viewModel.changeDraft(draft.copy(username = it)) },
                label = { Text(stringResource(R.string.web_dav_account)) }, singleLine = true, enabled = state.ready && !state.saving,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("remoteServerUsername"))
            OutlinedTextField(value = draft.password, onValueChange = { viewModel.changeDraft(draft.copy(password = it)) },
                label = { Text(stringResource(R.string.web_dav_pw)) }, singleLine = true, enabled = state.ready && !state.saving,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("remoteServerPassword"))
        }
    }
}
