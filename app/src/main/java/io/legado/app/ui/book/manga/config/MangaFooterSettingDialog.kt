package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.ReaderInfoBarView
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout

class MangaFooterSettingDialog : BaseDialogFragment() {
    val config = GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig).getOrNull()
        ?: MangaFooterConfig()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    var hideChapterLabel by remember { mutableStateOf(config.hideChapterLabel) }
                    var hideChapter by remember { mutableStateOf(config.hideChapter) }
                    var hideChapterName by remember { mutableStateOf(config.hideChapterName) }
                    var hidePageNumberLabel by remember { mutableStateOf(config.hidePageNumberLabel) }
                    var hidePageNumber by remember { mutableStateOf(config.hidePageNumber) }
                    var hideProgressRatioLabel by remember { mutableStateOf(config.hideProgressRatioLabel) }
                    var hideProgressRatio by remember { mutableStateOf(config.hideProgressRatio) }
                    var hideFooter by remember { mutableStateOf(config.hideFooter) }
                    var footerOrientation by remember { mutableIntStateOf(config.footerOrientation) }

                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.manga_footer_config),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.manga_header_chapter),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            FlowRow(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_chapter_label),
                                    checked = hideChapterLabel,
                                    onCheckedChange = {
                                        hideChapterLabel = it
                                        config.hideChapterLabel = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_chapter),
                                    checked = hideChapter,
                                    onCheckedChange = {
                                        hideChapter = it
                                        config.hideChapter = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_chapter_name),
                                    checked = hideChapterName,
                                    onCheckedChange = {
                                        hideChapterName = it
                                        config.hideChapterName = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.manga_header_page),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            FlowRow(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_page_label),
                                    checked = hidePageNumberLabel,
                                    onCheckedChange = {
                                        hidePageNumberLabel = it
                                        config.hidePageNumberLabel = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_page_number),
                                    checked = hidePageNumber,
                                    onCheckedChange = {
                                        hidePageNumber = it
                                        config.hidePageNumber = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.manga_header_progress),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            FlowRow(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_progress_label),
                                    checked = hideProgressRatioLabel,
                                    onCheckedChange = {
                                        hideProgressRatioLabel = it
                                        config.hideProgressRatioLabel = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                                FooterCheckOption(
                                    label = stringResource(R.string.manga_check_progress),
                                    checked = hideProgressRatio,
                                    onCheckedChange = {
                                        hideProgressRatio = it
                                        config.hideProgressRatio = it
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.manga_header_footer),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FooterRadioOption(
                                    label = stringResource(R.string.show),
                                    selected = !hideFooter,
                                    onClick = {
                                        hideFooter = false
                                        config.hideFooter = false
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                                FooterRadioOption(
                                    label = stringResource(R.string.hide),
                                    selected = hideFooter,
                                    onClick = {
                                        hideFooter = true
                                        config.hideFooter = true
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FooterRadioOption(
                                    label = stringResource(R.string.manga_radio_left),
                                    selected = footerOrientation == ReaderInfoBarView.ALIGN_LEFT,
                                    onClick = {
                                        footerOrientation = ReaderInfoBarView.ALIGN_LEFT
                                        config.footerOrientation = ReaderInfoBarView.ALIGN_LEFT
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                                FooterRadioOption(
                                    label = stringResource(R.string.manga_radio_center),
                                    selected = footerOrientation == ReaderInfoBarView.ALIGN_CENTER,
                                    onClick = {
                                        footerOrientation = ReaderInfoBarView.ALIGN_CENTER
                                        config.footerOrientation = ReaderInfoBarView.ALIGN_CENTER
                                        postEvent(EventBus.UP_MANGA_CONFIG, config)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaFooterConfig = GSON.toJson(config)
    }
}

@Composable
private fun FooterCheckOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FooterRadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}