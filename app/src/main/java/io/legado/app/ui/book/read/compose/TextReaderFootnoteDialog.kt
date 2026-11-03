package io.legado.app.ui.book.read.compose

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.model.*
import kotlinx.coroutines.launch

@Composable
internal fun textReaderLinkLabel(action: TextReaderLinkAction, text: String): String {
    val caption = text.replace("\uFFFC", "").trim().ifBlank { stringResource(R.string.view) }
    return stringResource(when (action) {
        is TextReaderLinkAction.Note -> R.string.text_reader_open_footnote
        is TextReaderLinkAction.Resource -> if (action.noteReference) R.string.text_reader_open_footnote else R.string.text_reader_open_link
        is TextReaderLinkAction.Jump -> R.string.text_reader_jump_to
        else -> R.string.text_reader_open_link
    }, caption)
}

private data class FootnotePage(val key: TextReaderRenderKey, val index: Int, val count: Int,
    val previous: Int?, val next: Int?)

/** A reader-owned modal with its own render/position keys. The book's viewport stays frozen. */
@Composable
internal fun TextReaderFootnoteDialog(ready: TextReaderReady, note: TextReaderFootnote, model: TextReaderViewModel,
    snackbar: SnackbarHostState, onLink: (TextReaderLinkAction?) -> Unit) {
    val title = stringResource(R.string.text_reader_footnote)
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maximumHeight = with(LocalDensity.current) { (windowHeight.coerceAtLeast(1) * .85f).toDp() }
    var page by remember(ready.key, note.instance) { mutableStateOf<FootnotePage?>(null) }
    Dialog(onDismissRequest = { model.dismissFootnote(ready.key, note.anchor, note.instance) },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().heightIn(max = maximumHeight)
            .testTag("text_reader_footnote").semantics { paneTitle = title }, shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { model.dismissFootnote(ready.key, note.anchor, note.instance) },
                        modifier = Modifier.testTag("text_reader_footnote_close")) { Text(stringResource(R.string.close)) }
                }
                FootnoteViewport(ready, note, model, Modifier.weight(1f, fill = false).fillMaxWidth(), { page = it }, onLink)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    page?.takeIf { it.count > 1 }?.let { shown ->
                        TextButton(enabled = shown.previous != null, onClick = {
                            shown.previous?.let { model.turnFootnote(shown.key, note.anchor, note.position, it) }
                        }, modifier = Modifier.testTag("text_reader_footnote_previous")) { Text(stringResource(R.string.prev_page)) }
                        Text("${shown.index + 1}/${shown.count}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                        TextButton(enabled = shown.next != null, onClick = {
                            shown.next?.let { model.turnFootnote(shown.key, note.anchor, note.position, it) }
                        }, modifier = Modifier.testTag("text_reader_footnote_next")) { Text(stringResource(R.string.next_page)) }
                    }
                    if (note.targetIn(ready.document) != null) TextButton(onClick = { model.showFootnoteInText(ready.key, note.anchor, note.instance) },
                        modifier = Modifier.testTag("text_reader_footnote_show")) { Text(stringResource(R.string.text_reader_view_in_text)) }
                }
                SnackbarHost(snackbar)
            }
        }
    }
}

