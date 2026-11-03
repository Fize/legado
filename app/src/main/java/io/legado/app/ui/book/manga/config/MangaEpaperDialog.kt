package io.legado.app.ui.book.manga.config

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.legado.app.R

@Composable
internal fun MangaEpaperDialog(
    initialThreshold: Int,
    onThresholdChange: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
) {
    var threshold by rememberSaveable { mutableIntStateOf(initialThreshold) }
    AlertDialog(
        onDismissRequest = { onDismiss(threshold) },
        title = { Text(stringResource(R.string.manga_epaper_stting)) },
        text = {
            MangaSliderRow(
                title = stringResource(R.string.manga_epaper_value),
                value = threshold,
                onValueChange = {
                    threshold = it
                    onThresholdChange(it)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(threshold) }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
