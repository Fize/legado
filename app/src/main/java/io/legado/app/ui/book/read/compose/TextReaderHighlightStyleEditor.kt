package io.legado.app.ui.book.read.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import io.legado.app.R
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles

@Composable
internal fun TextReaderHighlightStyleEditor(style: HighlightStyle,enabled: Boolean,onPickFont: () -> Unit,
    onColorValidity: (String,Boolean) -> Unit,onChange: ((HighlightStyle) -> HighlightStyle) -> Unit) {
    TextReaderHighlightPreview(style)
    FlowRow {
        HighlightStyles.presets.forEachIndexed { index,preset ->
            TextButton(onClick={ onChange { preset } },enabled=enabled,modifier=Modifier.testTag("annotation_preset_$index")) {
                Text(stringResource(R.string.reader_highlight_preset,index+1))
            }
        }
    }
    fun toggle(value: Boolean,change: (HighlightStyle,Boolean) -> HighlightStyle) { onChange { change(it,!value) } }
    FlowRow {
        FilterChip(style.fill!=0,{ toggle(style.fill!=0) { s,on -> s.copy(fill=if(on) 0x66FFEB3B else 0) } },enabled=enabled,
            modifier=Modifier.testTag("annotation_fill"),label={ Text(stringResource(R.string.highlight_bg_color)) })
        FilterChip(style.textColor!=0,{ toggle(style.textColor!=0) { s,on -> s.copy(textColor=if(on) 0xff000000.toInt() else 0) } },enabled=enabled,
            label={ Text(stringResource(R.string.highlight_text_color)) })
        FilterChip(style.bold,{ onChange { it.copy(bold=!it.bold) } },enabled=enabled,modifier=Modifier.testTag("annotation_bold"),label={ Text(stringResource(R.string.highlight_bold)) })
        FilterChip(style.italic,{ onChange { it.copy(italic=!it.italic) } },enabled=enabled,label={ Text(stringResource(R.string.highlight_italic)) })
        FilterChip(style.underline!=null,{ onChange { it.copy(underline=if(it.underline==null) HighlightStyle.Underline() else null) } },enabled=enabled,
            modifier=Modifier.testTag("annotation_underline"),label={ Text(stringResource(R.string.highlight_underline)) })
        FilterChip(style.strike!=null,{ onChange { it.copy(strike=if(it.strike==null) HighlightStyle.Deco() else null) } },enabled=enabled,label={ Text(stringResource(R.string.highlight_strike)) })
        FilterChip(style.box!=null,{ onChange { it.copy(box=if(it.box==null) HighlightStyle.Deco() else null) } },enabled=enabled,label={ Text(stringResource(R.string.highlight_box)) })
        FilterChip(style.emphasis!=null,{ onChange { it.copy(emphasis=if(it.emphasis==null) HighlightStyle.Deco() else null) } },enabled=enabled,label={ Text(stringResource(R.string.highlight_emphasis)) })
        FilterChip(style.shadow!=null,{ onChange { it.copy(shadow=if(it.shadow==null) HighlightStyle.Shadow() else null) } },enabled=enabled,label={ Text(stringResource(R.string.highlight_shadow)) })
    }
    if(style.fill!=0) {
        AnnotationColor(stringResource(R.string.highlight_bg_color),style.fill,enabled,"annotation_fill_color",onColorValidity) { value -> onChange { it.copy(fill=value) } }
        FlowRow {
            HighlightStyle.FillShape.entries.forEachIndexed { index,shape ->
                val label=listOf(R.string.highlight_fill_rectangle,R.string.highlight_fill_rounded,R.string.highlight_fill_marker,
                    R.string.highlight_fill_half,R.string.highlight_fill_baseline,R.string.highlight_fill_pill)[index]
                FilterChip(style.resolvedFillShape==shape,{ onChange { it.copy(fillShape=shape) } },enabled=enabled,
                    modifier=Modifier.testTag("annotation_shape_${shape.name}"),label={ Text(stringResource(label)) })
            }
        }
    }
    if(style.fill!=0 && style.resolvedFillShape==HighlightStyle.FillShape.PILL) {
        AnnotationSlider(stringResource(R.string.highlight_pill_padding),style.resolvedPillPaddingScale*100,25f..200f,enabled,"annotation_pill_padding") { value -> onChange { it.copy(pillPaddingScale=value/100) } }
        TextButton(onClick={ onChange { it.copy(pillPaddingScale=null) } },enabled=enabled,modifier=Modifier.testTag("annotation_pill_padding_reset")) { Text(stringResource(R.string.restore_default)) }
    }
    if(style.textColor!=0) AnnotationColor(stringResource(R.string.highlight_text_color),style.textColor,enabled,"annotation_text_color",onColorValidity) { value -> onChange { it.copy(textColor=value) } }
    style.underline?.let { underline ->
        FlowRow {
            HighlightStyle.Kind.entries.forEachIndexed { index,kind ->
                val label=listOf(R.string.highlight_underline_solid,R.string.highlight_underline_wavy,R.string.highlight_underline_dashed,
                    R.string.highlight_underline_dotted,R.string.highlight_underline_double)[index]
                FilterChip(underline.kind==kind,{ onChange { it.copy(underline=(it.underline ?: HighlightStyle.Underline()).copy(kind=kind)) } },enabled=enabled,
                    modifier=Modifier.testTag("annotation_line_${kind.name}"),label={ Text(stringResource(label)) })
            }
        }
        AnnotationColor(stringResource(R.string.highlight_underline),underline.color,enabled,"annotation_underline_color",onColorValidity) { value -> onChange { it.copy(underline=it.underline?.copy(color=value)) } }
        AnnotationSlider(stringResource(R.string.highlight_underline_width),underline.width,0f..10f,enabled) { value -> onChange { it.copy(underline=it.underline?.copy(width=value)) } }
        AnnotationSlider(stringResource(R.string.highlight_underline_distance),underline.distance,0f..30f,enabled) { value -> onChange { it.copy(underline=it.underline?.copy(distance=value)) } }
    }
    listOf(Triple(R.string.highlight_strike,style.strike,"strike"),Triple(R.string.highlight_box,style.box,"box"),Triple(R.string.highlight_emphasis,style.emphasis,"emphasis")).forEach { (label,deco,channel) ->
        deco?.let { AnnotationColor(stringResource(label),it.color,enabled,"annotation_${channel}_color",onColorValidity) { value -> onChange { current ->
            when(channel) { "strike" -> current.copy(strike=current.strike?.copy(color=value)); "box" -> current.copy(box=current.box?.copy(color=value)); else -> current.copy(emphasis=current.emphasis?.copy(color=value)) }
        } } }
    }
    style.shadow?.let { shadow ->
        AnnotationColor(stringResource(R.string.highlight_shadow),shadow.color,enabled,"annotation_shadow_color",onColorValidity) { value -> onChange { it.copy(shadow=it.shadow?.copy(color=value)) } }
        AnnotationSlider(stringResource(R.string.highlight_shadow_radius),shadow.radius,0f..10f,enabled) { value -> onChange { it.copy(shadow=it.shadow?.copy(radius=value)) } }
        AnnotationSlider(stringResource(R.string.highlight_shadow_offset_x),shadow.dx,-10f..10f,enabled) { value -> onChange { it.copy(shadow=it.shadow?.copy(dx=value)) } }
        AnnotationSlider(stringResource(R.string.highlight_shadow_offset_y),shadow.dy,-10f..10f,enabled) { value -> onChange { it.copy(shadow=it.shadow?.copy(dy=value)) } }
    }
    OutlinedTextField(style.resolvedFontPath,{ path -> onChange { it.copy(fontPath=path) } },Modifier.fillMaxWidth().testTag("annotation_font_path"),enabled=enabled,
        label={ Text(stringResource(R.string.reader_highlight_font)) },singleLine=true)
    TextButton(onClick=onPickFont,enabled=enabled) { Text(stringResource(R.string.select_file)) }
    FilterChip(style.fontSize!=null,{ onChange { it.copy(fontSize=if(it.fontSize==null) 20f else null) } },enabled=enabled,modifier=Modifier.testTag("annotation_font_size_toggle"),label={ Text(stringResource(R.string.title_font_size)) })
    style.resolvedFontSize?.let { size -> AnnotationSlider(stringResource(R.string.title_font_size),size,5f..100f,enabled,"annotation_font_size") { value -> onChange { it.copy(fontSize=value) } } }
    FilterChip(style.letterSpacing!=null,{ onChange { it.copy(letterSpacing=if(it.letterSpacing==null) 0f else null) } },enabled=enabled,modifier=Modifier.testTag("annotation_letter_spacing_toggle"),label={ Text(stringResource(R.string.text_letter_spacing)) })
    style.resolvedLetterSpacing?.let { spacing -> AnnotationSlider(stringResource(R.string.text_letter_spacing),spacing,-.5f..1f,enabled,"annotation_letter_spacing") { value -> onChange { it.copy(letterSpacing=value) } } }
}

