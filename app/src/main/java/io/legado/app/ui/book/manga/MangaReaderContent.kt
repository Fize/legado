package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.bumptech.glide.load.Transformation
import io.legado.app.model.BookCover
import io.legado.app.R
import io.legado.app.ui.compose.component.LegadoImageState
import io.legado.app.ui.compose.component.rememberLegadoImageState
import io.legado.app.ui.book.manga.entities.EpaperTransformation
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MangaReaderContent(
    state: MangaReaderUiState,
    onVisibleItem: (BaseMangaPage, Int, Int) -> Unit,
    onCommandHandled: (Long) -> Unit,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSaveImage: (String) -> Unit,
    onLoadNextChapter: () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.surface
    val focusRequester = remember { FocusRequester() }
    val previousPageLabel = stringResource(R.string.prev_page)
    val nextPageLabel = stringResource(R.string.next_page)
    val readerInputModifier = Modifier
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event ->
            if (!state.interactive || state.menuVisible || state.loadingVisible) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key == Key.VolumeUp || event.key == Key.VolumeDown
            when (event.key) {
                Key.DirectionLeft -> {
                    if (state.horizontal && state.rightToLeft) onNextPage() else onPreviousPage()
                    true
                }
                Key.DirectionRight -> {
                    if (state.horizontal && state.rightToLeft) onPreviousPage() else onNextPage()
                    true
                }
                Key.DirectionUp, Key.PageUp, Key.VolumeUp -> { onPreviousPage(); true }
                Key.DirectionDown, Key.PageDown, Key.VolumeDown -> { onNextPage(); true }
                Key.Spacebar -> {
                    if (event.isShiftPressed) onPreviousPage() else onNextPage()
                    true
                }
                else -> false
            }
        }
        .focusable()
        .semantics {
            stateDescription = "${state.seekPage + 1}/${state.seekPageCount}"
            customActions = if (state.loadingVisible) emptyList() else listOf(
                    CustomAccessibilityAction(previousPageLabel) {
                        onPreviousPage()
                        true
                    },
                    CustomAccessibilityAction(nextPageLabel) {
                        onNextPage()
                        true
                    },
                )
        }
    LaunchedEffect(focusRequester, state.horizontal, state.pageSnapEnabled) {
        focusRequester.requestFocus()
    }
    if (state.horizontal) {
        val spreads = remember(state.items, state.doublePage) {
            buildMangaSpreads(state.items, state.doublePage)
        }
        val initialPage = findMangaSpreadIndex(spreads, state.anchorItemIndex)
        if (!state.pageSnapEnabled) {
            MangaFreeScrollRow(
                state = state,
                spreads = spreads,
                initialPage = initialPage,
                pageColor = pageColor,
                onVisibleItem = onVisibleItem,
                onCommandHandled = onCommandHandled,
                onMiddleTap = onMiddleTap,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onSaveImage = onSaveImage,
                modifier = readerInputModifier,
            )
            return
        }
        val pagerState = rememberPagerState(initialPage = initialPage) { spreads.size.coerceAtLeast(1) }
        val identity = Triple(state.contentVersion, state.layoutVersion, state.interactive)
        val currentIdentity by rememberUpdatedState(identity)
        val restored = remember(identity) { kotlinx.coroutines.CompletableDeferred<Unit>() }
        LaunchedEffect(pagerState, spreads, identity) {
            if (!state.interactive) return@LaunchedEffect
            if (spreads.isEmpty()) return@LaunchedEffect
            pagerState.scrollToPage(initialPage)
            val expected = spreads.getOrNull(initialPage)?.key
            snapshotFlow {
                if (pagerState.isScrollInProgress || pagerState.currentPage != initialPage ||
                    pagerState.settledPage != initialPage) null
                else pagerState.layoutInfo.visiblePagesInfo.firstOrNull { it.index == initialPage }?.key
            }.first { it == expected }
            spreads.getOrNull(initialPage)?.first?.let { onVisibleItem(it, state.contentVersion, state.layoutVersion) }
            restored.complete(Unit)
            snapshotFlow {
                if (pagerState.isScrollInProgress) null else pagerState.layoutInfo.visiblePagesInfo
                    .firstOrNull { it.index == pagerState.settledPage }?.key
            }.collectLatest { key ->
                if (currentIdentity == identity) {
                    spreads.firstOrNull { it.key == key }?.first?.let { onVisibleItem(it, state.contentVersion, state.layoutVersion) }
                }
            }
        }
        LaunchedEffect(state.commandVersion, pagerState, spreads, identity) {
            if (!state.interactive) return@LaunchedEffect
            val command = state.command ?: return@LaunchedEffect
            restored.await()
            when (command) {
                is MangaScrollRequest.ToPage -> {
                    val item = mangaScrollTarget(state.items, command)
                    if (item >= 0) {
                        val target = findMangaSpreadIndex(spreads, item)
                        if (command.animated && state.pageAnimationEnabled) pagerState.animateScrollToPage(target, animationSpec = tween(260))
                        else pagerState.scrollToPage(target)
                    }
                }
                is MangaScrollRequest.ByPixels -> { onCommandHandled(state.commandVersion); pagerState.scrollBy(command.pixels) }
            }
            onCommandHandled(state.commandVersion)
        }

        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = state.rightToLeft,
                userScrollEnabled = state.interactive,
                modifier = Modifier
                    .fillMaxSize()
                    .background(pageColor)
                    .then(readerInputModifier),
                key = { spreads.getOrNull(it)?.key ?: "empty" },
            ) { spreadIndex ->
                val spread = spreads.getOrNull(spreadIndex)
                if (spread != null) {
                    MangaSpreadPage(
                        spread = spread,
                        state = state,
                        onMiddleTap = onMiddleTap,
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage,
                        onSaveImage = onSaveImage,
                    )
                }
            }
            if (state.pageAnimationEnabled) {
                MangaPageCurlCanvas(
                    offsetFraction = pagerState.currentPageOffsetFraction,
                    rightToLeft = state.rightToLeft,
                )
            }
        }
    } else {
        val listState = rememberLazyListState(state.anchorItemIndex.coerceAtLeast(0))
        val identity = Triple(state.contentVersion, state.layoutVersion, state.interactive)
        val currentIdentity by rememberUpdatedState(identity)
        val restored = remember(identity) { kotlinx.coroutines.CompletableDeferred<Unit>() }
        LaunchedEffect(listState, state.items, identity) {
            if (!state.interactive) return@LaunchedEffect
            if (state.items.isEmpty()) return@LaunchedEffect
            val target = state.anchorItemIndex.coerceIn(0, state.items.lastIndex)
            listState.scrollToItem(target)
            val expected = mangaItemKey(target, state.items[target])
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == expected } }.first { it }
            state.items.getOrNull(state.anchorItemIndex)?.let { onVisibleItem(it, state.contentVersion, state.layoutVersion) }
            restored.complete(Unit)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }?.key }
                .collectLatest { key ->
                    if (currentIdentity == identity) {
                        state.items.withIndex().firstOrNull { mangaItemKey(it.index, it.value) == key }?.value?.let { onVisibleItem(it, state.contentVersion, state.layoutVersion) }
                    }
                }
        }
        LaunchedEffect(state.commandVersion, listState, state.items, identity) {
            if (!state.interactive) return@LaunchedEffect
            val command = state.command ?: return@LaunchedEffect
            restored.await()
            when (command) {
                is MangaScrollRequest.ToPage -> {
                    val target = mangaScrollTarget(state.items, command)
                    if (target >= 0) {
                        if (command.animated && state.pageAnimationEnabled) listState.animateScrollToItem(target)
                        else listState.scrollToItem(target)
                    }
                }
                is MangaScrollRequest.ByPixels -> { onCommandHandled(state.commandVersion); listState.scrollBy(command.pixels) }
            }
            onCommandHandled(state.commandVersion)
        }

        LazyColumn(
            state = listState,
            userScrollEnabled = state.interactive,
            modifier = Modifier
                .fillMaxSize()
                .background(pageColor)
                .then(readerInputModifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = state.items,
                key = { index, item -> mangaItemKey(index, item) },
            ) { _, item ->
                when (item) {
                    is MangaPage -> MangaImagePage(
                        page = item,
                        state = state,
                        horizontal = false,
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth(),
                        onMiddleTap = onMiddleTap,
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage,
                        onSaveImage = onSaveImage,
                    )
                    is ReaderLoading -> MangaLoadingPage(item, state, horizontal = false, onMiddleTap, onPreviousPage, onNextPage)
                }
            }
            item(key = "manga-footer") {
                MangaChapterFooter(
                    hasNextChapter = state.hasNextChapter,
                    loading = state.nextChapterLoading,
                    onClick = onLoadNextChapter,
                )
            }
        }
    }
}

