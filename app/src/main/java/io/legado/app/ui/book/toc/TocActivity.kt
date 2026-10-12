@file:Suppress("DEPRECATION")

package io.legado.app.ui.book.toc

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.compose.component.LegadoSearchBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.showDialogFragment

/**
 * 目录
 */
class TocActivity : VMBaseComposeActivity<TocViewModel>(),
    TxtTocRuleDialog.CallBack {

    override val viewModel by viewModels<TocViewModel>()

    private var selectedTabPosition by mutableIntStateOf(0)
    private var searchQueryState by mutableStateOf("")
    private var showSearchState by mutableStateOf(false)
    private var currentBookState by mutableStateOf<Book?>(null)
    private val viewPagerId = View.generateViewId()
    private val waitDialog by lazy { WaitDialog(this) }

    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.saveBookmark(uri)
                2 -> viewModel.saveBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bookData.observe(this) {
            currentBookState = it
        }
        intent.getStringExtra("bookUrl")?.let {
            viewModel.initBook(it)
        }
    }

    @Composable
    override fun Content() {
        var showMenu by remember { mutableStateOf(false) }
        var viewPagerRef by remember { mutableStateOf<ViewPager?>(null) }
        val book = currentBookState

        var reverseTocChecked by remember(book, showMenu) {
            mutableStateOf(
                book?.run {
                    if (isEpub || isPdf) getReverseToc() else getReverseTocDisplay()
                } == true
            )
        }
        var useReplaceChecked by remember(showMenu) {
            mutableStateOf(AppConfig.tocUiUseReplace)
        }
        var loadWordCountChecked by remember(showMenu) {
            mutableStateOf(AppConfig.tocCountWords)
        }
        var splitLongChapterChecked by remember(book, showMenu) {
            mutableStateOf(book?.getSplitLongChapter() == true)
        }
        var expandTocChecked by remember(book, showMenu) {
            mutableStateOf(book?.getTocExpanded() != false)
        }

        Scaffold(
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LegadoTopAppBar(
                        title = book?.name ?: stringResource(R.string.chapter_list),
                        onNavigationClick = { finish() },
                        actions = {
                            IconButton(
                                onClick = {
                                    showSearchState = !showSearchState
                                    if (!showSearchState && searchQueryState.isNotEmpty()) {
                                        searchQueryState = ""
                                        viewModel.searchKey = ""
                                        startSearch(selectedTabPosition, "")
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search),
                                )
                            }
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                when (selectedTabPosition) {
                                    0 -> {
                                        if (book?.isLocalTxt == true) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.txt_toc_rule)) },
                                                onClick = {
                                                    showMenu = false
                                                    showDialogFragment(
                                                        TxtTocRuleDialog(viewModel.bookData.value?.tocUrl)
                                                    )
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.split_long_chapter)) },
                                                trailingIcon = {
                                                    Checkbox(
                                                        checked = splitLongChapterChecked,
                                                        onCheckedChange = null,
                                                    )
                                                },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.bookData.value?.let { currentBook ->
                                                        val next = !splitLongChapterChecked
                                                        splitLongChapterChecked = next
                                                        currentBook.setSplitLongChapter(next)
                                                        upBookAndToc(currentBook)
                                                    }
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.reverse_toc)) },
                                            trailingIcon = {
                                                Checkbox(
                                                    checked = reverseTocChecked,
                                                    onCheckedChange = null,
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                viewModel.reverseToc {
                                                    reverseTocChecked = !reverseTocChecked
                                                    viewModel.chapterListCallBack?.upChapterList(
                                                        searchQueryState,
                                                        resetCollapse = true,
                                                        replaceAll = true,
                                                    )
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.expand_toc)) },
                                            trailingIcon = {
                                                Checkbox(
                                                    checked = expandTocChecked,
                                                    onCheckedChange = null,
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                val expanded = !expandTocChecked
                                                expandTocChecked = expanded
                                                viewModel.setTocExpanded(expanded)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.use_replace)) },
                                            trailingIcon = {
                                                Checkbox(
                                                    checked = useReplaceChecked,
                                                    onCheckedChange = null,
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                val next = !useReplaceChecked
                                                useReplaceChecked = next
                                                AppConfig.tocUiUseReplace = next
                                                viewModel.chapterListCallBack?.clearDisplayTitle()
                                                viewModel.chapterListCallBack?.upChapterList(searchQueryState)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.load_word_count)) },
                                            trailingIcon = {
                                                Checkbox(
                                                    checked = loadWordCountChecked,
                                                    onCheckedChange = null,
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                val next = !loadWordCountChecked
                                                loadWordCountChecked = next
                                                AppConfig.tocCountWords = next
                                                viewModel.upChapterListAdapter()
                                            },
                                        )
                                    }

                                    1 -> {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export)) },
                                            onClick = {
                                                showMenu = false
                                                exportDir.launch {
                                                    requestCode = 1
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export_md)) },
                                            onClick = {
                                                showMenu = false
                                                exportDir.launch {
                                                    requestCode = 2
                                                }
                                            },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log)) },
                                    onClick = {
                                        showMenu = false
                                        showDialogFragment<AppLogDialog>()
                                    },
                                )
                            }
                        },
                    )
                    if (showSearchState) {
                        LegadoSearchBar(
                            query = searchQueryState,
                            onQueryChange = { newText ->
                                searchQueryState = newText
                                viewModel.searchKey = newText
                                startSearch(selectedTabPosition, newText)
                            },
                            placeholder = stringResource(R.string.search),
                        )
                    } else {
                        val tabs = listOf(
                            stringResource(R.string.chapter_list),
                            stringResource(R.string.bookmark),
                            stringResource(R.string.highlight_tab),
                        )
                        TabRow(
                            selectedTabIndex = selectedTabPosition,
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabPosition == index,
                                    onClick = {
                                        selectedTabPosition = index
                                        viewPagerRef?.currentItem = index
                                        startSearch(index, viewModel.searchKey)
                                    },
                                    text = { Text(title) },
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                factory = { ctx ->
                    ViewPager(ctx).apply {
                        id = viewPagerId
                        offscreenPageLimit = 2
                        adapter = TabFragmentPageAdapter()
                        addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                            override fun onPageSelected(position: Int) {
                                selectedTabPosition = position
                                startSearch(position, viewModel.searchKey)
                            }
                        })
                        viewPagerRef = this
                    }
                },
                update = { pager ->
                    if (pager.currentItem != selectedTabPosition) {
                        pager.currentItem = selectedTabPosition
                    }
                },
            )
        }
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    private fun upBookAndToc(book: Book) {
        waitDialog.show()
        viewModel.upBookTocRule(book) {
            waitDialog.dismiss()
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    private fun startSearch(position: Int, searchKey: String?) {
        when (position) {
            1 -> viewModel.startBookmarkSearch(searchKey)
            2 -> viewModel.startHighlightSearch(searchKey)
            else -> viewModel.startChapterListSearch(searchKey)
        }
    }

    private inner class TabFragmentPageAdapter :
        FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int): Fragment {
            return when (position) {
                1 -> BookmarkFragment()
                2 -> HighlightFragment()
                else -> ChapterListFragment()
            }
        }

        override fun getCount(): Int {
            return 3
        }

        override fun getPageTitle(position: Int): CharSequence {
            return when (position) {
                1 -> getString(R.string.bookmark)
                2 -> getString(R.string.highlight_tab)
                else -> getString(R.string.chapter_list)
            }
        }

    }

}
