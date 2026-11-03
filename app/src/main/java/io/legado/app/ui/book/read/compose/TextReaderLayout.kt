package io.legado.app.ui.book.read.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.Constraints
import io.legado.app.model.TextReaderUnderline

/** Each retained native layout owns one paragraph; painting it cannot expose a neighbour's glyphs. */
internal class TextReaderLayout private constructor(
    val key: TextReaderRenderKey,
    private val annotated: AnnotatedString,
    private val reference: () -> TextLayoutResult,
    val pages: List<TextReaderPageSlice>,
    val paragraphs: List<Paragraph>,
    private val lines: List<TextReaderLine>,
    private val lineParagraphs: List<Int>,
    private val placeholderOwners: Map<Int, Pair<Int, Int>>,
    val underline: TextReaderUnderline,
) {
    data class Paragraph(val start: Int, val end: Int, val visibleEnd: Int, val layout: TextLayoutResult,
        val top: Float, val sourceTop: Float, val height: Float, val underlines: List<TextReaderUnderlineRun> = emptyList(),
        val annotations: List<TextReaderAnnotationRun> = emptyList())
    data class DrawSlice(val paragraph: Int, val top: Float, val bottom: Float, val origin: Float,
        val layout: TextLayoutResult, val underlines: List<TextReaderUnderlineRun> = emptyList(),
        val annotations: List<TextReaderAnnotationRun> = emptyList())
    /** Optional whole-document reference for diagnostics; production paints only paragraph layouts. */
    val text: TextLayoutResult by lazy(reference)
    val chapterText: String get() = annotated.text
    fun pageAtOffset(offset: Int): TextReaderPageSlice = pages[textReaderPageAtOffset(pages, offset)]
    fun visibleText(page: TextReaderPageSlice): String = chapterText.substring(page.start, page.end)

    private fun paragraphAt(offset: Int): Paragraph {
        val target = offset.coerceIn(0, chapterText.length)
        var low = 0; var high = paragraphs.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (paragraphs[middle].start <= target) low = middle else high = middle - 1
        }
        return paragraphs[low]
    }
    private fun lineAt(page: TextReaderPageSlice, y: Float): Int =
        (page.firstLine until page.lastLineExclusive).minByOrNull {
            val line = lines[it]
            when { y < line.top -> line.top - y; y > line.bottom -> y - line.bottom; else -> 0f }
        } ?: page.firstLine

    fun offsetAt(page: TextReaderPageSlice, position: Offset): Int {
        val y = position.y + page.top
        val line = lineAt(page, y)
        val paragraph = paragraphs[lineParagraphs[line]]
        val sourceY = y.coerceIn(lines[line].top, (lines[line].bottom - .01f).coerceAtLeast(lines[line].top)) - paragraph.top
        val offset = paragraph.start + paragraph.layout.getOffsetForPosition(position.copy(y = sourceY))
        return textReaderCodePointBoundary(chapterText, offset.coerceIn(page.start, page.end))
    }
    /** A bidi boundary can share visual caret positions with a different logical character. */
    fun characterAt(page: TextReaderPageSlice, position: Offset): Int {
        val point=position.copy(y=position.y+page.top)
        val line=lines[lineAt(page,point.y)]
        var offset=maxOf(line.start,page.start)
        val end=minOf(line.end,page.end)
        var hit: Int?=null; var whitespace: Int?=null; var width=Float.MAX_VALUE
        while(offset<end) {
            if(chapterText[offset]!='\n' && chapterText[offset]!='\r') {
                val box=boundingBox(offset)
                if(box.width>0 && box.contains(point)) {
                    if(chapterText[offset].isWhitespace()) whitespace=offset
                    else if(box.width<width) { hit=offset; width=box.width }
                }
            }
            offset+=Character.charCount(chapterText.codePointAt(offset))
        }
        return hit ?: whitespace ?: offsetAt(page,position)
    }
    fun wordAt(page: TextReaderPageSlice, position: Offset): TextRange {
        if (chapterText.isEmpty()) return TextRange.Zero
        val offset = characterAt(page, position).coerceAtMost(chapterText.lastIndex)
        val paragraph = paragraphAt(offset)
        if (paragraph.visibleEnd == paragraph.start) return TextRange(offset, offset)
        val local = (offset - paragraph.start).coerceIn(0, paragraph.visibleEnd - paragraph.start - 1)
        return paragraph.layout.getWordBoundary(local).let {
            TextRange((paragraph.start + it.start).coerceAtLeast(page.start),
                (paragraph.start + it.end).coerceAtMost(page.end))
        }
    }
    private fun paragraphBounds(offset: Int, source: Boolean): Rect {
        val paragraph = paragraphAt(offset)
        val top = if (source) paragraph.sourceTop else paragraph.top
        if (paragraph.visibleEnd == paragraph.start) return Rect(0f, top, 0f, top + paragraph.height)
        val local = (offset - paragraph.start).coerceIn(0, paragraph.visibleEnd - paragraph.start - 1)
        return paragraph.layout.getBoundingBox(local).translate(0f, top)
    }
    fun boundingBox(offset: Int): Rect = paragraphBounds(offset, false)
    fun sourceBoundingBox(offset: Int): Rect = paragraphBounds(offset, true)
    fun placeholderRect(index: Int, offset: Int, source: Boolean = false): Rect? {
        val (owner, local) = placeholderOwners[index] ?: return null
        val paragraph = paragraphs[owner]
        if (offset !in paragraph.start until paragraph.end) return null
        return paragraph.layout.placeholderRects.getOrNull(local)?.translate(0f,
            if (source) paragraph.sourceTop else paragraph.top)
    }
    fun selectionPaths(selection: TextRange?): Map<Int, Path> {
        if (selection == null || selection.collapsed) return emptyMap()
        return buildMap {
            paragraphs.forEachIndexed { index, paragraph ->
                val start = maxOf(selection.min, paragraph.start)
                val end = minOf(selection.max, paragraph.visibleEnd)
                if (end > start) put(index, paragraph.layout.getPathForRange(start - paragraph.start, end - paragraph.start))
            }
        }
    }
    fun drawSlices(page: TextReaderPageSlice): List<DrawSlice> {
        val groups = ArrayList<DrawSlice>()
        for (line in page.firstLine until page.lastLineExclusive) {
            val owner = lineParagraphs[line]
            val paragraph = paragraphs[owner]
            val previous = groups.lastOrNull()
            if (previous?.paragraph == owner) groups[groups.lastIndex] = previous.copy(bottom = lines[line].bottom)
            else groups += DrawSlice(owner, lines[line].top, lines[line].bottom, paragraph.top, paragraph.layout)
        }
        return groups.map { slice -> slice.copy(underlines = paragraphs[slice.paragraph].underlines.filter {
            it.bottom + slice.origin > slice.top && it.top + slice.origin < slice.bottom
        },annotations=paragraphs[slice.paragraph].annotations.filter {
            it.bottom+slice.origin>slice.top && it.top+slice.origin<slice.bottom
        }) }
    }

    companion object {
        fun measure(key: TextReaderRenderKey, annotated: AnnotatedString, style: TextStyle,
            measurer: TextMeasurer, placeholders: List<AnnotatedString.Range<Placeholder>> = emptyList(),
            forcedBreaks: Set<Int> = emptySet(), titleLength: Int = 0, paragraphGap: Float = 0f,
            titleTop: Float = 0f, titleBottom: Float = 0f, underline: TextReaderUnderline = TextReaderUnderline(),
            bodyColor: Int = 0xff000000.toInt(), titleColor: Int = bodyColor,
            annotations: List<TextReaderAnnotation> = emptyList()): TextReaderLayout {
            val measure: (AnnotatedString, List<AnnotatedString.Range<Placeholder>>) -> TextLayoutResult = { text, images ->
                measurer.measure(text, style, placeholders = images,
                    constraints = Constraints(minWidth = key.viewportWidth, maxWidth = key.viewportWidth))
            }
            return paragraphs(key, annotated, placeholders, forcedBreaks, titleLength, paragraphGap, titleTop, titleBottom,
                underline, bodyColor, titleColor, annotations, measure) {
                measure(annotated, placeholders)
            }
        }
        fun create(key: TextReaderRenderKey, result: TextLayoutResult, forcedBreaks: Set<Int> = emptySet(),
            titleLength: Int = 0, paragraphGap: Float = 0f, titleTop: Float = 0f, titleBottom: Float = 0f,
            underline: TextReaderUnderline = TextReaderUnderline(), bodyColor: Int = 0xff000000.toInt(),
            titleColor: Int = bodyColor,annotations: List<TextReaderAnnotation> = emptyList()): TextReaderLayout {
            val input = result.layoutInput
            require(input.softWrap && input.maxLines == Int.MAX_VALUE)
            require(input.constraints.maxWidth == key.viewportWidth && result.size.width == key.viewportWidth)
            require(!result.didOverflowHeight) { "A height-clipped measurement cannot paginate a chapter" }
            val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0)
            return paragraphs(key, input.text, input.placeholders, forcedBreaks, titleLength, paragraphGap, titleTop, titleBottom,
                underline, bodyColor, titleColor, annotations,
                { text, images -> measurer.measure(text, input.style, overflow = input.overflow,
                    placeholders = images, constraints = input.constraints) }) { result }
        }
        private fun paragraphs(key: TextReaderRenderKey, annotated: AnnotatedString,
            imagePlaceholders: List<AnnotatedString.Range<Placeholder>>, forcedBreaks: Set<Int>,
            titleLength: Int, paragraphGap: Float, titleTop: Float, titleBottom: Float,
            underline: TextReaderUnderline, bodyColor: Int, titleColor: Int, annotations: List<TextReaderAnnotation>,
            measure: (AnnotatedString, List<AnnotatedString.Range<Placeholder>>) -> TextLayoutResult,
            reference: () -> TextLayoutResult): TextReaderLayout {
            val chapter = annotated.text
            require(titleLength in 0..chapter.length && paragraphGap.isFinite() && paragraphGap >= 0f)
            require(titleTop.isFinite() && titleBottom.isFinite() && titleTop >= 0 && titleBottom >= 0)
            val boundaries = sortedSetOf(0, chapter.length)
            boundaries += titleLength
            chapter.forEachIndexed { index, char -> if (char == '\n') boundaries += index + 1 }
            annotated.paragraphStyles.forEach { boundaries += it.start; boundaries += it.end }
            require(forcedBreaks.all { it in 0..chapter.length })
            boundaries += forcedBreaks
            val ranges = if (chapter.isEmpty()) listOf(0 to 0) else boundaries.zipWithNext()
            val paragraphs = ArrayList<Paragraph>()
            val lines = ArrayList<TextReaderLine>()
            val owners = ArrayList<Int>()
            val placeholders = hashMapOf<Int, Pair<Int, Int>>()
            val firstLines = ArrayList<Int>()
            var top = 0f; var sourceTop = 0f
            ranges.forEach { (start, end) ->
                val visibleEnd = if (end > start && chapter[end - 1] == '\n') end - 1 else end
                val fragment = annotated.subSequence(start, visibleEnd)
                val fullFragment = annotated.subSequence(start, end)
                val paragraphStyle = fullFragment.paragraphStyles.filter { it.end > it.start }
                    .fold(ParagraphStyle()) { current, range -> current.merge(range.item) }
                val local = AnnotatedString(fragment.text, fragment.spanStyles,
                    listOf(AnnotatedString.Range(paragraphStyle, 0, fragment.length)))
                val imageInputs = imagePlaceholders.withIndex().filter { it.value.start >= start && it.value.end <= visibleEnd }
                val localImages = imageInputs.map {
                    AnnotatedString.Range(it.value.item, it.value.start - start, it.value.end - start)
                }
                val original = measure(local, localImages)
                val isTitle = start < titleLength
                val guidesEnabled = underline.enabled(isTitle)
                val guided = if (guidesEnabled) measureTextReaderUnderline(original, underline) {
                    measure(it, localImages)
                } else original
                val localAnnotations=annotations.filter { it.start<visibleEnd && it.end>start }.map {
                    it.copy(start=maxOf(it.start,start)-start,end=minOf(it.end,visibleEnd)-start)
                }
                val measured=measureTextReaderAnnotations(guided,localAnnotations,original) { measure(it,localImages) }
                val index = paragraphs.size
                val height = measured.getLineBottom(measured.lineCount - 1)
                val leading = if (titleLength > 0 && start == 0) titleTop else 0f
                val trailing = if (titleLength > 0 && end == titleLength) titleBottom else 0f
                paragraphs += Paragraph(start, end, visibleEnd, measured, top + leading, sourceTop + leading, height,
                    if (guidesEnabled) textReaderUnderlineRuns(measured, underline, if (isTitle) titleColor else bodyColor) else emptyList(),
                    textReaderAnnotationRuns(measured,localAnnotations,if(isTitle) titleColor else bodyColor,original))
                firstLines += lines.size
                for (line in 0 until measured.lineCount) {
                    val from = start + measured.getLineStart(line)
                    val to = if (line == measured.lineCount - 1) end else start + measured.getLineEnd(line, false)
                    lines += TextReaderLine(from, to,
                        top + if (line == 0) 0f else leading + measured.getLineTop(line),
                        top + leading + measured.getLineBottom(line) + if (line == measured.lineCount - 1) trailing else 0f)
                    owners += index
                }
                imageInputs.forEachIndexed { localIndex, image -> placeholders[image.index] = index to localIndex }
                top += leading + height + trailing; sourceTop += leading + height + trailing
            }
            val positioned = spaceTextReaderParagraphs(chapter, titleLength, lines, paragraphGap)
            val placedParagraphs = paragraphs.mapIndexed { index, paragraph ->
                val first = firstLines[index]
                paragraph.copy(top = paragraph.top + positioned[first].top - lines[first].top)
            }
            return TextReaderLayout(key, annotated, reference, paginateTextReaderLines(chapter.length, positioned,
                key.viewportHeight.toFloat(), forcedBreaks), placedParagraphs, positioned, owners, placeholders, underline)
        }
    }
}
