package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.image.PhotoView
import io.legado.app.utils.setLayout

/**
 * 显示图片
 */
class PhotoDialog() : BaseDialogFragment() {

    constructor(src: String, sourceOrigin: String? = null, isBook: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("src", src)
            putString("sourceOrigin", sourceOrigin)
            putBoolean("isBook", isBook)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            setDimAmount(0f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val bgColorRes = if (!AppConfig.isEInkMode) {
                        R.color.photo_viewer_scrim
                    } else {
                        R.color.background
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(bgColorRes))
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PhotoView(ctx).also { photoView ->
                                    photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                                    photoView.setOnClickListener { dismiss() }
                                    loadPhotoInto(photoView)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    @SuppressLint("CheckResult")
    private fun loadPhotoInto(photoView: PhotoView) {
        val arguments = arguments ?: return
        val src = arguments.getString("src") ?: return
        ImageProvider.get(src)?.let {
            photoView.setImageBitmap(it)
            return
        }
        val isBook = arguments.getBoolean("isBook")
        val file = if (isBook) ReadBook.book?.let { book ->
            BookHelp.getImage(book, src)
        } else null
        if (file?.exists() == true) {
            ImageLoader.load(requireContext(), file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(photoView)
        } else {
            ImageLoader.load(requireContext(), src).apply {
                arguments.getString("sourceOrigin")?.let { sourceOrigin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }
            }.error(if (isBook) BookCover.defaultDrawable else R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .into(photoView)
        }
    }

}
