package io.legado.app.ui.book.read.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.model.TextReaderLinkAction
import io.legado.app.model.linkAction
import io.legado.app.ui.navigation.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** Actual Navigation 3 text reader; platform layout and selection stay inside this composition. */
@Composable
fun TextReaderDestination(route: TextReaderRoute, onBack: () -> Unit, onNavigate: (AppRoute) -> Unit,
    isCurrent: Boolean, detailEntries: Set<String>, model: TextReaderViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentBack by rememberUpdatedState(onBack)
    val currentNavigate by rememberUpdatedState(onNavigate)
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val uiMode = LocalConfiguration.current.uiMode
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val linkFailed = stringResource(R.string.text_reader_link_failed)
    val targetMissing = stringResource(R.string.text_reader_link_target_missing)
    fun dispatchLink(action: TextReaderLinkAction?) {
        when (action) {
            is TextReaderLinkAction.External -> runCatching { uriHandler.openUri(action.target) }.onFailure {
                scope.launch { snackbar.showSnackbar(linkFailed) }
            }
            TextReaderLinkAction.Missing -> scope.launch { snackbar.showSnackbar(targetMissing) }
            else -> Unit
        }
    }
    val annotationError=state.annotationError?.let { annotationMessage(it) }
    LaunchedEffect(annotationError) { if(annotationError!=null && state.annotationDraft==null && !state.annotationList) snackbar.showSnackbar(annotationError) }
    val ruleInterrupted=stringResource(R.string.reader_highlight_rules_interrupted)
    LaunchedEffect(state.ruleWarning) { if(state.ruleWarning>0) snackbar.showSnackbar(ruleInterrupted) }
    SideEffect { model.setDetailEntries(detailEntries) }
    LaunchedEffect(route, isCurrent, lifecycle, model) {
        model.initialize(route)
        if (isCurrent) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val ticket = model.enterLifecycle()
            try { kotlinx.coroutines.awaitCancellation() } finally { model.leaveLifecycle(ticket) }
        }
    }
    LaunchedEffect(state.navigation, isCurrent, lifecycleState) {
        if (isCurrent && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) state.navigation?.let {
            currentNavigate(it)
            model.navigationHandled(it)
        }
    }
    LaunchedEffect(state.closed, isCurrent) { if (state.closed && isCurrent) currentBack() }
    LaunchedEffect(state.linkFailure) { if (state.linkFailure > 0) snackbar.showSnackbar(targetMissing) }
    LaunchedEffect(uiMode, state.ready?.key, isCurrent, lifecycleState, state.ruleAction==null, state.annotationDraft==null, model) {
        if (isCurrent && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) model.refreshAppearance()
    }
    BackHandler(isCurrent) {
        when { state.ruleAction!=null -> model.dismissHighlightRule(); state.annotationDraft != null -> model.finishAnnotation(false); state.annotationList -> model.annotationList(false); state.linkLoading -> model.cancelPendingLink(); state.footnote != null -> model.dismissFootnote(); state.menu -> model.menu(false)
            state.linkReturnAvailable -> model.returnFromLink(); else -> model.close() }
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            TextReaderBackgroundLayer(if (state.error == null) state.background else null, Modifier.matchParentSize())
            val ready = state.ready
            if (ready != null && !state.loading && state.error == null) {
                Column(Modifier.fillMaxSize()) {
                    val appearance = ready.entry.typography
                    Box(Modifier.weight(1f).fillMaxWidth().padding(
                        start = appearance.paddingLeft.dp, top = appearance.paddingTop.dp,
                        end = appearance.paddingRight.dp, bottom = appearance.paddingBottom.dp)) {
                        ReaderViewport(ready, state.anchor, model, state.footnote == null && !state.linkLoading && state.annotationDraft==null && !state.annotationList && state.ruleAction==null, state.searchMatch, state.annotations, state.ruleHighlights, ::dispatchLink)
                    }
                    FlowRow(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.Center) {
                        if (state.linkReturnAvailable) TextButton(onClick = { model.returnFromLink() }, enabled = !state.linkLoading,
                            modifier = Modifier.testTag("text_reader_link_return")) { Text(stringResource(R.string.text_reader_link_return)) }
                        Text("${ready.entry.chapterIndex + 1}/${ready.entry.chapterCount} · ${state.page + 1}/${state.pageCount.coerceAtLeast(1)}",
                            Modifier.padding(8.dp), color = Color(ready.entry.textColor), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (state.loading || state.linkLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error?.let { message ->
                val displayedMessage = when (message) {
                    "Detail reading request expired", "Reader no longer owns this book" -> stringResource(R.string.reader_return_request_expired)
                    "Book does not exist", "Reader book was removed" -> stringResource(R.string.no_book)
                    else -> message
                }
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(displayedMessage)
                    TextButton(onClick = model::retry) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = model::close) { Text(stringResource(R.string.back)) }
                }
            }
            if (state.menu && ready != null) Surface(Modifier.align(Alignment.BottomCenter), tonalElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(ready.entry.bookName, style = MaterialTheme.typography.titleMedium)
                    Text(ready.title)
                    FlowRow {
                        TextButton(onClick = { model.chapter(-1) }, enabled = !state.loading && ready.entry.chapterIndex > 0) {
                            Text(stringResource(R.string.previous_chapter))
                        }
                        TextButton(onClick = model::catalog, enabled = !state.loading) { Text(stringResource(R.string.chapter_list)) }
                        TextButton(onClick = { model.chapter(1) }, enabled = !state.loading && ready.entry.chapterIndex + 1 < ready.entry.chapterCount) {
                            Text(stringResource(R.string.next_chapter))
                        }
                        TextButton(onClick = { model.search() },enabled = !state.loading,modifier=Modifier.testTag("text_reader_search")) { Text(stringResource(R.string.search_content)) }
                        TextButton(onClick=model::manageHighlightRules,enabled=!state.loading,modifier=Modifier.testTag("text_reader_highlight_rules")) { Text(stringResource(R.string.highlight_rule)) }
                        TextButton(onClick=model::bookmarkPage,enabled=!state.loading,modifier=Modifier.testTag("text_reader_bookmark_add")) { Text(stringResource(R.string.bookmark_add)) }
                        TextButton(onClick={ model.annotationList(true) },enabled=!state.loading,modifier=Modifier.testTag("text_reader_bookmarks")) { Text(stringResource(R.string.reader_annotations)) }
                        TextButton(onClick = model::details, enabled = !state.loading) { Text(stringResource(R.string.book_info)) }
                        TextButton(onClick = model::close) { Text(stringResource(R.string.back)) }
                    }
                }
            }
            if (state.footnote == null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    TextReaderAnnotationDialogs(state,model)
    state.ruleAction?.let { rule ->
        AlertDialog(onDismissRequest=model::dismissHighlightRule,modifier=Modifier.testTag("text_reader_rule_actions"),
            title={ Text(rule.name,maxLines=2) },text={ Column {
                Text(rule.pattern,maxLines=4,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if(state.ruleError) Text(stringResource(R.string.reader_highlight_rule_failed),color=MaterialTheme.colorScheme.error)
            } },dismissButton={ TextButton(onClick=model::dismissHighlightRule,enabled=!state.ruleBusy) { Text(stringResource(R.string.cancel)) } },
            confirmButton={ FlowRow {
                TextButton(onClick={ model.editHighlightRule(rule) },enabled=!state.ruleBusy,modifier=Modifier.testTag("text_reader_rule_edit")) { Text(stringResource(R.string.edit)) }
                TextButton(onClick={ model.disableHighlightRule(rule) },enabled=!state.ruleBusy,modifier=Modifier.testTag("text_reader_rule_disable")) { Text(stringResource(R.string.highlight_rule_disable)) }
                TextButton(onClick=model::manageHighlightRules,enabled=!state.ruleBusy) { Text(stringResource(R.string.highlight_rule)) }
            } })
    }
    val ready = state.ready
    val note = state.footnote
    if (ready != null && note != null && !state.loading) key(ready.key, note.instance) {
        TextReaderFootnoteDialog(ready, note, model, snackbar, ::dispatchLink)
    }
}

@Composable
private fun ReaderViewport(ready: TextReaderReady, anchor: Int, model: TextReaderViewModel, visible: Boolean,
    searchMatch: IntRange?,annotations: List<io.legado.app.data.repository.ReaderAnnotationItem>,
    rules: List<io.legado.app.data.repository.TextReaderRuleMatch>,onLink: (TextReaderLinkAction?) -> Unit) {
    val density = LocalDensity.current
    val locales = rememberTextReaderLocales()
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val generations = remember { AtomicLong() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focus = remember { FocusRequester() }
    val linkColor = MaterialTheme.colorScheme.primary
    val appearance = ready.entry.typography
    val fonts = rememberTextReaderFonts(appearance)
    val annotationSpans=key(ready.key) {
        val value by produceState<List<TextReaderAnnotation>>(emptyList(),ready.document,ready.entry,annotations,rules) {
            value=withContext(Dispatchers.Default) { textReaderCombinedAnnotations(ready.document,ready.entry,annotations,rules) }
        }
        value
    }
    val annotationFonts=rememberTextReaderAnnotationFonts(annotationSpans)
    val text = remember(ready, density, linkColor, fonts, annotationSpans,annotationFonts) {
        val base=textReaderAnnotatedString(ready.document, ready.entry.fontSize, ready.entry.titleSize, density, linkColor,
            appearance, fonts.title)
        textReaderApplyAnnotations(base,annotationSpans,annotationFonts)
    }
    val images = remember(ready.key) { ready.document.images.sortedBy { it.offset } }
    val bitmaps = remember(ready.key) { mutableStateMapOf<Int, ImageBitmap>() }
    val dimensions = remember(ready.key) { mutableStateMapOf<Int, Pair<Int, Int>>() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width <= 0 || height <= 0 || width == Constraints.Infinity || height == Constraints.Infinity) return@BoxWithConstraints
        val placeholders = textReaderPlaceholders(images, dimensions, width, height, ready.entry.fontSize, density)
        val bodyStyle = textReaderBodyStyle(ready.entry.fontSize, ready.entry.textColor, appearance, fonts.body, locales)
        val layout = remember(ready.key, text, bodyStyle, placeholders, measurer, width, height, density,
            appearance.paragraphSpacing, appearance.titleTopSpacing, appearance.titleBottomSpacing, appearance.underline,annotationSpans) {
            TextReaderLayout.measure(TextReaderRenderKey(ready.contentKey, width, height, generations.incrementAndGet()),
                text, bodyStyle, measurer, placeholders, ready.document.breaks, ready.document.titleLength,
                with(density) { ready.entry.fontSize.coerceAtLeast(1).sp.toPx() } * appearance.paragraphSpacing,
                with(density) { appearance.titleTopSpacing.dp.toPx() }, with(density) { appearance.titleBottomSpacing.dp.toPx() },
                appearance.underline, ready.entry.textColor, appearance.titleColor ?: ready.entry.textColor,annotationSpans)
        }
        var selection by remember(layout.key, anchor,searchMatch) {
            mutableStateOf(searchMatch?.takeUnless { it.isEmpty() }?.let { TextRange(it.first,it.last+1) })
        }
        SideEffect { model.installRender(layout.key) }
        fun turn(direction: Int): Boolean {
            val page = layout.pageAtOffset(anchor)
            val next = layout.pages.getOrNull(page.index + direction)
            return if (next != null) model.turn(layout.key, next.start) else model.chapter(direction, atEnd = direction < 0)
        }
        val page = layout.pageAtOffset(anchor)
        fun action(link: io.legado.app.model.TextReaderLink) = ready.document.linkAction(link, ready.entry.chapterUrl.ifBlank { ready.entry.bookUrl })
        val annotationActions=annotations.filter { it.belongsTo(ready.entry) }.mapNotNull { row ->
            val range=textReaderAnnotationRange(ready.document,row.position,row.end,row.titleLength,row.text)
            if(range.isEmpty() || range.first>=page.end || range.last<page.start) null
            else CustomAccessibilityAction(row.note.ifBlank { row.text }.take(120)) { model.editAnnotation(row); true }
        }
        val ruleActions=rules.filter { it.end>page.start && it.start<page.end }.distinctBy { it.rule.fingerprint }.map { hit ->
            val label=stringResource(R.string.reader_highlight_rule_action,hit.rule.name.take(120))
            CustomAccessibilityAction(label) { model.showHighlightRule(layout.key,anchor,hit) }
        }
        val linkActions = ready.document.links.filter { it.end > page.start && it.start < page.end && action(it) != TextReaderLinkAction.Unsupported }.map { link ->
            val caption = ready.document.text.substring(link.start, link.end)
            CustomAccessibilityAction(textReaderLinkLabel(action(link), caption)) {
                val result = model.followLink(layout.key, anchor, link)
                if (result == null) false else { onLink(result); true }
            }
        }
        LaunchedEffect(layout.key) { focus.requestFocus() }
        TextReaderCanvas(layout, anchor, selection,
            modifier = Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionLeft, Key.PageUp -> turn(-1)
                    Key.DirectionRight, Key.PageDown, Key.Spacebar -> turn(1)
                    else -> false
                }
            }.focusable(),
            onSelection = { key, range -> if (key == layout.key) selection = range },
            onPrevious = { turn(-1) }, onNext = { turn(1) },
            onTap = { position: Offset ->
                val offset = layout.characterAt(page, position)
                val link = ready.document.links.firstOrNull { offset in it.start until it.end && action(it) != TextReaderLinkAction.Unsupported }
                val bounds = if (offset in ready.document.text.indices) layout.boundingBox(offset) else null
                val annotation=annotations.firstOrNull { row -> row.belongsTo(ready.entry) &&
                    offset in textReaderAnnotationRange(ready.document,row.position,row.end,row.titleLength,row.text) }
                if(annotation!=null && bounds?.contains(position.copy(y=position.y+page.top))==true) model.editAnnotation(annotation)
                else if (link != null && bounds?.contains(position.copy(y = position.y + page.top)) == true) onLink(model.followLink(layout.key, anchor, link))
                else if(bounds?.contains(position.copy(y=position.y+page.top))==true && rules.any { offset in it.start until it.end })
                    model.showHighlightRule(layout.key,anchor,rules.last { offset in it.start until it.end })
                else when {
                    position.x < width / 3f -> turn(-1)
                    position.x > width * 2 / 3f -> turn(1)
                    else -> model.menu(true)
                }
            },
            onVisible = model::visible, images = bitmaps, imageOffsets = images.map { it.offset }, accessibilityActions = linkActions+annotationActions+ruleActions, visible = visible,
            imageOverlays = { visible ->
                images.forEachIndexed { index, image ->
                    val rect = layout.placeholderRect(index, image.offset)
                    if (image.offset in visible.start until visible.end && rect != null) key(ready.key, image.offset) {
                        TextReaderVisibleImage(ready.key, image, index + 1, rect, visible.top, width, height, model,
                            onBitmap = { if (it == null) bitmaps.remove(image.offset) else bitmaps[image.offset] = it },
                            onDimensions = { w, h -> dimensions[image.offset] = w to h },
                            canInteract = { model.acceptsInteraction(layout.key, anchor) })
                    }
                }
            })
        selection?.takeUnless { it.collapsed }?.let { range ->
            FlowRow(Modifier.align(Alignment.TopCenter),horizontalArrangement=Arrangement.Center) {
                TextButton(onClick = {
                    if (model.acceptsInteraction(layout.key, anchor)) {
                        val selected = ready.document.text.substring(range.min, range.max)
                        scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", selected))) }
                        selection = null
                    }
                }) { Text(stringResource(R.string.copy_text)) }
                TextButton(onClick={
                    model.createAnnotation(layout.key,anchor,range.min,range.max,false)
                    selection=null
                },modifier=Modifier.testTag("text_reader_bookmark_selection")) { Text(stringResource(R.string.bookmark)) }
                TextButton(onClick={
                    model.createAnnotation(layout.key,anchor,range.min,range.max,true)
                    selection=null
                },enabled=range.min>=ready.document.titleLength,modifier=Modifier.testTag("text_reader_highlight_selection")) { Text(stringResource(R.string.highlight_note)) }
                TextButton(onClick={
                    if(model.acceptsInteraction(layout.key,anchor)) {
                        model.search(ready.document.text.substring(range.min,range.max))
                        selection=null
                    }
                },enabled=range.length<=2048,modifier=Modifier.testTag("text_reader_search_selection")) {
                    Text(stringResource(R.string.search_content))
                }
            }
        }
    }
}
