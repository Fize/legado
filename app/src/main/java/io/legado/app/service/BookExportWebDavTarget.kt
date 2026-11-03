package io.legado.app.service

import android.content.Context
import android.net.Uri
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.transferSourceKey
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Captured once at execution. Credentials are never serialized in the task table. */
internal class BookExportWebDavTarget private constructor(
    private val directory: HttpUrl,
    private val authorization: Authorization,
    val key: String,
) {
    suspend fun upload(uri: Uri, filename: String) {
        val url = directory.newBuilder().addPathSegment(filename).build()
        WebDav(url.toString(), authorization).upload(uri, FileUtils.getMimeType(filename))
    }

    companion object {
        fun capture(context: Context): BookExportWebDavTarget = from(context.defaultSharedPreferences.all)

        internal fun from(values: Map<String, *>): BookExportWebDavTarget {
            val account = (values[PreferKey.webDavAccount] as? String).orEmpty()
            val password = (values[PreferKey.webDavPassword] as? String).orEmpty()
            require(account.isNotEmpty() && password.isNotEmpty()) { "WebDAV credentials are missing" }
            val raw = (values[PreferKey.webDavUrl] as? String)?.trim().orEmpty()
                .ifBlank { "https://dav.jianguoyun.com/dav/" }
            val base = (raw.trimEnd('/') + "/").toHttpUrl()
            require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
                "Invalid WebDAV export directory"
            }
            val builder = base.newBuilder()
            (values[PreferKey.webDavDir] as? String).orEmpty().trim().split('/')
                .filter { it.isNotEmpty() }.forEach {
                    require(it != "." && it != "..") { "Invalid WebDAV export directory" }
                    builder.addPathSegment(it)
                }
            val directory = builder.addPathSegment("books").addPathSegment("").build()
            val key = transferSourceKey(GSON.toJson(listOf(directory.toString(), account, password)))
            return BookExportWebDavTarget(directory, Authorization(account,password), key)
        }
    }
}
