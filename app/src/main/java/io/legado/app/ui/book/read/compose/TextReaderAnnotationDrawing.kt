package io.legado.app.ui.book.read.compose

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.*
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import kotlin.math.*

/** All positions are paragraph-local native layout coordinates, including bidi and font fallback. */
internal data class TextReaderAnnotationRun(val line: Int,val left: Float,val right: Float,
    val top: Float,val bottom: Float,val baseline: Float,val inkTop: Float,val inkBottom: Float,
    val textSize: Float,val marks: List<Float>,val style: HighlightStyle,val fallbackColor: Int)

private fun HighlightStyle.Underline.extent(density: Float): Float {
    val width=width*density
    return when(kind) {
        HighlightStyle.Kind.DOUBLE -> (width+density)/2+width/2
        HighlightStyle.Kind.WAVY -> max(width,density)+width/2
        else -> width/2
    }
}

internal fun textReaderAnnotationRuns(layout: TextLayoutResult,annotations: List<TextReaderAnnotation>,fallback: Int,inkReference: TextLayoutResult=layout): List<TextReaderAnnotationRun> {
    val text=layout.layoutInput.text.text
    val density=layout.layoutInput.density
    return buildList {
        annotations.forEach { annotation ->
            val start=annotation.start.coerceIn(0,text.length); val end=annotation.end.coerceIn(start,text.length)
            if(start==end) return@forEach
            val first=layout.getLineForOffset(start); val last=layout.getLineForOffset(end-1)
            for(line in first..last) {
                var from=max(start,layout.getLineStart(line)); var to=min(end,layout.getLineEnd(line,true))
                while(from<to && text[from].isWhitespace()) from++
                while(to>from && text[to-1].isWhitespace()) to--
                val boxes=ArrayList<Pair<Rect,Boolean>>()
                var offset=from
                var size=0f
                while(offset<to) {
                    if(text[offset]!='\uFFFC') {
                        val shaped=layout.getBoundingBox(offset)
                        val original=inkReference.getBoundingBox(offset)
                        val shift=layout.getLineBaseline(line)-inkReference.getLineBaseline(line)
                        val box=Rect(shaped.left,original.top+shift,shaped.right,original.bottom+shift)
                        if(box.width>0 && box.height>0) boxes+=box to !text[offset].isWhitespace()
                        val font=layout.layoutInput.text.spanStyles.lastOrNull { it.start<=offset && it.end>offset && it.item.fontSize.isSpecified }?.item?.fontSize
                            ?: layout.layoutInput.style.fontSize.takeIf { it.isSpecified } ?: 16.sp
                        size=max(size,if(font.isEm) font.value*with(density) { 16.sp.toPx() } else with(density) { font.toPx() })
                    }
                    offset+=Character.charCount(text.codePointAt(offset))
                }
                val sorted=boxes.distinctBy { it.first }.sortedBy { it.first.left }
                var group=ArrayList<Pair<Rect,Boolean>>()
                fun flush() {
                    if(group.isEmpty()) return
                    add(TextReaderAnnotationRun(line,group.minOf { it.first.left },group.maxOf { it.first.right },
                        layout.getLineTop(line),layout.getLineBottom(line),layout.getLineBaseline(line),
                        group.minOf { it.first.top },group.maxOf { it.first.bottom },size,
                        group.filter { it.second }.map { it.first.center.x }.distinct(),annotation.style,fallback))
                    group=ArrayList()
                }
                sorted.forEach { box ->
                    if(group.isNotEmpty() && box.first.left>group.maxOf { it.first.right }+.5f) flush()
                    group+=box
                }
                flush()
            }
        }
    }
}

internal fun measureTextReaderAnnotations(original: TextLayoutResult,annotations: List<TextReaderAnnotation>,
    inkReference: TextLayoutResult=original,measure: (AnnotatedString) -> TextLayoutResult): TextLayoutResult {
    if(annotations.isEmpty()) return original
    val density=original.layoutInput.density.density
    var result=original
    repeat(6) {
        val shortage=textReaderAnnotationRuns(result,annotations,0,inkReference).maxOfOrNull { run ->
            var top=run.inkTop; var bottom=run.inkBottom
            run.style.underline?.takeIf { it.width>0 }?.let { underline ->
                val y=run.baseline+underline.distance*density; val extent=underline.extent(density)
                top=min(top,y-extent); bottom=max(bottom,y+extent)
            }
            if(run.style.emphasis!=null) bottom=max(bottom,run.inkBottom+4.6f*density)
            run.style.shadow?.let { shadow -> top=min(top,run.inkTop+shadow.dy-shadow.radius); bottom=max(bottom,run.inkBottom+shadow.dy+shadow.radius) }
            if(run.style.fill!=0) when(run.style.resolvedFillShape) {
                HighlightStyle.FillShape.BASELINE -> bottom=max(bottom,run.baseline+5*density)
                HighlightStyle.FillShape.ROUNDED,HighlightStyle.FillShape.MARKER,HighlightStyle.FillShape.PILL -> {
                    top=min(top,run.baseline-run.textSize*.9f-2*density); bottom=max(bottom,run.baseline+run.textSize*.16f+2*density)
                }
                else -> Unit
            }
            max(run.top-top,bottom-run.bottom)
        } ?: 0f
        if(shortage<=.01f) return result
        val height=(0 until result.lineCount).maxOf { result.getLineBottom(it)-result.getLineTop(it) }+2*ceil(shortage+1)
        val input=result.layoutInput.text
        val paragraph=input.paragraphStyles.fold(ParagraphStyle()) { a,b -> a.merge(b.item) }.copy(
            lineHeight=with(result.layoutInput.density) { height.toSp() },lineHeightStyle=LineHeightStyle(
                LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None,LineHeightStyle.Mode.Minimum))
        result=measure(AnnotatedString(input.text,input.spanStyles,listOf(AnnotatedString.Range(paragraph,0,input.length))))
    }
    error("Annotation decorations must fit inside native line boxes")
}

