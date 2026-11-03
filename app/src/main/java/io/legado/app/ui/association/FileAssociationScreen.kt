package io.legado.app.ui.association

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.FileAssociationAction
import io.legado.app.data.repository.FileAssociationRequest
import io.legado.app.data.repository.FileAssociationResult
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.navigation.*
import io.legado.app.utils.toastOnUi

@Composable
fun FileAssociationScreen(
    route: FileAssociationRoute,
    initialRequest: FileAssociationRequest?,
    onReplace: (AppRoute) -> Unit,
    onOpenBook: (Book) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FileAssociationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val localBookTreeSelect = rememberLauncherForActivityResult(HandleFileContract()) {
        viewModel.selectLocalBookDirectory(it.uri, persist = it.uri != null)
    }
    LaunchedEffect(route.requestId) {
        viewModel.initialize(route.requestId, initialRequest ?: route.sourceUri?.let {
            FileAssociationRequest(FileAssociationAction.View, sourceUri = it)
        })
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.pendingRequest) {
        if (state.pendingRequest == null) return@LaunchedEffect
        if (state.storagePermissionRequired) {
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(R.string.tip_perm_request_storage)
                .onGranted { viewModel.processPendingRequest() }
                .onDenied { viewModel.reportError("请求存储权限失败。") }
                .request()
        } else viewModel.processPendingRequest()
    }
    LaunchedEffect(state.result) {
        state.result?.let { result ->
            if (result !is FileAssociationResult.Unsupported) {
                handleFileAssociationResult(result, onReplace, onOpenBook, onDismiss)
                viewModel.consumeResult()
            }
        }
    }
    LaunchedEffect(state.copiedBooks) {
        state.copiedBooks?.let { (copied, failed) ->
            context.toastOnUi(resources.getString(R.string.shared_local_books_copied, copied, failed))
            viewModel.consumeCopiedBooks()
        }
    }
    LaunchedEffect(state.finished) { if (state.finished) onDismiss() }
    TranslucenceLoadingScreen(isLoading = state.loading)
    state.localBooks?.let { books ->
        Dialog(
            onDismissRequest = { if (!state.importingBooks && !state.directoryRequested) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LocalBookImportDialog(
                viewModel = viewModel,
                items = books,
                importing = state.importingBooks,
                choosingDestination = state.directoryRequested,
                onDismiss = onDismiss,
            )
        }
    }
    if (state.mixedTypes) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.wrong_format)) },
            text = { Text(stringResource(R.string.shared_local_books_mixed_types)) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("sharedMixedTypesConfirm"),
                ) { Text(stringResource(R.string.confirm)) }
            },
            modifier = Modifier.testTag("sharedMixedTypesDialog"),
        )
    }
    if (state.folderChoice) {
        AlertDialog(
            onDismissRequest = {
                viewModel.selectLocalBookDirectory(null)
            },
            title = { Text(stringResource(R.string.select_book_folder)) },
            text = {
                Text(
                    stringResource(R.string.shared_local_books_storage) +
                        if (viewModel.importAfterDirectorySelection) ""
                        else "\n\n${state.directoryDescription}"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.choosingLocalBookDirectory = true
                        localBookTreeSelect.launch {
                            title = resources.getString(R.string.select_book_folder)
                            mode = HandleFileContract.DIR_SYS
                        }
                    },
                    modifier = Modifier.testTag("sharedLocalBookFolderSelect"),
                ) { Text(stringResource(R.string.select_folder)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.selectLocalBookDirectory(null)
                        },
                        modifier = Modifier.testTag("sharedLocalBookFolderCancel"),
                    ) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            viewModel.usePrivateBookDirectory()
                        },
                        modifier = Modifier.testTag("sharedLocalBookFolderPrivate"),
                    ) { Text(stringResource(R.string.shared_local_books_private)) }
                }
            },
            modifier = Modifier.testTag("sharedLocalBookFolderDialog"),
        )
    }
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("sharedImportErrorConfirm"),
                ) { Text(stringResource(R.string.confirm)) }
            },
            modifier = Modifier.testTag("sharedImportErrorDialog"),
        )
    }
    state.confirmation?.let { (type, source) ->
        ImportDataConfirmationDialog(
            type = type,
            source = source,
            importing = state.importingData,
            onConfirm = { viewModel.importData(type, source) },
            onDismiss = {
                onDismiss()
            },
        )
    }
    (state.result as? FileAssociationResult.Unsupported)?.let { unsupported ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.file_not_supported, unsupported.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.importBook(unsupported.uri) }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.no)) } },
        )
    }
}

private fun handleFileAssociationResult(
    result: FileAssociationResult,
    onReplace: (AppRoute) -> Unit,
    onOpenBook: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    when (result) {
        is FileAssociationResult.Online -> onReplace(OnlineImportRoute(result.uri))
        is FileAssociationResult.Data -> {
            val destination = if (result.type == "bookSource") BookSourceImportRoute(result.source) else {
                val kind = when (result.type) {
                    "replaceRule" -> ImportKind.ReplaceRule
                    "highlightRule" -> ImportKind.HighlightRule
                    "httpTts" -> ImportKind.HttpTts
                    "txtRule" -> ImportKind.TextTocRule
                    "dictRule" -> ImportKind.DictionaryRule
                    else -> null
                }
                kind?.let { ImportRoute(result.source, it) }
            }
            if (destination != null) onReplace(destination) else onDismiss()
        }
        is FileAssociationResult.OpenBook -> { onOpenBook(result.book); onDismiss() }
        else -> Unit
    }
}
