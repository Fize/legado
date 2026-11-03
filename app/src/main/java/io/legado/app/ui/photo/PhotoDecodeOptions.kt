package io.legado.app.ui.photo

import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions

internal const val PHOTO_DECODE_EDGE = 4096

/** Bound both decoded edges; Glide's default CENTER_OUTSIDE can enlarge a tall image. */
internal fun photoDecodeOptions(maxEdge: Int = PHOTO_DECODE_EDGE): RequestOptions {
    require(maxEdge > 0)
    return RequestOptions().override(maxEdge, maxEdge)
        .downsample(DownsampleStrategy.AT_MOST).dontTransform()
}
