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
 * 是否启用左右主从双栏布局（及侧边 NavigationRail）：
 * 仅在宽屏横屏比例（宽度 >= 840dp 且 宽高比 > 1.05）下启用左右分栏；
 * 在平板竖屏（如 800dp x 1280dp，宽 <= 高）下延续手机式的单栏页面流转，避免左右分栏过窄。
 */
fun shouldUseTwoPane(screenWidthDp: Int, screenHeightDp: Int): Boolean {
    if (screenWidthDp < 840 || screenHeightDp <= 0) return false
    return screenWidthDp > (screenHeightDp * 1.05f).toInt()
}

val Context.useTwoPaneLayout: Boolean
    get() = shouldUseTwoPane(
        resources.configuration.screenWidthDp,
        resources.configuration.screenHeightDp,
    )

/**
 * 发现详情列表（ExploreShow）与搜索结果列表（Search）在大屏下的自适应列数：
 * - 手机 (<600dp)：1 列
 * - 平板竖屏/横屏 (>=600dp)：2 列双栏书籍卡片网格
 */
fun resolveBookListSpanCount(screenWidthDp: Int): Int {
    return if (screenWidthDp >= 600) 2 else 1
}

/**
 * 平板单栏模式下的内容区水平留白边距：
 * 当处于平板竖屏单栏模式时，约束单列列表（如「我的」设置项、「发现」书源卡片）的最大版心宽度并居中。
 */
fun resolveTabletContentPadding(
    containerWidthPx: Int,
    screenWidthDp: Int,
    maxContentWidthPx: Int,
    minTabletPaddingPx: Int = 0,
): Int {
    if (screenWidthDp < 600 || containerWidthPx <= 0 || maxContentWidthPx <= 0) return 0
    val extra = if (containerWidthPx > maxContentWidthPx) {
        (containerWidthPx - maxContentWidthPx) / 2
    } else {
        0
    }
    return maxOf(extra, minTabletPaddingPx)
}

/**
 * 发现页分类标签在平板单栏模式下的 FlexBasis 比例适配：
 * 将原本针对手机窄屏设计的 2~4 列标签（0.20..0.49）在平板单栏（>=600dp 且非分栏）下自动重排为 5 列（0.18f）。
 */
fun resolveExploreFlexBasisPercent(
    configuredBasis: Float,
    screenWidthDp: Int,
    isTwoPane: Boolean,
): Float {
    if (screenWidthDp >= 600 && !isTwoPane && configuredBasis in 0.20f..0.49f) {
        return 0.18f
    }
    return configuredBasis
}

/**
 * 漫画阅读器是否在横屏下启用左右双页并排翻页模式：
 * 当开启横屏双页配置且窗口宽度 >= 600dp 且处于横屏比例（宽 > 高）时自动生效。
 */
fun shouldUseMangaDoublePage(
    screenWidthDp: Int,
    screenHeightDp: Int,
    enableMangaDoublePage: Boolean,
): Boolean {
    return enableMangaDoublePage && screenWidthDp >= 600 && screenWidthDp > screenHeightDp
}

enum class MangaSpreadSlot {
    SINGLE,
    LEFT,
    RIGHT,
}

/**
 * 计算漫画页面在横屏双页并排模式下的槽位（左页 / 右页 / 单页居中）：
 * - 若章节总页数为奇数，最后一页（偶数下标）作为单页居中展示，保证每个跨页总宽度恒等于 100% 屏幕宽度。
 */
fun resolveMangaSpreadSlot(
    pageIndex: Int,
    imageCount: Int,
    isDoublePage: Boolean,
    rightToLeft: Boolean = false,
): MangaSpreadSlot {
    if (!isDoublePage || imageCount <= 0 || pageIndex < 0) {
        return MangaSpreadSlot.SINGLE
    }
    if (imageCount % 2 == 1 && pageIndex == imageCount - 1) {
        return MangaSpreadSlot.SINGLE
    }
    val isFirstInPair = pageIndex % 2 == 0
    return if (!rightToLeft) {
        if (isFirstInPair) MangaSpreadSlot.LEFT else MangaSpreadSlot.RIGHT
    } else {
        if (isFirstInPair) MangaSpreadSlot.RIGHT else MangaSpreadSlot.LEFT
    }
}

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

