package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.legado.app.R
import io.legado.app.constant.AppConst.timeFormat
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.ReaderInfoValues
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.BatteryView
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTextIfNotEqual
import splitties.views.backgroundColor
import java.util.Date

internal object BookmarkIndicatorGeometry {
    fun marginRight(rootPaddingRight: Int, headerPaddingRight: Int, indicatorPaddingRight: Int): Int =
        rootPaddingRight + headerPaddingRight - indicatorPaddingRight

    fun top(baseline: Int, height: Int, paddingBottom: Int, minTop: Int): Int =
        (baseline - height + paddingBottom).coerceAtLeast(minTop)
}

/**
 * 页面视图
 */
class PageView(context: Context) : FrameLayout(context) {

    class ViewBookPageViewHolder(context: Context, parent: ViewGroup) {
        val vwStatusBar = FrameLayout(context).apply {
            id = R.id.vw_status_bar
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvHeaderLeft = BatteryView(context).apply {
            id = R.id.tv_header_left
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            textSize = 12f
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                horizontalWeight = 1f
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToLeft = R.id.tv_header_right
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvHeaderMiddle = BatteryView(context).apply {
            id = R.id.tv_header_middle
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            textSize = 12f
            visibility = View.GONE
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvHeaderRight = BatteryView(context).apply {
            id = R.id.tv_header_right
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            textSize = 12f
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 3.dpToPx()
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val llHeader = ConstraintLayout(context).apply {
            id = R.id.ll_header
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = R.id.vw_status_bar
            }
            addView(tvHeaderLeft)
            addView(tvHeaderMiddle)
            addView(tvHeaderRight)
        }
        val vwTopDivider = View(context).apply {
            id = R.id.vw_top_divider
            setBackgroundResource(R.color.divider)
            visibility = View.INVISIBLE
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (0.5f.dpToPx()).toInt().coerceAtLeast(1)
            ).apply {
                topToBottom = R.id.ll_header
            }
        }
        val contentTextView = ContentTextView(context, null).apply {
            id = R.id.content_text_view
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                topToBottom = R.id.vw_top_divider
                bottomToTop = R.id.vw_bottom_divider
            }
        }
        val vwBottomDivider = View(context).apply {
            id = R.id.vw_bottom_divider
            setBackgroundResource(R.color.divider)
            visibility = View.INVISIBLE
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (0.5f.dpToPx()).toInt().coerceAtLeast(1)
            ).apply {
                bottomToTop = R.id.ll_footer
            }
        }
        val tvFooterLeft = BatteryView(context).apply {
            id = R.id.tv_footer_left
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            textSize = 12f
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                horizontalWeight = 1f
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToLeft = R.id.tv_footer_right
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvFooterMiddle = BatteryView(context).apply {
            id = R.id.tv_footer_middle
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            textSize = 12f
            visibility = View.GONE
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvFooterRight = BatteryView(context).apply {
            id = R.id.tv_footer_right
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = true
            textSize = 12f
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 3.dpToPx()
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val llFooter = ConstraintLayout(context).apply {
            id = R.id.ll_footer
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToTop = R.id.vw_navigation_bar
            }
            addView(tvFooterLeft)
            addView(tvFooterMiddle)
            addView(tvFooterRight)
        }
        val vwNavigationBar = FrameLayout(context).apply {
            id = R.id.vw_navigation_bar
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val pageBookmarkIndicator = ImageView(context).apply {
            id = R.id.page_bookmark_indicator
            alpha = 0.88f
            contentDescription = context.getString(R.string.bookmark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val pad = 4.dpToPx()
            setPadding(pad, pad, pad, pad)
            setImageResource(R.drawable.ic_bookmark_filled)
            visibility = View.GONE
            layoutParams = ConstraintLayout.LayoutParams(
                32.dpToPx(),
                32.dpToPx()
            ).apply {
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val vwRoot = ConstraintLayout(context).apply {
            id = R.id.vw_root
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(R.color.background)
            addView(vwStatusBar)
            addView(llHeader)
            addView(vwTopDivider)
            addView(contentTextView)
            addView(vwBottomDivider)
            addView(llFooter)
            addView(vwNavigationBar)
            addView(pageBookmarkIndicator)
        }

        init {
            parent.addView(vwRoot)
        }
    }

    private val binding = ViewBookPageViewHolder(context, this)
    private val readBookActivity get() = activity as? ReadBookActivity
    private var battery = 100
    private var readerInfoValues = ReaderInfoValues(battery = 100)
    private var readerInfoViews = emptyArray<ReaderInfoView>()
    private var isMainView = false
    private var bookmarkIndicatorVisible = false
    private var progressTextPage: TextPage? = null
    var isScroll = false
    internal val contentViewTop: Float
        get() = binding.vwRoot.top + binding.contentTextView.top.toFloat()

    internal fun closePdfRenderer() = binding.contentTextView.closePdfRenderer()
    internal fun cancelHighlightTap() = binding.contentTextView.cancelHighlightTap()

    val headerHeight: Int
        get() {
            val h1 = if (binding.vwStatusBar.isGone) 0 else binding.vwStatusBar.height
            val h2 = if (binding.llHeader.isGone) 0 else binding.llHeader.height
            return h1 + h2 + binding.vwRoot.paddingTop
        }
    val imgBgPaddingStart: Int
        get() {
            return binding.vwRoot.paddingStart
        }
    fun bookmarkIndicatorMarginRight(indicatorPaddingRight: Int): Int =
        BookmarkIndicatorGeometry.marginRight(
            binding.vwRoot.paddingRight,
            binding.llHeader.paddingRight,
            indicatorPaddingRight,
        )

    fun bookmarkIndicatorTop(height: Int, paddingBottom: Int): Int {
        val baseline = binding.llHeader.top + binding.tvHeaderRight.top +
                binding.tvHeaderRight.baseline
        return BookmarkIndicatorGeometry.top(
            baseline,
            height,
            paddingBottom,
            binding.vwRoot.paddingTop,
        )
    }

    init {
        if (!isInEditMode) {
            binding.pageBookmarkIndicator.setColorFilter(context.accentColor)
            upStyle()
            binding.vwStatusBar.applyStatusBarPadding()
            binding.vwNavigationBar.applyNavigationBarPadding()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        upBg()
    }

    private val isDoublePageSpread: Boolean
        get() = (textPage.doublePage || ChapterProvider.doublePage) && !isScroll

    private fun applyDoublePageBarLayout() = binding.run {
        val isDouble = isDoublePageSpread
        val textColor = ReadBookConfig.textColor
        val tipColor = with(ReadTipConfig) {
            if (tipColor == 0) {
                if (isDouble) io.legado.app.utils.ColorUtils.withAlpha(textColor, 0.48f) else textColor
            } else {
                tipColor
            }
        }
        tvHeaderLeft.setColor(tipColor)
        tvHeaderMiddle.setColor(tipColor)
        tvHeaderRight.setColor(tipColor)
        tvFooterLeft.setColor(tipColor)
        tvFooterMiddle.setColor(tipColor)
        tvFooterRight.setColor(tipColor)

        if (isDouble) {
            llHeader.isGone = ReadTipConfig.headerMode == 2
            llFooter.isGone = ReadTipConfig.footerMode == 1
            vwTopDivider.isGone = true
            vwBottomDivider.isGone = true
            val colPadLeft = ChapterProvider.paddingLeft.takeIf { it > 0 } ?: 64.dpToPx()
            val colPadRight = ChapterProvider.paddingRight.takeIf { it > 0 } ?: 64.dpToPx()
            llHeader.setPadding(
                0,
                maxOf(ReadBookConfig.headerPaddingTop, 8).dpToPx(),
                0,
                maxOf(ReadBookConfig.headerPaddingBottom, 4).dpToPx(),
            )
            llFooter.setPadding(
                0,
                maxOf(ReadBookConfig.footerPaddingTop, 4).dpToPx(),
                0,
                maxOf(ReadBookConfig.footerPaddingBottom, 10).dpToPx(),
            )
            tvHeaderLeft.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                width = 0
                horizontalWeight = 1f
            }
            tvHeaderLeft.setPadding(colPadLeft, 0, colPadRight, 0)
            tvHeaderLeft.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL

            tvHeaderRight.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                width = 0
                horizontalWeight = 1f
                leftToRight = tvHeaderLeft.id
                leftMargin = 0
            }
            tvHeaderRight.setPadding(colPadLeft, 0, colPadRight, 0)
            tvHeaderRight.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL

            tvFooterLeft.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                width = 0
                horizontalWeight = 1f
            }
            tvFooterLeft.setPadding(colPadLeft, 0, colPadRight, 0)
            tvFooterLeft.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL

            tvFooterRight.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                width = 0
                horizontalWeight = 1f
                leftToRight = tvFooterLeft.id
                leftMargin = 0
            }
            tvFooterRight.setPadding(colPadLeft, 0, colPadRight, 0)
            tvFooterRight.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        } else {
            ReadBookConfig.let {
                val extraLeft = (ChapterProvider.paddingLeft - it.paddingLeft.dpToPx()).coerceAtLeast(0)
                val extraRight = (ChapterProvider.paddingRight - it.paddingRight.dpToPx()).coerceAtLeast(0)
                llHeader.setPadding(
                    it.headerPaddingLeft.dpToPx() + extraLeft,
                    it.headerPaddingTop.dpToPx(),
                    it.headerPaddingRight.dpToPx() + extraRight,
                    it.headerPaddingBottom.dpToPx()
                )
                llFooter.setPadding(
                    it.footerPaddingLeft.dpToPx() + extraLeft,
                    it.footerPaddingTop.dpToPx(),
                    it.footerPaddingRight.dpToPx() + extraRight,
                    it.footerPaddingBottom.dpToPx()
                )
                vwTopDivider.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    leftMargin = extraLeft
                    rightMargin = extraRight
                }
                vwBottomDivider.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    leftMargin = extraLeft
                    rightMargin = extraRight
                }
                vwTopDivider.gone(llHeader.isGone || !it.showHeaderLine)
                vwBottomDivider.gone(llFooter.isGone || !it.showFooterLine)
            }
            tvHeaderLeft.setPadding(0, 0, 0, 0)
            tvHeaderLeft.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            tvHeaderRight.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                horizontalWeight = 0f
                leftToRight = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                leftMargin = 3.dpToPx()
            }
            tvHeaderRight.setPadding(0, 0, 0, 0)
            tvHeaderRight.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL

            tvFooterLeft.setPadding(0, 0, 0, 0)
            tvFooterLeft.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            tvFooterRight.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                horizontalWeight = 0f
                leftToRight = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                leftMargin = 3.dpToPx()
            }
            tvFooterRight.setPadding(0, 0, 0, 0)
            tvFooterRight.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
    }

