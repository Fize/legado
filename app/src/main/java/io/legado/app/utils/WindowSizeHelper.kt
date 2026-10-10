package io.legado.app.utils

import android.content.Context
import android.view.ViewGroup

enum class WindowWidthSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

fun resolveWindowWidthSizeClass(screenWidthDp: Int): WindowWidthSizeClass {
    return when {
        screenWidthDp < 600 -> WindowWidthSizeClass.COMPACT
        screenWidthDp < 840 -> WindowWidthSizeClass.MEDIUM
        else -> WindowWidthSizeClass.EXPANDED
    }
}

val Context.windowWidthSizeClass: WindowWidthSizeClass
    get() = resolveWindowWidthSizeClass(resources.configuration.screenWidthDp)

val Context.isExpandedScreen: Boolean
    get() = resources.configuration.screenWidthDp >= 600

/**
 * 根据用户配置的书架布局模式与当前窗口宽度(dp)，计算实际生效的网格列数。
 * - 列表模式 (0 / 1)：手机 1 列，平板/大屏 (>=600dp) 自动升级为 2 列双栏列表
 * - 网格模式 (2~6)：手机保持原列数，平板竖屏/大折叠 (600..839dp) 默认 3 列扩展为 5 列，平板横屏 (>=840dp) 扩展为 6 列
 */
fun resolveBookshelfSpanCount(configuredLayout: Int, screenWidthDp: Int): Int {
    val sizeClass = resolveWindowWidthSizeClass(screenWidthDp)
    if (configuredLayout < 2) {
        return when (sizeClass) {
            WindowWidthSizeClass.COMPACT -> 1
            WindowWidthSizeClass.MEDIUM, WindowWidthSizeClass.EXPANDED -> 2
        }
    }
    return when (sizeClass) {
        WindowWidthSizeClass.COMPACT -> configuredLayout
        WindowWidthSizeClass.MEDIUM -> when (configuredLayout) {
            2 -> 4
            3 -> 5
            else -> maxOf(configuredLayout, 5)
        }
        WindowWidthSizeClass.EXPANDED -> when (configuredLayout) {
            2 -> 5
            3 -> 6
            else -> maxOf(configuredLayout, 6)
        }
    }
}

/**
 * 大屏对话框宽度约束：在 >=600dp 大屏下限制弹窗最大宽度（默认 560dp），避免横向铺满全屏。
 */
fun resolveDialogWidth(
    windowWidthPx: Int,
    widthMix: Float,
    screenWidthDp: Int,
    maxWidthPx: Int,
): Int {
    val desiredWidth = (windowWidthPx * widthMix).toInt()
    return if (screenWidthDp >= 600 && maxWidthPx > 0) {
        minOf(desiredWidth, maxWidthPx)
    } else {
        desiredWidth
    }
}

/**
 * 阅读器大屏自适应水平边距计算：
 * - 单页模式下，若版心宽度超过 maxSingleColumnWidth，将多余宽度对称分配至左右边距；
 * - 双页模式下，若单栏宽度超过 maxDoubleColumnWidth，将多余宽度对称分配至单栏左右边距。
 */
fun resolveAdaptiveReaderPadding(
    viewWidth: Int,
    configuredLeft: Int,
    configuredRight: Int,
    isDoublePage: Boolean,
    maxSingleColumnWidth: Int,
    maxDoubleColumnWidth: Int,
): Pair<Int, Int> {
    if (viewWidth <= 0) return configuredLeft to configuredRight
    return if (isDoublePage) {
        val halfWidth = viewWidth / 2
        val currentColWidth = halfWidth - configuredLeft - configuredRight
        if (maxDoubleColumnWidth > 0 && currentColWidth > maxDoubleColumnWidth) {
            val extra = (currentColWidth - maxDoubleColumnWidth) / 2
            (configuredLeft + extra) to (configuredRight + extra)
        } else {
            configuredLeft to configuredRight
        }
    } else {
        val currentWidth = viewWidth - configuredLeft - configuredRight
        if (maxSingleColumnWidth > 0 && currentWidth > maxSingleColumnWidth) {
            val extra = (currentWidth - maxSingleColumnWidth) / 2
            (configuredLeft + extra) to (configuredRight + extra)
        } else {
            configuredLeft to configuredRight
        }
    }
}

/**
 * 漫画阅读器大屏自适应画幅宽度计算：
 * - 竖向条漫模式 (!isHorizontalScroll)：限制大屏下的最大条漫版心宽度（横屏下额外结合高度比例约束），防止分镜被横向撑满导致过高与模糊；
 * - 横向翻页模式 (isHorizontalScroll)：返回 MATCH_PARENT 由内部 ImageView FIT_CENTER 自适应。
 */
fun resolveMangaColumnWidth(
    containerWidthPx: Int,
    containerHeightPx: Int,
    isHorizontalScroll: Boolean,
    maxVerticalColumnWidthPx: Int,
): Int {
    if (isHorizontalScroll || containerWidthPx <= 0 || maxVerticalColumnWidthPx <= 0) {
        return ViewGroup.LayoutParams.MATCH_PARENT
    }
    val landscapeCap = if (containerHeightPx > 0 && containerWidthPx > containerHeightPx) {
        minOf(maxVerticalColumnWidthPx, (containerHeightPx * 0.85f).toInt())
    } else {
        maxVerticalColumnWidthPx
    }
    return if (containerWidthPx > landscapeCap && landscapeCap > 0) {
        landscapeCap
    } else {
        ViewGroup.LayoutParams.MATCH_PARENT
    }
}

/**
 * 小说正文内嵌插图/轻小说插画在大屏与双页模式下的自适应宽高计算：
 * - 先完成宽高比缩放与 visibleHeight 上限裁剪，避免先判断换页再缩小高度导致的空白页；
 * - 在非 FULL 默认模式下，对高分屏平板上过小的非图标插画（>= minIllustrationPx）自适应放大至版心舒适比例。
 */
fun resolveIllustrationSize(
    origWidth: Int,
    origHeight: Int,
    visibleWidth: Int,
    visibleHeight: Int,
    isFullStyle: Boolean,
    minIllustrationPx: Int = 240,
): Pair<Int, Int> {
    if (origWidth <= 0 || origHeight <= 0 || visibleWidth <= 0 || visibleHeight <= 0) {
        return origWidth.coerceAtLeast(0) to origHeight.coerceAtLeast(0)
    }
    var width = origWidth
    var height = origHeight
    if (isFullStyle) {
        width = visibleWidth
        height = (origHeight.toLong() * visibleWidth / origWidth).toInt().coerceAtLeast(1)
    } else {
        if (origWidth > visibleWidth) {
            width = visibleWidth
            height = (origHeight.toLong() * visibleWidth / origWidth).toInt().coerceAtLeast(1)
        } else if (
            origWidth >= minIllustrationPx &&
            origHeight >= minIllustrationPx &&
            origWidth < visibleWidth * 6 / 10
        ) {
            val targetWidth = (visibleWidth * 75 / 100).coerceAtLeast(origWidth)
            width = targetWidth
            height = (origHeight.toLong() * targetWidth / origWidth).toInt().coerceAtLeast(1)
        }
    }
    if (height > visibleHeight) {
        width = (width.toLong() * visibleHeight / height).toInt().coerceAtLeast(1)
        height = visibleHeight
    }
    return width to height
}