@Composable
private fun MangaFreeScrollRow(
    state: MangaReaderUiState,
    spreads: List<MangaSpread>,
    initialPage: Int,
    pageColor: Color,
    onVisibleItem: (BaseMangaPage, Int, Int) -> Unit,
    onCommandHandled: (Long) -> Unit,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSaveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val identity = Triple(state.contentVersion, state.layoutVersion, state.interactive)
    val currentIdentity by rememberUpdatedState(identity)
    val restored = remember(identity) { kotlinx.coroutines.CompletableDeferred<Unit>() }
    LaunchedEffect(listState, spreads, identity) {
        if (!state.interactive) return@LaunchedEffect
        if (spreads.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(initialPage)
        val expected = spreads.getOrNull(initialPage)?.key
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == expected } }.first { it }
        spreads.getOrNull(initialPage)?.first?.let { onVisibleItem(it, state.contentVersion, state.layoutVersion) }
        restored.complete(Unit)
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }?.key }
            .collectLatest { key ->
                if (currentIdentity == identity) {
                    spreads.firstOrNull { it.key == key }?.first?.let { onVisibleItem(it, state.contentVersion, state.layoutVersion) }
                }
            }
    }
    LaunchedEffect(state.commandVersion, listState, spreads, identity) {
        if (!state.interactive) return@LaunchedEffect
        val command = state.command ?: return@LaunchedEffect
        restored.await()
        when (command) {
            is MangaScrollRequest.ToPage -> {
                val item = mangaScrollTarget(state.items, command)
                if (item >= 0) {
                    val target = findMangaSpreadIndex(spreads, item)
                    if (command.animated && state.pageAnimationEnabled) listState.animateScrollToItem(target)
                    else listState.scrollToItem(target)
                }
            }
            is MangaScrollRequest.ByPixels -> { onCommandHandled(state.commandVersion); listState.scrollBy(command.pixels) }
        }
        onCommandHandled(state.commandVersion)
    }

    LazyRow(
        state = listState,
        reverseLayout = state.rightToLeft,
        modifier = modifier
            .fillMaxSize()
            .background(pageColor),
    ) {
        itemsIndexed(
            items = spreads,
            key = { _, spread -> spread.key },
        ) { _, spread ->
            MangaSpreadPage(
                spread = spread,
                state = state,
                onMiddleTap = onMiddleTap,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onSaveImage = onSaveImage,
                modifier = Modifier.fillParentMaxSize(),
            )
        }
    }
}

