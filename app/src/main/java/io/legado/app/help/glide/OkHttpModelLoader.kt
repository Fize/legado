package io.legado.app.help.glide

import com.bumptech.glide.signature.ObjectKey
import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import java.io.InputStream

object OkHttpModelLoader : ModelLoader<GlideUrl?, InputStream?> {

    val retryFailedImagesOption = Option.memory("retryFailedImages", false)

    val loadOnlyWifiOption = Option.memory("loadOnlyWifi", false)
    val sourceOriginOption = Option.memory<String>("sourceOrigin")
    val bookUrlOption = Option.memory<String>("bookUrl")
    val mangaOption = Option.memory<Boolean>("manga",false)

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream?> {
        val key = ObjectKey(imageSourceCacheIdentity(model.cacheKey, model.headers,
            options.get(sourceOriginOption), options.get(bookUrlOption), options.get(mangaOption) == true))
        return ModelLoader.LoadData(key, OkHttpStreamFetcher(model, options))
    }

    override fun handles(model: GlideUrl): Boolean {
        return true
    }

}
