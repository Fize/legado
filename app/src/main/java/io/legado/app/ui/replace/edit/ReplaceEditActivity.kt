package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.code.CodeTextTransfer
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 编辑替换规则
 */
class ReplaceEditActivity : VMBaseComposeActivity<ReplaceEditViewModel>() {

    companion object {

        private const val PREVIEW_DEBOUNCE_MILLIS = 250L

        private const val FIELD_NAME = 1
        private const val FIELD_GROUP = 2
        private const val FIELD_PATTERN = 3
        private const val FIELD_REPLACEMENT = 4
        private const val FIELD_SCOPE = 5
        private const val FIELD_EXCLUDE_SCOPE = 6
        private const val FIELD_TIMEOUT = 7
        private const val FIELD_PREVIEW_INPUT = 8

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null
        ): Intent {
            val intent = Intent(context, ReplaceEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            intent.putExtra("scope", scope)
            return intent
        }

    }

    override val viewModel by viewModels<ReplaceEditViewModel>()

    private var previewJob: Job? = null
    private var updatingView = false
    private var originalRule: ReplaceRule? = null
    private var originalSample = ""
    private var saved = false

    private val rawFields = mutableStateMapOf<Int, String>()
    private var focusedFieldId by mutableStateOf<Int?>(null)
    private var pendingFieldId: Int? = null

