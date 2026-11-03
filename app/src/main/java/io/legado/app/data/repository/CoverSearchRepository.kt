package io.legado.app.data.repository

import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Cache
import java.security.MessageDigest
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class CoverCandidateKind { Default, Rule, Source }
data class BookCoverCandidate(
    val id: String,
    val url: String,
    val source: String? = null,
    val sourceName: String = "",
    val order: Int = 0,
    val kind: CoverCandidateKind = CoverCandidateKind.Source,
)
data class BookCoverSelection(val url: String, val source: String? = null)
interface CoverSearchData {
    suspend fun cached(name: String, author: String): List<BookCoverCandidate>
    suspend fun sourceKeys(): List<String>
    suspend fun rule(name: String, author: String): BookCoverCandidate?
    suspend fun search(key: String, name: String, author: String): BookCoverCandidate?
    fun parallelism(): Int
}
@Singleton
class CoverSearchRepository @Inject constructor(private val database: AppDatabase) : CoverSearchData {
    override fun parallelism() = AppConfig.threadCount.coerceIn(1, AppConst.MAX_THREAD)
    override suspend fun cached(name: String, author: String) = withContext(IO) {
        val rule = database.cacheDao.get(ruleKey(name, author), System.currentTimeMillis())?.takeIf(String::isNotBlank)
            ?.let { BookCoverCandidate("rule", it, order = -1, kind = CoverCandidateKind.Rule) }
        listOfNotNull(rule) + database.searchBookDao.getEnableHasCover(name, author).map(::candidate)
    }
    override suspend fun sourceKeys() = withContext(IO) { database.bookSourceDao.allEnabledPart.map { it.bookSourceUrl } }
    override suspend fun rule(name: String, author: String): BookCoverCandidate? = withContext(IO) {
        withTimeoutOrNull(60_000) {
            val url = BookCover.searchCover(Book(name = name, author = author))?.takeIf(String::isNotBlank)
            if (url == null) database.cacheDao.delete(ruleKey(name, author))
            else database.cacheDao.insert(Cache(ruleKey(name, author), url, System.currentTimeMillis() + 86_400_000))
            url?.let { BookCoverCandidate("rule", it, order = -1, kind = CoverCandidateKind.Rule) }
        }
    }
    override suspend fun search(key: String, name: String, author: String): BookCoverCandidate? = withContext(IO) {
        withTimeoutOrNull(60_000) {
            val source = database.bookSourceDao.getBookSource(key)?.takeIf { it.enabled } ?: return@withTimeoutOrNull null
            if (source.getSearchRule().coverUrl.isNullOrBlank()) return@withTimeoutOrNull null
            val result = WebBook.searchBookAwait(source, name, shouldBreak = { it > 0 }).firstOrNull() ?: return@withTimeoutOrNull null
            if (result.name != name || result.author != author || result.coverUrl.isNullOrBlank()) return@withTimeoutOrNull null
            currentCoroutineContext().ensureActive()
            database.runInTransaction {
                if (database.bookSourceDao.getBookSource(key)?.enabled != true) return@runInTransaction
                database.searchBookDao.insert(result)
            }
            candidate(result).takeIf { database.bookSourceDao.getBookSource(key)?.enabled == true }
        }
    }
    private fun ruleKey(name: String, author: String) = "coverSearch.rule:" + MessageDigest.getInstance("SHA-256")
        .digest((name + "\u0000" + author).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun candidate(book: SearchBook) = BookCoverCandidate("${book.origin}\u0000${book.bookUrl}", book.coverUrl.orEmpty(),
        book.origin, book.originName, book.originOrder)
}
