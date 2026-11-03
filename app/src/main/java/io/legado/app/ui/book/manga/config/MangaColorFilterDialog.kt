package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable
internal fun MangaColorFilterDialog(
    initialConfig: MangaColorFilterConfig,
    onConfigChange: (MangaColorFilterConfig) -> Unit,
    onDismiss: (MangaColorFilterConfig) -> Unit,
) {
    var config by rememberSaveable(initialConfig, stateSaver = Saver(
        save = { GSON.toJson(it) },
        restore = { GSON.fromJsonObject<MangaColorFilterConfig>(it).getOrNull() ?: initialConfig.copy() },
    )) { mutableStateOf(initialConfig.copy()) }
    fun update(transform: MangaColorFilterConfig.() -> Unit) {
        config = config.copy().apply(transform)
        onConfigChange(config)
    }
    AlertDialog(
        onDismissRequest = { onDismiss(config) },
        title = { Text(stringResource(R.string.manga_color_filter)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                MangaSliderRow(stringResource(R.string.brightness), config.l) { update { l = it } }
                MangaSliderRow("R", config.r) { update { r = it } }
                MangaSliderRow("G", config.g) { update { g = it } }
                MangaSliderRow("B", config.b) { update { b = it } }
                MangaSliderRow("A", config.a) { update { a = it } }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(config) }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
internal fun MangaSliderRow(
    title: String,
    value: Int,
    max: Int = 255,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onValueChange((value - 1).coerceAtLeast(0)) }) {
                Text("-", style = MaterialTheme.typography.titleMedium)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(0, max)) },
                valueRange = 0f..max.toFloat(),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onValueChange((value + 1).coerceAtMost(max)) }) {
                Text("+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
