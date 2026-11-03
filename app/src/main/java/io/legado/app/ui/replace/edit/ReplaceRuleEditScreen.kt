package io.legado.app.ui.replace.edit

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.code.CodeTextTransfer
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.utils.GSON
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val FIELD_NAME = 1
private const val FIELD_GROUP = 2
private const val FIELD_PATTERN = 3
private const val FIELD_REPLACEMENT = 4
private const val FIELD_SCOPE = 5
private const val FIELD_EXCLUDE_SCOPE = 6
private const val FIELD_TIMEOUT = 7
private const val FIELD_PREVIEW_INPUT = 8
private const val PREVIEW_DEBOUNCE_MILLIS = 250L

private val RawFieldsSaver = mapSaver(
    save = { fields -> fields.mapKeys { it.key.toString() } },
    restore = { fields -> fields.mapKeys { it.key.toInt() }.mapValues { it.value as String } },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplaceRuleEditScreen(
    ruleId: Long? = null,
    pattern: String? = null,
    isRegex: Boolean = false,
    scope: String? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ReplaceEditViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val initialRule by viewModel.initialRule.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    var originalRule by rememberSaveable { mutableStateOf<ReplaceRule?>(null) }
    var originalSample by rememberSaveable { mutableStateOf("") }
    var initializedForKey by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var rawFields by rememberSaveable(stateSaver = RawFieldsSaver) { mutableStateOf(emptyMap()) }
    var focusedFieldId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingFieldId by rememberSaveable { mutableStateOf<Int?>(null) }
    var nameState by rememberSaveable { mutableStateOf("") }
    var groupState by rememberSaveable { mutableStateOf("") }
    var patternState by rememberSaveable { mutableStateOf("") }
    var useRegexState by rememberSaveable { mutableStateOf(false) }
    var replacementState by rememberSaveable { mutableStateOf("") }
    var scopeTitleState by rememberSaveable { mutableStateOf(false) }
    var scopeSourceState by rememberSaveable { mutableStateOf(false) }
    var scopeContentState by rememberSaveable { mutableStateOf(true) }
    var scopeState by rememberSaveable { mutableStateOf("") }
    var excludeScopeState by rememberSaveable { mutableStateOf("") }
    var timeoutState by rememberSaveable { mutableStateOf("3000") }
    var previewInputState by rememberSaveable { mutableStateOf("") }
    var previewOutputState by rememberSaveable { mutableStateOf("") }
    var previewErrorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val editKey = "$ruleId|$pattern|$isRegex|$scope"

    fun applyFieldText(fieldId: Int, raw: String) {
        val unsafe = EditSafety.isTooLongForInline(raw) || EditSafety.isCombiningHeavy(raw)
        rawFields = if (unsafe) rawFields + (fieldId to raw) else rawFields - fieldId
        val display = if (unsafe) "" else raw
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

    fun fieldDisplay(fieldId: Int, value: String): String {
        val raw = rawFields[fieldId] ?: return value
        return if (EditSafety.isTooLongForInline(raw)) resources.getString(R.string.large_text_placeholder, raw.length)
        else resources.getString(R.string.combining_text_placeholder)
    }

    fun getReplaceRule(): ReplaceRule {
        val source = originalRule ?: ReplaceRule()
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

    fun sameRule(a: ReplaceRule, b: ReplaceRule): Boolean =
        a.name == b.name && a.group == b.group && a.pattern == b.pattern && a.replacement == b.replacement &&
            a.scope == b.scope && a.scopeTitle == b.scopeTitle && a.scopeSource == b.scopeSource &&
            a.scopeContent == b.scopeContent && a.excludeScope == b.excludeScope && a.isEnabled == b.isEnabled &&
            a.isRegex == b.isRegex && a.timeoutMillisecond == b.timeoutMillisecond && a.order == b.order

    fun requestBack() {
        val baseline = originalRule
        val changed = baseline != null && !sameRule(getReplaceRule(), baseline) ||
            ReplacePreview.normalizeSample(previewInputState) != originalSample
        if (changed && !saved) confirmDiscard = true else onBack()
    }

    val textEditLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val fieldId = pendingFieldId
            val text = result.data?.getStringExtra("text")
                ?: result.data?.getStringExtra("textFile")?.let { path ->
                    CodeTextTransfer.read(context, path).also { CodeTextTransfer.delete(context, path) }
                }
            val cursorPosition = result.data?.takeIf { it.hasExtra("cursorPosition") }
                ?.getIntExtra("cursorPosition", -1)
            if (fieldId != null && text != null) {
                applyFieldText(fieldId, text)
                pendingFieldId = null
            } else if (fieldId != null && cursorPosition != null) {
                pendingFieldId = null
            } else {
                context.toastOnUi(R.string.focus_lost_on_textbox)
            }
        }
    }

    fun onFullEditClicked(fieldId: Int? = focusedFieldId) {
        if (fieldId == null) {
            context.toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        val (hint, currentText) = when (fieldId) {
            FIELD_NAME -> resources.getString(R.string.replace_rule_summary) to (rawFields[FIELD_NAME] ?: nameState)
            FIELD_GROUP -> resources.getString(R.string.group) to (rawFields[FIELD_GROUP] ?: groupState)
            FIELD_PATTERN -> resources.getString(R.string.replace_rule) to (rawFields[FIELD_PATTERN] ?: patternState)
            FIELD_REPLACEMENT -> resources.getString(R.string.replace_to) to (rawFields[FIELD_REPLACEMENT] ?: replacementState)
            FIELD_SCOPE -> resources.getString(R.string.replace_scope) to (rawFields[FIELD_SCOPE] ?: scopeState)
            FIELD_EXCLUDE_SCOPE -> resources.getString(R.string.replace_exclude_scope) to (rawFields[FIELD_EXCLUDE_SCOPE] ?: excludeScopeState)
            FIELD_TIMEOUT -> resources.getString(R.string.timeout_millisecond) to timeoutState
            FIELD_PREVIEW_INPUT -> resources.getString(R.string.replace_preview_input) to previewInputState
            else -> return
        }
        val intent = Intent(context, CodeEditActivity::class.java).apply {
            putExtra("useTextFile", true)
            if (currentText.length > EditSafety.MAX_INLINE_TEXT_LENGTH) {
                putExtra("textFile", CodeTextTransfer.write(context, currentText))
            } else {
                putExtra("text", currentText)
            }
            putExtra("title", hint)
            putExtra("cursorPosition", currentText.length)
        }
        pendingFieldId = fieldId
        textEditLauncher.launch(intent)
    }

    fun populateRule(rule: ReplaceRule, resetBaseline: Boolean = false) {
        rawFields = emptyMap()
        if (resetBaseline) {
            originalRule = rule.copy()
            originalSample = ReplacePreview.normalizeSample(rule.previewText ?: viewModel.sampleFor(rule.id))
        }
        applyFieldText(FIELD_NAME, rule.name)
        applyFieldText(FIELD_GROUP, rule.group.orEmpty())
        applyFieldText(FIELD_PATTERN, rule.pattern)
        useRegexState = rule.isRegex
        applyFieldText(FIELD_REPLACEMENT, rule.replacement)
        scopeTitleState = rule.scopeTitle
        scopeSourceState = rule.scopeSource
        scopeContentState = rule.scopeContent
        applyFieldText(FIELD_SCOPE, rule.scope.orEmpty())
        applyFieldText(FIELD_EXCLUDE_SCOPE, rule.excludeScope.orEmpty())
        timeoutState = rule.timeoutMillisecond.toString()
        previewInputState = ReplacePreview.normalizeSample(
            rule.previewText ?: viewModel.sampleFor(originalRule?.id ?: rule.id)
        )
        previewOutputState = previewInputState
        previewErrorKey = null
    }

    LaunchedEffect(editKey) {
        viewModel.initData(ruleId ?: -1, pattern, isRegex, scope)
    }
    LaunchedEffect(editKey, initialRule) {
        val rule = initialRule ?: return@LaunchedEffect
        if (initializedForKey != editKey) {
            populateRule(rule, resetBaseline = true)
            initializedForKey = editKey
        }
    }
    LaunchedEffect(loadError) {
        loadError?.let {
            context.toastOnUi(it)
            viewModel.consumeLoadError()
        }
    }
    LaunchedEffect(
        editKey, originalRule, nameState, groupState, patternState, useRegexState, replacementState,
        scopeTitleState, scopeSourceState, scopeContentState, scopeState, excludeScopeState,
        timeoutState, previewInputState, rawFields,
    ) {
        if (initialRule == null) return@LaunchedEffect
        val sample = ReplacePreview.normalizeSample(previewInputState)
        val rule = getReplaceRule().copy()
        delay(PREVIEW_DEBOUNCE_MILLIS)
        try {
            previewOutputState = ReplacePreview.apply(rule, sample)
            previewErrorKey = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: StackOverflowError) {
            previewOutputState = sample
            previewErrorKey = ReplacePreviewMessage.Error.name
        } catch (error: Exception) {
            previewOutputState = sample
            previewErrorKey = ReplacePreviewMessage.fromReason((error as? ReplacePreviewException)?.reason).name
        }
    }

    BackHandler { requestBack() }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.replace_rule_edit),
                onBack = ::requestBack,
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
                            if (saving) return@LegadoMenuAction
                            saving = true
                            viewModel.save(
                                rule = getReplaceRule(),
                                sample = previewInputState,
                                onSaved = {
                                    saving = false
                                    saved = true
                                    onSaved()
                                },
                                onError = {
                                    saving = false
                                    context.toastOnUi("save error, ${it.localizedMessage}")
                                },
                            )
                        },
                    ),
                    LegadoMenuAction(
                        title = stringResource(R.string.copy_rule),
                        onClick = { context.sendToClip(GSON.toJson(getReplaceRuleForExport(getReplaceRule(), previewInputState))) },
                    ),
                    LegadoMenuAction(
                        title = stringResource(R.string.paste_rule),
                        onClick = {
                            viewModel.pasteRule(
                                text = context.getClipText(),
                                onLoaded = { populateRule(it) },
                                onError = { context.toastOnUi(it.localizedMessage ?: "Error") },
                            )
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
                label = stringResource(R.string.replace_rule_summary), displayValue = fieldDisplay(FIELD_NAME, nameState),
                isUnsafe = FIELD_NAME in rawFields, onValueChange = { nameState = it },
                onFocus = { focusedFieldId = FIELD_NAME }, onFullEdit = { onFullEditClicked(FIELD_NAME) },
            )
            Spacer(Modifier.height(8.dp))
            SafeRuleTextField(
                label = stringResource(R.string.group), displayValue = fieldDisplay(FIELD_GROUP, groupState),
                isUnsafe = FIELD_GROUP in rawFields, onValueChange = { groupState = it },
                onFocus = { focusedFieldId = FIELD_GROUP }, onFullEdit = { onFullEditClicked(FIELD_GROUP) },
            )
            Spacer(Modifier.height(8.dp))
            SafeRuleTextField(
                label = stringResource(R.string.replace_rule), displayValue = fieldDisplay(FIELD_PATTERN, patternState),
                isUnsafe = FIELD_PATTERN in rawFields, onValueChange = { patternState = it },
                onFocus = { focusedFieldId = FIELD_PATTERN }, onFullEdit = { onFullEditClicked(FIELD_PATTERN) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable { useRegexState = !useRegexState }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(useRegexState, { useRegexState = it })
                Text(stringResource(R.string.use_regex), style = MaterialTheme.typography.bodyMedium)
            }
            SafeRuleTextField(
                label = stringResource(R.string.replace_to), displayValue = fieldDisplay(FIELD_REPLACEMENT, replacementState),
                isUnsafe = FIELD_REPLACEMENT in rawFields, onValueChange = { replacementState = it },
                onFocus = { focusedFieldId = FIELD_REPLACEMENT }, onFullEdit = { onFullEditClicked(FIELD_REPLACEMENT) },
            )
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScopeCheckbox(R.string.scope_title, scopeTitleState) { scopeTitleState = it }
                ScopeCheckbox(R.string.scope_source, scopeSourceState) { scopeSourceState = it }
                ScopeCheckbox(R.string.scope_content, scopeContentState) { scopeContentState = it }
            }
            SafeRuleTextField(
                label = stringResource(R.string.replace_scope), displayValue = fieldDisplay(FIELD_SCOPE, scopeState),
                isUnsafe = FIELD_SCOPE in rawFields, onValueChange = { scopeState = it },
                onFocus = { focusedFieldId = FIELD_SCOPE }, onFullEdit = { onFullEditClicked(FIELD_SCOPE) },
            )
            Spacer(Modifier.height(8.dp))
            SafeRuleTextField(
                label = stringResource(R.string.replace_exclude_scope), displayValue = fieldDisplay(FIELD_EXCLUDE_SCOPE, excludeScopeState),
                isUnsafe = FIELD_EXCLUDE_SCOPE in rawFields, onValueChange = { excludeScopeState = it },
                onFocus = { focusedFieldId = FIELD_EXCLUDE_SCOPE }, onFullEdit = { onFullEditClicked(FIELD_EXCLUDE_SCOPE) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = timeoutState,
                onValueChange = { timeoutState = it },
                label = { Text(stringResource(R.string.timeout_millisecond)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedFieldId = FIELD_TIMEOUT },
            )
            Spacer(Modifier.height(10.dp))
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
                            context.toastOnUi(resources.getString(R.string.replace_preview_truncated, ReplacePreview.MAX_SAMPLE_LENGTH))
                        }
                    },
                    label = { Text(stringResource(R.string.replace_preview_input)) },
                    minLines = 4,
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) focusedFieldId = FIELD_PREVIEW_INPUT },
                )
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = previewOutputState,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.replace_preview_output)) },
                    isError = previewErrorKey != null,
                    supportingText = previewErrorKey?.let { key -> { Text(stringResource(ReplacePreviewMessage.fromKey(key).labelId)) } },
                    minLines = 4,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.exit)) },
            text = { Text(stringResource(R.string.exit_no_save)) },
            confirmButton = {
                TextButton({ confirmDiscard = false; onBack() }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.no)) }
            },
        )
    }

}

@Composable
private fun SafeRuleTextField(
    label: String,
    displayValue: String,
    isUnsafe: Boolean,
    onValueChange: (String) -> Unit,
    onFocus: () -> Unit,
    onFullEdit: () -> Unit,
) {
    OutlinedTextField(
        value = displayValue,
        onValueChange = { if (!isUnsafe) onValueChange(it) },
        readOnly = isUnsafe,
        label = { Text(label) },
        maxLines = if (isUnsafe) EditSafety.PREVIEW_LINES else 8,
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(if (isUnsafe) Modifier.clickable(onClick = onFullEdit) else Modifier),
    )
}

@Composable
private fun ScopeCheckbox(label: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun getReplaceRuleForExport(rule: ReplaceRule, previewInput: String): ReplaceRule = rule.also {
    it.previewText = ReplacePreview.normalizeSample(previewInput).takeIf(String::isNotEmpty)
}