    private var nameState by mutableStateOf("")
    private var groupState by mutableStateOf("")
    private var patternState by mutableStateOf("")
    private var useRegexState by mutableStateOf(false)
    private var replacementState by mutableStateOf("")
    private var scopeTitleState by mutableStateOf(false)
    private var scopeSourceState by mutableStateOf(false)
    private var scopeContentState by mutableStateOf(true)
    private var scopeState by mutableStateOf("")
    private var excludeScopeState by mutableStateOf("")
    private var timeoutState by mutableStateOf("3000")
    private var previewInputState by mutableStateOf("")
    private var previewOutputState by mutableStateOf("")
    private var previewErrorState by mutableStateOf<String?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { finish() }
        viewModel.initData(intent) {
            originalRule = it.copy()
            originalSample = ReplacePreview.normalizeSample(it.previewText ?: viewModel.sampleFor(it.id))
            upReplaceView(it)
        }
    }

    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val fieldId = pendingFieldId
                val text = result.data?.getStringExtra("text")
                    ?: result.data?.getStringExtra("textFile")?.let { path ->
                        CodeTextTransfer.read(this, path).also { CodeTextTransfer.delete(this, path) }
                    }
                val cursorPosition = result.data?.takeIf { it.hasExtra("cursorPosition") }
                    ?.getIntExtra("cursorPosition", -1)
                if (fieldId != null && text != null) {
                    applyFieldText(fieldId, text)
                    pendingFieldId = null
                    schedulePreview()
                } else if (fieldId != null && cursorPosition != null) {
                    // CodeEditActivity returns only the cursor when the user discards edits.
                    pendingFieldId = null
                } else {
                    toastOnUi(R.string.focus_lost_on_textbox)
                }
            }
        }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.replace_rule_edit),
                    onBack = { finish() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.edit_content),
                            iconRes = R.drawable.ic_code,
                            showAsAction = true,
                            onClick = { onFullEditClicked() },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.action_save),
                            iconRes = R.drawable.ic_save,
                            showAsAction = true,
                            onClick = {
                                val rule = getReplaceRule()
                                viewModel.save(rule) {
                                    viewModel.saveSample(rule.id, previewInputState)
                                    setResult(RESULT_OK)
                                    saved = true
                                    finish()
                                }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.copy_rule),
                            onClick = {
                                sendToClip(GSON.toJson(getReplaceRuleForExport()))
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.paste_rule),
                            onClick = {
                                viewModel.pasteRule { upReplaceView(it) }
                            },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SafeRuleTextField(
                    fieldId = FIELD_NAME,
                    label = stringResource(R.string.replace_rule_summary),
                    displayValue = nameState,
                    onValueChange = {
                        nameState = it
                        schedulePreview()
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SafeRuleTextField(
                    fieldId = FIELD_GROUP,
                    label = stringResource(R.string.group),
                    displayValue = groupState,
                    onValueChange = { groupState = it },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SafeRuleTextField(
                    fieldId = FIELD_PATTERN,
                    label = stringResource(R.string.replace_rule),
                    displayValue = patternState,
                    onValueChange = {
                        patternState = it
                        schedulePreview()
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            useRegexState = !useRegexState
                            schedulePreview()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useRegexState,
                        onCheckedChange = {
                            useRegexState = it
                            schedulePreview()
                        },
                    )
                    Text(
                        text = stringResource(R.string.use_regex),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SafeRuleTextField(
                    fieldId = FIELD_REPLACEMENT,
                    label = stringResource(R.string.replace_to),
                    displayValue = replacementState,
                    onValueChange = {
                        replacementState = it
                        schedulePreview()
                    },
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.clickable { scopeTitleState = !scopeTitleState },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = scopeTitleState,
                            onCheckedChange = { scopeTitleState = it },
                        )
                        Text(
                            text = stringResource(R.string.scope_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        modifier = Modifier.clickable { scopeSourceState = !scopeSourceState },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = scopeSourceState,
                            onCheckedChange = { scopeSourceState = it },
                        )
                        Text(
                            text = stringResource(R.string.scope_source),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        modifier = Modifier.clickable { scopeContentState = !scopeContentState },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = scopeContentState,
                            onCheckedChange = { scopeContentState = it },
                        )
                        Text(
                            text = stringResource(R.string.scope_content),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                SafeRuleTextField(
                    fieldId = FIELD_SCOPE,
                    label = stringResource(R.string.replace_scope),
                    displayValue = scopeState,
                    onValueChange = { scopeState = it },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SafeRuleTextField(
                    fieldId = FIELD_EXCLUDE_SCOPE,
                    label = stringResource(R.string.replace_exclude_scope),
                    displayValue = excludeScopeState,
                    onValueChange = { excludeScopeState = it },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = timeoutState,
                    onValueChange = {
                        timeoutState = it
                        schedulePreview()
                    },
                    label = { Text(stringResource(R.string.timeout_millisecond)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) focusedFieldId = FIELD_TIMEOUT },
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OutlinedTextField(
                        value = previewInputState,
                        onValueChange = { value ->
                            val normalized = ReplacePreview.normalizeSample(value)
                            previewInputState = normalized
                            if (value != normalized) {
                                toastOnUi(
                                    getString(
                                        R.string.replace_preview_truncated,
                                        ReplacePreview.MAX_SAMPLE_LENGTH,
                                    )
                                )
                            }
                            schedulePreview()
                        },
                        label = { Text(stringResource(R.string.replace_preview_input)) },
                        minLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { if (it.isFocused) focusedFieldId = FIELD_PREVIEW_INPUT },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedTextField(
                        value = previewOutputState,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.replace_preview_output)) },
                        isError = previewErrorState != null,
                        supportingText = previewErrorState?.let { err -> { Text(err) } },
                        minLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Composable
    private fun SafeRuleTextField(
        fieldId: Int,
        label: String,
        displayValue: String,
        onValueChange: (String) -> Unit,
    ) {
        val isUnsafe = rawFields.containsKey(fieldId)
        OutlinedTextField(
            value = displayValue,
            onValueChange = {
                if (!isUnsafe) onValueChange(it)
            },
            readOnly = isUnsafe,
            label = { Text(label) },
            maxLines = if (isUnsafe) EditSafety.PREVIEW_LINES else 8,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) focusedFieldId = fieldId }
                .then(
                    if (isUnsafe) {
                        Modifier.clickable {
                            pendingFieldId = fieldId
                            onFullEditClicked(fieldId)
                        }
                    } else {
                        Modifier
                    }
                ),
        )
    }

    private fun onFullEditClicked(fieldId: Int? = focusedFieldId) {
        if (fieldId == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        val (hint, currentText) = when (fieldId) {
            FIELD_NAME -> getString(R.string.replace_rule_summary) to (rawFields[FIELD_NAME] ?: nameState)
            FIELD_GROUP -> getString(R.string.group) to (rawFields[FIELD_GROUP] ?: groupState)
            FIELD_PATTERN -> getString(R.string.replace_rule) to (rawFields[FIELD_PATTERN] ?: patternState)
            FIELD_REPLACEMENT -> getString(R.string.replace_to) to (rawFields[FIELD_REPLACEMENT] ?: replacementState)
            FIELD_SCOPE -> getString(R.string.replace_scope) to (rawFields[FIELD_SCOPE] ?: scopeState)
            FIELD_EXCLUDE_SCOPE -> getString(R.string.replace_exclude_scope) to (rawFields[FIELD_EXCLUDE_SCOPE] ?: excludeScopeState)
            FIELD_TIMEOUT -> getString(R.string.timeout_millisecond) to timeoutState
            FIELD_PREVIEW_INPUT -> getString(R.string.replace_preview_input) to previewInputState
            else -> return
        }
        val intent = Intent(this, CodeEditActivity::class.java).apply {
            putExtra("useTextFile", true)
            if (currentText.length > EditSafety.MAX_INLINE_TEXT_LENGTH) {
                putExtra("textFile", CodeTextTransfer.write(this@ReplaceEditActivity, currentText))
            } else {
                putExtra("text", currentText)
            }
            putExtra("title", hint)
            putExtra("cursorPosition", currentText.length)
        }
        pendingFieldId = fieldId
        textEditLauncher.launch(intent)
    }

    override fun onDestroy() {
        previewJob?.cancel()
        super.onDestroy()
    }

    private fun upReplaceView(replaceRule: ReplaceRule) {
        updatingView = true
        try {
            rawFields.clear()
            applyFieldText(FIELD_NAME, replaceRule.name)
            applyFieldText(FIELD_GROUP, replaceRule.group.orEmpty())
            applyFieldText(FIELD_PATTERN, replaceRule.pattern)
            useRegexState = replaceRule.isRegex
            applyFieldText(FIELD_REPLACEMENT, replaceRule.replacement)
            scopeTitleState = replaceRule.scopeTitle
            scopeSourceState = replaceRule.scopeSource
            scopeContentState = replaceRule.scopeContent
            applyFieldText(FIELD_SCOPE, replaceRule.scope.orEmpty())
            applyFieldText(FIELD_EXCLUDE_SCOPE, replaceRule.excludeScope.orEmpty())
            timeoutState = replaceRule.timeoutMillisecond.toString()
            val editingRuleId = viewModel.replaceRule?.id ?: replaceRule.id
            previewInputState = ReplacePreview.normalizeSample(
                replaceRule.previewText ?: viewModel.sampleFor(editingRuleId)
            )
        } finally {
            updatingView = false
        }
        schedulePreview()
    }

    private fun applyFieldText(fieldId: Int, raw: String) {
        val unsafe = EditSafety.isTooLongForInline(raw) || EditSafety.isCombiningHeavy(raw)
        if (unsafe) rawFields[fieldId] = raw else rawFields.remove(fieldId)
        val display = when {
            EditSafety.isTooLongForInline(raw) -> getString(R.string.large_text_placeholder, raw.length)
            unsafe -> getString(R.string.combining_text_placeholder)
            else -> raw
        }
        when (fieldId) {
            FIELD_NAME -> nameState = display
            FIELD_GROUP -> groupState = display
            FIELD_PATTERN -> patternState = display
            FIELD_REPLACEMENT -> replacementState = display
            FIELD_SCOPE -> scopeState = display
            FIELD_EXCLUDE_SCOPE -> excludeScopeState = display
            FIELD_TIMEOUT -> timeoutState = raw
            FIELD_PREVIEW_INPUT -> previewInputState = ReplacePreview.normalizeSample(raw)
        }
    }

    private fun schedulePreview() {
        if (updatingView) return
        previewJob?.cancel()
        val sample = ReplacePreview.normalizeSample(previewInputState)
        val rule = getReplaceRule().copy()
        previewJob = lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MILLIS)
            val result = try {
                Result.success(ReplacePreview.apply(rule, sample))
            } catch (error: CancellationException) {
                throw error
            } catch (error: StackOverflowError) {
                Result.failure(error)
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!isActive) return@launch
            result.onSuccess {
                previewErrorState = null
                previewOutputState = it
            }.onFailure {
                previewOutputState = sample
                previewErrorState = getString(
                    when ((it as? ReplacePreviewException)?.reason) {
                        ReplacePreviewException.Reason.TIMEOUT -> R.string.replace_preview_timeout
                        ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE ->
                            R.string.replace_preview_context_unavailable
                        ReplacePreviewException.Reason.JS_EVALUATION ->
                            R.string.replace_preview_js_error
                        null -> R.string.replace_preview_error
                    }
                )
            }
        }
    }

    private fun getReplaceRule(): ReplaceRule {
        val source = viewModel.replaceRule ?: ReplaceRule()
        return source.copy(
            name = rawFields[FIELD_NAME] ?: nameState,
            group = (rawFields[FIELD_GROUP] ?: groupState).ifBlank { null },
            pattern = rawFields[FIELD_PATTERN] ?: patternState,
            isRegex = useRegexState,
            replacement = rawFields[FIELD_REPLACEMENT] ?: replacementState,
            scopeTitle = scopeTitleState,
            scopeSource = scopeSourceState,
            scopeContent = scopeContentState,
            scope = (rawFields[FIELD_SCOPE] ?: scopeState).ifBlank { null },
            excludeScope = (rawFields[FIELD_EXCLUDE_SCOPE] ?: excludeScopeState).ifBlank { null },
            timeoutMillisecond = timeoutState.toLongOrNull() ?: 3000L,
        )
    }

    override fun finish() {
        val current = getReplaceRule()
        val baseline = originalRule
        val sample = ReplacePreview.normalizeSample(previewInputState)
        val changed = baseline != null && !sameRule(current, baseline) || sample != originalSample
        if (changed && !saved) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) { super@ReplaceEditActivity.finish() }
            }
        } else {
            super.finish()
        }
    }

    private fun sameRule(a: ReplaceRule, b: ReplaceRule): Boolean =
        a.name == b.name && a.group == b.group && a.pattern == b.pattern && a.replacement == b.replacement &&
            a.scope == b.scope && a.scopeTitle == b.scopeTitle && a.scopeSource == b.scopeSource &&
            a.scopeContent == b.scopeContent && a.excludeScope == b.excludeScope && a.isEnabled == b.isEnabled &&
            a.isRegex == b.isRegex && a.timeoutMillisecond == b.timeoutMillisecond && a.order == b.order

    private fun getReplaceRuleForExport(): ReplaceRule {
        return getReplaceRule().also { rule ->
            rule.previewText = ReplacePreview.normalizeSample(
                previewInputState
            ).takeIf { it.isNotEmpty() }
        }
    }

}
