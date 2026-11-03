package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.help.HighlightStyle
import io.legado.app.model.TextReaderDocument
import io.legado.app.model.textReaderLinkKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable list rows; mutable legacy Room entities never enter reader screen state. */
data class ReaderAnnotationItem(val time: Long,val highlight: Boolean,val chapterIndex: Int,val position: Int,
    val end: Int,val titleLength: Int,val chapterUrl: String,val title: String,val text: String,val note: String,
    val style: String,val fingerprint: String,val parsedStyle: HighlightStyle?=null) {
    val key get()="${if(highlight) 'h' else 'b'}:$time"
}

@Singleton
class ReaderAnnotationRepository @Inject constructor(private val db: AppDatabase,
    private val content: TextReaderContentRepository,private val entries: TextReaderEntryRepository) {
    fun observe(bookUrl: String,name: String,author: String)=combine(db.bookmarkDao.flowByBook(name,author),
        db.bookHighlightDao.flowByBook(bookUrl)) { bookmarks,highlights ->
        java.util.Collections.unmodifiableList((bookmarks.map(::item)+highlights.map(::item))
            .sortedWith(compareBy({it.chapterIndex},{it.position},{it.time})))
    }.flowOn(Dispatchers.Default)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(bookUrl: String)=db.bookDao.observeAnnotationBook(bookUrl).map { it?.let { book -> book.name to book.author } }
        .distinctUntilChanged().flatMapLatest { identity ->
            if(identity==null) flowOf(emptyList()) else observe(bookUrl,identity.first,identity.second)
        }
    private fun item(value: Bookmark)=ReaderAnnotationItem(value.time,false,value.chapterIndex,value.chapterPos,value.chapterPos,-1,"",
        value.chapterName,value.bookText,value.content,"",textReaderLinkKey(value.toString()))
    private fun item(value: BookHighlight)=ReaderAnnotationItem(value.time,true,value.chapterIndex,value.chapterPos,value.chapterPosEnd,
        value.layoutTitleLength,value.chapterUrl,value.chapterName,value.bookText,value.note,value.style,textReaderLinkKey(value.toString()),value.styleObj())
    private fun validate(owner: ReaderProgressOwner,bookUrl: String,origin: String): Book {
        check(db.readerSessionDao.ownsBook(owner.sessionId,owner.token,owner.revision,bookUrl)) { "Reader no longer owns this book" }
        return checkNotNull(db.bookDao.getBook(bookUrl)).also { check(it.origin==origin) { "Reader content changed" } }
    }
    private fun current(book: Book,row: ReaderAnnotationItem): ReaderAnnotationItem {
        val actual=if(row.highlight) db.bookHighlightDao.get(row.time)?.takeIf { it.bookUrl==book.bookUrl }?.let(::item)
            else db.bookmarkDao.get(row.time)?.takeIf { it.bookName==book.name && it.bookAuthor==book.author }?.let(::item)
        check(actual?.fingerprint==row.fingerprint) { "Annotation changed" }
        return checkNotNull(actual)
    }
    suspend fun restore(owner: ReaderProgressOwner,bookUrl: String,origin: String)=withContext(Dispatchers.IO) {
        db.runInTransaction<ReaderAnnotationDraft?> {
            validate(owner,bookUrl,origin)
            db.readerAnnotationDao.draft(owner.sessionId)?.takeIf { it.bookUrl==bookUrl && it.origin==origin }
        }
    }
    suspend fun create(owner: ReaderProgressOwner,entry: TextReaderEntry,document: TextReaderDocument,title: String,
        start: Int,end: Int,highlight: Boolean)=withContext(Dispatchers.IO) {
        require(start in 0..document.text.length && end in start..document.text.length && end-start<=MAX_TEXT)
        require(!highlight || start>=document.titleLength && end>start && document.text.substring(start,end).any { !it.isWhitespace() && it!='\uFFFC' })
        db.runInTransaction<ReaderAnnotationDraft> {
            val book=validate(owner,entry.bookUrl,entry.origin)
            check(db.bookChapterDao.getChapter(book.bookUrl,entry.chapterIndex)?.url==entry.chapterUrl) { "Reader chapter changed" }
            ReaderAnnotationDraft(owner.sessionId,UUID.randomUUID().toString(),book.bookUrl,book.origin,book.name,book.author,
                entry.chapterIndex,entry.chapterUrl,title,document.storagePosition(start),
                document.positions?.toStorage(end) ?: end,document.storageTitleLength,textReaderLinkKey(document.text),highlight,null,null,
                document.text.substring(start,end),"",if(highlight) io.legado.app.utils.GSON.toJson(HighlightStyle(fill=0x66FFEB3B)) else "",
                System.currentTimeMillis()).also(db.readerAnnotationDao::put)
        }
    }
    suspend fun edit(owner: ReaderProgressOwner,entry: TextReaderEntry,row: ReaderAnnotationItem)=withContext(Dispatchers.IO) {
        db.runInTransaction<ReaderAnnotationDraft> {
            val book=validate(owner,entry.bookUrl,entry.origin); val actual=current(book,row)
            val chapter=checkNotNull(db.bookChapterDao.getChapter(book.bookUrl,actual.chapterIndex)) { "Reader chapter changed" }
            check(actual.chapterUrl.isEmpty() || actual.chapterUrl==chapter.url) { "Reader chapter changed" }
            ReaderAnnotationDraft(owner.sessionId,UUID.randomUUID().toString(),book.bookUrl,book.origin,book.name,book.author,
                actual.chapterIndex,chapter.url,actual.title,actual.position,actual.end,actual.titleLength,"",actual.highlight,
                actual.time,actual.fingerprint,actual.text,actual.note,actual.style,System.currentTimeMillis()).also(db.readerAnnotationDao::put)
        }
    }
    suspend fun update(owner: ReaderProgressOwner,id: String,text: String,note: String)=withContext(Dispatchers.IO) {
        require(text.length<=MAX_TEXT && note.length<=MAX_TEXT)
        db.runInTransaction {
            val draft=ownedDraft(owner,id)
            db.readerAnnotationDao.put(draft.copy(bookText=text,note=note,updatedAt=System.currentTimeMillis()))
        }
    }
    suspend fun updateStyle(owner: ReaderProgressOwner,id: String,style: HighlightStyle)=withContext(Dispatchers.IO) {
        require(style.resolvedFontPath.length<=2048)
        db.runInTransaction {
            val draft=ownedDraft(owner,id)
            check(draft.highlight)
            db.readerAnnotationDao.put(draft.copy(style=io.legado.app.model.mergeReaderHighlightStyle(draft.style,style),updatedAt=System.currentTimeMillis()))
        }
    }
    private fun ownedDraft(owner: ReaderProgressOwner,id: String): ReaderAnnotationDraft {
        val draft=checkNotNull(db.readerAnnotationDao.draft(owner.sessionId)?.takeIf { it.id==id }) { "Annotation expired" }
        val book=validate(owner,draft.bookUrl,draft.origin)
        check(book.name==draft.bookName && book.author==draft.bookAuthor) { "Annotation changed" }
        return draft
    }
    suspend fun discard(owner: ReaderProgressOwner,id: String)=withContext(Dispatchers.IO) {
        db.runInTransaction { ownedDraft(owner,id); db.readerAnnotationDao.discard(owner.sessionId,id) }
    }
    suspend fun save(owner: ReaderProgressOwner,id: String)=withContext(Dispatchers.IO) {
        val draft=db.runInTransaction<ReaderAnnotationDraft> { ownedDraft(owner,id) }
        if(draft.documentKey.isNotEmpty()) {
            val session=checkNotNull(db.readerSessionDao.get(owner.sessionId))
            val entry=entries.entry(session,draft.chapterIndex,0)
            val chapter=checkNotNull(content.cachedContent(draft.bookUrl,draft.origin,draft.chapterIndex,entry.processing)) { "Reader content changed" }
            check(textReaderLinkKey(entries.document(entry,chapter).text)==draft.documentKey) { "Reader content changed" }
        }
        db.runInTransaction {
            check(ownedDraft(owner,id)==draft) { "Annotation changed" }
            check(db.bookChapterDao.getChapter(draft.bookUrl,draft.chapterIndex)?.url==draft.chapterUrl) { "Reader chapter changed" }
            if(draft.editingTime!=null) {
                val actual=if(draft.highlight) db.bookHighlightDao.get(draft.editingTime)?.let(::item) else db.bookmarkDao.get(draft.editingTime)?.let(::item)
                check(actual?.fingerprint==draft.originalKey) { "Annotation changed" }
            }
            if(draft.highlight) {
                val time=draft.editingTime ?: maxOf(System.currentTimeMillis(),Math.incrementExact(db.bookHighlightDao.latestTime() ?: 0))
                val value=BookHighlight(time,draft.bookUrl,draft.chapterUrl,draft.bookName,draft.bookAuthor,draft.chapterIndex,
                    draft.position,draft.end,draft.titleLength,draft.chapterName,draft.bookText,draft.style,draft.note)
                if(draft.editingTime==null) db.bookHighlightDao.insertNew(value) else db.bookHighlightDao.update(value)
            } else {
                val time=draft.editingTime ?: maxOf(System.currentTimeMillis(),Math.incrementExact(db.bookmarkDao.latestTime() ?: 0))
                val value=Bookmark(time,draft.bookName,draft.bookAuthor,draft.chapterIndex,draft.position,draft.chapterName,draft.bookText,draft.note)
                if(draft.editingTime==null) db.bookmarkDao.insertNew(value) else db.bookmarkDao.update(value)
            }
            db.readerAnnotationDao.discard(owner.sessionId,id)
        }
    }
    suspend fun target(owner: ReaderProgressOwner,entry: TextReaderEntry,row: ReaderAnnotationItem)=withContext(Dispatchers.IO) {
        db.runInTransaction<BookChapter> {
            val book=validate(owner,entry.bookUrl,entry.origin); current(book,row)
            checkNotNull(db.bookChapterDao.getChapter(book.bookUrl,row.chapterIndex)).also {
                check(row.chapterUrl.isEmpty() || row.chapterUrl==it.url) { "Reader chapter changed" }
            }.infoSnapshot()
        }
    }
    suspend fun delete(owner: ReaderProgressOwner,entry: TextReaderEntry,row: ReaderAnnotationItem)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val book=validate(owner,entry.bookUrl,entry.origin); current(book,row)
            if(row.highlight) db.bookHighlightDao.delete(checkNotNull(db.bookHighlightDao.get(row.time)))
            else db.bookmarkDao.delete(checkNotNull(db.bookmarkDao.get(row.time)))
        }
    }
    companion object { const val MAX_TEXT=65_536 }
}
