package io.legado.app.ui.book.read.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.legado.app.data.repository.ReaderAnnotationItem
import io.legado.app.data.repository.TextReaderEntry
import io.legado.app.help.HighlightAnchor
import io.legado.app.help.HighlightStyle
import io.legado.app.model.TextReaderDocument
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/** Stored offsets include the title; EPUB display text may have a separate coordinate space. */
internal fun textReaderAnnotationRange(document: TextReaderDocument,start: Int,end: Int,oldTitle: Int,quote: String): IntRange {
    val source=document.storage ?: document
    val at=source.position(start,oldTitle.takeIf { it>=0 })
    val finish=(end.toLong()+at-start).coerceIn(at.toLong(),source.text.length.toLong()).toInt()
    val a=document.positions?.toDisplay(at) ?: at
    val b=document.positions?.toDisplayEnd(finish) ?: finish
    val anchored=if(end>start) HighlightAnchor.reanchor(document.text,a,b,quote) else null
    val left=(anchored?.start ?: a).coerceIn(0,document.text.length)
    val right=(anchored?.end ?: b).coerceIn(left,document.text.length)
    if(end>start && (anchored==null || quote.isNotEmpty() && right-left==quote.length &&
            !document.text.startsWith(quote,left))) return left until left
    return left until right
}
internal fun ReaderAnnotationItem.belongsTo(entry: TextReaderEntry)=highlight && chapterIndex==entry.chapterIndex &&
    (chapterUrl.isEmpty() || chapterUrl==entry.chapterUrl)

internal data class TextReaderAnnotation(val start: Int,val end: Int,val style: HighlightStyle)

internal fun textReaderManualAnnotations(document: TextReaderDocument,entry: TextReaderEntry,
    rows: List<ReaderAnnotationItem>): List<TextReaderAnnotation> = textReaderResolveAnnotations(rows.filter { it.belongsTo(entry) }.mapNotNull { row ->
    val range=textReaderAnnotationRange(document,row.position,row.end,row.titleLength,row.text)
    if(range.isEmpty() || range.last<document.titleLength) null else TextReaderAnnotation(maxOf(range.first,document.titleLength),range.last+1,
        row.parsedStyle ?: GSON.fromJsonObject<HighlightStyle>(row.style).getOrNull()?.normalized() ?: HighlightStyle())
},document.indents.map { it.start until it.end })

internal fun textReaderCombinedAnnotations(document: TextReaderDocument,entry: TextReaderEntry,
    rows: List<ReaderAnnotationItem>,rules: List<io.legado.app.data.repository.TextReaderRuleMatch>): List<TextReaderAnnotation> =
    textReaderResolveAnnotations(rules.map { TextReaderAnnotation(it.start,it.end,it.rule.style) }+
        textReaderManualAnnotations(document,entry,rows),document.indents.map { it.start until it.end })

/** Resolve overlapping channels once. Fills and strokes must not be drawn twice on the overlap. */
internal fun textReaderResolveAnnotations(annotations: List<TextReaderAnnotation>,excluded: List<IntRange> = emptyList()): List<TextReaderAnnotation> {
    val boundaries=sortedSetOf<Int>()
    val starts=hashMapOf<Int,MutableList<Int>>(); val ends=hashMapOf<Int,MutableList<Int>>()
    annotations.forEachIndexed { index,a ->
        if(a.end>a.start) { boundaries+=a.start; boundaries+=a.end; starts.getOrPut(a.start) { arrayListOf() }+=index; ends.getOrPut(a.end) { arrayListOf() }+=index }
    }
    val excludedStarts=excluded.filterNot { it.isEmpty() }.groupingBy { it.first }.eachCount()
    val excludedEnds=excluded.filterNot { it.isEmpty() }.groupingBy { it.last+1 }.eachCount()
    boundaries+=excludedStarts.keys; boundaries+=excludedEnds.keys
    val active=java.util.TreeMap<Int,HighlightStyle>(); val result=arrayListOf<TextReaderAnnotation>()
    var skipped=0
    boundaries.zipWithNext().forEach { (start,end) ->
        ends[start]?.forEach(active::remove); starts[start]?.forEach { active[it]=annotations[it].style }
        skipped+=(excludedStarts[start] ?: 0)-(excludedEnds[start] ?: 0)
        if(active.isNotEmpty() && skipped==0) {
            val style=active.values.fold(HighlightStyle()) { a,b -> HighlightStyle.merge(a,b) }
            val previous=result.lastOrNull()
            if(previous?.end==start && previous.style==style) result[result.lastIndex]=previous.copy(end=end)
            else result+=TextReaderAnnotation(start,end,style)
        }
    }
    return result
}
internal fun textReaderApplyAnnotations(base: AnnotatedString,annotations: List<TextReaderAnnotation>,
    fonts: Map<String,androidx.compose.ui.text.font.FontFamily> = emptyMap()): AnnotatedString=buildAnnotatedString {
    append(base)
    annotations.forEach { annotation ->
        val style=annotation.style
        addStyle(SpanStyle(color=if(style.textColor!=0) Color(style.textColor) else Color.Unspecified,
            fontWeight=if(style.bold) FontWeight.Bold else null,fontStyle=if(style.italic) FontStyle.Italic else null,
            fontFamily=fonts[style.resolvedFontPath],
            fontSize=style.resolvedFontSize?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
            letterSpacing=style.resolvedLetterSpacing?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified,
            shadow=style.shadow?.let { androidx.compose.ui.graphics.Shadow(Color(it.color),androidx.compose.ui.geometry.Offset(it.dx,it.dy),it.radius) }),
            annotation.start,annotation.end)
    }
}
