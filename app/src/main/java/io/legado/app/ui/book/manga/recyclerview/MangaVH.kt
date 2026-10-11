package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import io.legado.app.model.ReadManga
import io.legado.app.utils.MangaSpreadSlot
import io.legado.app.utils.dpToPx
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.resolveMangaColumnWidth
import io.legado.app.utils.resolveMangaPageEdgeBackgroundColor

private val mangaPageEdgeColorCache = LruCache<String, Int>(256)

internal fun mangaImagePath(imageUrl: String): String {
    val book = ReadManga.book ?: return imageUrl
    return BookHelp.getImage(book, imageUrl).takeIf { it.isFile }?.absolutePath ?: imageUrl
}

internal fun sampleMangaDrawableEdgeColor(
    drawable: Drawable,
    spreadSlot: MangaSpreadSlot,
): Int {
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return Color.WHITE
    if (bitmap.isRecycled || bitmap.width < 4 || bitmap.height < 4) return Color.WHITE
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
        return Color.WHITE
    }
    return runCatching {
        val w = bitmap.width
        val h = bitmap.height
        val leftX = 2.coerceAtMost(w - 1)
        val rightX = (w - 3).coerceAtLeast(0)
        val topY = 2.coerceAtMost(h - 1)
        val bottomY = (h - 3).coerceAtLeast(0)
        val samples = IntArray(32)
        var idx = 0

        when (spreadSlot) {
            MangaSpreadSlot.LEFT -> {
                for (i in 0 until 20) {
                    val y = ((h - 1) * (0.03f + 0.94f * i / 19f)).toInt().coerceIn(0, h - 1)
                    samples[idx++] = bitmap.getPixel(leftX, y)
                }
                for (i in 0 until 6) {
                    val x = ((w - 1) * (0.04f + 0.46f * i / 5f)).toInt().coerceIn(0, w - 1)
                    samples[idx++] = bitmap.getPixel(x, topY)
                }
                for (i in 0 until 6) {
                    val x = ((w - 1) * (0.04f + 0.46f * i / 5f)).toInt().coerceIn(0, w - 1)
                    samples[idx++] = bitmap.getPixel(x, bottomY)
                }
            }
            MangaSpreadSlot.RIGHT -> {
                for (i in 0 until 20) {
                    val y = ((h - 1) * (0.03f + 0.94f * i / 19f)).toInt().coerceIn(0, h - 1)
                    samples[idx++] = bitmap.getPixel(rightX, y)
                }
                for (i in 0 until 6) {
                    val x = ((w - 1) * (0.50f + 0.46f * i / 5f)).toInt().coerceIn(0, w - 1)
                    samples[idx++] = bitmap.getPixel(x, topY)
                }
                for (i in 0 until 6) {
                    val x = ((w - 1) * (0.50f + 0.46f * i / 5f)).toInt().coerceIn(0, w - 1)
                    samples[idx++] = bitmap.getPixel(x, bottomY)
                }
            }
            MangaSpreadSlot.SINGLE -> {
                for (i in 0 until 12) {
                    val y = ((h - 1) * (0.04f + 0.92f * i / 11f)).toInt().coerceIn(0, h - 1)
                    samples[idx++] = bitmap.getPixel(leftX, y)
                }
                for (i in 0 until 12) {
                    val y = ((h - 1) * (0.04f + 0.92f * i / 11f)).toInt().coerceIn(0, h - 1)
                    samples[idx++] = bitmap.getPixel(rightX, y)
                }
                for (i in 0 until 4) {
                    val x = ((w - 1) * (0.12f + 0.76f * i / 3f)).toInt().coerceIn(0, w - 1)
                    samples[idx++] = bitmap.getPixel(x, topY)
                }
                for (i in 0 until 4) {
                    val x = ((w - 1) * (0.12f + 0.76f * i / 3f)).toInt().coerceIn(0, w - 1)
                    samples[idx++] = bitmap.getPixel(x, bottomY)
                }
            }
        }
        resolveMangaPageEdgeBackgroundColor(samples, defaultColor = Color.WHITE)
    }.getOrDefault(Color.WHITE)
}

