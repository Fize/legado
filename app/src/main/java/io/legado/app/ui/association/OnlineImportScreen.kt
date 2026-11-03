package io.legado.app.ui.association

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.ui.navigation.OnlineImportRoute

/** Handles exported legado:// and yuedu:// import links inside the app shell. */
@Composable
fun OnlineImportScreen(
    route: OnlineImportRoute,
    onExit: () -> Unit,
    onImport: (String, String) -> Unit = { _, _ -> },
    onOpenFile: (String) -> Unit = {},
    viewModel: OnLineImportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(route.uri) { viewModel.initialize(route.uri) }
    LaunchedEffect(state.destination) {
        state.destination?.let { result ->
            when (result.type) {
                "bookshelf" -> onOpenFile(result.source)
                "bookSource", "replaceRule", "highlightRule", "txtRule", "dictRule", "httpTts", "addToBookshelf" ->
                    onImport(result.type, result.source)
                else -> onExit()
            }
            viewModel.consumeDestination()
        }
    }
    LaunchedEffect(state.finished) { if (state.finished) onExit() }
    TranslucenceLoadingScreen(isLoading = state.loading || state.importing)
    if (state.confirmReadConfig) {
        AlertDialog(
            modifier = Modifier.testTag("onlineReadConfigConfirmation"),
            onDismissRequest = viewModel::cancelReadConfigImport,
            title = { Text(stringResource(R.string.import_str)) },
            text = { Text(stringResource(R.string.confirm_read_config_import)) },
            confirmButton = {
                TextButton(onClick = viewModel::importReadConfig,
                    modifier = Modifier.testTag("onlineReadConfigConfirm")) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelReadConfigImport,
                    modifier = Modifier.testTag("onlineReadConfigCancel")) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (state.errorMessage != null || state.importComplete) {
        AlertDialog(
            modifier = Modifier.testTag("onlineImportResult"),
            onDismissRequest = viewModel::dismissResult,
            title = { Text(stringResource(if (state.importComplete) R.string.success else R.string.error)) },
            text = { Text(state.errorMessage ?: stringResource(R.string.read_config_import_success)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissResult,
                    modifier = Modifier.testTag("onlineImportResultConfirm")) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
