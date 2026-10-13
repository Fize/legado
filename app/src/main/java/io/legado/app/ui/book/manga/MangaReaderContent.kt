@file:OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)

package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.book.BookHelp
import io.legado.app.model.BookCover
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.manga.entities.EpaperTransformation
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
internal fun MangaReaderContent(
    state: MangaReaderUiState,
    commands: ReceiveChannel<MangaReaderCommand>,
    onVisiblePage: (MangaPage) -> Unit,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSaveImage: (String) -> Unit,
    onLoadNextChapter: () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.surface
    if (state.horizontal) {
        val spreads = remember(state.items, state.doublePage) {
            buildMangaSpreads(state.items, state.doublePage)
        }
        val initialPage = remember(state.contentVersion, state.layoutVersion, state.doublePage) {
            findMangaSpreadIndex(spreads, state.anchorItemIndex)
        }
        val pagerState = rememberPagerState(initialPage = initialPage) { spreads.size.coerceAtLeast(1) }
        var restoringPosition by remember(state.contentVersion, state.layoutVersion) {
            mutableStateOf(true)
        }

        LaunchedEffect(pagerState, spreads) {
            snapshotFlow { pagerState.currentPage }.collectLatest { page ->
                if (!restoringPosition) {
                    (spreads.getOrNull(page)?.first as? MangaPage)?.let(onVisiblePage)
                }
            }
        }
        LaunchedEffect(state.contentVersion, state.layoutVersion, initialPage, spreads.size) {
            if (spreads.isNotEmpty()) pagerState.scrollToPage(initialPage)
            restoringPosition = false
            (spreads.getOrNull(initialPage)?.first as? MangaPage)?.let(onVisiblePage)
        }
        LaunchedEffect(commands, pagerState, spreads, state.rightToLeft) {
            for (command in commands) {
                when (command) {
                    is MangaReaderCommand.ScrollToItem -> {
                        val target = findMangaSpreadIndex(spreads, command.itemIndex)
                        if (command.animated) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target)
                    }
                    is MangaReaderCommand.TurnPage -> {
                        val target = (pagerState.currentPage + command.direction)
                            .coerceIn(0, (spreads.size - 1).coerceAtLeast(0))
                        if (command.animated && state.pageAnimationEnabled) {
                            pagerState.animateScrollToPage(target, animationSpec = tween(260))
                        } else {
                            pagerState.scrollToPage(target)
                        }
                    }
                    is MangaReaderCommand.ScrollBy -> pagerState.scrollBy(command.pixels)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = state.rightToLeft,
                userScrollEnabled = true,
                modifier = Modifier
                    .fillMaxSize()
                    .background(pageColor),
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
        var restoringPosition by remember(state.contentVersion, state.layoutVersion) { mutableStateOf(true) }
        LaunchedEffect(listState, state.items) {
            snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { index ->
                if (!restoringPosition) {
                    (state.items.getOrNull(index) as? MangaPage)?.let(onVisiblePage)
                }
            }
        }
        LaunchedEffect(state.contentVersion, state.layoutVersion, state.anchorItemIndex, state.items.size) {
            if (state.items.isNotEmpty()) {
                listState.scrollToItem(state.anchorItemIndex.coerceIn(0, state.items.lastIndex))
            }
            restoringPosition = false
            (state.items.getOrNull(state.anchorItemIndex) as? MangaPage)?.let(onVisiblePage)
        }
        LaunchedEffect(commands, listState, state.items) {
            for (command in commands) {
                when (command) {
                    is MangaReaderCommand.ScrollToItem -> {
                        val target = command.itemIndex.coerceIn(0, (state.items.size - 1).coerceAtLeast(0))
                        if (command.animated) listState.animateScrollToItem(target) else listState.scrollToItem(target)
                    }
                    is MangaReaderCommand.TurnPage -> {
                        val target = (listState.firstVisibleItemIndex + command.direction)
                            .coerceIn(0, (state.items.size - 1).coerceAtLeast(0))
                        if (command.animated && state.pageAnimationEnabled) listState.animateScrollToItem(target)
                        else listState.scrollToItem(target)
                    }
                    is MangaReaderCommand.ScrollBy -> listState.scrollBy(command.pixels)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(pageColor),
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
                            .fillMaxWidth()
                            .widthIn(max = 680.dp),
                        onMiddleTap = onMiddleTap,
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage,
                        onSaveImage = onSaveImage,
                    )
                    is ReaderLoading -> MangaLoadingPage(item, horizontal = false)
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
private fun MangaSpreadPage(
    spread: MangaSpread,
    state: MangaReaderUiState,
    onMiddleTap: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSaveImage: (String) -> Unit,
) {
    val first = spread.first
    if (first is ReaderLoading) {
        MangaLoadingPage(first, horizontal = true)
        return
    }
    val firstPage = first as MangaPage
    val pages = if (state.rightToLeft) listOfNotNull(spread.second, firstPage) else listOfNotNull(firstPage, spread.second)
    Row(
        modifier = Modifier.fillMaxSize(),
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
    val context = LocalContext.current
    var loading by remember(page.mImageUrl) { mutableStateOf(true) }
    var failed by remember(page.mImageUrl) { mutableStateOf(false) }
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

    Box(
        modifier = modifier
            .clipToBounds()
            .then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        GlideImage(
            model = "${mangaImagePath(page.mImageUrl)}#$reloadKey",
            contentDescription = page.mChapterName,
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
            requestBuilderTransform = {
                BookCover.loadManga(
                    context = context,
                    path = mangaImagePath(page.mImageUrl),
                    sourceOrigin = ReadManga.book?.origin,
                    transformation = transformation,
                ).listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
                    ): Boolean {
                        loading = false
                        failed = true
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        loading = false
                        failed = false
                        return false
                    }
                })
            },
        )
        if (loading) CircularProgressIndicator()
        if (failed) {
            Button(onClick = {
                loading = true
                failed = false
                reloadKey++
            }) { Text("重新加载") }
        }
        MangaTapOverlay(
            enabled = scale <= 1.01f,
            clickScrollEnabled = state.clickScrollEnabled,
            onMiddleTap = onMiddleTap,
            onPreviousPage = onPreviousPage,
            onNextPage = onNextPage,
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clickScrollEnabled) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onDoubleTap = { onDoubleTap() },
                    onTap = { offset ->
                        when {
                            offset.x in size.width * 0.33f..size.width * 0.67f -> onMiddleTap()
                            clickScrollEnabled && offset.x < size.width * 0.33f -> onPreviousPage()
                            clickScrollEnabled -> onNextPage()
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
private fun MangaLoadingPage(item: ReaderLoading, horizontal: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (horizontal || item.isVolume) Modifier.fillMaxHeight() else Modifier.heightIn(min = 96.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(item.mMessage.orEmpty(), color = MaterialTheme.colorScheme.onSurface)
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

private fun mangaItemKey(index: Int, item: Any): String = when (item) {
    is MangaPage -> "page:${item.chapterIndex}:${item.index}:${item.mImageUrl}"
    is ReaderLoading -> "loading:${item.chapterIndex}:${item.index}:${item.mMessage}"
    else -> "unknown:$index"
}

internal fun mangaImagePath(imageUrl: String): String {
    val book = ReadManga.book ?: return imageUrl
    return BookHelp.getImage(book, imageUrl).takeIf { it.isFile }?.absolutePath ?: imageUrl
}
