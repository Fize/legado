package io.legado.app.ui.book.read.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.ReaderAnnotationItem

@Composable
internal fun TextReaderAnnotationDialogs(state: TextReaderUiState,model: TextReaderViewModel) {
    val draft=state.annotationDraft
    val context=LocalContext.current
    var fontRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val fontPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id=fontRequest; fontRequest=null
        if(uri!=null && id!=null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.annotationStyle(id) { it.copy(fontPath=uri.toString()) }
        }
    }
    if(draft!=null) key(draft.id) {
    var showStyle by rememberSaveable { mutableStateOf(false) }
    var invalidColors by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest={ model.finishAnnotation(false,draft.id) },
        modifier=Modifier.testTag("text_reader_annotation_editor"),
        title={ Text(stringResource(if(draft.highlight) R.string.highlight_note else R.string.bookmark)) },
        text={
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(draft.chapterName,style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(draft.bookText,{ model.annotationText(draft.id,text=it) },
                    Modifier.fillMaxWidth().testTag("text_reader_annotation_text"),enabled=!state.annotationBusy,
                    label={ Text(stringResource(R.string.content)) },maxLines=5)
                OutlinedTextField(draft.note,{ model.annotationText(draft.id,note=it) },
                    Modifier.fillMaxWidth().testTag("text_reader_annotation_note"),enabled=!state.annotationBusy,
                    label={ Text(stringResource(R.string.note_content)) },maxLines=5)
                if(draft.highlight) {
                    TextButton(onClick={ showStyle=!showStyle },enabled=!state.annotationBusy,modifier=Modifier.testTag("annotation_style_toggle")) { Text(stringResource(R.string.highlight_style)) }
                    if(showStyle) TextReaderHighlightStyleEditor(GSON.fromJsonObject<HighlightStyle>(draft.style).getOrNull()?.normalized() ?: HighlightStyle(),
                        !state.annotationBusy,onPickFont={ fontRequest=draft.id; fontPicker.launch(arrayOf("*/*")) },
                        onColorValidity={ tag,valid -> invalidColors=if(valid) invalidColors-tag else invalidColors+tag }) { change -> model.annotationStyle(draft.id,change) }
                }
                state.annotationError?.let { Text(annotationMessage(it),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("text_reader_annotation_error")) }
            }
        },dismissButton={
            FlowRow {
                state.annotations.firstOrNull { it.time==draft.editingTime && it.highlight==draft.highlight }?.let { row ->
                    TextButton(onClick={ model.deleteAnnotation(row,draft.id) },enabled=!state.annotationBusy,
                        modifier=Modifier.testTag("text_reader_annotation_delete")) { Text(stringResource(R.string.delete)) }
                }
                TextButton(onClick={ model.finishAnnotation(false,draft.id) },enabled=!state.annotationBusy) { Text(stringResource(R.string.cancel)) }
            }
        },confirmButton={ TextButton(onClick={ model.finishAnnotation(true,draft.id) },enabled=!state.annotationBusy && invalidColors.isEmpty(),
            modifier=Modifier.testTag("text_reader_annotation_save")) { Text(stringResource(R.string.ok)) } })
    } else if(state.annotationList) AlertDialog(onDismissRequest={ model.annotationList(false) },
        modifier=Modifier.testTag("text_reader_annotations"),title={ Text(stringResource(R.string.reader_annotations)) },
        text={
            Column {
                if(state.annotations.isEmpty()) Text(stringResource(R.string.empty))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=480.dp)) {
                    items(state.annotations,key={ it.key }) { row ->
                        Column(Modifier.fillMaxWidth().clickable(enabled=!state.annotationBusy) { model.jumpAnnotation(row) }
                            .padding(vertical=8.dp).testTag("text_reader_annotation_${row.key}")) {
                            Text(row.title,style=MaterialTheme.typography.titleSmall)
                            Text(row.text,maxLines=3)
                            if(row.note.isNotBlank()) Text(row.note,maxLines=3,style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={ model.editAnnotation(row) },enabled=!state.annotationBusy,
                                modifier=Modifier.testTag("text_reader_annotation_edit_${row.key}")) { Text(stringResource(R.string.edit)) }
                        }
                    }
                }
                state.annotationError?.let { Text(annotationMessage(it),color=MaterialTheme.colorScheme.error) }
            }
        },confirmButton={ TextButton(onClick={ model.annotationList(false) }) { Text(stringResource(R.string.close)) } })
}

@Composable
internal fun annotationMessage(error: String): String=stringResource(when {
    error=="Annotation text too long" -> R.string.reader_annotation_too_long
    error.contains("owns") || error.contains("expired") -> R.string.reader_return_request_expired
    error.contains("changed") -> R.string.reader_annotation_changed
    else -> R.string.reader_annotation_failed
})
