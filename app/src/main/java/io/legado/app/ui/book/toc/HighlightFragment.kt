package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun resolveHighlightChapterIndex(
    highlight: BookHighlight,
    chapterIndexes: Map<String, Int>
): Int? {
    return if (highlight.chapterUrl.isBlank()) {
        highlight.chapterIndex
    } else {
        chapterIndexes[highlight.chapterUrl]
    }
}

internal fun highlightBodyPosition(highlight: BookHighlight): Int {
    return (highlight.chapterPos - highlight.layoutTitleLength.coerceAtLeast(0))
        .coerceAtLeast(0)
}

class HighlightFragment : VMBaseFragment<TocViewModel>(),
    TocViewModel.HighlightCallBack {

    override val viewModel by activityViewModels<TocViewModel>()
    private var highlightJob: Job? = null
    private var chapterIndexes = emptyMap<String, Int>()
    private var durChapterIndex = 0
    private var highlightsState by mutableStateOf<List<BookHighlight>>(emptyList())
    private var scrollTargetIndex by mutableIntStateOf(0)
    private var scrollTargetNonce by mutableLongStateOf(0L)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    HighlightScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.highlightCallBack = this
        viewModel.bookData.observe(viewLifecycleOwner) {
            durChapterIndex = it.durChapterIndex
            upHighlight(viewModel.searchKey)
        }
    }

    override fun onDestroyView() {
        highlightJob?.cancel()
        highlightJob = null
        highlightsState = emptyList()
        chapterIndexes = emptyMap()
        viewModel.highlightCallBack = clearCallbackIfOwned(
            viewModel.highlightCallBack,
            this
        )
        super.onDestroyView()
    }

    override fun upHighlight(searchKey: String?) {
        highlightJob?.cancel()
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book)) {
            chapterIndexes = emptyMap()
            highlightsState = emptyList()
            return
        }
        highlightJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() ->
                    appDb.bookHighlightDao.flowByBook(book.bookUrl)

                else -> appDb.bookHighlightDao.flowSearch(book.bookUrl, searchKey)
            }.flowOn(IO).catch {
                AppLog.put("目录界面获取标注数据失败\n${it.localizedMessage}", it)
            }.collect { highlights ->
                chapterIndexes = withContext(IO) {
                    appDb.bookChapterDao.getChapterList(book.bookUrl)
                        .associate { it.url to it.index }
                }
                val orderedHighlights = highlights.sortedWith(
                    compareBy(
                        { resolveHighlightChapterIndex(it, chapterIndexes) ?: Int.MAX_VALUE },
                        ::highlightBodyPosition,
                        BookHighlight::time
                    )
                )
                highlightsState = orderedHighlights
                val scrollPosition = orderedHighlights
                    .indexOfLast {
                        resolveHighlightChapterIndex(it, chapterIndexes)
                            ?.let { chapterIndex -> chapterIndex < durChapterIndex } == true
                    }
                    .coerceAtLeast(0)
                scrollTargetIndex = scrollPosition
                scrollTargetNonce++
            }
        }
    }

    fun onClick(highlight: BookHighlight) {
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book)) return
        val chapterIndex = resolveHighlightChapterIndex(highlight, chapterIndexes) ?: return
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("index", chapterIndex)
                putExtra("chapterPos", highlight.chapterPos)
                putExtra(
                    TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                    highlight.layoutTitleLength
                )
                putExtra(
                    TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT,
                    highlight.bookText.takeIf {
                        highlight.chapterPosEnd - highlight.chapterPos == it.length
                    }.orEmpty()
                )
            })
            finish()
        }
    }

    fun onLongClick(highlight: BookHighlight) {
        showDialogFragment(HighlightNoteDialog(highlight))
    }

    private fun supportsHighlightPosition(book: Book): Boolean {
        return !book.isAudio && !book.isVideo &&
            (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HighlightScreen() {
        val listState = rememberLazyListState()
        LaunchedEffect(scrollTargetNonce) {
            if (scrollTargetNonce > 0L && highlightsState.isNotEmpty()) {
                listState.scrollToItem(scrollTargetIndex.coerceIn(0, highlightsState.lastIndex))
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = highlightsState,
                key = { item -> item.time },
            ) { item ->
                HighlightCardItem(
                    item = item,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HighlightCardItem(
        item: BookHighlight,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        val style = item.styleObj()
        val colorInt = style.fill.takeIf { it != 0 }
            ?: style.textColor.takeIf { it != 0 }
            ?: style.underline?.color?.takeIf { it != 0 }
            ?: style.strike?.color?.takeIf { it != 0 }
            ?: style.box?.color?.takeIf { it != 0 }
            ?: style.emphasis?.color?.takeIf { it != 0 }
            ?: 0

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(if (colorInt != 0) Color(colorInt) else Color.Transparent),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.chapterName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.bookText.isNotEmpty()) {
                        Text(
                            text = item.bookText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (item.note.isNotEmpty()) {
                        Text(
                            text = item.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
