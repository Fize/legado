package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaFooterSettingDialog(
    initialConfig: MangaFooterConfig,
    onConfigChange: (MangaFooterConfig) -> Unit,
    onDismiss: (MangaFooterConfig) -> Unit,
) {
    var config by rememberSaveable(initialConfig, stateSaver = Saver(
        save = { GSON.toJson(it) },
        restore = { GSON.fromJsonObject<MangaFooterConfig>(it).getOrNull() ?: initialConfig.copy() },
    )) { mutableStateOf(initialConfig.copy()) }
    var orientation by rememberSaveable(initialConfig) { mutableIntStateOf(initialConfig.footerOrientation) }

    fun update(transform: MangaFooterConfig.() -> Unit) {
        config = config.copy().apply(transform)
        orientation = config.footerOrientation
        onConfigChange(config)
    }

    AlertDialog(
        onDismissRequest = { onDismiss(config) },
        title = { Text(stringResource(R.string.manga_footer_config)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                FooterSection(stringResource(R.string.manga_header_chapter)) {
                    FooterCheckOption(stringResource(R.string.manga_check_chapter_label), config.hideChapterLabel) {
                        update { hideChapterLabel = it }
                    }
                    FooterCheckOption(stringResource(R.string.manga_check_chapter), config.hideChapter) {
                        update { hideChapter = it }
                    }
                    FooterCheckOption(stringResource(R.string.manga_check_chapter_name), config.hideChapterName) {
                        update { hideChapterName = it }
                    }
                }
                FooterSection(stringResource(R.string.manga_header_page)) {
                    FooterCheckOption(stringResource(R.string.manga_check_page_label), config.hidePageNumberLabel) {
                        update { hidePageNumberLabel = it }
                    }
                    FooterCheckOption(stringResource(R.string.manga_check_page_number), config.hidePageNumber) {
                        update { hidePageNumber = it }
                    }
                }
                FooterSection(stringResource(R.string.manga_header_progress)) {
                    FooterCheckOption(stringResource(R.string.manga_check_progress_label), config.hideProgressRatioLabel) {
                        update { hideProgressRatioLabel = it }
                    }
                    FooterCheckOption(stringResource(R.string.manga_check_progress), config.hideProgressRatio) {
                        update { hideProgressRatio = it }
                    }
                }
                FooterSection(stringResource(R.string.manga_header_footer)) {
                    FooterRadioOption(stringResource(R.string.show), !config.hideFooter) {
                        update { hideFooter = false }
                    }
                    FooterRadioOption(stringResource(R.string.hide), config.hideFooter) {
                        update { hideFooter = true }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FooterRadioOption(
                        stringResource(R.string.manga_radio_left),
                        orientation == MangaFooterConfig.ALIGN_LEFT,
                    ) { update { footerOrientation = MangaFooterConfig.ALIGN_LEFT } }
                    FooterRadioOption(
                        stringResource(R.string.manga_radio_center),
                        orientation == MangaFooterConfig.ALIGN_CENTER,
                    ) { update { footerOrientation = MangaFooterConfig.ALIGN_CENTER } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(config) }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FooterSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun FooterCheckOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.minimumInteractiveComponentSize().toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FooterRadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.minimumInteractiveComponentSize().selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
