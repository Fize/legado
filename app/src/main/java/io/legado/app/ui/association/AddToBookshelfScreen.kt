package io.legado.app.ui.association

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Book

@Composable
fun AddToBookshelfScreen(
    bookUrl: String,
    onBookLoaded: (Book) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AddToBookshelfViewModel = viewModel(key = "add-to-bookshelf:$bookUrl"),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(bookUrl) { viewModel.load(bookUrl) }
    LaunchedEffect(state.completed, state.book) {
        if (state.completed) {
            state.book?.let(onBookLoaded) ?: onDismiss()
        }
    }

    AlertDialog(
        modifier = Modifier.testTag("addToBookshelfDialog"),
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text(stringResource(R.string.add_to_bookshelf)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.size(48.dp))
                    state.error != null -> Text(state.error.orEmpty())
                    else -> Text(state.book?.name.orEmpty())
                }
            }
        },
        confirmButton = {
            if (state.error != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("addToBookshelfClose")) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!state.loading && state.error == null) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("addToBookshelfCancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
