package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.ContentSearchResult as SearchResult
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.visible

/**
 * 搜索界面菜单
 */
class SearchMenu @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    class ViewSearchMenuViewHolder(context: Context, parent: ViewGroup) {
        private val borderlessBgResId: Int = TypedValue().let {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true
            )
            it.resourceId
        }

        val vwMenuBg = View(context).apply {
            id = R.id.vw_menu_bg
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentDescription = context.getString(R.string.content)
        }
        val fabLeft = FloatingActionButton(context).apply {
            id = R.id.fabLeft
            size = FloatingActionButton.SIZE_MINI
            contentDescription = context.getString(R.string.previous_search_result)
            rotation = 180f
            setImageResource(R.drawable.ic_arrow_right)
            compatElevation = 2f.dpToPx()
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = 16.dpToPx()
                setMargins(m, m, m, m)
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val fabRight = FloatingActionButton(context).apply {
            id = R.id.fabRight
            size = FloatingActionButton.SIZE_MINI
            contentDescription = context.getString(R.string.next_search_result)
            setImageResource(R.drawable.ic_arrow_right)
            compatElevation = 2f.dpToPx()
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = 16.dpToPx()
                setMargins(m, m, m, m)
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val ivSearchContentUp = AppCompatImageView(context).apply {
            id = R.id.iv_search_content_up
            layoutParams = LinearLayout.LayoutParams(
                48.dpToPx(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(borderlessBgResId)
            contentDescription = context.getString(R.string.previous_search_result)
            setImageResource(R.drawable.ic_arrow_drop_up)
        }
        val ivSearchContentDown = AppCompatImageView(context).apply {
            id = R.id.iv_search_content_down
            layoutParams = LinearLayout.LayoutParams(
                48.dpToPx(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(borderlessBgResId)
            contentDescription = context.getString(R.string.next_search_result)
            setImageResource(R.drawable.ic_arrow_drop_down)
        }
        val tvCurrentSearchInfo = TextView(context).apply {
            id = R.id.tv_current_search_info
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
            setBackgroundResource(borderlessBgResId)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            isSingleLine = true
            textSize = 12f
        }
        val llSearchBaseInfo = LinearLayout(context).apply {
            id = R.id.ll_search_base_info
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            )
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            addView(ivSearchContentUp)
            addView(ivSearchContentDown)
            addView(tvCurrentSearchInfo)
        }
        val ivSearchResults = AppCompatImageView(context).apply {
            id = R.id.iv_search_results
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adjustViewBounds = true
            contentDescription = context.getString(R.string.search_menu_results)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_toc)
        }
        val tvSearchResults = TextView(context).apply {
            id = R.id.tv_search_results
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            maxLines = 2
            gravity = Gravity.CENTER
            text = context.getString(R.string.search_menu_results)
            textSize = 12f
        }
        val llSearchResults = LinearLayout(context).apply {
            id = R.id.ll_search_results
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.search_menu_results)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivSearchResults)
            addView(tvSearchResults)
        }
        val ivMainMenu = AppCompatImageView(context).apply {
            id = R.id.iv_main_menu
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adjustViewBounds = true
            contentDescription = context.getString(R.string.main_menu)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_menu)
        }
        val tvMainMenu = TextView(context).apply {
            id = R.id.tv_main_menu
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            maxLines = 2
            gravity = Gravity.CENTER
            text = context.getString(R.string.main_menu)
            textSize = 12f
        }
        val llMainMenu = LinearLayout(context).apply {
            id = R.id.ll_main_menu
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.main_menu)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivMainMenu)
            addView(tvMainMenu)
        }
        val ivSearchExit = AppCompatImageView(context).apply {
            id = R.id.iv_search_exit
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adjustViewBounds = true
            contentDescription = context.getString(R.string.exit)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_auto_page_stop)
        }
        val tvSearchExit = TextView(context).apply {
            id = R.id.tv_search_exit
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            maxLines = 2
            gravity = Gravity.CENTER
            text = context.getString(R.string.exit)
            textSize = 12f
        }
        val llSearchExit = LinearLayout(context).apply {
            id = R.id.ll_search_exit
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.exit)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivSearchExit)
            addView(tvSearchExit)
        }
        val llBottomBg = LinearLayout(context).apply {
            id = R.id.ll_bottom_bg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isBaselineAligned = false
            orientation = LinearLayout.HORIZONTAL
            addView(llSearchResults)
            addView(llMainMenu)
            addView(llSearchExit)
        }
        val llBottomMenu = LinearLayout(context).apply {
            id = R.id.ll_bottom_menu
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            orientation = LinearLayout.VERTICAL
            addView(llSearchBaseInfo)
            addView(llBottomBg)
        }
        val root = ConstraintLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(vwMenuBg)
            addView(fabLeft)
            addView(fabRight)
            addView(llBottomMenu)
        }

        init {
            parent.addView(root)
        }
    }

    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewSearchMenuViewHolder(context, this)

    private val menuBottomIn: Animation = loadAnimation(context, R.anim.anim_readbook_bottom_in)
    private val menuBottomOut: Animation = loadAnimation(context, R.anim.anim_readbook_bottom_out)
    private val bgColor: Int = context.bottomBackground
    private val textColor: Int = context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    private val bottomBackgroundList: ColorStateList =
        Selector.colorBuild().setDefaultColor(bgColor)
            .setPressedColor(ColorUtils.darkenColor(bgColor)).create()
    private var onMenuOutEnd: (() -> Unit)? = null
    private var isMenuOutAnimating = false

    private val searchResultList: MutableList<SearchResult> = mutableListOf()
    private var currentSearchResultIndex: Int = -1
    private var lastSearchResultIndex: Int = -1
    private val hasSearchResult: Boolean
        get() = searchResultList.isNotEmpty()
    val selectedSearchResult: SearchResult?
        get() = searchResultList.getOrNull(currentSearchResultIndex)
    val previousSearchResult: SearchResult?
        get() = searchResultList.getOrNull(lastSearchResultIndex)
    val bottomMenuVisible get() = isVisible && binding.llBottomMenu.isVisible

    init {
        initAnimation()
        initView()
        bindEvent()
        updateNavigationAvailability()
        updateSearchInfo()
    }

    fun upSearchResultList(resultList: List<SearchResult>) {
        searchResultList.clear()
        searchResultList.addAll(resultList)
        updateNavigationAvailability()
        updateSearchInfo()
    }

    private fun updateNavigationAvailability() = binding.run {
        listOf(fabLeft, fabRight, ivSearchContentUp, ivSearchContentDown).forEach {
            it.isEnabled = hasSearchResult
        }
    }

    private fun navigateResult(index: Int) {
        if (!hasSearchResult) return
        updateSearchResultIndex(index)
        searchResultList.getOrNull(currentSearchResultIndex)?.let {
            callBack.navigateToSearch(it, currentSearchResultIndex)
        }
    }

    private fun initView() = binding.run {
        llSearchBaseInfo.setBackgroundColor(bgColor)
        tvCurrentSearchInfo.setTextColor(bottomBackgroundList)
        llBottomBg.setBackgroundColor(bgColor)
        fabLeft.backgroundTintList = bottomBackgroundList
        fabLeft.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        fabRight.backgroundTintList = bottomBackgroundList
        fabRight.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvMainMenu.setTextColor(textColor)
        tvSearchResults.setTextColor(textColor)
        tvSearchExit.setTextColor(textColor)
        ivMainMenu.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchResults.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchExit.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchContentUp.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        ivSearchContentDown.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvCurrentSearchInfo.setTextColor(textColor)
        applyNavigationBarPadding()
    }


    fun runMenuIn() {
        this.visible()
        binding.llBottomMenu.visible()
        binding.vwMenuBg.visible()
        binding.llBottomMenu.startAnimation(menuBottomIn)
    }

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            binding.llBottomMenu.startAnimation(menuBottomOut)
        }
    }

    fun updateSearchInfo() {
        ReadBook.curTextChapter?.let {
            binding.tvCurrentSearchInfo.text = context.getString(
                R.string.search_menu_chapter_summary, searchResultList.size, it.title,
            )
        }
    }

    fun updateSearchResultIndex(updateIndex: Int) {
        lastSearchResultIndex = currentSearchResultIndex
        currentSearchResultIndex = when {
            updateIndex < 0 -> 0
            updateIndex >= searchResultList.size -> searchResultList.size - 1
            else -> updateIndex
        }
    }

    private fun bindEvent() = binding.run {
        //搜索结果
        llSearchResults.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(selectedSearchResult?.query)
            }
        }

        //主菜单
        llMainMenu.setOnClickListener {
            runMenuOut {
                callBack.cancelSelect()
                callBack.showMenuBar()
                this@SearchMenu.invisible()
            }
        }

        //退出
        llSearchExit.setOnClickListener {
            runMenuOut {
                callBack.exitSearchMenu()
            }
        }

        fabLeft.setOnClickListener {
            navigateResult(currentSearchResultIndex - 1)
        }

        ivSearchContentUp.setOnClickListener {
            navigateResult(currentSearchResultIndex - 1)
        }

        ivSearchContentDown.setOnClickListener {
            navigateResult(currentSearchResultIndex + 1)
        }

        fabRight.setOnClickListener {
            navigateResult(currentSearchResultIndex + 1)
        }
    }

    private fun initAnimation() {
        //显示菜单
        menuBottomIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                callBack.upSystemUiVisibility()
                binding.fabLeft.visible(hasSearchResult)
                binding.fabRight.visible(hasSearchResult)
            }

            @SuppressLint("RtlHardcoded")
            override fun onAnimationEnd(animation: Animation) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                callBack.upSystemUiVisibility()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })

        //隐藏菜单
        menuBottomOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                isMenuOutAnimating = true
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onAnimationEnd(animation: Animation) {
                isMenuOutAnimating = false
                binding.llBottomMenu.invisible()
                binding.vwMenuBg.invisible()
                binding.vwMenuBg.setOnClickListener { runMenuOut() }

                onMenuOutEnd?.invoke()
                callBack.upSystemUiVisibility()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })
    }

    interface CallBack {
        var isShowingSearchResult: Boolean
        fun openSearchActivity(searchWord: String?)
        fun showSearchSetting()
        fun upSystemUiVisibility()
        fun exitSearchMenu()
        fun showMenuBar()
        fun navigateToSearch(searchResult: SearchResult, index: Int)
        fun onMenuShow()
        fun onMenuHide()
        fun cancelSelect()
    }

}
