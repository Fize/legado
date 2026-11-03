package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.util.ContentLengthInputStream
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.okHttpClientManga
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream


class OkHttpStreamFetcher(
    private val url: GlideUrl,
    private val options: Options,
) :
    DataFetcher<InputStream>, okhttp3.Callback {
    private var stream: InputStream? = null
    private var responseBody: ResponseBody? = null
    private var callback: DataFetcher.DataCallback<in InputStream>? = null
    private var source: BaseSource? = null
    private var book: Book? = null
    private val manga = options.get(OkHttpModelLoader.mangaOption) == true
    private val coroutineContext = SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)
    private lateinit var analyzedUrl: GlideUrl

    @Volatile
    private var call: Call? = null

    private val failureIdentity = imageSourceCacheIdentity(url.cacheKey, url.headers,
        options.get(OkHttpModelLoader.sourceOriginOption), options.get(OkHttpModelLoader.bookUrlOption), manga)

    companion object {
        private val failUrl = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (options.get(OkHttpModelLoader.retryFailedImagesOption) == true) failUrl.remove(failureIdentity)
        if (failUrl.contains(failureIdentity)) {
            callback.onLoadFailed(NoStackTraceException("跳过加载失败的图片"))
            return
        }
        val loadOnlyWifi = options.get(OkHttpModelLoader.loadOnlyWifiOption) ?: false
        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            callback.onLoadFailed(NoStackTraceException("只在wifi加载图片"))
            return
        }

        options.get(OkHttpModelLoader.sourceOriginOption)?.let { sourceUrl ->
            source = SourceHelp.getSource(sourceUrl)
        }
        options.get(OkHttpModelLoader.bookUrlOption)?.let { bookUrl ->
            book = appDb.bookDao.getBook(bookUrl)
        }

        analyzedUrl = AnalyzeUrl(
            url.toString(),
            source = source,
            coroutineContext = coroutineContext
        ).getGlideUrl()

        val requestBuilder = Request.Builder().url(analyzedUrl.toStringUrl())
        requestBuilder.addHeaders(analyzedUrl.headers)
        val request: Request = requestBuilder.build()
        this.callback = callback
        call = if (manga) {
            try {
                ConcurrentRateLimiter(source).withLimitBlocking {
                    okHttpClientManga.newCall(request)
                }
            } catch (error: Exception) {
                callback.onLoadFailed(error)
                return
            }
        } else {
            okHttpClient.newCall(request)
        }
        call?.enqueue(this)
    }

    override fun cleanup() {
        kotlin.runCatching {
            stream?.close()
        }
        responseBody?.close()
        coroutineContext.cancel()
        callback = null
    }

    override fun cancel() {
        call?.cancel()
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }

    override fun onFailure(call: Call, e: IOException) {
        callback?.onLoadFailed(e)
    }

    override fun onResponse(call: Call, response: Response) {
        responseBody = response.body
        if (!response.isSuccessful) {
            if (!manga) {
                failUrl.add(failureIdentity)
            }
            callback?.onLoadFailed(HttpException(response.message, response.code))
            return
        }
        if (ImageUtils.skipDecode(source, !manga)) {
            onStreamReady(responseBody!!.byteStream())
            return
        }
        Coroutine.async(coroutineScope, executeContext = IO) {
            val decodeResult = runScriptWithContext(coroutineContext) {
                if (manga) {
                    ImageUtils.decode(
                        url.toString(),
                        responseBody!!.bytes(),
                        isCover = false,
                        source,
                        book
                    )?.inputStream()
                } else {
                    ImageUtils.decode(
                        analyzedUrl.toStringUrl(), responseBody!!.byteStream(),
                        isCover = true, source
                    )
                }
            }
            onStreamReady(decodeResult)
        }
    }

    private fun onStreamReady(inputStream: InputStream?) {
        if (inputStream == null) {
            if (!manga) {
                failUrl.add(failureIdentity)
            }
            callback?.onLoadFailed(NoStackTraceException("封面二次解密失败"))
        } else {
            val contentLength: Long =
                if (inputStream is ByteArrayInputStream) inputStream.available().toLong()
                else responseBody!!.contentLength()
            stream = ContentLengthInputStream.obtain(inputStream, contentLength)
            callback?.onDataReady(stream)
        }
    }

}
