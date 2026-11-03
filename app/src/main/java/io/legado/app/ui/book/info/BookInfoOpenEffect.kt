package io.legado.app.ui.book.info

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import io.legado.app.R
import io.legado.app.data.repository.BookInfoOpenRequest

/** Navigation is acknowledged only by the currently resumed originating entry. */
@Composable
internal fun BookInfoOpenEffect(model: BookInfoOpenViewModel, isCurrent: Boolean, knownDetails: Set<String>,
    onOpen: (BookInfoOpenRequest) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(state.pending, isCurrent, knownDetails, lifecycle) {
        val request = state.pending ?: return@LaunchedEffect
        if (request.id in knownDetails) model.consume(request.id)
        else if (isCurrent) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            currentOpen(request)
            model.consume(request.id)
        }
    }
    if (isCurrent && state.error != null) AlertDialog(
        onDismissRequest = model::dismissError,
        text = { Text(state.error.orEmpty()) },
        confirmButton = {
            if (model.canRetry) TextButton(onClick = model::retry) { Text(stringResource(R.string.retry)) }
            else TextButton(onClick = model::dismissError) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { if (model.canRetry) TextButton(onClick = model::dismissError) { Text(stringResource(R.string.cancel)) } },
    )
}