open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null
    var resolvedPageBgColor: Int = Color.WHITE
        private set

    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3

    fun initComponent(
        loading: ProgressBar,
        image: AppCompatImageView,
        progress: TextView,
        button: Button? = null,
        flProgress: FrameLayout,
    ) {
        mLoading = loading
        mImage = image
        mRetry = button
        mProgress = progress
        mFlProgress = flProgress
    }

    fun applyPageBackgroundColor(bgColor: Int) {
        resolvedPageBgColor = bgColor
        itemView.setBackgroundColor(bgColor)
        mFlProgress.setBackgroundColor(bgColor)
        val isLight = ColorUtils.calculateLuminance(bgColor) > 0.5
        val fgColor = if (isLight) 0xFF333333.toInt() else Color.WHITE
        mProgress.setTextColor(fgColor)
        mLoading.indeterminateTintList = ColorStateList.valueOf(fgColor)
        (itemView.parent as? View)?.let { rv ->
            rv.setBackgroundColor(bgColor)
            (rv.parent as? WebtoonFrame)?.setPageBackgroundColor(bgColor)
        }
    }

    private fun applyPageLayout(
        isHorizontal: Boolean,
        isLastImage: Boolean,
        spreadSlot: MangaSpreadSlot,
        isLoaded: Boolean,
    ) {
        val dm = context.resources.displayMetrics
        if (!isHorizontal) {
            val columnWidth = resolveMangaColumnWidth(
                containerWidthPx = dm.widthPixels,
                containerHeightPx = dm.heightPixels,
                isHorizontalScroll = false,
                maxVerticalColumnWidthPx = 680.dpToPx(),
            )
            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (isLoaded) {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            mImage.setPadding(0, 0, 0, 0)
            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                width = columnWidth
                height = if (isLastImage && isLoaded) {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    ViewGroup.LayoutParams.MATCH_PARENT
                }
                gravity = Gravity.CENTER_HORIZONTAL
            }
            itemView.minimumHeight = if (isLastImage && isLoaded) minHeight else 0
            mImage.scaleType = ImageView.ScaleType.FIT_XY
        } else if (spreadSlot != MangaSpreadSlot.SINGLE) {
            val halfWidth = (dm.widthPixels / 2).coerceAtLeast(1)
            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                width = halfWidth
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            itemView.minimumHeight = 0
            mImage.setPadding(0, 0, 0, 0)
            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
            }
            mImage.scaleType = if (spreadSlot == MangaSpreadSlot.LEFT) {
                ImageView.ScaleType.FIT_END
            } else {
                ImageView.ScaleType.FIT_START
            }
        } else {
            itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            itemView.minimumHeight = 0
            mImage.setPadding(0, 0, 0, 0)
            mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
            }
            mImage.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    @SuppressLint("CheckResult")
    fun loadImageWithRetry(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?,
        spreadSlot: MangaSpreadSlot = MangaSpreadSlot.SINGLE,
        fallbackBgColor: Int = Color.WHITE,
        colorTransform: (Int) -> Int = { it },
        onBgColorResolved: ((Int) -> Unit)? = null,
    ) {
        val cacheKey = "$imageUrl:${spreadSlot.ordinal}:${transformation?.javaClass?.simpleName.orEmpty()}"
        val cachedRawColor = mangaPageEdgeColorCache.get(cacheKey)
        val initialBgColor = colorTransform(cachedRawColor ?: fallbackBgColor)
        applyPageBackgroundColor(initialBgColor)
        if (cachedRawColor != null) {
            onBgColorResolved?.invoke(initialBgColor)
        }

        applyPageLayout(isHorizontal, isLastImage, spreadSlot, isLoaded = false)
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, _, _ ->
            @SuppressLint("SetTextI18n")
            mProgress.text = "$percentage%"
        }
        try {
            mImage.tag = imageUrl
            BookCover.loadManga(
                context,
                mangaImagePath(imageUrl),
                sourceOrigin = ReadManga.book?.origin,
                transformation = transformation
            ).addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    mFlProgress.isVisible = true
                    mLoading.isGone = true
                    mRetry?.isVisible = true
                    mProgress.isGone = true
                    applyPageLayout(isHorizontal, isLastImage, spreadSlot, isLoaded = false)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    mFlProgress.isGone = true
                    val rawEdgeColor = mangaPageEdgeColorCache.get(cacheKey)
                        ?: sampleMangaDrawableEdgeColor(resource, spreadSlot).also {
                            mangaPageEdgeColorCache.put(cacheKey, it)
                        }
                    val finalBgColor = colorTransform(rawEdgeColor)
                    applyPageBackgroundColor(finalBgColor)
                    onBgColorResolved?.invoke(finalBgColor)
                    applyPageLayout(isHorizontal, isLastImage, spreadSlot, isLoaded = true)
                    return false
                }
            }).into(mImage)
        } catch (e: Exception) {
            e.printOnDebug()
        }

    }
}

