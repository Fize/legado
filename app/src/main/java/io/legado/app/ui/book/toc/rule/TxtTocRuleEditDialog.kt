package io.legado.app.ui.book.toc.rule

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class TxtTocRuleEditDialog() : BaseDialogFragment(adaptationSoftKeyboard = true) {

    constructor(id: Long?) : this() {
        id ?: return
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<ViewModel>()
    private val callback get() = (parentFragment as? Callback) ?: activity as? Callback

    private var nameState by mutableStateOf("")
    private var ruleRegexState by mutableStateOf("")
    private var replacementState by mutableStateOf("")
    private var exampleState by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel.initData(arguments?.getLong("id")) {
            upRuleView(it)
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Surface(
                        modifier = Modifier
                            .padding(16.dp)
                            .widthIn(max = 560.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LegadoTopAppBar(
                                title = stringResource(R.string.txt_toc_rule),
                                menuActions = listOf(
                                    LegadoMenuAction(
                                        title = stringResource(R.string.action_save),
                                        iconRes = R.drawable.ic_save,
                                        showAsAction = true,
                                        onClick = {
                                            val tocRule = getRuleFromView()
                                            if (checkValid(tocRule)) {
                                                callback?.saveTxtTocRule(tocRule)
                                                dismissAllowingStateLoss()
                                            }
                                        },
                                    ),
                                    LegadoMenuAction(
                                        title = stringResource(R.string.copy_rule),
                                        onClick = {
                                            context?.sendToClip(GSON.toJson(getRuleFromView()))
                                        },
                                    ),
                                    LegadoMenuAction(
                                        title = stringResource(R.string.paste_rule),
                                        onClick = {
                                            viewModel.pasteRule { upRuleView(it) }
                                        },
                                    ),
                                ),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                OutlinedTextField(
                                    value = nameState,
                                    onValueChange = { nameState = it },
                                    label = { Text(stringResource(R.string.name)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = ruleRegexState,
                                    onValueChange = { ruleRegexState = it },
                                    label = { Text(stringResource(R.string.regex)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 4,
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = replacementState,
                                    onValueChange = { replacementState = it },
                                    label = { Text(stringResource(R.string.replace_to_js)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 6,
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = exampleState,
                                    onValueChange = { exampleState = it },
                                    label = { Text(stringResource(R.string.example)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 4,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkValid(tocRule: TxtTocRule): Boolean {
        if (tocRule.name.isEmpty()) {
            toastOnUi("名称不能为空")
            return false
        }

        try {
            Pattern.compile(tocRule.rule, Pattern.MULTILINE)
        } catch (ex: PatternSyntaxException) {
            AppLog.put("正则语法错误或不支持(txt)：${ex.localizedMessage}", ex, true)
            return false
        }

        return true
    }

    private fun upRuleView(tocRule: TxtTocRule?) {
        nameState = tocRule?.name.orEmpty()
        ruleRegexState = tocRule?.rule.orEmpty()
        replacementState = tocRule?.replacement.orEmpty()
        exampleState = tocRule?.example.orEmpty()
    }

    private fun getRuleFromView(): TxtTocRule {
        val tocRule = viewModel.tocRule ?: TxtTocRule().apply {
            viewModel.tocRule = this
        }
        tocRule.name = nameState
        tocRule.rule = ruleRegexState
        tocRule.replacement = replacementState
        tocRule.example = exampleState
        return tocRule
    }

    private fun isSame(): Boolean {
        val tocRule = viewModel.tocRule ?: return nameState.isEmpty()
        return tocRule.name == nameState &&
            tocRule.rule == ruleRegexState &&
            tocRule.replacement.orEmpty() == replacementState &&
            tocRule.example.orEmpty() == exampleState
    }

    override fun dismiss() {
        if (!isSame()) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.dismiss()
                }
            }
        } else {
            super.dismiss()
        }
    }

    class ViewModel(application: Application) : BaseViewModel(application) {

        var tocRule: TxtTocRule? = null

        fun initData(id: Long?, finally: (tocRule: TxtTocRule?) -> Unit) {
            if (tocRule != null) return
            execute {
                if (id == null) return@execute
                tocRule = appDb.txtTocRuleDao.get(id)
            }.onFinally {
                finally.invoke(tocRule)
            }
        }

        fun pasteRule(success: (TxtTocRule) -> Unit) {
            execute(context = Dispatchers.Main) {
                val text = context.getClipText()
                if (text.isNullOrBlank()) {
                    throw NoStackTraceException("剪贴板为空")
                }
                GSON.fromJsonObject<TxtTocRule>(text).getOrNull()
                    ?: throw NoStackTraceException("格式不对")
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi(it.localizedMessage ?: "Error")
                it.printOnDebug()
            }
        }

    }

    interface Callback {

        fun saveTxtTocRule(txtTocRule: TxtTocRule)

    }

}