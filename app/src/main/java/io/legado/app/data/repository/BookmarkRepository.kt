package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class BookmarkExportFormat { Json, Markdown }

@Singleton
class BookmarkRepository @Inject constructor() {

    fun observeAll(): Flow<List<Bookmark>> = appDb.bookmarkDao.flowAll()

    suspend fun findBook(bookmark: Bookmark): Book? = withContext(Dispatchers.IO) {
        appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
    }

    suspend fun save(bookmark: Bookmark) = withContext(Dispatchers.IO) {
        appDb.bookmarkDao.insert(bookmark)
    }

    suspend fun delete(bookmark: Bookmark) = withContext(Dispatchers.IO) {
        appDb.bookmarkDao.delete(bookmark)
    }

    suspend fun export(uri: Uri, format: BookmarkExportFormat): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val timestamp = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault()).format(Date())
                val extension = if (format == BookmarkExportFormat.Json) "json" else "md"
                FileDoc.fromUri(uri, true)
                    .createFileIfNotExist("bookmark-$timestamp.$extension")
                    .openOutputStream()
                    .getOrThrow()
                    .use { output ->
                        val bookmarks = appDb.bookmarkDao.all
                        if (format == BookmarkExportFormat.Json) {
                            GSON.writeToOutputStream(output, bookmarks)
                        } else {
                            writeMarkdown(output::write, bookmarks)
                        }
                    }
            }
        }

    private fun writeMarkdown(write: (ByteArray) -> Unit, bookmarks: List<Bookmark>) {
        var identity: Pair<String, String>? = null
        bookmarks.forEach { bookmark ->
            val nextIdentity = bookmark.bookName to bookmark.bookAuthor
            if (identity != nextIdentity) {
                identity = nextIdentity
                write("## ${bookmark.bookName} ${bookmark.bookAuthor}\n\n".toByteArray())
            }
            write("#### ${bookmark.chapterName}\n\n".toByteArray())
            write("###### 原文\n ${bookmark.bookText}\n\n".toByteArray())
            write("###### 摘要\n ${bookmark.content}\n\n".toByteArray())
        }
    }
}
