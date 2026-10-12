package io.legado.app.ui.book.manga.recyclerview

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.IntRange
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import androidx.core.util.size
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.ListPreloader.PreloadModelProvider
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter.Companion.TYPE_FOOTER_VIEW
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import io.legado.app.model.ReadManga
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.EpaperTransformation
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.utils.applyMangaColorFilterToBgColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.resolveMangaSpreadSlot

class MangaAdapter(private val context: Context) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(), PreloadModelProvider<Any> {

    private lateinit var mConfig: MangaColorFilterConfig
    private var mTransformation: BitmapTransformation? = null
    private var currentMangaEInkThreshold = 0

    var lastResolvedBgColor: Int = Color.WHITE
        private set
    var onPageBackgroundColorChanged: ((Int) -> Unit)? = null

    private fun transformBgColor(rawColor: Int): Int {
        if (!::mConfig.isInitialized) return rawColor
        return applyMangaColorFilterToBgColor(rawColor, mConfig.r, mConfig.g, mConfig.b)
    }

    companion object {
        private const val LOADING_VIEW = 0
        private const val CONTENT_VIEW = 1
    }

    var isHorizontal = false
    var isDoublePage = false
    var rightToLeft = false

    private val mDiffCallback: DiffUtil.ItemCallback<Any> = object : DiffUtil.ItemCallback<Any>() {
        override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
            return if (oldItem is ReaderLoading && newItem is ReaderLoading) {
                newItem.mMessage == oldItem.mMessage
            } else if (oldItem is MangaPage && newItem is MangaPage) {
                oldItem.mImageUrl == newItem.mImageUrl
            } else false
        }

