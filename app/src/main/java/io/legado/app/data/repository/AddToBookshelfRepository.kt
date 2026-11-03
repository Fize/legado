package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AddToBookshelfResult(
    val book: Book,
    val alreadyInBookshelf: Boolean,
)

@Singleton
class AddToBookshelfRepository @Inject constructor(
    private val database: AppDatabase,
) {
    suspend fun resolve(bookUrl: String): AddToBookshelfResult = withContext(Dispatchers.IO) {
        database.bookDao.getBook(bookUrl)?.let { return@withContext AddToBookshelfResult(it, true) }
        val baseUrl = NetworkUtils.getBaseUrl(bookUrl)
            ?: throw NoStackTraceException("书籍地址格式不对")
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(bookUrl)
        if (urlMatcher.find()) {
            val origin = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
                bookUrl.substring(urlMatcher.end())
            ).getOrNull()?.getOrigin()
            origin?.let { database.bookSourceDao.getBookSource(it) }?.let { source ->
                getBookInfo(bookUrl, source)?.let { return@withContext AddToBookshelfResult(it, false) }
            }
        }
        database.bookSourceDao.getBookSourceAddBook(baseUrl)?.let { source ->
            getBookInfo(bookUrl, source)?.let { return@withContext AddToBookshelfResult(it, false) }
        }
        database.bookSourceDao.hasBookUrlPattern.forEach { source ->
            try {
                val bookSource = source.getBookSource() ?: return@forEach
                if (bookUrl.matches(bookSource.bookUrlPattern!!.toRegex())) {
                    getBookInfo(bookUrl, bookSource)?.let { return@withContext AddToBookshelfResult(it, false) }
                }
            } catch (_: Exception) {
                // A malformed source pattern must not block trying the remaining enabled sources.
            }
        }
        throw NoStackTraceException("未找到匹配书源")
    }

    private suspend fun getBookInfo(bookUrl: String, source: io.legado.app.data.entities.BookSource): Book? =
        runCatching {
            WebBook.getBookInfoAwait(
                source,
                Book(
                    bookUrl = bookUrl,
                    origin = source.bookSourceUrl,
                    originName = source.bookSourceName,
                ),
            )
        }.getOrNull()
}
