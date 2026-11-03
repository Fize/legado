package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.CheckSource
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

/**
 * 校验书源配置对话框 (Compose)
 */
class CheckSourceConfig : BaseDialogFragment() {

    // 允许的最小超时时间，秒
    private val minTimeout = 0L

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val context = LocalContext.current
                    val barBg = Color(context.primaryColor)
                    val barContent = Color(context.primaryTextColor)
                    val accent = remember(context) { Color(context.accentColor) }

                    var timeoutText by rememberSaveable {
                        mutableStateOf((CheckSource.timeout / 1000).toString())
                    }
                    var wSourceCommentState by rememberSaveable {
                        mutableStateOf(CheckSource.wSourceComment)
                    }
                    var checkDomainState by rememberSaveable {
                        mutableStateOf(CheckSource.checkDomain)
                    }
                    var checkSearchState by rememberSaveable {
                        mutableStateOf(CheckSource.checkSearch)
                    }
                    var checkDiscoveryState by rememberSaveable {
                        mutableStateOf(CheckSource.checkDiscovery)
                    }
                    var checkInfoState by rememberSaveable {
                        mutableStateOf(CheckSource.checkInfo)
                    }
                    var checkCategoryState by rememberSaveable {
                        mutableStateOf(CheckSource.checkCategory)
                    }
                    var checkContentState by rememberSaveable {
                        mutableStateOf(CheckSource.checkContent)
                    }

                    val infoEnabled = checkSearchState || checkDiscoveryState
                    val categoryEnabled = infoEnabled && checkInfoState
                    val contentEnabled = categoryEnabled && checkCategoryState

                    fun disableInfoSection() {
                        checkInfoState = false
                        checkCategoryState = false
                        checkContentState = false
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(R.color.background_card))
                    ) {
                        TopAppBar(
                            title = { Text(stringResource(R.string.check_source_config)) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = barBg,
                                titleContentColor = barContent
                            )
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = timeoutText,
                                onValueChange = { input ->
                                    timeoutText = input.filter { it.isDigit() }
                                },
                                label = { Text(stringResource(R.string.check_source_timeout)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            CheckBoxOption(
                                label = stringResource(R.string.source_check_detail),
                                checked = wSourceCommentState,
                                enabled = true,
                                onCheckedChange = { wSourceCommentState = it }
                            )

                            Text(
                                text = stringResource(R.string.check_source_item),
                                color = accent,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CheckBoxOption(
                                    label = stringResource(R.string.domain),
                                    checked = checkDomainState,
                                    enabled = true,
                                    onCheckedChange = { next ->
                                        checkDomainState = next
                                        if (!checkSearchState && !checkDiscoveryState && !checkDomainState) {
                                            checkSearchState = true
                                        }
                                    }
                                )
                                CheckBoxOption(
                                    label = stringResource(R.string.search),
                                    checked = checkSearchState,
                                    enabled = true,
                                    onCheckedChange = { next ->
                                        checkSearchState = next
                                        if (!checkSearchState && !checkDiscoveryState) {
                                            disableInfoSection()
                                            if (!checkDomainState) {
                                                checkDiscoveryState = true
                                            }
                                        }
                                    }
                                )
                                CheckBoxOption(
                                    label = stringResource(R.string.discovery),
                                    checked = checkDiscoveryState,
                                    enabled = true,
                                    onCheckedChange = { next ->
                                        checkDiscoveryState = next
                                        if (!checkSearchState && !checkDiscoveryState) {
                                            disableInfoSection()
                                            if (!checkDomainState) {
                                                checkSearchState = true
                                            }
                                        }
                                    }
                                )
                                CheckBoxOption(
                                    label = stringResource(R.string.source_tab_info),
                                    checked = checkInfoState,
                                    enabled = infoEnabled,
                                    onCheckedChange = { next ->
                                        checkInfoState = next
                                        if (!checkInfoState) {
                                            checkCategoryState = false
                                            checkContentState = false
                                        }
                                    }
                                )
                                CheckBoxOption(
                                    label = stringResource(R.string.chapter_list),
                                    checked = checkCategoryState,
                                    enabled = categoryEnabled,
                                    onCheckedChange = { next ->
                                        checkCategoryState = next
                                        if (!checkCategoryState) {
                                            checkContentState = false
                                        }
                                    }
                                )
                                CheckBoxOption(
                                    label = stringResource(R.string.main_body),
                                    checked = checkContentState,
                                    enabled = contentEnabled,
                                    onCheckedChange = { checkContentState = it }
                                )
                            }

                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { dismiss() }) {
                                Text(stringResource(R.string.cancel), color = accent)
                            }
                            TextButton(
                                onClick = {
                                    val text = timeoutText.trim()
                                    when {
                                        text.isBlank() -> {
                                            toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                                            return@TextButton
                                        }

                                        (text.toLongOrNull() ?: 0L) <= minTimeout -> {
                                            toastOnUi(
                                                "${getString(R.string.timeout)}${getString(R.string.less_than)}${minTimeout}${
                                                    getString(R.string.seconds)
                                                }"
                                            )
                                            return@TextButton
                                        }

                                        else -> CheckSource.timeout = text.toLong() * 1000
                                    }
                                    CheckSource.wSourceComment = wSourceCommentState
                                    CheckSource.checkDomain = checkDomainState
                                    CheckSource.checkSearch = checkSearchState
                                    CheckSource.checkDiscovery = checkDiscoveryState
                                    CheckSource.checkInfo = checkInfoState
                                    CheckSource.checkCategory = checkCategoryState
                                    CheckSource.checkContent = checkContentState
                                    CheckSource.putConfig()
                                    putPrefString(PreferKey.checkSource, CheckSource.summary)
                                    dismiss()
                                }
                            ) {
                                Text(stringResource(R.string.ok), color = accent)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit
}

@Composable
private fun CheckBoxOption(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            color = if (enabled) colorResource(R.color.primaryText) else colorResource(R.color.secondaryText).copy(alpha = 0.5f),
            fontSize = 14.sp
        )
    }
}