        override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
            return if (oldItem is ReaderLoading && newItem is ReaderLoading) {
                oldItem == newItem
            } else if (oldItem is MangaPage && newItem is MangaPage) {
                oldItem == newItem
            } else false
        }
    }

    private val mDiffer = AsyncListDiffer(this, mDiffCallback)

    fun getItem(@IntRange(from = 0) position: Int) = mDiffer.currentList.getOrNull(position)

    fun getItems() = mDiffer.currentList

    fun isEmpty() = mDiffer.currentList.isEmpty()

    fun isNotEmpty() = !isEmpty()

    //全部替换数据
    fun submitList(contents: List<Any>, runnable: Runnable? = null) {
        mDiffer.submitList(contents, runnable)
    }

    class PageViewBinding(
        val root: FrameLayout,
        val image: AppCompatImageView,
        val flProgress: FrameLayout,
        val loading: ProgressBar,
        val retry: Button,
        val progress: TextView,
    )

    private fun createPageViewBinding(context: Context): PageViewBinding {
        val defaultBg = context.getCompatColor(R.color.book_ant_10)
        val root = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(defaultBg)
        }
        val image = AppCompatImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val flProgress = FrameLayout(context).apply {
            setBackgroundColor(defaultBg)
        }
        val loading = ProgressBar(context).apply {
            isIndeterminate = true
        }
        val retry = Button(context).apply {
            text = "重新加载"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            visibility = View.GONE
        }
        val progress = TextView(context).apply {
            text = "0%"
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        flProgress.addView(loading, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        flProgress.addView(retry, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        flProgress.addView(progress, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(flProgress, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return PageViewBinding(root, image, flProgress, loading, retry, progress)
    }

    inner class PageViewHolder(val binding: PageViewBinding = createPageViewBinding(context)) :
        MangaVH(binding.root, context) {

        init {
            initComponent(
                binding.loading,
                binding.image,
                binding.progress,
                binding.retry,
                binding.flProgress
            )
            binding.retry.setOnClickListener {
                val item = mDiffer.currentList.getOrNull(layoutPosition)
                if (item is MangaPage) {
                    val isLastImage = item.imageCount > 0 && item.index == item.imageCount - 1
                    val slot = resolveMangaSpreadSlot(
                        pageIndex = item.index,
                        imageCount = item.imageCount,
                        isDoublePage = isHorizontal && isDoublePage,
                        rightToLeft = rightToLeft,
                    )
                    loadImageWithRetry(
                        imageUrl = item.mImageUrl,
                        isHorizontal = isHorizontal,
                        isLastImage = isLastImage,
                        transformation = mTransformation,
                        spreadSlot = slot,
                        fallbackBgColor = lastResolvedBgColor,
                        colorTransform = ::transformBgColor,
                    ) { resolvedColor ->
                        lastResolvedBgColor = resolvedColor
                        onPageBackgroundColorChanged?.invoke(resolvedColor)
                    }
                }
            }
        }

        fun onBind(item: MangaPage) {
            setImageColorFilter()
            val isLastImage = item.imageCount > 0 && item.index == item.imageCount - 1
            val slot = resolveMangaSpreadSlot(
                pageIndex = item.index,
                imageCount = item.imageCount,
                isDoublePage = isHorizontal && isDoublePage,
                rightToLeft = rightToLeft,
            )
            loadImageWithRetry(
                imageUrl = item.mImageUrl,
                isHorizontal = isHorizontal,
                isLastImage = isLastImage,
                transformation = mTransformation,
                spreadSlot = slot,
                fallbackBgColor = lastResolvedBgColor,
                colorTransform = ::transformBgColor,
            ) { resolvedColor ->
                lastResolvedBgColor = resolvedColor
                onPageBackgroundColorChanged?.invoke(resolvedColor)
            }
        }

        fun setImageColorFilter() {
            require(
                mConfig.r in 0..255 &&
                        mConfig.g in 0..255 &&
                        mConfig.b in 0..255 &&
                        mConfig.a in 0..255
            ) {
                "ARGB values must be between 0-255"
            }
            val matrix = floatArrayOf(
                (255 - mConfig.r) / 255f, 0f, 0f, 0f, 0f,
                0f, (255 - mConfig.g) / 255f, 0f, 0f, 0f,
                0f, 0f, (255 - mConfig.b) / 255f, 0f, 0f,
                0f, 0f, 0f, (255 - mConfig.a) / 255f, 0f
            )
            binding.image.colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
        }
    }

    inner class PageMoreViewHolder(
        private val rootView: FrameLayout = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, 96.dpToPx())
            setBackgroundColor(context.getCompatColor(R.color.book_ant_10))
        },
        private val textView: TextView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
    ) : RecyclerView.ViewHolder(rootView) {
        init {
            rootView.addView(textView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }

        fun onBind(item: ReaderLoading) {
            val message = item.mMessage
            textView.text = message
            val bgColor = lastResolvedBgColor
            rootView.setBackgroundColor(bgColor)
            val isLight = ColorUtils.calculateLuminance(bgColor) > 0.5
            textView.setTextColor(if (isLight) 0xFF333333.toInt() else Color.WHITE)
            itemView.updateLayoutParams {
                width = MATCH_PARENT
                height = if (isHorizontal || item.isVolume) {
                    MATCH_PARENT
                } else {
                    96.dpToPx()
                }
            }
        }
    }

    private class FooterViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is PageViewHolder) {
            val bgColor = holder.resolvedPageBgColor
            lastResolvedBgColor = bgColor
            onPageBackgroundColorChanged?.invoke(bgColor)
            (holder.itemView.parent as? View)?.let { rv ->
                rv.setBackgroundColor(bgColor)
                (rv.parent as? WebtoonFrame)?.setPageBackgroundColor(bgColor)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when {
            viewType >= TYPE_FOOTER_VIEW -> {
                FooterViewHolder(footerItems.get(viewType).invoke(parent))
            }

            viewType == LOADING_VIEW -> {
                PageMoreViewHolder()
            }

            viewType == CONTENT_VIEW -> {
                PageViewHolder()
            }

            else -> error("Unknown view type!")
        }
    }

    override fun getItemCount(): Int = getActualItemCount() + getFooterCount()

    override fun getItemViewType(position: Int): Int {
        return when {
            isFooter(position) -> TYPE_FOOTER_VIEW + position - getActualItemCount()
            getItem(position) is MangaPage -> CONTENT_VIEW
            getItem(position) is ReaderLoading -> LOADING_VIEW
            else -> error("Unknown view type!")
        }
    }

    fun getFooterCount() = footerItems.size

    private fun isFooter(position: Int) = position >= getActualItemCount()

    override fun onViewRecycled(vh: RecyclerView.ViewHolder) {
        super.onViewRecycled(vh)
        when (vh) {
            is PageViewHolder -> {
                vh.itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = MATCH_PARENT
                }
                Glide.with(context).clear(vh.binding.image)
                if (vh.binding.image.tag is String) {
                    ProgressManager.removeListener(vh.binding.image.tag as String)
                }
            }
        }
    }

    override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
        when (vh) {
            is PageViewHolder -> vh.onBind(getItem(position) as MangaPage)
            is PageMoreViewHolder -> vh.onBind(getItem(position) as ReaderLoading)
        }
    }

    private val footerItems: SparseArray<(parent: ViewGroup) -> View> by lazy { SparseArray() }

    @Synchronized
    fun addFooterView(footer: ((parent: ViewGroup) -> View)) {
        kotlin.runCatching {
            val index = getActualItemCount() + footerItems.size
            footerItems.put(TYPE_FOOTER_VIEW + footerItems.size, footer)
            notifyItemInserted(index)
        }
    }

    /**
     * 除去header和footer
     */
    fun getActualItemCount() = getItems().size

    @Synchronized
    fun removeFooterView(footer: ((parent: ViewGroup) -> View)) {
        kotlin.runCatching {
            val index = footerItems.indexOfValue(footer)
            if (index >= 0) {
                footerItems.remove(index)
                notifyItemRemoved(getActualItemCount() + index - 2)
            }
        }
    }

    override fun getPreloadItems(position: Int): List<Any> {
        if (isEmpty() || position >= getItems().size) {
            return emptyList()
        }
        return getItems().subList(position, position + 1)
    }

    override fun getPreloadRequestBuilder(item: Any): RequestBuilder<*>? {
        if (item is MangaPage) {
            return BookCover.preloadManga(
                context,
                mangaImagePath(item.mImageUrl),
                sourceOrigin = ReadManga.book?.origin,
            )
        }
        return null
    }

    fun setMangaImageColorFilter(config: MangaColorFilterConfig) {
        mConfig = config
        notifyItemRangeChanged(0, itemCount)
    }

    fun enableMangaEInk(enable: Boolean, value: Int) {
        if (enable) {
            currentMangaEInkThreshold = value
            mTransformation = EpaperTransformation(currentMangaEInkThreshold)
        } else {
            mTransformation = null
        }
        notifyItemRangeChanged(0, itemCount)
    }

    fun updateThreshold(mangaEInkThreshold: Int) {
        if (currentMangaEInkThreshold != mangaEInkThreshold) {
            currentMangaEInkThreshold = mangaEInkThreshold
            mTransformation = EpaperTransformation(currentMangaEInkThreshold)
            notifyItemRangeChanged(0, itemCount)
        }
    }

    //开启灰色图片
    fun enableGray(enable: Boolean) {
        mTransformation = if (enable) {
            GrayscaleTransformation()
        } else {
            null
        }
        notifyItemRangeChanged(0, itemCount)
    }
}