/**
 * 根据漫画画稿边缘采样像素集合，提取漫画页面的纸张底色（用于填充左右留白背景，使全屏与书页底色融为一体）：
 * 1. 过滤透明像素（alpha < 128），若无有效采样点则返回 defaultColor；
 * 2. 优先检测高亮度低饱和度的「纸张留白/白底」（R>=230, G>=230, B>=225 且色差<=28）：
 *    只要边缘有 >=25% 的采样点属于白底/暖白纸面（避开局部出血分镜干扰），即返回这些纸面像素的平均色；
 * 3. 其次检测深黑底色（R<=32, G<=32, B<=35）：若占比 >=50%，返回深色采样点的平均色；
 * 4. 其余情况（如复古羊皮纸底色、彩色扉页底色），按 16x16x16 RGB 量化分桶，返回最大主色桶的平均色。
 */
fun resolveMangaPageEdgeBackgroundColor(
    samples: IntArray,
    defaultColor: Int = -0x1,
): Int {
    if (samples.isEmpty()) return defaultColor
    var validCount = 0
    var lightCount = 0
    var lightR = 0L
    var lightG = 0L
    var lightB = 0L

    var darkCount = 0
    var darkR = 0L
    var darkG = 0L
    var darkB = 0L

    val bucketCounts = HashMap<Int, Int>()
    val bucketSumR = HashMap<Int, Long>()
    val bucketSumG = HashMap<Int, Long>()
    val bucketSumB = HashMap<Int, Long>()

    for (pixel in samples) {
        val a = (pixel ushr 24) and 0xFF
        if (a < 128) continue
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        validCount++

        val maxCh = maxOf(r, g, b)
        val minCh = minOf(r, g, b)
        if (r >= 230 && g >= 230 && b >= 225 && (maxCh - minCh) <= 28) {
            lightCount++
            lightR += r
            lightG += g
            lightB += b
        } else if (r <= 32 && g <= 32 && b <= 35) {
            darkCount++
            darkR += r
            darkG += g
            darkB += b
        }

        val bucketKey = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
        bucketCounts[bucketKey] = (bucketCounts[bucketKey] ?: 0) + 1
        bucketSumR[bucketKey] = (bucketSumR[bucketKey] ?: 0L) + r
        bucketSumG[bucketKey] = (bucketSumG[bucketKey] ?: 0L) + g
        bucketSumB[bucketKey] = (bucketSumB[bucketKey] ?: 0L) + b
    }

    if (validCount == 0) return defaultColor

    if (lightCount * 4 >= validCount) {
        val avgR = (lightR / lightCount).toInt().coerceIn(0, 255)
        val avgG = (lightG / lightCount).toInt().coerceIn(0, 255)
        val avgB = (lightB / lightCount).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
    }

    if (darkCount * 2 >= validCount) {
        val avgR = (darkR / darkCount).toInt().coerceIn(0, 255)
        val avgG = (darkG / darkCount).toInt().coerceIn(0, 255)
        val avgB = (darkB / darkCount).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
    }

    var bestBucket = -1
    var bestCount = -1
    for ((key, count) in bucketCounts) {
        if (count > bestCount) {
            bestCount = count
            bestBucket = key
        }
    }
    if (bestBucket == -1 || bestCount <= 0) return defaultColor
    val avgR = ((bucketSumR[bestBucket] ?: 0L) / bestCount).toInt().coerceIn(0, 255)
    val avgG = ((bucketSumG[bestBucket] ?: 0L) / bestCount).toInt().coerceIn(0, 255)
    val avgB = ((bucketSumB[bestBucket] ?: 0L) / bestCount).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
}

/**
 * 将漫画色彩滤镜参数同步作用于提取出的页面边缘底色，保持留白背景与画稿颜色完全一致。
 */
fun applyMangaColorFilterToBgColor(
    color: Int,
    filterR: Int,
    filterG: Int,
    filterB: Int,
): Int {
    val r = (((color ushr 16) and 0xFF) * (255 - filterR.coerceIn(0, 255)) / 255f).toInt().coerceIn(0, 255)
    val g = (((color ushr 8) and 0xFF) * (255 - filterG.coerceIn(0, 255)) / 255f).toInt().coerceIn(0, 255)
    val b = ((color and 0xFF) * (255 - filterB.coerceIn(0, 255)) / 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