internal fun DrawScope.drawTextReaderAnnotationFills(runs: List<TextReaderAnnotationRun>) {
    runs.forEach { run ->
        val style=run.style
        if(style.fill==0) return@forEach
        val band=HighlightGeometry.fillBand(run.baseline-run.top,run.textSize,run.bottom-run.top,style.resolvedFillShape,density)
        val top=band.top+run.top; val bottom=band.bottom+run.top
        val rect=Rect(run.left,top,run.right,bottom)
        if(rect.width<=0 || rect.height<=0) return@forEach
        val color=Color(style.fill)
        when(style.resolvedFillShape) {
            HighlightStyle.FillShape.RECTANGLE,HighlightStyle.FillShape.HALF,HighlightStyle.FillShape.BASELINE -> drawRect(color,rect.topLeft,rect.size)
            HighlightStyle.FillShape.ROUNDED -> drawRoundRect(color,rect.topLeft,rect.size,CornerRadius(3*density))
            HighlightStyle.FillShape.MARKER -> {
                val unit=2*density
                val path=Path().apply { addRoundRect(RoundRect(rect,CornerRadius(unit,2*unit),CornerRadius(2.5f*unit,unit),CornerRadius(1.5f*unit,2*unit),CornerRadius(2*unit,unit))) }
                drawPath(path,Brush.horizontalGradient(listOf(color.copy(alpha=0f),color,color,color.copy(alpha=0f)),rect.left,rect.right))
            }
            HighlightStyle.FillShape.PILL -> {
                val radius=CornerRadius(min(rect.width/2,rect.height/2),rect.height/2)
                drawRoundRect(color.copy(alpha=color.alpha*.35f),rect.topLeft,rect.size,radius)
                val border=density.coerceAtMost(min(rect.width,rect.height)/2)
                if(border>0) drawRoundRect(color,Offset(rect.left+border/2,rect.top+border/2),Size(rect.width-border,rect.height-border),
                    CornerRadius(max(0f,radius.x-border/2),max(0f,radius.y-border/2)),style=Stroke(border))
            }
        }
    }
}

internal fun DrawScope.drawTextReaderAnnotationDecorations(runs: List<TextReaderAnnotationRun>) {
    runs.forEach { run ->
        val style=run.style
        fun color(value: Int)=Color(if(value!=0) value else if(style.textColor!=0) style.textColor else run.fallbackColor)
        style.underline?.takeIf { it.width>0 }?.let { underline ->
            val width=underline.width*density; val y=run.baseline+underline.distance*density
            val extent=underline.extent(density)
            clipRect(run.left,y-extent,run.right,y+extent) {
                fun line(at: Float,dash: PathEffect?=null)=drawLine(color(underline.color),Offset(run.left,at),Offset(run.right,at),width,pathEffect=dash)
                when(underline.kind) {
                    HighlightStyle.Kind.SOLID -> line(y)
                    HighlightStyle.Kind.DASHED -> line(y,PathEffect.dashPathEffect(floatArrayOf(6*density,4*density)))
                    HighlightStyle.Kind.DOUBLE -> { val half=(width+density)/2; line(y-half); line(y+half) }
                    HighlightStyle.Kind.DOTTED -> {
                        var x=run.left+width/2; val step=max(width*2,3.5f*density)
                        while(x<=run.right) { drawCircle(color(underline.color),width/2,Offset(x,y)); x+=step }
                    }
                    HighlightStyle.Kind.WAVY -> {
                        val points=HighlightGeometry.wavePoints(run.left,run.right,y,max(width,density),6*density,density)
                        val path=Path()
                        if(points.size>=4) { path.moveTo(points[0],points[1]); for(i in 2 until points.size step 2) path.lineTo(points[i],points[i+1]); drawPath(path,color(underline.color),style=Stroke(width)) }
                    }
                }
            }
        }
        style.strike?.let { drawLine(color(it.color),Offset(run.left,(run.inkTop+run.inkBottom)/2),Offset(run.right,(run.inkTop+run.inkBottom)/2),1.5f*density) }
        style.box?.let {
            val width=1.5f*density; val inset=width/2
            if(run.right-run.left>width && run.inkBottom-run.inkTop>width) drawRect(color(it.color),Offset(run.left+inset,run.inkTop+inset),Size(run.right-run.left-width,run.inkBottom-run.inkTop-width),style=Stroke(width))
        }
        style.emphasis?.let { deco -> run.marks.forEach { x -> drawCircle(color(deco.color),1.6f*density,Offset(x,run.inkBottom+3*density)) } }
    }
}
