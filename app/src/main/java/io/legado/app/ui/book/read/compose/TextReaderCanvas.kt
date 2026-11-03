package io.legado.app.ui.book.read.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import io.legado.app.R
import kotlin.math.max

/** Canvas only renders the supplied immutable generation; actions carry that exact generation. */
@Composable
internal fun TextReaderCanvas(
    layout: TextReaderLayout,
    anchor: Int,
    selection: TextRange?,
    modifier: Modifier = Modifier,
    selectionColor: Color = Color(0x663F51B5),
    onSelection: (TextReaderRenderKey, TextRange) -> Unit,
    onPrevious: () -> Boolean,
    onNext: () -> Boolean,
    onTap: (Offset) -> Unit,
    onSelectionFinished: (TextReaderRenderKey) -> Unit = {},
    onVisible: (TextReaderRenderKey, TextReaderPageSlice, Int, Int) -> Unit = { _, _, _, _ -> },
    images: Map<Int, ImageBitmap> = emptyMap(),
    imageOffsets: List<Int> = emptyList(),
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    visible: Boolean = true,
    fillViewport: Boolean = true,
    imageOverlays: @Composable (TextReaderPageSlice) -> Unit = {},
) {
    val page = layout.pageAtOffset(anchor)
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val currentSelect by rememberUpdatedState(onSelection)
    val currentTap by rememberUpdatedState(onTap)
    val currentFinished by rememberUpdatedState(onSelectionFinished)
    val currentVisible by rememberUpdatedState(onVisible)
    val previousLabel = stringResource(R.string.prev_page)
    val nextLabel = stringResource(R.string.next_page)
    val visibleText = layout.visibleText(page)
    val selected = selection?.let { range ->
        val start = range.start.coerceIn(page.start, page.end)
        val end = range.end.coerceIn(page.start, page.end)
        TextRange(start, end).takeUnless { it.collapsed }
    }
    val drawSlices = remember(layout, page.index) { layout.drawSlices(page) }
    val selectionPaths = remember(layout, selected) { layout.selectionPaths(selected) }
    val scrollReset = remember(layout.key, page.index, anchor) { mutableStateOf(false) }
    LaunchedEffect(layout.key, page.index, anchor, visible) {
        if (!scrollReset.value) { scroll.scrollTo(0); scrollReset.value = true }
        withFrameNanos { }
        withFrameNanos { }
        if (visible) currentVisible(layout.key, page, anchor, layout.pages.size)
    }
    Box(modifier.verticalScroll(scroll)) {
        Canvas(Modifier.fillMaxWidth()
            .height(with(density) { max(if (fillViewport) layout.key.viewportHeight.toFloat() else 1f, page.height).toDp() })
            .semantics {
                text = AnnotatedString(visibleText)
                selected?.let { textSelectionRange = TextRange(it.start - page.start, it.end - page.start) }
                customActions = listOf(CustomAccessibilityAction(previousLabel, onPrevious),
                    CustomAccessibilityAction(nextLabel, onNext)) + accessibilityActions
                setSelection { start, end, _ ->
                    if (start !in 0..visibleText.length || end !in 0..visibleText.length) false
                    else {
                        currentSelect(layout.key, TextRange(
                            textReaderCodePointBoundary(layout.chapterText, page.start + start),
                            textReaderCodePointBoundary(layout.chapterText, page.start + end)))
                        currentFinished(layout.key)
                        true
                    }
                }
            }
            .pointerInput(layout.key, page.index) { detectTapGestures(onTap = { currentTap(it) }) }
            .pointerInput(layout.key, page.index) {
                var start = page.start
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        val word = layout.wordAt(page, position)
                        start = word.start
                        currentSelect(layout.key, word)
                    },
                    onDragEnd = { currentFinished(layout.key) },
                    onDrag = { change, _ ->
                        currentSelect(layout.key, TextRange(start, layout.offsetAt(page, change.position)))
                        change.consume()
                    },
                )
            }) {
            clipRect(bottom = page.height.coerceAtMost(size.height)) {
                drawSlices.forEach { slice ->
                    clipRect(top = slice.top - page.top, bottom = slice.bottom - page.top) {
                        translate(top = slice.origin - page.top) {
                            drawTextReaderAnnotationFills(slice.annotations)
                            selectionPaths[slice.paragraph]?.let { drawPath(it, selectionColor) }
                            drawText(slice.layout)
                            drawTextReaderUnderlines(slice.underlines, layout.underline)
                            drawTextReaderAnnotationDecorations(slice.annotations)
                        }
                    }
                }
                translate(top = -page.top) {
                    imageOffsets.forEachIndexed { index, offset ->
                        if (offset !in page.start until page.end) return@forEachIndexed
                        val positioned = layout.placeholderRect(index, offset) ?: return@forEachIndexed
                        val bitmap = images[offset]
                        if (bitmap == null) drawRect(Color.Gray.copy(alpha = 0.15f), positioned.topLeft, positioned.size)
                        else drawImage(bitmap, dstOffset = IntOffset(positioned.left.toInt(), positioned.top.toInt()),
                            dstSize = IntSize(positioned.width.toInt().coerceAtLeast(1), positioned.height.toInt().coerceAtLeast(1)))
                    }
                }
            }
        }
        imageOverlays(page)
    }
}
