package io.legado.app.ui.book.info.intro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.data.entities.*
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.book.info.BookInfoIntroUiState
import io.legado.app.ui.book.info.introIndentRanges
import io.legado.app.ui.compose.component.*
import io.legado.app.ui.platformadapter.BookIntroWebContent

@Composable
internal fun BookIntroContent(
    state: BookInfoIntroUiState,
    source: BookSource?,
    bookUrl: String,
    onOverflow: (String?, Boolean) -> Unit,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onButton: (String, String) -> Unit,
    onImage: (String) -> Unit,
    onLink: (String) -> Unit,
) {
    if (!state.visible) return
    val document = state.document
    if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book_intro_loading")); return }
    if (state.failed) { TextButton(onRetry, Modifier.testTag("book_intro_retry")) { Text(stringResource(R.string.retry)) }; return }
    document ?: return
    if (document.format == IntroFormat.Web) {
        key(state.contentKey, source?.bookSourceUrl, source?.lastUpdateTime) {
            BookIntroWebContent(document.webHtml.orEmpty(), bookUrl, source, onLink)
        }
        return
    }
    val density = LocalDensity.current
    val lineHeight = MaterialTheme.typography.bodyMedium.lineHeight.takeOrElse { 20.sp }
    val collapsedHeight = with(density) { (lineHeight * 4).roundToPx() + 8.dp.roundToPx() }
    var fullHeight by remember(state.contentKey) { mutableIntStateOf(0) }
    LaunchedEffect(state.contentKey, fullHeight, collapsedHeight) { if (fullHeight > 0) onOverflow(state.contentKey, introCanCollapse(fullHeight, collapsedHeight)) }
    val toggleRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().testTag("book_intro")) {
        Box(Modifier.fillMaxWidth()) {
            Layout(modifier = Modifier.fillMaxWidth().clipToBounds(), content = {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp).onSizeChanged { fullHeight = it.height }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        document.blocks.forEachIndexed { index, block -> key(state.contentKey, index) {
                            IntroBlockContent(block, source?.bookSourceUrl, onButton, onImage, onLink)
                        } }
                    }
                }
            }) { children, constraints ->
                val child = children.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val height = introDisplayHeight(state.expanded, child.height, collapsedHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
                layout(child.width, height) { child.placeRelative(0, 0) }
            }
            if (!state.expanded && state.canCollapse) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(10.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        if (state.toggleVisible) TextButton({
            onToggle()
            if (state.expanded) scope.launch { withFrameNanos { }; toggleRequester.bringIntoView() }
        }, Modifier.align(Alignment.End).heightIn(min = 48.dp).bringIntoViewRequester(toggleRequester).testTag("book_intro_toggle")) {
            Text(stringResource(if (state.expanded) R.string.book_intro_collapse else R.string.book_intro_expand))
        }
    }
}

