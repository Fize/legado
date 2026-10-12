package io.legado.app.ui.dict.rule

import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class DictRuleEditDialog() : BaseDialogFragment(adaptationSoftKeyboard = true) {

    val viewModel by viewModels<DictRuleEditViewModel>()

    private var nameState by mutableStateOf("")
    private var urlRuleState by mutableStateOf("")
    private var showRuleState by mutableStateOf("")
    private var focusedFieldState by mutableStateOf<String?>(null)

    constructor(name: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val edited = result.data?.getStringExtra("text") ?: return@registerForActivityResult
                when (focusedFieldState) {
                    "name" -> nameState = edited
                    "urlRule" -> urlRuleState = edited
                    "showRule" -> showRuleState = edited
                    else -> toastOnUi(R.string.focus_lost_on_textbox)
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel.initData(arguments?.getString("name")) {
            upRuleView(viewModel.dictRule)
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
                                title = stringResource(R.string.dict_rule),
                                menuActions = listOf(
                                    LegadoMenuAction(
                                        title = stringResource(R.string.edit_content),
                                        iconRes = R.drawable.ic_code,
                                        showAsAction = true,
                                        onClick = ::onFullEditClicked,
                                    ),
                                    LegadoMenuAction(
                                        title = stringResource(R.string.action_save),
                                        iconRes = R.drawable.ic_save,
                                        showAsAction = true,
                                        onClick = {
                                            viewModel.save(getDictRule()) {
                                                dismissAllowingStateLoss()
                                            }
                                        },
                                    ),
                                    LegadoMenuAction(
                                        title = stringResource(R.string.copy_rule),
                                        onClick = { viewModel.copyRule(getDictRule()) },
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { if (it.isFocused) focusedFieldState = "name" },
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = urlRuleState,
                                    onValueChange = { urlRuleState = it },
                                    label = { Text(stringResource(R.string.url_rule)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { if (it.isFocused) focusedFieldState = "urlRule" },
                                    maxLines = 4,
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = showRuleState,
                                    onValueChange = { showRuleState = it },
                                    label = { Text(stringResource(R.string.show_rule)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { if (it.isFocused) focusedFieldState = "showRule" },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onFullEditClicked() {
        val field = focusedFieldState
        if (field == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        val (title, currentText) = when (field) {
            "name" -> getString(R.string.name) to nameState
            "urlRule" -> getString(R.string.url_rule) to urlRuleState
            "showRule" -> getString(R.string.show_rule) to showRuleState
            else -> return
        }
        val intent = Intent(requireActivity(), CodeEditActivity::class.java).apply {
            putExtra("text", currentText)
            putExtra("title", title)
            putExtra("cursorPosition", currentText.length)
        }
        textEditLauncher.launch(intent)
    }

    private fun upRuleView(dictRule: DictRule?) {
        nameState = dictRule?.name.orEmpty()
        urlRuleState = dictRule?.urlRule.orEmpty()
        showRuleState = dictRule?.showRule.orEmpty()
    }

    private fun getDictRule(): DictRule {
        val dictRule = viewModel.dictRule?.copy() ?: DictRule()
        dictRule.name = nameState
        dictRule.urlRule = urlRuleState
        dictRule.showRule = showRuleState
        return dictRule
    }

    private fun isSame(): Boolean {
        val dictRule = viewModel.dictRule ?: return nameState.isEmpty()
        return dictRule.name == nameState &&
            dictRule.urlRule == urlRuleState &&
            dictRule.showRule == showRuleState
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

    class DictRuleEditViewModel(application: Application) : BaseViewModel(application) {

        var dictRule: DictRule? = null

        fun initData(name: String?, onFinally: () -> Unit) {
            execute {
                if (dictRule == null && name != null) {
                    dictRule = appDb.dictRuleDao.getByName(name)
                }
            }.onFinally {
                onFinally.invoke()
            }
        }

        fun save(newDictRule: DictRule, onFinally: () -> Unit) {
            execute {
                dictRule?.let {
                    appDb.dictRuleDao.delete(it)
                }
                appDb.dictRuleDao.insert(newDictRule)
                dictRule = newDictRule
            }.onFinally {
                onFinally.invoke()
            }
        }

        fun copyRule(dictRule: DictRule) {
            context.sendToClip(GSON.toJson(dictRule))
        }

        fun pasteRule(success: (DictRule) -> Unit) {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                context.toastOnUi("剪贴板没有内容")
                return
            }
            execute {
                GSON.fromJsonObject<DictRule>(text).getOrThrow()
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi("格式不对")
            }
        }

    }

}