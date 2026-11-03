package io.legado.app.data.repository

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.model.*
import kotlinx.coroutines.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BookContentSearchEntryPoint { fun contentSearchRepository(): BookContentSearchRepository }

data class BookContentSearchSelection(val sessionId: String,val ordinal: Int,val delivered: Boolean)

/** Search owns its result session, never the book's reading progress or a download task. */
@Singleton
class BookContentSearchRepository @Inject constructor(private val db: AppDatabase,
    private val content: TextReaderContentRepository,private val entries: TextReaderEntryRepository) {
    private val dao get()=db.bookContentSearchDao
    fun observe(id: String)=dao.observe(id)
    suspend fun page(id: String,start: Int)=withContext(Dispatchers.IO) { dao.page(id,start.coerceAtLeast(0),100) }

    suspend fun open(id: String,bookUrl: String,owner: String,query: String,catalogId: String?=null): BookContentSearchSession {
        try { return withContext(Dispatchers.IO) {
            require(query.length<=2048) { "Query is too long" }
            require(UUID.fromString(id).toString()==id && owner.isNotBlank())
            currentCoroutineContext().ensureActive()
            dao.pruneResults(System.currentTimeMillis()-java.util.concurrent.TimeUnit.DAYS.toMillis(7))
            val existing=dao.get(id)
            if(existing!=null) {
                check(existing.catalogId==catalogId) { "Search identity changed" }
                return@withContext checkNotNull(dao.claim(id,bookUrl,owner)) { "Search identity changed" }
            }
            val created=snapshot(id,bookUrl,owner,query,false,true,null,0,0).copy(catalogId=catalogId)
            db.runInTransaction<BookContentSearchSession> {
                val raced=dao.get(id)
                if(raced==null) { dao.insert(created); created }
                else {
                    check(raced.bookUrl==bookUrl && raced.catalogId==catalogId) { "Search identity changed" }
                    checkNotNull(dao.claim(id,bookUrl,owner))
                }
            }
        }
        } catch(cancelled: CancellationException) { release(id,owner); throw cancelled }
    }
    fun release(id: String,owner: String) {
        io.legado.app.help.globalExecutor.execute { runCatching { dao.release(id,owner,System.currentTimeMillis()) } }
    }
    suspend fun restart(expected: BookContentSearchSession,query: String,regex: Boolean,matchCase: Boolean,
        replace: Boolean): BookContentSearchSession = withContext(Dispatchers.IO) {
        require(query.length<=2048)
        if(query.isNotEmpty()) BookContentMatcher(query,regex,matchCase)
        val next=snapshot(expected.id,expected.bookUrl,expected.owner,query,regex,matchCase,replace,
            expected.epoch,Math.incrementExact(expected.generation)).copy(catalogId=expected.catalogId)
        check(dao.restart(expected,next)) { "Search session expired" }; next
    }
    suspend fun draft(expected: BookContentSearchSession,value: String)=withContext(Dispatchers.IO) {
        require(value.length<=2048); check(dao.draft(expected,value)) { "Search session expired" }
    }
    suspend fun stop(expected: BookContentSearchSession)=withContext(Dispatchers.IO) { checkNotNull(dao.revoke(expected,false)) }
    suspend fun resume(expected: BookContentSearchSession): BookContentSearchSession = withContext(Dispatchers.IO) {
        check(!expected.complete && expected.query.isNotEmpty())
        checkNotNull(dao.revoke(expected,true))
    }

    private suspend fun snapshot(id: String,bookUrl: String,owner: String,query: String,regex: Boolean,
        matchCase: Boolean,replace: Boolean?,epoch: Long,generation: Long): BookContentSearchSession {
        val book=checkNotNull(db.bookDao.getBook(bookUrl)) { "Book does not exist" }.infoSnapshot()
        val chapters=db.bookChapterDao.getChapterList(bookUrl)
        val entry=entries.cachedSearchEntry(bookUrl,chapters.firstOrNull()?.index ?: error("Book has no chapters"))
        val p=entry.processing
        return BookContentSearchSession(id,bookUrl,book.origin,owner,epoch,generation,query,query,regex,matchCase,
            replace ?: actualReplacement(book,p),p.paragraphIndent,p.adaptSpecialStyle,p.chineseConverterType,p.manualReplaceRules,
            entry.titleMode,entry.splitTitle,rulesKey(book,p),chaptersKey(chapters),0,0,0,chapters.size,0,0,
            query.isNotEmpty(),false,null,System.currentTimeMillis())
    }
    private fun processing(session: BookContentSearchSession)=TextReaderProcessing(session.paragraphIndent,session.adaptSpecialStyle,
        session.chineseConverterType,session.manualReplaceRules)
    private fun chaptersKey(chapters: List<BookChapter>)=textReaderLinkKey(chapters.joinToString { "${it.index}:${it.url.length}:${it.url}:${it.title}:${it.startFragmentId}:${it.endFragmentId}:${it.variable}" })
    private fun actualReplacement(book: Book,p: TextReaderProcessing): Boolean = if(p.manualReplaceRules)
        book.config.manualReplaceRuleIds.let { if(it.isEmpty()) false else db.replaceRuleDao.findByIds(*it.toLongArray()).any { rule -> rule.scopeContent || rule.scopeTitle } }
        else book.getUseReplaceRule()
    private fun rulesKey(book: Book,p: TextReaderProcessing): String {
        val rules=if(p.manualReplaceRules) book.config.manualReplaceRuleIds.let { if(it.isEmpty()) emptyList() else db.replaceRuleDao.findByIds(*it.toLongArray()) }
            else db.replaceRuleDao.findEnabledByContentScope(book.name,book.origin)+db.replaceRuleDao.findEnabledByTitleScope(book.name,book.origin)
        return textReaderLinkKey("${book.name}:${book.originName}:${book.charset}:${book.getUseReplaceRule()}:${book.config.manualReplaceRuleIds}:$rules")
    }
    private fun validate(session: BookContentSearchSession) {
        check(dao.owns(session)) { "Search session expired" }
        val book=checkNotNull(db.bookDao.getBook(session.bookUrl))
        check(book.origin==session.origin && rulesKey(book,processing(session))==session.rulesKey) { "Search content changed" }
    }
    private suspend fun document(session: BookContentSearchSession,index: Int,actual: Boolean=false): Pair<TextReaderEntry,TextReaderDocument>? {
        val current=entries.cachedSearchEntry(session.bookUrl,index)
        val entry=if(actual) current else current.copy(processing=processing(session),
            adaptSpecialStyle=session.adaptSpecialStyle,titleMode=session.titleMode,splitTitle=session.splitTitle)
        check(entry.origin==session.origin) { "Search content changed" }
        val chapter=content.cachedContent(session.bookUrl,session.origin,index,entry.processing,if(actual) null else session.replace) ?: return null
        return entry to entries.document(entry,chapter)
    }
    suspend fun run(initial: BookContentSearchSession)=withContext(Dispatchers.IO) {
        var current=dao.get(initial.id)?.takeIf { it.owner==initial.owner && it.epoch==initial.epoch && it.generation==initial.generation }
            ?: return@withContext
        if(!current.running || current.complete || current.query.isEmpty()) return@withContext
        try {
            validate(current)
            val chapters=db.bookChapterDao.getChapterList(current.bookUrl).map { it.infoSnapshot() }
            check(chaptersKey(chapters)==current.chaptersKey) { "Search content changed" }
            val matcher=BookContentMatcher(current.query,current.regex,current.matchCase)
            val context=currentCoroutineContext()
            while(current.chapterOrdinal<chapters.size) {
                context.ensureActive(); validate(current)
                val chapter=chapters[current.chapterOrdinal]
                val document=document(current,chapter.index)?.second
                if(document==null) {
                    val next=current.copy(chapterOrdinal=current.chapterOrdinal+1,matchOffset=0,
                        searchedChapters=current.searchedChapters+1,skippedChapters=current.skippedChapters+1,updatedAt=System.currentTimeMillis())
                    check(dao.append(current,emptyList(),next)); current=next; continue
                }
                val key=textReaderLinkKey(document.text)
                val matches=matcher.matches(document.text,current.matchOffset) { context.ensureActive() }.iterator()
                do {
                    val batch=ArrayList<BookContentSearchHit>(128)
                    var nextOffset=current.matchOffset
                    while(batch.size<128 && matches.hasNext()) {
                        val match=matches.next(); val start=match.first; val end=match.last+1
                        var left=(start-40).coerceAtLeast(0); var right=(end+40).coerceAtMost(document.text.length)
                        if(left>0 && document.text[left].isLowSurrogate() && document.text[left-1].isHighSurrogate()) left--
                        if(right<document.text.length && right>0 && document.text[right].isLowSurrogate() && document.text[right-1].isHighSurrogate()) right++
                        // An unbounded regex match is represented by a bounded preview, never a large result payload.
                        right=minOf(right,left+240)
                        if(right<document.text.length && right>0 && document.text[right].isLowSurrogate() && document.text[right-1].isHighSurrogate()) right--
                        batch+=BookContentSearchHit(current.id,current.resultCount+batch.size,current.generation,chapter.index,chapter.url,
                            chapter.title,key,start,end,document.storagePosition(start),document.storagePosition(end),
                            document.text.substring(left,right),start-left,(end-left).coerceAtMost(right-left))
                        nextOffset=if(end>start) end else if(start<document.text.length) start+Character.charCount(document.text.codePointAt(start)) else document.text.length+1
                    }
                    val more=matches.hasNext()
                    val next=current.copy(resultCount=current.resultCount+batch.size,matchOffset=if(more) nextOffset else 0,
                        chapterOrdinal=current.chapterOrdinal+if(more) 0 else 1,
                        searchedChapters=current.searchedChapters+if(more) 0 else 1,updatedAt=System.currentTimeMillis())
                    context.ensureActive()
                    db.runInTransaction {
                        validate(current)
                        check(db.bookChapterDao.getChapter(current.bookUrl,chapter.index)?.url==chapter.url) { "Search content changed" }
                        check(dao.append(current,batch,next)) { "Search session expired" }
                    }
                    current=next
                } while(more)
            }
            context.ensureActive()
            db.runInTransaction {
                validate(current)
                check(chaptersKey(db.bookChapterDao.getChapterList(current.bookUrl))==current.chaptersKey) { "Search content changed" }
                check(dao.append(current,emptyList(),current.copy(running=false,complete=true,updatedAt=System.currentTimeMillis())))
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { dao.setRunning(current,false,error.message ?: "Search failed") }
    }

    /** Re-read the exact displayed document before publishing a durable reading result. */
    suspend fun select(expected: BookContentSearchSession,ordinal: Int,catalogId: String?): BookContentSearchSelection = withContext(Dispatchers.IO) {
        validate(expected)
        val hit=checkNotNull(dao.hit(expected.id,ordinal))
        check(hit.generation==expected.generation)
        val (entry,document)=checkNotNull(document(expected,hit.chapterIndex))
        check(entry.chapterUrl==hit.chapterUrl && textReaderLinkKey(document.text)==hit.documentKey) { "Search content changed" }
        check(catalogId==expected.catalogId)
        val actual=checkNotNull(document(expected,hit.chapterIndex,actual=true)).second
        val map=TextReaderPositionMap.between(document.text,actual.text)
        val position=actual.storagePosition(map.toDisplay(hit.displayStart))
        val end=maxOf(position,actual.positions?.toStorage(map.toDisplayEnd(hit.displayEnd)) ?: map.toDisplayEnd(hit.displayEnd))
        db.runInTransaction {
            validate(expected)
            check(db.bookChapterDao.getChapter(expected.bookUrl,hit.chapterIndex)?.url==hit.chapterUrl)
            check(dao.select(expected,ordinal,position,end,textReaderLinkKey(actual.text),actual.storageTitleLength,actual.storageIndentLength))
            if(catalogId!=null) check(db.readerSessionDao.deliverCatalog(catalogId,expected.bookUrl,hit.chapterIndex,position)) { "Reading request expired" }
        }
        BookContentSearchSelection(expected.id,ordinal,catalogId!=null)
    }
    suspend fun readerSelection(id: String): Pair<BookContentSearchSession,BookContentSearchHit>? = withContext(Dispatchers.IO) {
        val session=dao.get(id) ?: return@withContext null
        val ordinal=session.selectedOrdinal ?: return@withContext null
        dao.hit(id,ordinal)?.let { session to it }
    }
    suspend fun legacyResults(id: String,bookUrl: String): Pair<BookContentSearchSession,List<ContentSearchResult>> = withContext(Dispatchers.IO) {
        val session=checkNotNull(dao.get(id)).also { check(it.bookUrl==bookUrl) }
        val counts=hashMapOf<Int,Int>()
        session to dao.page(id,0,Int.MAX_VALUE).map { hit -> ContentSearchResult(
            resultCountWithinChapter=counts.getOrDefault(hit.chapterIndex,0).also { counts[hit.chapterIndex]=it+1 },
            resultCount=hit.ordinal,resultText=hit.snippet,chapterTitle=hit.chapterTitle,query=session.query,
            chapterIndex=hit.chapterIndex,queryIndexInResult=hit.snippetStart,queryIndexInChapter=hit.canonicalPosition,isRegex=session.regex,matchCase=session.matchCase) }
    }
}
