package io.legado.app.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResultSnapshot(val row: SearchSessionResult, val variants: List<SearchSessionResult>) {
    val origins: Set<String> get() = variants.map { it.origin }.toSet()
}

internal fun SearchSessionResult.toSearchBook() = SearchBook(
    bookUrl = bookUrl, origin = origin, originName = originName, type = type,
    name = name, author = author, kind = kind, coverUrl = coverUrl, intro = intro,
    wordCount = wordCount, latestChapterTitle = latestChapterTitle, tocUrl = tocUrl,
    variable = variable, originOrder = originOrder, chapterWordCountText = chapterWordCountText,
    chapterWordCount = chapterWordCount, respondTime = respondTime,
)

data class SearchSessionFrame(val session: SearchSession, val results: List<SearchResultSnapshot>)
data class SearchSourceOption(val url: String, val name: String, val group: String?, val enabled: Boolean)
data class SearchHistoryItem(val word: String, val usage: Int, val lastUseTime: Long)
data class SearchShelfMatch(val url: String, val name: String, val author: String)
data class SearchBookIdentity(val name: String, val author: String)
data class SearchLibraryIndex(val urls: Set<String>, val identities: Set<SearchBookIdentity>, val names: Set<String>) {
    fun contains(book: SearchSessionResult) = book.bookUrl in urls ||
        if (book.author.isBlank()) book.name in names else SearchBookIdentity(book.name, book.author) in identities
}

data class ResolvedSearchScope(val scope: SearchScopeSpec, val sources: List<BookSourcePart>)

interface SearchSourceFetcher {
    suspend fun search(source: BookSource, query: String, page: Int, precision: Boolean): List<SearchBook>
}

