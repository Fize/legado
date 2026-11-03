package io.legado.app.ui.book.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaEpaperDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

internal sealed interface MangaReaderDialogAction {
    data object Dismiss : MangaReaderDialogAction
    data class Footer(val config: MangaFooterConfig, val save: Boolean) : MangaReaderDialogAction
    data class ColorFilter(val config: MangaColorFilterConfig, val save: Boolean) : MangaReaderDialogAction
    data class Epaper(val threshold: Int, val save: Boolean) : MangaReaderDialogAction
    data class CloudProgress(val accept: Boolean) : MangaReaderDialogAction
    data class BrowserMode(val external: Boolean) : MangaReaderDialogAction
    data class Bookshelf(val keep: Boolean) : MangaReaderDialogAction
    data class Download(val start: Int, val end: Int) : MangaReaderDialogAction
    data class Number(val kind: MangaNumberSetting, val value: Int) : MangaReaderDialogAction
}

/** Dialogs render state and emit decisions; navigation, persistence and platform work belong to owners. */
@Composable
internal fun MangaReaderDialogs(state: MangaReaderUiState, onAction: (MangaReaderDialogAction) -> Unit) {
    val dismiss = { onAction(MangaReaderDialogAction.Dismiss) }
    when (val dialog = state.dialog) {
        MangaReaderDialog.Footer -> {
            val initial = remember {
                GSON.fromJsonObject<MangaFooterConfig>(state.footerConfigJson).getOrNull() ?: MangaFooterConfig()
            }
            MangaFooterSettingDialog(initialConfig = initial,
                onConfigChange = { onAction(MangaReaderDialogAction.Footer(it, false)) },
                onDismiss = { onAction(MangaReaderDialogAction.Footer(it, true)) })
        }
        MangaReaderDialog.ColorFilter -> {
            val initial = remember {
                GSON.fromJsonObject<MangaColorFilterConfig>(state.colorFilterJson).getOrNull() ?: MangaColorFilterConfig()
            }
            MangaColorFilterDialog(initialConfig = initial,
                onConfigChange = { onAction(MangaReaderDialogAction.ColorFilter(it, false)) },
                onDismiss = { onAction(MangaReaderDialogAction.ColorFilter(it, true)) })
        }
        MangaReaderDialog.Epaper -> MangaEpaperDialog(initialThreshold = state.eInkThreshold,
            onThresholdChange = { onAction(MangaReaderDialogAction.Epaper(it, false)) },
            onDismiss = { onAction(MangaReaderDialogAction.Epaper(it, true)) })
        MangaReaderDialog.CloudProgress -> ReaderDecisionDialog(
            title = stringResource(R.string.get_book_progress),
            message = stringResource(R.string.cloud_progress_exceeds_current),
            onDismiss = { onAction(MangaReaderDialogAction.CloudProgress(false)) },
            onDecision = { onAction(MangaReaderDialogAction.CloudProgress(it)) })
        MangaReaderDialog.BrowserOpenMode -> ReaderDecisionDialog(
            title = stringResource(R.string.open_fun), message = stringResource(R.string.use_browser_open),
            onDismiss = dismiss, onDecision = { onAction(MangaReaderDialogAction.BrowserMode(it)) })
        MangaReaderDialog.AddToBookshelf -> ReaderDecisionDialog(
            title = stringResource(R.string.add_to_bookshelf),
            message = stringResource(R.string.check_add_bookshelf, state.bookTitle),
            onDismiss = dismiss, onDecision = { onAction(MangaReaderDialogAction.Bookshelf(it)) })
        is MangaReaderDialog.NumberPicker -> MangaNumberPickerDialog(dialog, dismiss) {
            onAction(MangaReaderDialogAction.Number(dialog.kind, it))
        }
        is MangaReaderDialog.DownloadRange -> MangaDownloadRangeDialog(dialog, dismiss) { start, end ->
            onAction(MangaReaderDialogAction.Download(start, end))
        }
        MangaReaderDialog.ChangeSource, null -> Unit
    }
}

@Composable
private fun ReaderDecisionDialog(title: String, message: String, onDismiss: () -> Unit, onDecision: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDecision(true) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { onDecision(false) }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun MangaDownloadRangeDialog(request: MangaReaderDialog.DownloadRange, onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit) {
    var startText by rememberSaveable(request) { mutableStateOf(request.startChapter.toString()) }
    var endText by rememberSaveable(request) { mutableStateOf(request.endChapter.toString()) }
    val start = startText.toIntOrNull()
    val end = endText.toIntOrNull()
    val valid = start != null && end != null && start >= 1 && end >= start && end <= request.endChapter
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.offline_cache)) },
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chapter))
                OutlinedTextField(value = startText, onValueChange = { startText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.start)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Text(stringResource(R.string.to))
                OutlinedTextField(value = endText, onValueChange = { endText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.end)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { if (valid) onConfirm(start!! - 1, end!! - 1) }) {
            Text(stringResource(android.R.string.ok))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun MangaNumberPickerDialog(request: MangaReaderDialog.NumberPicker, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var valueText by rememberSaveable(request) { mutableStateOf(request.initialValue.toString()) }
    val value = valueText.toIntOrNull()?.takeIf { it in request.minimumValue..9999 }
    val title = when (request.kind) {
        MangaNumberSetting.PreDownload -> stringResource(R.string.pre_download)
        MangaNumberSetting.AutoPageSpeed -> stringResource(R.string.setting_manga_auto_page_speed)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = valueText,
            onValueChange = { valueText = it.filter(Char::isDigit).take(4) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("${request.minimumValue}–9999") }) },
        confirmButton = { TextButton(enabled = value != null, onClick = { value?.let(onConfirm) }) {
            Text(stringResource(android.R.string.ok))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