@Composable
private fun IntroBlockContent(block: IntroBlock, source: String?, onButton: (String, String) -> Unit, onImage: (String) -> Unit, onLink: (String) -> Unit) {
    when (block) {
        IntroBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        is IntroBlock.Paragraph -> IntroParagraph(block, source, onButton, onImage, onLink)
        is IntroBlock.Table -> {
            val columns = block.rows.maxOfOrNull { it.size } ?: 0
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("book_intro_table")
                .semantics { collectionInfo = CollectionInfo(block.rows.size, columns) }) {
                Column(Modifier.horizontalScroll(rememberScrollState()).width(maxOf(maxWidth, (columns * 120).dp))) {
                    block.rows.forEachIndexed { rowIndex, row ->
                        val outline = MaterialTheme.colorScheme.outlineVariant
                        Layout(modifier = Modifier.fillMaxWidth().border(1.dp, outline)
                            .background(if (rowIndex < block.headerRows) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .drawBehind { repeat((columns - 1).coerceAtLeast(0)) { index ->
                                val x = size.width * (index + 1) / columns
                                drawLine(outline, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1.dp.toPx())
                            } }, content = {
                        repeat(columns) { column ->
                            Box(Modifier.padding(8.dp)) {
                                row.getOrNull(column)?.let { IntroParagraph(it, source, onButton, onImage, onLink) }
                            }
                        }
                        }) { children, constraints ->
                            val cellWidth = constraints.maxWidth / columns.coerceAtLeast(1)
                            val cells = children.map { it.measure(Constraints(minWidth = cellWidth, maxWidth = cellWidth)) }
                            layout(constraints.maxWidth, cells.maxOfOrNull { it.height } ?: 0) {
                                cells.forEachIndexed { index, cell -> cell.placeRelative(index * cellWidth, 0) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroParagraph(paragraph: IntroBlock.Paragraph, source: String?, onButton: (String, String) -> Unit, onImage: (String) -> Unit, onLink: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = (paragraph.quote * 12).dp)) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val measurer = rememberTextMeasurer()
        val inline = linkedMapOf<String, InlineTextContent>()
        val text = AnnotatedString.Builder()
        paragraph.content.forEachIndexed { index, item -> when (item) {
            is IntroInline.Text -> {
                val mark = item.style
                val span = SpanStyle(fontWeight = if (mark.bold) FontWeight.Bold else null,
                    fontStyle = if (mark.italic) FontStyle.Italic else null,
                    fontFamily = if (mark.code) FontFamily.Monospace else null,
                    textDecoration = TextDecoration.combine(listOfNotNull(if (mark.underline) TextDecoration.Underline else null, if (mark.strike) TextDecoration.LineThrough else null)),
                    fontSize = style.fontSize * mark.size,
                    color = parseIntroColor(mark.color), background = parseIntroColor(mark.background))
                text.pushStyle(span)
                val link = item.link
                if (link != null) text.pushLink(LinkAnnotation.Clickable("link-$index", TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))) { onLink(link) })
                text.append(item.text)
                if (link != null) text.pop()
                text.pop()
            }
            is IntroInline.Button -> {
                val buttonStyle = MaterialTheme.typography.labelLarge
                val label = measurer.measure(item.label, buttonStyle, constraints = Constraints(maxWidth = (maxWidthPx - with(density) { 32.dp.toPx() }).toInt().coerceAtLeast(1)))
                val width = minOf(maxWidthPx, label.size.width + with(density) { 32.dp.toPx() })
                val height = maxOf(with(density) { 48.dp.toPx() }, label.size.height + with(density) { 16.dp.toPx() })
                val id = "button-$index"
                inline[id] = InlineTextContent(Placeholder(with(density) { width.toSp() }, with(density) { height.toSp() }, PlaceholderVerticalAlign.Center)) {
                    Button({ onButton(item.label, item.script) }, Modifier.fillMaxSize().testTag("intro_button_$index"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(item.label) }
                }
                text.appendInlineContent(id, item.label)
            }
            is IntroInline.Image -> key(index, item.url, source) {
                var retry by remember(item.url, source) { mutableIntStateOf(0) }
                val image by rememberLegadoDrawableImageState(listOf(item.url, source), retry) { context ->
                    val request = if (item.url.startsWith("file://") || item.url.startsWith("android.resource://")) ImageLoader.load(context, android.net.Uri.parse(item.url)) else ImageLoader.load(context, item.url)
                    request.let { request -> source?.let { request.apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it)) } ?: request }
                        .apply(RequestOptions().set(OkHttpModelLoader.retryFailedImagesOption, retry > 0)).skipMemoryCache(retry > 0)
                        .override(maxWidthPx.toInt().coerceAtLeast(1), 2048).dontTransform()
                }
                val drawable = (image as? LegadoDrawableImageState.Ready)?.drawable
                val naturalWidth = drawable?.intrinsicWidth?.toFloat()?.coerceAtLeast(1f) ?: with(density) { 120.dp.toPx() }
                val width = minOf(maxWidthPx, introDimension(item.width, maxWidthPx) ?: naturalWidth).coerceAtLeast(1f)
                val height = introDimension(item.height, maxWidthPx) ?: (width * (drawable?.intrinsicHeight?.coerceAtLeast(1) ?: 120) / naturalWidth)
                val slotWidth = if (item.alignment in setOf("center", "right")) maxWidthPx else width
                val id = "image-$index"
                inline[id] = InlineTextContent(Placeholder(with(density) { slotWidth.toSp() }, with(density) { height.coerceAtLeast(1f).toSp() }, PlaceholderVerticalAlign.Center)) {
                    val label = item.alt.ifBlank { stringResource(R.string.photo_viewer_title) }
                    Box(Modifier.fillMaxSize().testTag("intro_image_$index").combinedClickable(
                        onClick = { item.click?.takeIf(String::isNotBlank)?.let { onButton("image", it) } ?: onImage(item.url) }, onLongClick = { onImage(item.url) }), contentAlignment = Alignment.Center) {
                        when {
                            drawable != null -> Image(rememberLegadoDrawablePainter(drawable), label, Modifier.width(with(density) { width.toDp() }).fillMaxHeight()
                                .align(when (item.alignment) { "right" -> Alignment.CenterEnd; "center" -> Alignment.Center; else -> Alignment.CenterStart }))
                            image == LegadoDrawableImageState.Error -> TextButton({ retry++ }) { Text(stringResource(R.string.retry)) }
                            else -> CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
                text.appendInlineContent(id, item.alt.ifBlank { "\uFFFC" })
            }
        } }
        val value = text.toAnnotatedString()
        val decorated = if (paragraph.indent) AnnotatedString.Builder(value).apply {
            introIndentRanges(value.text).forEach { addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 2.em, restLine = 0.em)), it.start, it.endExclusive) }
        }.toAnnotatedString() else value
        Text(decorated, style = style.copy(fontFamily = if (paragraph.preformatted) FontFamily.Monospace else style.fontFamily,
            lineHeight = style.lineHeight * (paragraph.content.filterIsInstance<IntroInline.Text>().maxOfOrNull { it.style.size } ?: 1f).coerceAtLeast(1f),
            textAlign = when (paragraph.alignment) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }), inlineContent = inline,
            modifier = Modifier.fillMaxWidth().testTag("book_intro_text"))
    }
}
private fun parseIntroColor(value: String?): Color = value?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color.Unspecified
private fun introDimension(value: String?, maximum: Float): Float? = value?.let { if (it.endsWith('%')) it.dropLast(1).toFloatOrNull()?.let { number -> maximum * number / 100f } else it.removeSuffix("px").toFloatOrNull() }?.takeIf { it.isFinite() && it > 0 }