class WebSearchSourceFetcher @Inject constructor() : SearchSourceFetcher {
    override suspend fun search(source: BookSource, query: String, page: Int, precision: Boolean) =
        WebBook.searchBookAwait(source, query, page, filter = { name, author, kind ->
            !precision || name.contains(query) || author.contains(query) || kind?.contains(query) == true
        })
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchDataModule {
    @Binds abstract fun bindFetcher(fetcher: WebSearchSourceFetcher): SearchSourceFetcher
}

/** Search work is scoped by persisted session/generation; no Activity or parser job is stored here. */
@Singleton
class SearchRepository @Inject constructor(
    private val database: AppDatabase,
    private val preferences: SearchPreferencesRepository,
    private val fetcher: SearchSourceFetcher,
    private val previews: BookInfoPreviewStore,
) {
    private val dao get() = database.searchSessionDao

    suspend fun draft(id: String): SearchScreenDraft? = withContext(IO) { dao.draft(id) }
    suspend fun saveDraft(draft: SearchScreenDraft) = withContext(IO) {
        database.runInTransaction {
            val current = dao.draft(draft.id)
            if (current == null || current.revision <= draft.revision) dao.saveDraft(draft)
        }
    }

    fun groups(): Flow<List<String>> = database.bookSourceDao.flowEnabledGroups().flowOn(IO)
    fun sources(query: String): Flow<List<SearchSourceOption>> =
        (if (query.isBlank()) database.bookSourceDao.flowAll() else database.bookSourceDao.flowSearch(query))
            .map { rows -> rows.map { SearchSourceOption(it.bookSourceUrl, it.bookSourceName, it.bookSourceGroup, it.enabled) } }.flowOn(IO)

    fun history(query: String): Flow<List<SearchHistoryItem>> =
        (if (query.isBlank()) database.searchKeywordDao.flowByTime() else database.searchKeywordDao.flowSearch(query))
            .map { rows -> rows.map { SearchHistoryItem(it.word, it.usage, it.lastUseTime) } }.flowOn(IO)

    fun shelfMatches(query: String): Flow<List<SearchShelfMatch>> = database.bookDao.flowSearch(query)
        .map { rows -> if (query.isBlank()) emptyList() else rows.map { SearchShelfMatch(it.bookUrl, it.name, it.author) } }.flowOn(IO)

    fun libraryIndex(): Flow<SearchLibraryIndex> = database.bookDao.flowAll().map { rows ->
        val books = rows.filterNot { it.isNotShelf }
        SearchLibraryIndex(books.map { it.bookUrl }.toSet(), books.map { SearchBookIdentity(it.name, it.author) }.toSet(), books.map { it.name }.toSet())
    }.flowOn(IO)

    fun readRecordIndex(): Flow<ReadRecordIndex> = database.readRecordDao.flowBooks().map { ReadRecordIndex.of(it) }.flowOn(IO)

    suspend fun hasShelfName(name: String): Boolean = withContext(IO) { database.bookDao.findByName(name).isNotEmpty() }
    suspend fun clearHistory() = withContext(IO) { database.searchKeywordDao.deleteAll() }
    suspend fun deleteHistory(word: String) = withContext(IO) { database.searchKeywordDao.get(word)?.let(database.searchKeywordDao::delete) }
    suspend fun saveHistory(word: String) = withContext(IO) {
        database.runInTransaction {
            val current = database.searchKeywordDao.get(word)
            if (current == null) database.searchKeywordDao.insert(SearchKeyword(word))
            else database.searchKeywordDao.update(current.copy(usage = current.usage + 1, lastUseTime = System.currentTimeMillis()))
        }
    }

    suspend fun resolveScope(value: SearchScopeSpec): ResolvedSearchScope = withContext(IO) {
        if (value.isSource) {
            val source = value.sourceUrl?.let(database.bookSourceDao::getBookSourcePart)
            if (source != null) return@withContext ResolvedSearchScope(SearchScopeSpec.source(source.bookSourceName, source.bookSourceUrl), listOf(source))
        } else if (!value.isAll) {
            val sources = linkedMapOf<String, BookSourcePart>()
            val valid = value.groups.filter { group ->
                val matches = database.bookSourceDao.getEnabledPartByGroup(group)
                matches.forEach { sources[it.bookSourceUrl] = it }
                matches.isNotEmpty()
            }
            if (sources.isNotEmpty()) return@withContext ResolvedSearchScope(SearchScopeSpec.groups(valid), sources.values.sortedBy { it.customOrder })
        }
        ResolvedSearchScope(SearchScopeSpec(""), database.bookSourceDao.allEnabledPart)
    }

    suspend fun create(query: String, scope: SearchScopeSpec, precision: Boolean): SearchSessionFrame = withContext(IO) {
        val resolved = resolveScope(scope)
        val id = UUID.randomUUID().toString()
        val session = SearchSession(id, query, resolved.scope.raw, precision, 0, 0,
            0, 0, SearchSessionStatus.Ready, true, 0, resolved.sources.size, 0, null, System.currentTimeMillis())
        database.runInTransaction {
            dao.insert(session)
            dao.insertSources(resolved.sources.mapIndexed { index, source -> SearchSessionSource(id, source.bookSourceUrl, index) })
        }
        SearchSessionFrame(session, emptyList())
    }

    suspend fun restore(id: String): SearchSessionFrame? = withContext(IO) {
        database.runInTransaction<SearchSessionFrame?> {
            val stored = dao.get(id) ?: return@runInTransaction null
            if (stored.status == SearchSessionStatus.Running || (stored.status == SearchSessionStatus.Ready && stored.requestedPage == 0)) {
                dao.insert(stored.copy(status = SearchSessionStatus.Interrupted, generation = stored.generation + 1,
                    revision = stored.revision + 1, updatedAt = System.currentTimeMillis()))
            }
            frame(id)
        }
    }

    fun observe(id: String): Flow<SearchSessionFrame?> = dao.observe(id).map {
        withContext(IO) { database.runInTransaction<SearchSessionFrame?> { frame(id) } }
    }.flowOn(IO)

    private fun frame(id: String): SearchSessionFrame? {
        val session = dao.get(id) ?: return null
        val groups = dao.results(id).groupBy { it.resultKey }.values.map { it.sortedBy(SearchSessionResult::arrival) }
        val ordered = groups.sortedWith(compareBy<List<SearchSessionResult>> {
            val first = it.first()
            searchResultRank(first.name, first.author, first.kind, session.query)
        }.thenByDescending {
            val first = it.first()
            if (searchResultRank(first.name, first.author, first.kind, session.query) < 3) it.map { row -> row.origin }.toSet().size else 0
        }.thenBy { it.first().arrival })
        return SearchSessionFrame(session, ordered.map { variants -> SearchResultSnapshot(variants.first(), variants) })
    }

    suspend fun stop(id: String) = withContext(IO) {
        database.runInTransaction {
            dao.get(id)?.let { current ->
                dao.insert(current.copy(generation = current.generation + 1, revision = current.revision + 1,
                    status = SearchSessionStatus.Stopped, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun searchPage(id: String, retry: Boolean, active: Flow<Boolean>) = withContext(IO) {
        val started = database.runInTransaction<SearchSession?> {
            val current = dao.get(id) ?: return@runInTransaction null
            if (current.status == SearchSessionStatus.Running) return@runInTransaction null
            val page = if (retry && current.requestedPage > 0) current.requestedPage else current.completedPage + 1
            val next = current.copy(generation = current.generation + 1, revision = current.revision + 1,
                requestedPage = page, status = SearchSessionStatus.Running, done = 0, failures = 0, error = null,
                updatedAt = System.currentTimeMillis())
            dao.insert(next)
            next
        } ?: return@withContext
        try {
            val sources = dao.sources(id)
            val semaphore = Semaphore(preferences.current().parallelism)
            coroutineScope {
                sources.forEach { reference -> launch {
                    semaphore.withPermit {
                        active.first { it }
                        currentCoroutineContext().ensureActive()
                        try {
                            val (source, stamp) = database.runInTransaction<Pair<BookSource, SearchSourceStamp>> {
                                val source = database.bookSourceDao.getBookSource(reference.sourceUrl)
                                    ?: error("Search source is no longer available")
                                if (!SearchScopeSpec(started.scope).isSource && !source.enabled) error("Search source has been disabled")
                                source to SearchSourceStamp(source.bookSourceUrl, source.bookSourceName, source.lastUpdateTime,
                                    database.bookSourceDao.getCheckState(source.bookSourceUrl)?.sourceRevision, source.checkContent())
                            }
                            val books = withTimeout(30_000) { fetcher.search(source, started.query, started.requestedPage, started.precision) }
                            currentCoroutineContext().ensureActive()
                            val snapshots = books.map { it.releaseHtmlData(); it.copy(origin = stamp.url, originName = stamp.name) }
                            commitSource(started, stamp, snapshots, null)
                        } catch (error: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            commitSource(started, null, emptyList(), "Search source timed out")
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            AppLog.put("Search source failed: ${reference.sourceUrl}", error)
                            commitSource(started, null, emptyList(), error.localizedMessage ?: error.javaClass.simpleName)
                        }
                    }
                } }
            }
            database.runInTransaction {
                val current = dao.get(id) ?: return@runInTransaction
                if (current.generation != started.generation) return@runInTransaction
                val failed = current.total == 0 || current.failures == current.total
                dao.insert(current.copy(revision = current.revision + 1,
                    completedPage = if (failed) current.completedPage else current.requestedPage,
                    status = if (failed) SearchSessionStatus.Error else if (current.failures > 0) SearchSessionStatus.Partial else SearchSessionStatus.Ready, updatedAt = System.currentTimeMillis(),
                    error = if (current.total == 0) "no_sources" else current.error))
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { finishAborted(started, SearchSessionStatus.Interrupted, null) }
            throw error
        } catch (error: Exception) {
            finishAborted(started, SearchSessionStatus.Error, error.localizedMessage ?: error.javaClass.simpleName)
            throw error
        }
    }

    private fun finishAborted(request: SearchSession, status: SearchSessionStatus, error: String?) {
        database.runInTransaction {
            val current = dao.get(request.id) ?: return@runInTransaction
            if (current.generation != request.generation || current.status != SearchSessionStatus.Running) return@runInTransaction
            dao.insert(current.copy(generation = current.generation + 1, revision = current.revision + 1,
                status = status, error = error?.take(4_000), updatedAt = System.currentTimeMillis()))
        }
    }

    private fun commitSource(request: SearchSession, source: SearchSourceStamp?, books: List<SearchBook>, error: String?) {
        database.runInTransaction {
            val current = dao.get(request.id) ?: return@runInTransaction
            if (current.generation != request.generation || current.status != SearchSessionStatus.Running) return@runInTransaction
            val currentSource = source?.let { database.bookSourceDao.getBookSource(it.url) }
            val sourceChanged = source != null && (currentSource == null || currentSource.lastUpdateTime != source.updatedAt ||
                currentSource.checkContent() != source.content ||
                database.bookSourceDao.getCheckState(source.url)?.sourceRevision != source.sourceRevision ||
                (!SearchScopeSpec(current.scope).isSource && !currentSource.enabled))
            val failure = error ?: if (sourceChanged) "Search source changed during the request" else null
            if (failure == null && source != null) {
                database.searchBookDao.insert(*books.toTypedArray())
                var arrival = dao.nextArrival(current.id)
                // Only write this response. Rewriting every existing variant per source grows quadratically.
                val incoming = linkedMapOf<Triple<String, String, String>, SearchSessionResult>()
                books.filter { it.bookUrl.isNotBlank() && it.name.isNotBlank() &&
                    (!current.precision || searchResultRank(it.name, it.author, it.kind, current.query) < 3)
                }.forEach { book ->
                    val key = searchResultKey(book.name, book.author)
                    val variantKey = Triple(key, book.origin, book.bookUrl)
                    val previous = incoming[variantKey] ?: dao.variant(current.id, key, book.origin, book.bookUrl)
                    incoming[variantKey] = book.asSessionResult(current.id, key, searchResultRank(book.name, book.author, book.kind, current.query), previous?.arrival ?: arrival++)
                }
                dao.insertResults(incoming.values.toList())
            }
            dao.insert(current.copy(revision = current.revision + 1, done = current.done + 1,
                failures = current.failures + if (failure != null) 1 else 0,
                hasMore = (if (current.done == 0) false else current.hasMore) || (failure == null && books.isNotEmpty()),
                error = failure?.take(4_000) ?: current.error, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun prepareSelection(sessionId: String, key: String): BookInfoOpenRequest = withContext(IO) {
        val preview = database.runInTransaction<BookInfoPreview> {
            val selected = frame(sessionId)?.results?.firstOrNull { it.row.resultKey == key }
                ?: error("Search result is no longer available")
            val variant = selected.variants.firstOrNull { database.bookSourceDao.getBookSource(it.origin) != null }
                ?: error("Search source is no longer available")
            val shelf = database.bookDao.getBook(variant.name, variant.author)?.takeUnless { it.isNotShelf }
                ?: database.bookDao.getBook(variant.bookUrl)?.takeUnless { it.isNotShelf }
            val book = shelf ?: variant.toSearchBook().toBook()
            val chapters = if (shelf != null) database.bookChapterDao.getChapterList(book.bookUrl) else emptyList()
            BookInfoPreview(book, chapters, book.origin, shelf != null)
        }
        preparePreview(preview)
    }

    suspend fun prepareShelfSelection(bookUrl: String): BookInfoOpenRequest = withContext(IO) {
        val preview = database.runInTransaction<BookInfoPreview> {
            val book = database.bookDao.getBook(bookUrl) ?: error("Book is no longer available")
            BookInfoPreview(book, database.bookChapterDao.getChapterList(bookUrl), book.origin, !book.isNotShelf)
        }
        preparePreview(preview)
    }

    suspend fun pendingPreview(key: String): BookInfoOpenRequest? = withContext(IO) {
        previews.peek(key)?.let { BookInfoOpenRequest(key, it.book.bookUrl, key) }
    }

    private fun preparePreview(preview: BookInfoPreview): BookInfoOpenRequest {
        return BookInfoOpenRepository(database, previews).preparePreview(preview)
    }

}

internal fun searchResultKey(name: String, author: String): String {
    val first = name.toByteArray(Charsets.UTF_8)
    val second = author.toByteArray(Charsets.UTF_8)
    val bytes = ByteBuffer.allocate(8 + first.size + second.size).putInt(first.size).put(first).putInt(second.size).put(second).array()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

internal fun searchResultRank(name: String, author: String, kind: String?, query: String): Int = when {
    name == query || author == query -> 0
    kind?.contains(query) == true -> 1
    name.contains(query) || author.contains(query) -> 2
    else -> 3
}

private data class SearchSourceStamp(val url: String, val name: String, val updatedAt: Long, val sourceRevision: String?, val content: String)


private fun SearchBook.asSessionResult(session: String, key: String, position: Int, arrival: Long) = SearchSessionResult(
    session, key, position, bookUrl, origin, originName, type, name, author, kind, coverUrl, intro, wordCount,
    latestChapterTitle, tocUrl, variable, chapterWordCountText, chapterWordCount, respondTime, originOrder, arrival,
)