@Composable
private fun AnnotationColor(label: String,value: Int,enabled: Boolean,tag: String,onValidity: (String,Boolean) -> Unit,onChange: (Int) -> Unit) {
    var input by remember(value) { mutableStateOf("#%08X".format(value)) }
    val hex=input.removePrefix("#")
    val parsed=if(hex.length==6 || hex.length==8) hex.toLongOrNull(16)?.let { if(hex.length==6) (it or 0xff000000).toInt() else it.toInt() } else null
    SideEffect { onValidity(tag,parsed!=null) }
    DisposableEffect(tag) { onDispose { onValidity(tag,true) } }
    OutlinedTextField(input,{ text -> if(text.length<=9) { input=text; val raw=text.removePrefix("#"); onValidity(tag,(raw.length==6 || raw.length==8) && raw.toLongOrNull(16)!=null); if(raw.length==6 || raw.length==8) raw.toLongOrNull(16)?.let { onChange(if(raw.length==6) (it or 0xff000000).toInt() else it.toInt()) } } },
        Modifier.fillMaxWidth().testTag(tag),enabled=enabled,singleLine=true,label={ Text(label) },isError=parsed==null,
        supportingText={ Text(stringResource(R.string.reader_highlight_color_hex)) })
}
@Composable
private fun AnnotationSlider(label: String,value: Float,range: ClosedFloatingPointRange<Float>,enabled: Boolean,tag: String="",onChange: (Float) -> Unit) {
    Text("$label: ${"%.1f".format(value)}",style=MaterialTheme.typography.bodySmall)
    Slider(value.coerceIn(range),onChange,modifier=if(tag.isEmpty()) Modifier else Modifier.testTag(tag),enabled=enabled,valueRange=range)
}