    fun upStyle() = binding.run {
        upTipStyle()
        ReadBookConfig.let {
            val textColor = it.textColor
            val tipDividerColor = with(ReadTipConfig) {
                when (tipDividerColor) {
                    -1 -> ContextCompat.getColor(context, R.color.divider)
                    0 -> textColor
                    else -> tipDividerColor
                }
            }
            vwTopDivider.backgroundColor = tipDividerColor
            vwBottomDivider.backgroundColor = tipDividerColor
            upStatusBar()
            upNavigationBar()
            upPaddingDisplayCutouts()
        }
        applyDoublePageBarLayout()
        readerInfoValues = readerInfoValues.copy(
            time = timeFormat.format(Date(System.currentTimeMillis())),
            battery = battery,
        )
        renderReaderInfo()
    }

    /**
     * 显示状态栏时隐藏header
     */
    fun upStatusBar() = with(binding.vwStatusBar) {
//        setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)
        isGone = ReadBookConfig.hideStatusBar || readBookActivity?.isInMultiWindow == true
    }

    fun upNavigationBar() {
        binding.vwNavigationBar.isGone = ReadBookConfig.hideNavigationBar
    }

    fun upPaddingDisplayCutouts() {
        if (ReadBookConfig.isNineBgImg) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            return
        }
        if (AppConfig.paddingDisplayCutouts) {
            binding.vwRoot.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                binding.vwRoot.setPadding(
                    insets.left,
                    if (binding.vwStatusBar.isGone) insets.top else 0,
                    insets.right,
                    insets.bottom
                )
                if (isMainView) {
                    readBookActivity?.upBookmarkIndicator()
                }
                windowInsets
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(binding.vwRoot, null)
            binding.vwRoot.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * 更新阅读信息
     */
    private fun upTipStyle() = binding.run {
        llHeader.isGone = when (ReadTipConfig.headerMode) {
            1 -> false
            2 -> true
            else -> if (isDoublePageSpread) false else !ReadBookConfig.hideStatusBar
        }
        llFooter.isGone = when (ReadTipConfig.footerMode) {
            1 -> true
            else -> false
        }
        readerInfoViews = with(ReadTipConfig) {
            arrayOf(
                ReaderInfoView(
                    tvHeaderLeft,
                    effectiveTemplate(tipHeaderLeftTemplate, tipHeaderLeft),
                ),
                ReaderInfoView(
                    tvHeaderMiddle,
                    effectiveTemplate(tipHeaderMiddleTemplate, tipHeaderMiddle),
                ),
                ReaderInfoView(
                    tvHeaderRight,
                    effectiveTemplate(tipHeaderRightTemplate, tipHeaderRight),
                ),
                ReaderInfoView(
                    tvFooterLeft,
                    effectiveTemplate(tipFooterLeftTemplate, tipFooterLeft),
                ),
                ReaderInfoView(
                    tvFooterMiddle,
                    effectiveTemplate(tipFooterMiddleTemplate, tipFooterMiddle),
                ),
                ReaderInfoView(
                    tvFooterRight,
                    effectiveTemplate(tipFooterRightTemplate, tipFooterRight),
                ),
            )
        }
        readerInfoViews.forEach { readerInfoView ->
            readerInfoView.view.apply {
                isBattery = false
                typeface = ChapterProvider.typeface
                textSize = ReadTipConfig.tipTextSize.toFloat()
            }
        }
    }

    private fun renderReaderInfo() {
        applyDoublePageBarLayout()
        if (isDoublePageSpread) {
            val headerText = readerInfoValues.chapterTitle.ifEmpty { readerInfoValues.bookName }
            binding.tvHeaderMiddle.isGone = true
            binding.tvHeaderLeft.isGone = headerText.isEmpty()
            binding.tvHeaderRight.isGone = headerText.isEmpty()
            binding.tvHeaderLeft.setTextIfNotEqual(headerText)
            binding.tvHeaderRight.setTextIfNotEqual(headerText)
            binding.tvHeaderRight.contentDescription = if (bookmarkIndicatorVisible) {
                context.getString(R.string.bookmark)
            } else {
                null
            }

            val activePage = progressTextPage ?: textPage
            val curIdx = activePage.index.coerceAtLeast(0)
            val rawPageSize = activePage.pageSize
            val leftNum = curIdx * 2 + 1
            val rightNum = curIdx * 2 + 2
            val doubleTotal = when {
                rawPageSize <= 0 -> "-"
                activePage.textChapter.isCompleted -> (rawPageSize * 2).toString()
                else -> "~${rawPageSize * 2}"
            }
            binding.tvFooterMiddle.isGone = true
            binding.tvFooterLeft.isInvisible = false
            binding.tvFooterLeft.isGone = false
            binding.tvFooterRight.isGone = false
            binding.tvFooterLeft.setTextIfNotEqual("$leftNum / $doubleTotal")
            binding.tvFooterRight.setTextIfNotEqual("$rightNum / $doubleTotal")
            return
        }
        readerInfoViews.forEach { readerInfoView ->
            val view = readerInfoView.view
            val template = readerInfoView.template
            if (view === binding.tvHeaderRight) {
                view.minimumWidth = 0
                if (bookmarkIndicatorVisible) {
                    view.minimumWidth = 32.dpToPx()
                    view.setTextIfNotEqual(" ")
                    view.contentDescription = context.getString(R.string.bookmark)
                    view.isGone = false
                    return@forEach
                }
                view.contentDescription = null
            }
            if (view === binding.tvFooterLeft) {
                view.isInvisible = template.isEmpty()
            } else {
                view.isGone = template.isEmpty()
            }
            if (template.isNotEmpty()) {
                view.setTextIfNotEqual(
                    ReaderInfoTemplateRenderer.render(template, readerInfoValues)
                )
            }
        }
    }

    fun showBookmarkIndicator(show: Boolean) {
        val showInHeader = show && !binding.llHeader.isGone
        if (bookmarkIndicatorVisible != showInHeader) {
            bookmarkIndicatorVisible = showInHeader
            renderReaderInfo()
        }
        binding.pageBookmarkIndicator.isVisible = show
        if (show) {
            binding.pageBookmarkIndicator.run {
                val width = if (showInHeader) 32 else 20
                val height = if (showInHeader) 32 else 40
                updateLayoutParams {
                    this.width = width.dpToPx()
                    this.height = height.dpToPx()
                }
                val padding = if (showInHeader) 4.dpToPx() else 0
                setPadding(padding, padding, padding, padding)
                setImageResource(
                    if (showInHeader) R.drawable.ic_bookmark_filled
                    else R.drawable.ic_bookmark_long
                )
                importantForAccessibility = if (showInHeader) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
            }
            doOnLayout {
                if (binding.pageBookmarkIndicator.isVisible) {
                    binding.pageBookmarkIndicator.run {
                        if (showInHeader) {
                            translationX = (
                                this@PageView.width -
                                    bookmarkIndicatorMarginRight(paddingRight) - right
                                ).toFloat()
                            translationY = (
                                bookmarkIndicatorTop(layoutParams.height, paddingBottom) - top
                                ).toFloat()
                        } else {
                            translationX = (
                                this@PageView.width - binding.vwRoot.paddingRight - right
                                ).toFloat()
                            translationY = (headerHeight - top).toFloat()
                        }
                    }
                }
            }
        }
    }

    private data class ReaderInfoView(
        val view: BatteryView,
        val template: String,
    )

    /**
     * 更新背景
     */
    fun upBg() {
        binding.vwRoot.background = LayerDrawable(
            arrayOf(
                ReadBookConfig.bgMeanColor.toDrawable(),
                ReadBookConfig.bg
            )
        )
        upBgAlpha()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        ReadBookConfig.bg?.alpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        binding.vwRoot.invalidate()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        readerInfoValues = readerInfoValues.copy(
            time = timeFormat.format(Date(System.currentTimeMillis())),
        )
        renderReaderInfo()
    }

    /**
     * 更新电池信息
     */
    fun upBattery(battery: Int) {
        this.battery = battery.coerceIn(0, 100)
        readerInfoValues = readerInfoValues.copy(battery = this.battery)
        renderReaderInfo()
    }

    /**
     * 设置内容
     */
    fun setContent(
        textPage: TextPage,
        resetPageOffset: Boolean = true,
        chapterPosition: Int = ReadBook.durChapterPos,
    ) {
        if (isMainView && !isScroll) {
            setProgress(textPage)
        } else {
            post {
                setProgress(textPage)
            }
        }
        if (resetPageOffset) {
            resetPageOffset()
        }
        binding.contentTextView.setContent(textPage)
        if (resetPageOffset && isMainView && isScroll) {
            binding.contentTextView.restorePageOffset(chapterPosition)
        }
    }

    fun invalidateContentView() {
        binding.contentTextView.invalidate()
    }

    /**
     * 设置无障碍文本
     */
    fun setContentDescription(content: String) {
        binding.contentTextView.contentDescription = content
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        binding.contentTextView.resetPageOffset()
    }

    /**
     * 设置进度
     */
    fun setProgress(textPage: TextPage) = textPage.apply {
        progressTextPage = textPage
        val readProgress = readProgress
        val totalPages = if (textChapter.isCompleted) {
            pageSize.toString()
        } else {
            val pageSizeInt = pageSize
            if (pageSizeInt <= 0) "-" else "~$pageSizeInt"
        }
        readerInfoValues = readerInfoValues.copy(
            bookName = ReadBook.book?.name.orEmpty(),
            chapterTitle = title,
            page = index.plus(1).toString(),
            totalPages = totalPages,
            readProgress = readProgress,
            chapter = chapterIndex.plus(1).toString(),
            totalChapters = chapterSize.toString(),
        )
        renderReaderInfo()
    }

    fun setAutoPager(autoPager: AutoPager?) {
        binding.contentTextView.setAutoPager(autoPager)
    }

    fun submitRenderTask() {
        binding.contentTextView.submitRenderTask()
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
        binding.contentTextView.setIsScroll(value)
    }

    /**
     * 滚动事件
     */
    fun scroll(offset: Int) {
        binding.contentTextView.scroll(offset)
    }

    fun isAtChapterTop(): Boolean {
        return binding.contentTextView.isAtChapterTop()
    }

    /**
     * 更新是否开启选择功能
     */
    fun upSelectAble(selectAble: Boolean) {
        binding.contentTextView.selectAble = selectAble
    }

    /**
     * 优先处理页面内单击
     * @return true:已处理, false:未处理
     */
    fun onClick(x: Float, y: Float): Boolean {
        return binding.contentTextView.click(x - imgBgPaddingStart, y - headerHeight)
    }

    /**
     * 长按事件
     */
    fun longPress(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return binding.contentTextView.longPress(x - imgBgPaddingStart, y - headerHeight, select)
    }

    /**
     * 选择文本
     */
    fun selectText(
        x: Float, y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return binding.contentTextView.selectText(x - imgBgPaddingStart, y - headerHeight, select)
    }

    fun getCurVisiblePage(): TextPage {
        return binding.contentTextView.getCurVisiblePage()
    }

    fun getReadPosition(): Pair<Int, TextLine>? {
        return binding.contentTextView.getReadPosition()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return binding.contentTextView.getReadAloudPos()
    }

    fun markAsMainView() {
        isMainView = true
        binding.contentTextView.isMainView = true
    }

    fun selectStartMove(x: Float, y: Float) {
        binding.contentTextView.selectStartMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectStartMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectStartMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectStartMoveIndex(textPos)
    }

    fun selectEndMove(x: Float, y: Float) {
        binding.contentTextView.selectEndMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectEndMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int
    ) {
        binding.contentTextView.selectEndMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectEndMoveIndex(textPos: TextPos) {
        binding.contentTextView.selectEndMoveIndex(textPos)
    }

    fun getReverseStartCursor(): Boolean {
        return binding.contentTextView.reverseStartCursor
    }

    fun getReverseEndCursor(): Boolean {
        return binding.contentTextView.reverseEndCursor
    }

    fun isLongScreenShot(): Boolean {
        return binding.contentTextView.longScreenshot
    }

    fun resetReverseCursor() {
        binding.contentTextView.resetReverseCursor()
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        binding.contentTextView.cancelSelect(clearSearchResult)
    }

    fun createBookmark(): Bookmark? {
        return binding.contentTextView.createBookmark()
    }

    fun createHighlight(style: HighlightStyle): BookHighlight? {
        return binding.contentTextView.createHighlight(style)
    }

    fun relativePage(relativePagePos: Int): TextPage {
        return binding.contentTextView.relativePage(relativePagePos)
    }

    val textPage get() = binding.contentTextView.textPage

    val selectedText: String get() = binding.contentTextView.getSelectedText()

    val selectStartPos get() = binding.contentTextView.selectStart
}
