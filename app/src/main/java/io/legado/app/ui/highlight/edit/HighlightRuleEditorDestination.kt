package io.legado.app.ui.highlight.edit

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.book.read.compose.TextReaderHighlightStyleEditor
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.HighlightRuleEditRoute

@Composable
fun HighlightRuleEditorDestination(route: HighlightRuleEditRoute,onBack: () -> Unit,
    model: HighlightRuleEditorViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(route) { model.initialize(route) }
    LaunchedEffect(state.closed) { if(state.closed) back() }
    fun cancel() { model.finish(route.requestId,false) }
    BackHandler { cancel() }
    val context=LocalContext.current
    var fontRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val fontPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id=fontRequest; fontRequest=null
        if(uri!=null && id!=null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.style(id) { it.copy(fontPath=uri.toString()) }
        }
    }
    var invalidColors by remember(route.requestId) { mutableStateOf(emptySet<String>()) }
    var showStyle by rememberSaveable(route.requestId) { mutableStateOf(false) }
    val enabled=!state.loading && !state.busy && !state.closed && state.value!=null
    Scaffold(modifier=Modifier.testTag("highlight_rule_editor"),topBar={
        LegadoTopAppBar(title=stringResource(R.string.highlight_rule_edit_title),onBack={ cancel() })
    },bottomBar={ Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.End) {
        TextButton(onClick={ cancel() },enabled=!state.busy && !state.loading,
            modifier=Modifier.testTag("highlightRuleEditCancel")) { Text(stringResource(R.string.cancel)) }
        TextButton(onClick={ model.finish(route.requestId,true) },enabled=enabled && invalidColors.isEmpty(),
            modifier=Modifier.testTag("highlightRuleEditSave")) { Text(stringResource(R.string.ok)) }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(state.loading) CircularProgressIndicator()
            if(state.failed) Text(stringResource(if(state.value==null) R.string.highlight_rule_not_found else R.string.highlight_rule_editor_failed),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("highlightRuleEditError"))
            if(state.invalid) Text(stringResource(R.string.highlight_rule_invalid,state.value?.pattern.orEmpty()),color=MaterialTheme.colorScheme.error,
                modifier=Modifier.testTag("highlightRuleEditInvalid"))
            state.value?.let { rule -> key(route.requestId) {
                OutlinedTextField(rule.name,{ text -> model.edit(route.requestId) { it.copy(name=text) } },Modifier.fillMaxWidth().testTag("highlightRuleEditName"),
                    enabled=enabled,label={ Text(stringResource(R.string.name)) })
                OutlinedTextField(rule.group.orEmpty(),{ text -> model.edit(route.requestId) { it.copy(group=text) } },Modifier.fillMaxWidth().testTag("highlightRuleEditGroup"),
                    enabled=enabled,label={ Text(stringResource(R.string.highlight_rule_group_hint)) })
                OutlinedTextField(rule.pattern,{ text -> model.edit(route.requestId) { it.copy(pattern=text) } },Modifier.fillMaxWidth().testTag("highlightRuleEditPattern"),
                    enabled=enabled,label={ Text(stringResource(R.string.highlight_rule_pattern)) })
                FlowRow {
                    FilterChip(rule.isRegex,{ model.edit(route.requestId) { it.copy(isRegex=!rule.isRegex) } },enabled=enabled,
                        modifier=Modifier.testTag("highlightRuleEditRegex"),label={ Text(stringResource(R.string.use_regex)) })
                    FilterChip(rule.applyToBody,{ model.edit(route.requestId) { it.copy(applyToBody=!rule.applyToBody) } },enabled=enabled,
                        modifier=Modifier.testTag("highlightRuleEditBody"),label={ Text(stringResource(R.string.scope_content)) })
                    FilterChip(rule.applyToTitle,{ model.edit(route.requestId) { it.copy(applyToTitle=!rule.applyToTitle) } },enabled=enabled,
                        modifier=Modifier.testTag("highlightRuleEditTitle"),label={ Text(stringResource(R.string.highlight_rule_apply_to_title)) })
                }
                OutlinedTextField(rule.scope.orEmpty(),{ text -> model.edit(route.requestId) { it.copy(scope=text) } },Modifier.fillMaxWidth().testTag("highlightRuleEditScope"),
                    enabled=enabled,label={ Text(stringResource(R.string.highlight_rule_scope_hint)) })
                TextButton(onClick={ showStyle=!showStyle },enabled=enabled,modifier=Modifier.testTag("highlightRuleEditStyle")) { Text(stringResource(R.string.highlight_style)) }
                if(showStyle) TextReaderHighlightStyleEditor(state.style,enabled,
                    onPickFont={ fontRequest=route.requestId; fontPicker.launch(arrayOf("*/*")) },
                    onColorValidity={ tag,valid -> invalidColors=if(valid) invalidColors-tag else invalidColors+tag }) { change -> model.style(route.requestId,change) }
            } }
        }
    }
}
