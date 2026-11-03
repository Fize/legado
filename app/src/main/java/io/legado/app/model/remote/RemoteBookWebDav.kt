package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.findExactRemoteBook
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.runBlocking
import io.legado.app.lib.webdav.WebDavContentMatch
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

internal fun remoteBookUploadFileName(book: Book): String =
    if (book.isArchive) book.archiveName else book.originName

class RemoteBookWebDav(
    val rootBookUrl: String,
    val authorization: Authorization,
    val serverID: Long? = null
) : RemoteBookManager() {

    init {
        runBlocking {
            WebDav(rootBookUrl, authorization).makeAsDir()
        }
    }


    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val remoteBooks = mutableListOf<RemoteBook>()
        //读取文件列表
        val remoteWebDavFileList: List<WebDavFile> = WebDav(path, authorization).listFiles()
        //转化远程文件信息到本地对象
        remoteWebDavFileList.forEach { webDavFile ->
            if (webDavFile.isDir
                || bookFileRegex.matches(webDavFile.displayName)
                || archiveFileRegex.matches(webDavFile.displayName)
            ) {
                //扩展名符合阅读的格式则认为是书籍
                remoteBooks.add(RemoteBook(webDavFile))
            }
        }
        return remoteBooks
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val webDavFile = WebDav(path, authorization).getWebDavFile()
            ?: return null
        return RemoteBook(webDavFile)
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoStackTraceException("没有设置书籍保存位置!")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val webdav = WebDav(remoteBook.path, authorization)
        return webdav.downloadInputStream().let { inputStream ->
            LocalBook.saveBookFile(inputStream, remoteBook.filename)
        }
    }

    suspend fun hasRemoteBook(book: Book): Boolean {
        val fileName = remoteBookUploadFileName(book)
        return findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName) != null
    }

    override suspend fun upload(book: Book) {
        upload(book, overwrite = true)
    }

    suspend fun upload(book: Book, overwrite: Boolean) {
        book.origin = uploadFile(book, overwrite)
        book.update()
    }

    /** Transfer only; the calling repository commits metadata after the network succeeds. */
    suspend fun uploadFile(book: Book, overwrite: Boolean): String {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val fileName = remoteBookUploadFileName(book)
        val localBookUri = if (book.isArchive) {
            book.getArchiveUri() ?: throw NoStackTraceException("未找到压缩文件: $fileName")
        } else {
            book.getLocalUri()
        }
        val putUrl = "$rootBookUrl$fileName"
        val webDav = WebDav(putUrl, authorization)
        if (localBookUri.isContentScheme()) {
            webDav.upload(localBookUri, overwrite = overwrite)
        } else {
            webDav.upload(localBookUri.path!!, overwrite = overwrite)
        }
        return BookType.webDavTag + CustomUrl(putUrl)
            .putAttribute("serverID", serverID)
            .toString()
    }

    private fun snapshotUrl(fileName: String): String {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && fileName !in setOf(".", ".."))
        return rootBookUrl.toHttpUrl().newBuilder().addPathSegment(fileName).build().toString()
    }
    fun snapshotOrigin(fileName: String): String = BookType.webDavTag + CustomUrl(snapshotUrl(fileName))
        .putAttribute("serverID", serverID).toString()
    suspend fun inspectSnapshot(fileName: String, sha256: String, size: Long): WebDavContentMatch =
        WebDav(snapshotUrl(fileName), authorization).matchesContent(sha256, size)
    suspend fun uploadSnapshot(fileName: String, file: File, overwrite: Boolean): String {
        WebDav(snapshotUrl(fileName), authorization).upload(file, overwrite = overwrite)
        return snapshotOrigin(fileName)
    }

    suspend fun delete(book: Book): Boolean {
        val fileName = remoteBookUploadFileName(book)
        val remoteBook = findExactRemoteBook(getRemoteBookList(rootBookUrl), fileName)
            ?: return true
        return WebDav(remoteBook.path, authorization).delete()
    }

    override suspend fun delete(remoteBookUrl: String) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        WebDav(remoteBookUrl, authorization).delete()
    }

}