@Composable
private fun MangaSpreadPage(
    spread: MangaSpread,
    state: MangaReaderUiState,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSaveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = spread.first
    if (first is ReaderLoading) {
        MangaLoadingPage(first, state, horizontal = true, onMiddleTap, onPreviousPage, onNextPage)
        return
    }
    val firstPage = first as MangaPage
    val pages = if (state.rightToLeft) listOfNotNull(spread.second, firstPage) else listOfNotNull(firstPage, spread.second)
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
    ) {
        pages.forEach { page ->
            MangaImagePage(
                page = page,
                state = state,
                horizontal = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onMiddleTap = onMiddleTap,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onSaveImage = onSaveImage,
            )
        }
    }
}

@Composable
private fun MangaImagePage(
    page: MangaPage,
    state: MangaReaderUiState,
    horizontal: Boolean,
    modifier: Modifier,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSaveImage: (String) -> Unit,
) {
    var reloadKey by remember(page.mImageUrl) { mutableStateOf(0) }
    var scale by remember(page.mImageUrl) { mutableFloatStateOf(1f) }
    var offsetX by remember(page.mImageUrl) { mutableFloatStateOf(0f) }
    var offsetY by remember(page.mImageUrl) { mutableFloatStateOf(0f) }
    val transformation: Transformation<Bitmap>? = remember(state.eInk, state.gray, state.eInkThreshold) {
        when {
            state.eInk -> EpaperTransformation(state.eInkThreshold)
            state.gray -> GrayscaleTransformation()
            else -> null
        }
    }
    val matrix = remember(state.colorFilter.contentHashCode()) {
        val r = state.colorFilter.getOrElse(0) { 0 }
        val g = state.colorFilter.getOrElse(1) { 0 }
        val b = state.colorFilter.getOrElse(2) { 0 }
        val a = state.colorFilter.getOrElse(3) { 0 }
        ColorMatrix(
            floatArrayOf(
                (255 - r) / 255f, 0f, 0f, 0f, 0f,
                0f, (255 - g) / 255f, 0f, 0f, 0f,
                0f, 0f, (255 - b) / 255f, 0f, 0f,
                0f, 0f, 0f, (255 - a) / 255f, 0f,
            )
        )
    }
    val imageLoadState by rememberLegadoImageState(
        requestKey = listOf(page.mImageUrl, state.contentVersion, transformation),
        retryKey = reloadKey,
    ) { requestContext ->
        BookCover.loadManga(
            context = requestContext,
            path = mangaImagePath(page),
            sourceOrigin = state.sourceOrigin,
            bookUrl = state.bookUrl,
            transformation = transformation,
        )
    }
    val gestureModifier = if (state.scaleEnabled) {
        Modifier.pointerInput(page.mImageUrl) {
            detectTransformGestures { _, pan, zoom, _ ->
                val next = (scale * zoom).coerceIn(1f, 5f)
                scale = next
                if (next > 1f) {
                    offsetX += pan.x
                    offsetY += pan.y
                } else {
                    offsetX = 0f
                    offsetY = 0f
                }
            }
        }
    } else Modifier

    val decoded = (imageLoadState as? LegadoImageState.Success)?.bitmap
    val imageRatio = when {
        decoded != null && decoded.height > 0 -> decoded.width.toFloat() / decoded.height
        page.imageWidth > 0 && page.imageHeight > 0 -> page.imageWidth.toFloat() / page.imageHeight
        else -> 2f / 3f
    }
    Box(
        modifier = modifier
            .then(if (horizontal) Modifier else Modifier.aspectRatio(imageRatio))
            .clipToBounds()
            .then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        when (val loadState = imageLoadState) {
            LegadoImageState.Loading -> CircularProgressIndicator()
            LegadoImageState.Error -> Button(onClick = { reloadKey++ }) {
                Text("重新加载")
            }
            is LegadoImageState.Success -> Image(
                bitmap = loadState.bitmap,
                contentDescription = "${page.mChapterName} ${page.index + 1}/${page.imageCount}",
                contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillWidth,
                colorFilter = ColorFilter.colorMatrix(matrix),
                modifier = Modifier
                    .then(if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
        }
        MangaTapOverlay(
            enabled = state.interactive && scale <= 1.01f,
            clickScrollEnabled = state.clickScrollEnabled,
            onMiddleTap = onMiddleTap,
            onPreviousPage = if (horizontal && state.rightToLeft) onNextPage else onPreviousPage,
            onNextPage = if (horizontal && state.rightToLeft) onPreviousPage else onNextPage,
            onLongPress = { onSaveImage(page.mImageUrl) },
            onDoubleTap = {
                if (state.scaleEnabled) {
                    scale = if (scale > 1.01f) 1f else 2f
                    if (scale == 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        )
    }
}

@Composable
private fun MangaTapOverlay(
    enabled: Boolean,
    clickScrollEnabled: Boolean,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    if (!enabled) return
    val middleTap by rememberUpdatedState(onMiddleTap)
    val previousPage by rememberUpdatedState(onPreviousPage)
    val nextPage by rememberUpdatedState(onNextPage)
    val longPress by rememberUpdatedState(onLongPress)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clickScrollEnabled) {
                detectTapGestures(
                    onLongPress = { longPress() },
                    onDoubleTap = { doubleTap() },
                    onTap = { offset ->
                        when {
                            offset.x in size.width * 0.33f..size.width * 0.67f -> middleTap()
                            clickScrollEnabled && offset.x < size.width * 0.33f -> previousPage()
                            clickScrollEnabled -> nextPage()
                        }
                    },
                )
            }
    )
}

@Composable
private fun MangaPageCurlCanvas(offsetFraction: Float, rightToLeft: Boolean) {
    val progress = abs(offsetFraction).coerceIn(0f, 1f)
    if (progress <= 0.001f) return
    Canvas(Modifier.fillMaxSize()) {
        val foldWidth = size.width * (0.08f + progress * 0.28f)
        val fromRight = (offsetFraction < 0f) xor rightToLeft
        val startX = if (fromRight) size.width - foldWidth else 0f
        val endX = if (fromRight) size.width else foldWidth
        drawRect(
            brush = Brush.horizontalGradient(
                colors = if (fromRight) {
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f * progress))
                } else {
                    listOf(Color.Black.copy(alpha = 0.30f * progress), Color.Transparent)
                },
                startX = startX,
                endX = endX,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(startX, 0f),
            size = androidx.compose.ui.geometry.Size(foldWidth, size.height),
        )
    }
}

@Composable
private fun MangaLoadingPage(
    item: ReaderLoading,
    state: MangaReaderUiState,
    horizontal: Boolean,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (horizontal || item.isVolume) Modifier.fillMaxHeight() else Modifier.heightIn(min = 96.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(item.mMessage.orEmpty(), color = MaterialTheme.colorScheme.onSurface)
        MangaTapOverlay(
            enabled = state.interactive,
            clickScrollEnabled = state.clickScrollEnabled,
            onMiddleTap = onMiddleTap,
            onPreviousPage = if (horizontal && state.rightToLeft) onNextPage else onPreviousPage,
            onNextPage = if (horizontal && state.rightToLeft) onPreviousPage else onNextPage,
            onLongPress = {},
            onDoubleTap = {},
        )
    }
}

@Composable
private fun MangaChapterFooter(hasNextChapter: Boolean, loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            hasNextChapter -> Button(onClick = onClick) { Text("加载下一章") }
            else -> Text("暂无章节了！")
        }
    }
}

private fun mangaItemKey(index: Int, item: BaseMangaPage): String = when (item) {
    is MangaPage -> "page:${item.chapterIndex}:${item.index}:${item.mImageUrl}"
    is ReaderLoading -> "loading:${item.chapterIndex}:${item.index}:${item.mMessage}"
}

internal fun mangaImagePath(page: MangaPage): String =
    page.cachePath?.takeIf { File(it).isFile } ?: page.mImageUrl
