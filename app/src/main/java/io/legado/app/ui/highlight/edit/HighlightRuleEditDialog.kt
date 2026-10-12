package io.legado.app.ui.highlight.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.HighlightFillPreviewDrawable
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HighlightRuleEditDialog : BaseDialogFragment(0, true),
    HighlightStyleDialog.StyleHost,
    ColorPickerDialogListener {

    private var editingStyle by mutableStateOf(HighlightStyle())
    private var styleDialog: HighlightStyleDialog? = null
    private var rule: HighlightRule? = null
    private var isLoaded = false
    private var isLoadedState by mutableStateOf(false)
    private var isSaving = false

    private var nameState by mutableStateOf("")
    private var groupState by mutableStateOf("")
    private var patternState by mutableStateOf("")
    private var isRegexState by mutableStateOf(false)
    private var applyToBodyState by mutableStateOf(true)
    private var applyToTitleState by mutableStateOf(false)
    private var scopeState by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        (childFragmentManager.findFragmentByTag(COLOR_PICKER_TAG) as? ColorPickerDialog)
            ?.setColorPickerDialogListener(this)

        val restoredRule = savedInstanceState?.getParcelable<HighlightRule>(STATE_RULE)
        if (restoredRule != null) {
            rule = restoredRule
            isLoaded = true
            isLoadedState = true
            upView(restoredRule)
        } else {
            val id = arguments?.getLong(ARG_ID, -1L) ?: -1L
            if (id > 0L) loadById(id) else fromArgs()
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isLoaded) (if (view == null) rule else getRule())?.let {
            outState.putParcelable(STATE_RULE, it)
        }
        super.onSaveInstanceState(outState)
    }

    private fun loadById(id: Long) {
        lifecycleScope.launch {
            val loaded = withContext(IO) { appDb.highlightRuleDao.findById(id) }
            if (loaded == null) {
                requireActivity().toastOnUi(R.string.highlight_rule_not_found)
                dismiss()
            } else {
                rule = loaded
                isLoaded = true
                isLoadedState = true
                upView(loaded)
            }
        }
    }

    private fun fromArgs() {
        val args = arguments ?: Bundle.EMPTY
        HighlightRule(
            name = args.getString(ARG_PATTERN).orEmpty(),
            pattern = args.getString(ARG_PATTERN).orEmpty(),
            isRegex = args.getBoolean(ARG_REGEX),
            scope = args.getString(ARG_SCOPE),
            style = args.getString(ARG_STYLE).orEmpty()
        ).also {
            rule = it
            isLoaded = true
            isLoadedState = true
            upView(it)
        }
    }

    private fun upView(rule: HighlightRule) {
        nameState = rule.name
        patternState = rule.pattern
        isRegexState = rule.isRegex
        applyToBodyState = rule.applyToBody
        applyToTitleState = rule.applyToTitle
        scopeState = rule.scope.orEmpty()
        groupState = rule.group.orEmpty()
        editingStyle = rule.styleObj()
    }

    private fun getRule(): HighlightRule =
        (rule ?: HighlightRule()).also {
            it.name = nameState
            it.pattern = patternState
            it.isRegex = isRegexState
            it.applyToBody = applyToBodyState
            it.applyToTitle = applyToTitleState
            it.scope = scopeState.ifBlank { null }
            it.group = groupState.trim().ifBlank { null }
            it.applyStyle(editingStyle)
        }

    private fun save() {
        if (!isLoaded || isSaving) return
        val savedRule = getRule()
        if (!savedRule.isValid()) {
            requireActivity().toastOnUi(
                getString(R.string.highlight_rule_invalid, savedRule.pattern)
            )
            return
        }
        isSaving = true
        lifecycleScope.launch {
            try {
                withContext(IO) {
                    if (savedRule.order == Int.MIN_VALUE) {
                        savedRule.order = appDb.highlightRuleDao.maxOrder + 1
                    }
                    appDb.highlightRuleDao.insert(savedRule)
                }
                ReadBook.upHighlightRules()
                dismiss()
            } finally {
                isSaving = false
            }
        }
    }

    @Composable
    private fun DialogContent() {
        val currentStyle = editingStyle
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.highlight_rule_edit_title),
                    onBack = { dismiss() },
                )
            },
            bottomBar = {
                Column {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { dismiss() }) {
                            Text(text = stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            enabled = isLoadedState,
                            onClick = { save() },
                        ) {
                            Text(text = stringResource(R.string.ok))
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = nameState,
                    onValueChange = { nameState = it },
                    label = { Text(text = stringResource(R.string.name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = groupState,
                    onValueChange = { groupState = it },
                    label = { Text(text = stringResource(R.string.highlight_rule_group_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = patternState,
                    onValueChange = { patternState = it },
                    label = { Text(text = stringResource(R.string.highlight_rule_pattern)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isRegexState = !isRegexState },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isRegexState,
                        onCheckedChange = { isRegexState = it },
                    )
                    Text(text = stringResource(R.string.use_regex))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToBodyState = !applyToBodyState },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = applyToBodyState,
                        onCheckedChange = { applyToBodyState = it },
                    )
                    Text(text = stringResource(R.string.scope_content))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToTitleState = !applyToTitleState },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = applyToTitleState,
                        onCheckedChange = { applyToTitleState = it },
                    )
                    Text(text = stringResource(R.string.highlight_rule_apply_to_title))
                }
                OutlinedTextField(
                    value = scopeState,
                    onValueChange = { scopeState = it },
                    label = { Text(text = stringResource(R.string.highlight_rule_scope_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            HighlightStyleDialog().also {
                                styleDialog = it
                                showDialogFragment(it)
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.highlight_style))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                setText(R.string.highlight_style)
                                setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
                            }
                        },
                        update = { tv ->
                            tv.background = if (currentStyle.fill != 0) {
                                HighlightFillPreviewDrawable(currentStyle, tv.textSize)
                            } else {
                                null
                            }
                            tv.setTextColor(
                                currentStyle.textColor.takeIf { it != 0 }
                                    ?: tv.context.getCompatColor(R.color.primaryText)
                            )
                        },
                    )
                }
            }
        }
    }

    override fun currentHighlightStyle(): HighlightStyle = editingStyle

    override fun onHighlightStyleChanged(style: HighlightStyle) {
        editingStyle = style
    }

    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        createColorPickerDialog(dialogId, initial, withAlpha, this)
            .show(childFragmentManager, COLOR_PICKER_TAG)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        editingStyle = HighlightStyleDialog.applyChannelColor(editingStyle, dialogId, color)
        refreshStyleDialog()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun refreshStyleDialog() {
        styleDialog?.refresh()
        (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName)
                as? HighlightStyleDialog)?.refresh()
    }

    data class ColorPickerConfig(
        val dialogId: Int,
        val color: Int,
        val withAlpha: Boolean,
        val presets: IntArray
    )

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_PATTERN = "pattern"
        private const val ARG_REGEX = "isRegex"
        private const val ARG_SCOPE = "scope"
        private const val ARG_STYLE = "style"
        private const val STATE_RULE = "rule"
        private const val COLOR_PICKER_TAG = "highlight-rule-color-picker"

        fun create(
            pattern: String,
            isRegex: Boolean = false,
            scope: String? = null,
            style: String? = null
        ) = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_PATTERN, pattern)
                putBoolean(ARG_REGEX, isRegex)
                putString(ARG_SCOPE, scope)
                putString(ARG_STYLE, initialStyle(style))
            }
        }

        fun edit(id: Long) = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }

        fun initialStyle(style: String?): String =
            style ?: GSON.toJson(HighlightStyles.presets.first())

        fun colorPickerConfig(
            dialogId: Int,
            initial: Int,
            withAlpha: Boolean
        ): ColorPickerConfig {
            val presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
            return ColorPickerConfig(
                dialogId,
                initial.takeIf { it != 0 } ?: presets.first(),
                withAlpha,
                presets
            )
        }

        fun createColorPickerDialog(
            dialogId: Int,
            initial: Int,
            withAlpha: Boolean,
            listener: ColorPickerDialogListener
        ): ColorPickerDialog {
            val config = colorPickerConfig(dialogId, initial, withAlpha)
            return ColorPickerDialog.newBuilder()
                .setColor(config.color)
                .setShowAlphaSlider(config.withAlpha)
                .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                .setPresets(config.presets)
                .setDialogId(config.dialogId)
                .create()
                .also { it.setColorPickerDialogListener(listener) }
        }
    }
}
