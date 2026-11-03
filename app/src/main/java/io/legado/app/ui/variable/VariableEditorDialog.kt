package io.legado.app.ui.variable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.ui.compose.component.LegadoTopAppBar

@Composable
fun VariableEditorDialog(
    state: VariableEditorUiState,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(modifier = Modifier.imePadding(), topBar = {
                LegadoTopAppBar(title = stringResource(if (state.source) R.string.set_source_variable else R.string.set_book_variable),
                    onNavigationClick = onDismiss, actions = {
                        TextButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.testTag("variable_editor_cancel")) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = onSave, enabled = state.ready && !state.loading && !state.saving,
                            modifier = Modifier.testTag("variable_editor_save")) {
                            Text(stringResource(R.string.action_save))
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (state.loading || state.saving) CircularProgressIndicator(Modifier.testTag("variable_editor_progress"))
                    OutlinedTextField(value = state.draft, onValueChange = onDraftChange,
                        enabled = state.ready && !state.saving,
                        label = { Text(stringResource(R.string.variable_value)) },
                        modifier = Modifier.fillMaxWidth().testTag("variable_editor_input"), minLines = 4)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        if (!state.ready) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                    Spacer(Modifier.padding(8.dp))
                    Text(stringResource(R.string.variable_comment), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        val usage = stringResource(if (state.source) R.string.source_variable_usage else R.string.book_variable_usage)
                        Text(listOf(state.comment, usage).filter(String::isNotBlank).joinToString("\n"))
                    }
                }
            }
        }
    }
}
