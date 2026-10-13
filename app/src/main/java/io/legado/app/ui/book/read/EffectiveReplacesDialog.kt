package io.legado.app.ui.book.read

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout

/**
 * 起效的替换规则
 */
class EffectiveReplacesDialog() : BaseDialogFragment() {
    constructor(ids: List<Long>) : this() {
        arguments = Bundle().apply { putLongArray("sourceRuleIds", ids.toLongArray()) }
    }

    interface Callback {
        fun onEffectiveSourceRulesChanged()
    }

    private val sourceReplacement get() = arguments?.containsKey("sourceRuleIds") == true

    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val chineseConvert by lazy { ReplaceRule(0, "繁简转换") }
    private val rulesState = mutableStateListOf<ReplaceRule>()

    private var isEdit = false

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    EffectiveReplacesScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isEdit = savedInstanceState?.getBoolean("isEdit") == true
        val effectiveReplaceRules = if (sourceReplacement) {
            appDb.replaceRuleDao.findByIds(*arguments!!.getLongArray("sourceRuleIds")!!)
        } else ReadBook.curTextChapter?.effectiveReplaceRules ?: emptyList()
        rulesState.clear()
        if (!sourceReplacement && AppConfig.chineseConverterType > 0) {
            rulesState.addAll(effectiveReplaceRules + chineseConvert)
        } else {
            rulesState.addAll(effectiveReplaceRules)
        }
    }

    @Composable
    private fun EffectiveReplacesScreen() {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val textColor = Color(context.primaryTextColor)
        val topBarColor = Color(context.primaryColor)

        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(8.dp),
            color = bgColor
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarColor)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.effective_replaces),
                        color = textColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    itemsIndexed(rulesState, key = { _, item -> item.id }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item == chineseConvert) {
                                        showChineseConvertAlert()
                                    } else {
                                        editActivity.launch(
                                            ReplaceEditActivity.startIntent(requireContext(), item.id)
                                        )
                                    }
                                }
                                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                color = textColor,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    isEdit = true
                                    if (index in rulesState.indices) {
                                        rulesState.removeAt(index)
                                    }
                                    if (item == chineseConvert) {
                                        AppConfig.chineseConverterType = 0
                                    } else {
                                        item.isEnabled = false
                                        appDb.replaceRuleDao.enable(item.id, false)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_close),
                                    contentDescription = stringResource(R.string.delete),
                                    tint = textColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isEdit) {
            if (sourceReplacement) (parentFragment as? Callback)?.onEffectiveSourceRulesChanged()
            else viewModel.replaceRuleChanged()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("isEdit", isEdit)
        super.onSaveInstanceState(outState)
    }

    private fun showChineseConvertAlert() {
        alert(titleResource = R.string.chinese_converter) {
            items(resources.getStringArray(R.array.chinese_mode).toList()) { _, i ->
                if (AppConfig.chineseConverterType != i) {
                    AppConfig.chineseConverterType = i
                    isEdit = true
                }
            }
        }
    }
}
