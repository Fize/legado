package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class BookmarkFragment : VMBaseFragment<TocViewModel>(),
    TocViewModel.BookmarkCallBack {

    override val viewModel by activityViewModels<TocViewModel>()
    private var bookmarkJob: Job? = null
    private var durChapterIndex = 0
    private var bookmarksState by mutableStateOf<List<Bookmark>>(emptyList())
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
                    BookmarkScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.bookMarkCallBack = this
        viewModel.bookData.observe(viewLifecycleOwner) {
            durChapterIndex = it.durChapterIndex
            upBookmark(viewModel.searchKey)
        }
    }

    override fun onDestroyView() {
        bookmarkJob?.cancel()
        bookmarkJob = null
        bookmarksState = emptyList()
        viewModel.bookMarkCallBack = clearCallbackIfOwned(
            viewModel.bookMarkCallBack,
            this
        )
        super.onDestroyView()
    }

    override fun upBookmark(searchKey: String?) {
        bookmarkJob?.cancel()
        val book = viewModel.bookData.value ?: return
        bookmarkJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.bookmarkDao.flowByBook(book.name, book.author)
                else -> appDb.bookmarkDao.flowSearch(book.name, book.author, searchKey)
            }.catch {
                AppLog.put("目录界面获取书签数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { bookmarks ->
                bookmarksState = bookmarks
                val scrollPosition = bookmarks
                    .indexOfLast { it.chapterIndex < durChapterIndex }
                    .coerceAtLeast(0)
                scrollTargetIndex = scrollPosition
                scrollTargetNonce++
            }
        }
    }

    fun onClick(bookmark: Bookmark) {
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("index", bookmark.chapterIndex)
                putExtra("chapterPos", bookmark.chapterPos)
            })
            finish()
        }
    }

    fun onLongClick(bookmark: Bookmark, pos: Int) {
        showDialogFragment(BookmarkDialog(bookmark, pos))
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BookmarkScreen() {
        val listState = rememberLazyListState()
        LaunchedEffect(scrollTargetNonce) {
            if (scrollTargetNonce > 0L && bookmarksState.isNotEmpty()) {
                listState.scrollToItem(scrollTargetIndex.coerceIn(0, bookmarksState.lastIndex))
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = bookmarksState,
                key = { _, item -> item.time },
            ) { index, item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onClick(item) },
                            onLongClick = { onLongClick(item, index) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                    if (item.content.isNotEmpty()) {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
