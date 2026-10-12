package io.legado.app.ui.config

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.BookCover
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 封面规则配置对话框 (Compose)
 */
class CoverRuleConfigDialog : BaseDialogFragment() {

    private var enableState by mutableStateOf(false)
    private var searchUrlState by mutableStateOf("")
    private var coverRuleState by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @OptIn(ExperimentalMaterial3Api::class)
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

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(R.color.background_card))
                    ) {
                        TopAppBar(
                            title = { Text(stringResource(R.string.cover_config)) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = barBg,
                                titleContentColor = barContent
                            )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { enableState = !enableState },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = enableState,
                                    onCheckedChange = { enableState = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.enable),
                                    color = colorResource(R.color.primaryText)
                                )
                            }

                            OutlinedTextField(
                                value = searchUrlState,
                                onValueChange = { searchUrlState = it },
                                label = { Text(stringResource(R.string.r_search_url)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = coverRuleState,
                                onValueChange = { coverRuleState = it },
                                label = { Text(stringResource(R.string.rule_cover_url)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    BookCover.delCoverRule()
                                    dismissAllowingStateLoss()
                                }
                            ) {
                                Text(stringResource(R.string.btn_default_s), color = accent)
                            }

                            Row {
                                TextButton(onClick = { dismissAllowingStateLoss() }) {
                                    Text(
                                        text = stringResource(R.string.cancel),
                                        color = colorResource(R.color.secondaryText)
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        if (searchUrlState.isBlank() || coverRuleState.isBlank()) {
                                            toastOnUi("搜索url和cover规则不能为空")
                                        } else {
                                            BookCover.CoverRule(
                                                enableState,
                                                searchUrlState,
                                                coverRuleState
                                            ).let { config ->
                                                BookCover.saveCoverRule(config)
                                            }
                                            dismissAllowingStateLoss()
                                        }
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
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rule = withContext(IO) {
                BookCover.getCoverRule()
            }
            Log.e("coverRule", GSON.toJson(rule))
            enableState = rule.enable
            searchUrlState = rule.searchUrl
            coverRuleState = rule.coverRule
        }
    }
}