@Composable
private fun FootnoteViewport(ready: TextReaderReady, note: TextReaderFootnote, model: TextReaderViewModel,
    modifier: Modifier, onPage: (FootnotePage) -> Unit, onLink: (TextReaderLinkAction?) -> Unit) {
    val sourceDocument = note.document ?: ready.document
    val source = sourceDocument.anchors.firstOrNull { it.key == note.anchor } ?: return
    val document = remember(sourceDocument, source) { sourceDocument.slice(source.start, source.end) }
    val density = LocalDensity.current
    val locales = rememberTextReaderLocales()
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val appearance = ready.entry.typography
    val fonts = rememberTextReaderFonts(appearance)
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(document, appearance, density, fonts, linkColor) {
        textReaderAnnotatedString(document, ready.entry.fontSize, ready.entry.titleSize, density, linkColor, appearance, fonts.title)
    }
    val images = remember(document) { document.images.sortedBy { it.offset } }
    val originals = remember(sourceDocument) { sourceDocument.images.associateBy { it.offset } }
    val bitmaps = remember(document) { mutableStateMapOf<Int, ImageBitmap>() }
    val dimensions = remember(document) { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width <= 0 || height <= 0 || width == Constraints.Infinity || height == Constraints.Infinity) return@BoxWithConstraints
        val placeholders = textReaderPlaceholders(images, dimensions, width, height, ready.entry.fontSize, density)
        val style = textReaderBodyStyle(ready.entry.fontSize, ready.entry.textColor, appearance, fonts.body, locales)
        val layout = remember(ready.key, document, text, style, placeholders, measurer, width, height, density, appearance) {
            TextReaderLayout.measure(TextReaderRenderKey(ready.contentKey, width, height, model.nextFootnoteRenderGeneration()),
                text, style, measurer, placeholders, document.breaks, paragraphGap =
                    with(density) { ready.entry.fontSize.coerceAtLeast(1).sp.toPx() } * appearance.paragraphSpacing,
                underline = appearance.underline, bodyColor = ready.entry.textColor)
        }
        val page = layout.pageAtOffset(note.position)
        SideEffect {
            model.installFootnoteRender(layout.key, note.anchor)
            onPage(FootnotePage(layout.key, page.index, layout.pages.size, layout.pages.getOrNull(page.index - 1)?.start,
                layout.pages.getOrNull(page.index + 1)?.start))
        }
        fun turn(direction: Int): Boolean = layout.pages.getOrNull(page.index + direction)?.let {
            model.turnFootnote(layout.key, note.anchor, note.position, it.start)
        } ?: false
        fun action(link: TextReaderLink): TextReaderLinkAction {
            val original = link.copy(start = link.start + source.start, end = link.end + source.start)
            val main = ready.document.linkAction(original, ready.entry.chapterUrl.ifBlank { ready.entry.bookUrl })
            return if (main is TextReaderLinkAction.Resource) sourceDocument.linkAction(original, ready.entry.chapterUrl) else main
        }
        val links = document.links.filter { it.start < page.end && it.end > page.start && action(it) != TextReaderLinkAction.Unsupported }
        val actions = links.map { link ->
            CustomAccessibilityAction(textReaderLinkLabel(action(link), document.text.substring(link.start, link.end))) {
                val result = model.followFootnoteLink(layout.key, note.anchor, note.position, link)
                if (result == null) false else { onLink(result); true }
            }
        }
        var selection by remember(layout.key, note.position) { mutableStateOf<TextRange?>(null) }
        LaunchedEffect(layout.key) { focus.requestFocus() }
        val shownHeight = minOf(height.toFloat(), maxOf(page.height, with(density) { 72.dp.toPx() }))
        Box(Modifier.fillMaxWidth().height(with(density) { shownHeight.toDp() })) {
            TextReaderBackgroundLayer(ready.entry.background, Modifier.matchParentSize())
            TextReaderCanvas(layout, note.position, selection, Modifier.fillMaxSize().testTag("text_reader_footnote_canvas")
                .focusRequester(focus).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.DirectionLeft, Key.PageUp -> turn(-1)
                        Key.DirectionRight, Key.PageDown, Key.Spacebar -> turn(1)
                        else -> false
                    }
                }.focusable(), onSelection = { key, range -> if (key == layout.key) selection = range },
                onPrevious = { turn(-1) }, onNext = { turn(1) },
                onTap = { position: Offset ->
                    val offset = layout.characterAt(page, position)
                    val link = links.firstOrNull { offset in it.start until it.end }
                    val bounds = if (offset in document.text.indices) layout.boundingBox(offset) else null
                    if (link != null && bounds?.contains(position.copy(y = position.y + page.top)) == true)
                        onLink(model.followFootnoteLink(layout.key, note.anchor, note.position, link))
                    else when {
                        position.x < width / 3f -> turn(-1)
                        position.x > width * 2 / 3f -> turn(1)
                    }
                }, images = bitmaps, imageOffsets = images.map { it.offset }, accessibilityActions = actions,
                fillViewport = false, imageOverlays = { visible ->
                    images.forEachIndexed { index, image ->
                        val rect = layout.placeholderRect(index, image.offset)
                        val original = originals[source.start + image.offset]
                        if (image.offset in visible.start until visible.end && rect != null && original != null) key(ready.key, note.anchor, image.offset) {
                            TextReaderVisibleImage(ready.key, original, index + 1, rect, visible.top, width, height, model,
                                onBitmap = { if (it == null) bitmaps.remove(image.offset) else bitmaps[image.offset] = it },
                                onDimensions = { w, h -> dimensions[image.offset] = w to h },
                                canInteract = { model.acceptsFootnote(layout.key, note.anchor, note.position) })
                        }
                    }
                })
            selection?.takeUnless { it.collapsed }?.let { range ->
                TextButton(onClick = {
                    if (model.acceptsFootnote(layout.key, note.anchor, note.position)) {
                        val selected = document.text.substring(range.min, range.max)
                        scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", selected))) }
                        selection = null
                    }
                }, modifier = Modifier.align(Alignment.TopCenter)) { Text(stringResource(R.string.copy_text)) }
            }
        }
    }
}
