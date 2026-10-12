package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

/**
 * 发现详情分栏 Fragment (用于平板/折叠屏双栏模式)
 */
class ExploreShowFragment : VMBaseFragment<ExploreShowViewModel>() {

    companion object {
        fun newInstance(sourceUrl: String, exploreName: String, exploreUrl: String): ExploreShowFragment {
            return ExploreShowFragment().apply {
                arguments = Bundle().apply {
                    putString("sourceUrl", sourceUrl)
                    putString("exploreName", exploreName)
                    putString("exploreUrl", exploreUrl)
                }
            }
        }
    }

    override val viewModel by viewModels<ExploreShowViewModel>()

    private var titleState by mutableStateOf("")
    private var booksState by mutableStateOf<List<SearchBook>>(emptyList())
    private var categoriesState by mutableStateOf<List<ExploreCategory>>(emptyList())
    private var selectedCategoryState by mutableStateOf<ExploreCategory?>(null)
    private var showCategoriesState by mutableStateOf(AppConfig.showExploreCategories)
    private var pageState by mutableIntStateOf(1)
    private var oldPage by mutableIntStateOf(-1)
    private var isClearAll by mutableStateOf(false)
    private var topLoadingState by mutableStateOf(false)
    private var topErrorState by mutableStateOf<String?>(null)
    private var bottomLoadingState by mutableStateOf(true)
    private var bottomHasMoreState by mutableStateOf(true)
    private var bottomErrorState by mutableStateOf<String?>(null)
    private var addBooksBusyState by mutableStateOf(false)
    private var adapterRefreshVersion by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ExploreShowScreen(
                        title = titleState,
                        onBack = null,
                        page = pageState,
                        oldPage = oldPage,
                        showCategories = showCategoriesState,
                        categories = categoriesState,
                        selectedCategory = selectedCategoryState,
                        addBooksBusy = addBooksBusyState,
                        books = booksState,
                        topLoading = topLoadingState,
                        topError = topErrorState,
                        bottomLoading = bottomLoadingState,
                        bottomHasMore = bottomHasMoreState,
                        bottomError = bottomErrorState,
                        adapterRefreshVersion = adapterRefreshVersion,
                        isInBookshelf = { viewModel.isInBookShelf(it) },
                        onToggleCategories = {
                            AppConfig.showExploreCategories = !AppConfig.showExploreCategories
                            updateCategories()
                        },
                        onAddLoadedBooks = ::alertAddLoadedBooksToShelf,
                        onChangePageClick = {
                            val page = viewModel.pageLiveData.value ?: 1
                            NumberPickerDialog(requireContext())
                                .setTitle(getString(R.string.change_page))
                                .setMaxValue(999)
                                .setMinValue(1)
                                .setValue(page)
                                .show {
                                    if (page != it) {
                                        oldPage = it
                                        viewModel.skipPage(it)
                                        topLoadingState = false
                                        topErrorState = null
                                        bottomHasMoreState = true
                                        bottomErrorState = null
                                        isClearAll = true
                                        booksState = emptyList()
                                        viewModel.explore()
                                    }
                                }
                        },
                        onSelectCategory = { category ->
                            if (category != viewModel.categoryData.value) {
                                isClearAll = false
                                viewModel.switchCategory(category)
                            }
                        },
                        onLoadBottom = ::scrollToBottom,
                        onLoadTop = ::scrollToTop,
                        onBookClick = { book ->
                            startActivity<BookInfoActivity> {
                                putExtra("name", book.name)
                                putExtra("author", book.author)
                                putExtra("bookUrl", book.bookUrl)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        titleState = arguments?.getString("exploreName").orEmpty()
        viewModel.booksData.observe(viewLifecycleOwner) { upData(it) }
        viewModel.categoryData.observe(viewLifecycleOwner) {
            titleState = it.title
            selectedCategoryState = it
        }
        viewModel.categoriesData.observe(viewLifecycleOwner) { updateCategories() }
        viewModel.initData(arguments, savedInstanceState)
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            if (it != null) {
                bottomLoadingState = false
                bottomErrorState = it
            }
        }
        viewModel.errorTopLiveData.observe(viewLifecycleOwner) {
            if (it != null) {
                topLoadingState = false
                topErrorState = it
            }
        }
        viewModel.upAdapterLiveData.observe(viewLifecycleOwner) {
            adapterRefreshVersion++
        }
        viewModel.pageLiveData.observe(viewLifecycleOwner) {
            pageState = it ?: 1
        }
        viewModel.addBooksBusy.observe(viewLifecycleOwner) {
            addBooksBusyState = it == true
        }
    }

    override fun onResume() {
        super.onResume()
        updateCategories()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateCategories() {
        val show = AppConfig.showExploreCategories
        showCategoriesState = show
        if (show) viewModel.loadCategories()
        categoriesState = viewModel.categoriesData.value.orEmpty().map {
            ExploreCategory(it.title, it.url.orEmpty())
        }
    }

    private fun alertAddLoadedBooksToShelf() {
        val loadedBooks = viewModel.getLoadedBooks()
        if (loadedBooks.isEmpty()) {
            toastOnUi(R.string.no_loaded_books_to_add)
            return
        }
        alert(titleResource = R.string.add_loaded_books_to_bookshelf) {
            setMessage(
                getString(R.string.add_loaded_books_to_bookshelf_message, loadedBooks.size)
            )
            yesButton {
                val started = viewModel.addLoadedBooksToShelf(loadedBooks)
                if (!started) {
                    toastOnUi(R.string.add_loaded_books_to_bookshelf_in_progress)
                }
            }
            noButton()
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((bottomHasMoreState && !bottomLoadingState && !topLoadingState && bottomErrorState == null) || forceLoad) {
            bottomErrorState = null
            bottomLoadingState = true
            bottomHasMoreState = true
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !bottomLoadingState && !topLoadingState && topErrorState == null) || forceLoad) {
            topErrorState = null
            topLoadingState = true
            viewModel.explore(oldPage - 1)
        } else if (oldPage <= 1) {
            viewModel.showPage(1)
        }
    }

    private fun upData(state: ExploreListState) {
        booksState = state.books
        oldPage = state.firstPage
        topLoadingState = false
        if (state.loading) {
            bottomLoadingState = true
            bottomHasMoreState = true
            bottomErrorState = null
        } else if (state.books.isEmpty()) {
            bottomLoadingState = false
            bottomHasMoreState = false
        } else if (!state.hasMore) {
            bottomLoadingState = false
            bottomHasMoreState = false
        } else {
            bottomLoadingState = false
            bottomHasMoreState = true
        }
        if (!state.loading && isClearAll) {
            isClearAll = false
        }
    }
}
