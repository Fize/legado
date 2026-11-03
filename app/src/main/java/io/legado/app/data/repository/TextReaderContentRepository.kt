package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isEpub
import io.legado.app.model.TextReaderDocument
import io.legado.app.model.TextReaderChapterContent
import io.legado.app.model.TextReaderChapterKey
import io.legado.app.model.TextReaderChapterLoader
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads exact book/chapter identities; never falls back to a title or another global session. */
@Singleton
class TextReaderContentRepository @Inject constructor(
    private val database: AppDatabase,
    private val resources: CacheResourceLeases,
    private val epubs: TextReaderEpubRepository = TextReaderEpubRepository(database, resources),
) : TextReaderChapterLoader {
    suspend fun resourceDocument(key: TextReaderChapterKey, resource: io.legado.app.model.localBook.EpubReadingResource): TextReaderDocument =
        withContext(Dispatchers.IO) {
            val (book, chapter) = database.runInTransaction<Pair<io.legado.app.data.entities.Book, io.legado.app.data.entities.BookChapter>> {
                requireOwner(key)
                val book = checkNotNull(database.bookDao.getBook(key.bookUrl)).infoSnapshot()
                check(book.origin == key.origin && book.isEpub)
                book to checkNotNull(database.bookChapterDao.getChapter(key.bookUrl, key.chapterIndex)).infoSnapshot()
            }
            val processor = ContentProcessor.create(book, database)
            val manual = if (key.processing.manualReplaceRules) book.config.manualReplaceRuleIds.let {
                if (it.isEmpty()) emptyList() else database.replaceRuleDao.findByIds(*it.toLongArray())
            } else null
            val titleRules = manual?.filter { it.scopeTitle } ?: processor.getTitleReplaceRules()
            val contentRules = manual?.filter { it.scopeContent }
            val enabled = manual?.any { it.scopeTitle || it.scopeContent } ?: book.getUseReplaceRule()
            val raw = TextReaderDocumentDecoder.epub(listOf(resource), book.getImageStyle().orEmpty(), complete = true)
            val processed = processor.getContent(book, chapter, raw.text, includeTitle = false, replaceEnabledOverride = enabled,
                titleReplaceRulesOverride = titleRules, contentReplaceRulesOverride = contentRules,
                paragraphIndent = key.processing.paragraphIndent, adaptSpecialStyle = false,
                chineseConverterType = key.processing.chineseConverterType)
            currentCoroutineContext().ensureActive()
            requireOwner(key)
            raw.project(TextReaderDocument.create(emptyList(), processed.textList), preserveSource = true)
        }

    override suspend fun load(key: TextReaderChapterKey): TextReaderChapterContent = withContext(Dispatchers.IO) {
        val input = database.runInTransaction<Triple<io.legado.app.data.entities.Book,
            io.legado.app.data.entities.BookChapter, io.legado.app.data.entities.BookSource?>> {
            requireOwner(key)
            val book = checkNotNull(database.bookDao.getBook(key.bookUrl)) { "Reader book was removed" }
            check(book.origin == key.origin) { "Reader source changed" }
            val chapter = checkNotNull(database.bookChapterDao.getChapter(key.bookUrl, key.chapterIndex)) {
                "Reader chapter was removed"
            }
            Triple(book.infoSnapshot(), chapter.infoSnapshot(),
                database.bookSourceDao.getBookSource(book.origin)?.infoSnapshot())
        }
        val (book, chapter, source) = input
        resources.acquireBook(book).use {
            currentCoroutineContext().ensureActive()
            val raw = BookHelp.getContent(book, chapter) ?: run {
                check(!book.isLocal) { "Local chapter content is unavailable" }
                WebBook.getContentAwait(checkNotNull(source) { "Reader source is unavailable" }, book, chapter,
                    database.bookChapterDao.getChapter(book.bookUrl, chapter.index + 1)?.url)
            }
            currentCoroutineContext().ensureActive()
            val epub = if (book.isEpub) try { epubs.chapter(key) } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.FileNotFoundException) { null } else null
            val prepared = processChapter(book,chapter,raw,key.processing,epub)
            currentCoroutineContext().ensureActive()
            database.runInTransaction {
                requireOwner(key)
                check(database.bookDao.getBook(key.bookUrl)?.origin == key.origin) { "Reader source changed" }
                check(database.bookChapterDao.getChapter(key.bookUrl, key.chapterIndex)?.url == chapter.url) {
                    "Reader chapter changed"
                }
            }
            prepared
        }
    }

    /** Read cached/local text without claiming the book's progress owner or starting a download. */
    suspend fun cachedContent(bookUrl: String, origin: String, chapterIndex: Int,
        processing: io.legado.app.model.TextReaderProcessing, replaceOverride: Boolean? = null): TextReaderChapterContent? = withContext(Dispatchers.IO) {
        val (book,chapter) = database.runInTransaction<Pair<io.legado.app.data.entities.Book,io.legado.app.data.entities.BookChapter>> {
            val book=checkNotNull(database.bookDao.getBook(bookUrl)).infoSnapshot()
            check(book.origin==origin) { "Reader source changed" }
            book to checkNotNull(database.bookChapterDao.getChapter(bookUrl,chapterIndex)).infoSnapshot()
        }
        resources.acquireBook(book).use {
            val raw=BookHelp.getContent(book,chapter) ?: return@withContext null
            currentCoroutineContext().ensureActive()
            val epub=if(book.isEpub) try { io.legado.app.model.localBook.EpubFile.getReaderChapter(book,chapter) }
                catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch(_: java.io.FileNotFoundException) { null } else null
            val prepared=processChapter(book,chapter,raw,processing,epub,replaceOverride)
            currentCoroutineContext().ensureActive()
            database.runInTransaction {
                val current=checkNotNull(database.bookDao.getBook(bookUrl))
                check(current.origin==book.origin && current.originName==book.originName && current.charset==book.charset) { "Reader source changed" }
                check(database.bookChapterDao.getChapter(bookUrl,chapterIndex)?.url==chapter.url) { "Reader chapter changed" }
            }
            prepared
        }
    }

    private fun processChapter(book: io.legado.app.data.entities.Book, chapter: io.legado.app.data.entities.BookChapter,
        raw: String, processing: io.legado.app.model.TextReaderProcessing,
        epub: io.legado.app.model.localBook.EpubReadingChapter?, replaceOverride: Boolean? = null): TextReaderChapterContent {
        // Each load has its own processor and rules; another reader cannot refresh them midway.
        val processor = ContentProcessor.create(book, database)
        val manual = if (processing.manualReplaceRules) {
            val ids = book.config.manualReplaceRuleIds
            if (ids.isEmpty()) emptyList() else database.replaceRuleDao.findByIds(*ids.toLongArray())
        } else null
        val titleRules = manual?.filter { it.scopeTitle } ?: processor.getTitleReplaceRules()
        val contentRules = manual?.filter { it.scopeContent }
        val replaceEnabled = replaceOverride ?: manual?.let { it.any { rule -> rule.scopeTitle || rule.scopeContent } }
            ?: book.getUseReplaceRule()
        val title = chapter.getDisplayTitleWithConversion(titleRules, replaceEnabled, replaceBook = book.toReplaceBook(),
            chineseConverterType = processing.chineseConverterType)
        val content = processor.getContent(book, chapter, raw, includeTitle = false,
            replaceEnabledOverride = replaceEnabled, titleReplaceRulesOverride = titleRules,
            contentReplaceRulesOverride = contentRules, paragraphIndent = processing.paragraphIndent,
            adaptSpecialStyle = processing.adaptSpecialStyle, chineseConverterType = processing.chineseConverterType)
        val rich = epub?.takeIf { it.legacyText == raw }?.let { snapshot ->
            val decoded = TextReaderDocumentDecoder.epub(snapshot.parts, book.getImageStyle().orEmpty())
            val processed = processor.getContent(book, chapter, decoded.text, includeTitle = false,
                replaceEnabledOverride = replaceEnabled, titleReplaceRulesOverride = titleRules,
                contentReplaceRulesOverride = contentRules, paragraphIndent = processing.paragraphIndent,
                adaptSpecialStyle = false, chineseConverterType = processing.chineseConverterType)
            decoded.project(TextReaderDocument.create(emptyList(), processed.textList))
        }
        return TextReaderChapterContent(title,content.textList,content.sameTitleRemoved,rich)
    }

    private fun requireOwner(key: TextReaderChapterKey) {
        check(database.readerSessionDao.ownsBook(key.owner.sessionId, key.owner.token,
            key.owner.revision, key.bookUrl)) { "Reader no longer owns this book" }
    }
}