@Composable
private fun TextReaderHighlightPreview(style: HighlightStyle) {
    val caption=stringResource(R.string.reader_highlight_preview)
    val text=stringResource(R.string.reader_highlight_sample)
    val annotations=remember(style,text) { listOf(TextReaderAnnotation(0,text.length,style)) }
    val fonts=rememberTextReaderAnnotationFonts(annotations)
    val annotated=remember(text,annotations,fonts) { textReaderApplyAnnotations(AnnotatedString(text),annotations,fonts) }
    val measurer=rememberTextMeasurer(cacheSize=1)
    val color=MaterialTheme.colorScheme.onSurface
    val density=LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout=remember(annotated,annotations,size,measurer,color) {
        if(size.width>0 && size.height>0) TextReaderLayout.measure(TextReaderRenderKey(TextReaderContentKey("preview","preview","preview",0,0),size.width,size.height,0),
            annotated,TextStyle(fontSize=20.sp,color=color),measurer,annotations=annotations) else null
    }
    val height=with(density) {
        (layout?.paragraphs?.sumOf { it.height.toDouble() }?.toFloat() ?: 0f).coerceIn(64.dp.toPx(),160.dp.toPx()).toDp()
    }
    Canvas(Modifier.fillMaxWidth().height(height).onSizeChanged { size=it }.testTag("annotation_style_preview").semantics { contentDescription=caption }) {
        layout?.let { rendered ->
            val page=rendered.pageAtOffset(0)
            val inset=((this.size.height-page.height)/2).coerceAtLeast(0f)
            rendered.drawSlices(page).forEach { slice -> clipRect(top=slice.top-page.top+inset,bottom=slice.bottom-page.top+inset) {
                translate(top=slice.origin-page.top+inset) { drawTextReaderAnnotationFills(slice.annotations); drawText(slice.layout); drawTextReaderAnnotationDecorations(slice.annotations) }
            } }
        }
    }
}
