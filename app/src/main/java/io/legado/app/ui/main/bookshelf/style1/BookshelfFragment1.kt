@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfTopBar
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.isCreated
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 书架界面 (样式1 - 标签页)
 */
class BookshelfFragment1() : BaseBookshelfFragment() {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val adapter by lazy { TabFragmentPageAdapter(childFragmentManager) }
    private val bookGroups = mutableListOf<BookGroup>()
    private var bookGroupsState by mutableStateOf<List<BookGroup>>(emptyList())
    private var selectedTabPositionState by mutableIntStateOf(0)
    private var viewPagerRef: ViewPager? = null
    private val fragmentMap = hashMapOf<Long, BooksFragment>()

    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() {
            val fragment = fragmentMap[groupId]
            return fragment?.getBooks() ?: emptyList()
        }

    override var onlyUpdateRead = false

    private val selectedGroup: BookGroup?
        get() = bookGroups.getOrNull(selectedTabPositionState)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Bookshelf1Screen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        subscribeShelfHeaderRefresh()
        initBookGroupData()
    }

    override fun onDestroyView() {
        viewPagerRef = null
        super.onDestroyView()
    }

    @Composable
    private fun Bookshelf1Screen() {
        Column(modifier = Modifier.fillMaxSize()) {
            BookshelfTopBar(
                title = stringResource(R.string.bookshelf),
                onMenuAction = ::handleBookshelfMenuAction,
                bookGroups = bookGroupsState,
                selectedTabIndex = selectedTabPositionState,
                onTabSelected = { index ->
                    selectedTabPositionState = index
                    AppConfig.saveTabPosition = index
                    viewPagerRef?.currentItem = index
                },
                onTabReselected = {
                    selectedGroup?.let { group ->
                        fragmentMap[group.groupId]?.let {
                            toastOnUi("${group.groupName}(${it.getBooksCount()})")
                        }
                    }
                },
                onTabLongClick = { group ->
                    showDialogFragment(GroupEditDialog(group))
                },
            )

            ShelfHeaderComposable()

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    ViewPager(ctx).apply {
                        id = R.id.view_pager_bookshelf
                        setEdgeEffectColor(requireContext().primaryColor)
                        offscreenPageLimit = 1
                        adapter = this@BookshelfFragment1.adapter
                        addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                            override fun onPageSelected(position: Int) {
                                selectedTabPositionState = position
                                AppConfig.saveTabPosition = position
                            }
                        })
                        viewPagerRef = this
                    }
                },
                update = { pager ->
                    val target = selectedTabPositionState.coerceIn(0, (adapter.count - 1).coerceAtLeast(0))
                    if (adapter.count > 0 && pager.currentItem != target) {
                        pager.currentItem = target
                    }
                },
            )
        }

        BookshelfDialogsComposable()
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else {
            if (data != bookGroups) {
                bookGroups.clear()
                bookGroups.addAll(data)
                bookGroupsState = data.toList()
                adapter.notifyDataSetChanged()
                val savedPos = AppConfig.saveTabPosition.coerceIn(0, (data.size - 1).coerceAtLeast(0))
                selectedTabPositionState = savedPos
                viewPagerRef?.post {
                    if (adapter.count > savedPos) {
                        viewPagerRef?.currentItem = savedPos
                    }
                }
            }
        }
    }

    override fun upSort() {
        adapter.notifyDataSetChanged()
    }

    override fun gotoTop() {
        fragmentMap[groupId]?.gotoTop()
    }

    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getPageTitle(position: Int): CharSequence {
            return bookGroups[position].groupName
        }

        override fun getItemPosition(any: Any): Int {
            val fragment = any as BooksFragment
            val position = fragment.position
            val group = bookGroups.getOrNull(position)
            if (fragment.groupId != group?.groupId) {
                return POSITION_NONE
            }
            val bookSort = group.getRealBookSort()
            fragment.setEnableRefresh(group.enableRefresh)
            if (fragment.bookSort != bookSort) {
                fragment.upBookSort(bookSort)
            }
            return POSITION_UNCHANGED
        }

        override fun getItem(position: Int): Fragment {
            val group = bookGroups[position]
            onlyUpdateRead = group.onlyUpdateRead
            return BooksFragment(position, group)
        }

        override fun getCount(): Int {
            return bookGroups.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as BooksFragment
            val group = bookGroups[position]
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as BooksFragment
            }
            fragmentMap[group.groupId] = fragment
            return fragment
        }

    }
}
