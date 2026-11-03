package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BookContentSearchDao {
    @Query("SELECT * FROM book_content_search_sessions WHERE id=:id")
    abstract fun get(id: String): BookContentSearchSession?
    @Query("SELECT * FROM book_content_search_sessions WHERE id=:id")
    abstract fun observe(id: String): Flow<BookContentSearchSession?>
    @Insert(onConflict=OnConflictStrategy.ABORT)
    abstract fun insert(session: BookContentSearchSession)
    @Update
    protected abstract fun update(session: BookContentSearchSession)
    @Insert(onConflict=OnConflictStrategy.ABORT)
    protected abstract fun insertHits(hits: List<BookContentSearchHit>)
    @Query("DELETE FROM book_content_search_hits WHERE sessionId=:id")
    protected abstract fun clearHits(id: String)
    @Query("SELECT * FROM book_content_search_hits WHERE sessionId=:id AND ordinal>=:start ORDER BY ordinal LIMIT :limit")
    abstract fun page(id: String,start: Int,limit: Int): List<BookContentSearchHit>
    @Query("SELECT * FROM book_content_search_hits WHERE sessionId=:id AND ordinal=:ordinal")
    abstract fun hit(id: String,ordinal: Int): BookContentSearchHit?
    @Query("DELETE FROM book_content_search_sessions WHERE id=:id AND owner=:owner AND epoch=:epoch")
    abstract fun delete(id: String,owner: String,epoch: Long): Int

    @Query("UPDATE book_content_search_sessions SET owner='',epoch=epoch+1,running=0,updatedAt=MAX(updatedAt,:now) WHERE id=:id AND owner=:owner")
    abstract fun release(id: String,owner: String,now: Long): Int
    @Query("SELECT * FROM book_content_search_sessions WHERE updatedAt<:before AND resultCount>0")
    protected abstract fun expired(before: Long): List<BookContentSearchSession>
    /** Results are rebuildable cache. Keep query drafts while fencing every previous writer. */
    @Transaction
    open fun pruneResults(before: Long): Int {
        val sessions=expired(before)
        sessions.forEach { current ->
            clearHits(current.id)
            update(current.copy(owner="",epoch=Math.incrementExact(current.epoch),generation=Math.incrementExact(current.generation),
                chapterOrdinal=0,matchOffset=0,resultCount=0,searchedChapters=0,skippedChapters=0,
                running=current.query.isNotEmpty(),complete=false,error=null,selectedOrdinal=null,selectedPosition=null,
                selectedEnd=null,selectedDocumentKey=null,selectedTitleLength=null,selectedIndentLength=null,updatedAt=System.currentTimeMillis()))
        }
        return sessions.size
    }

    @Transaction
    open fun claim(id: String,bookUrl: String,owner: String): BookContentSearchSession? {
        val current=get(id)?.takeIf { it.bookUrl==bookUrl } ?: return null
        return current.copy(owner=owner,epoch=Math.incrementExact(current.epoch),updatedAt=maxOf(current.updatedAt,System.currentTimeMillis())).also(::update)
    }
    @Transaction
    open fun restart(expected: BookContentSearchSession,next: BookContentSearchSession): Boolean {
        if(!owns(expected)) return false
        check(next.id==expected.id && next.owner==expected.owner && next.epoch==expected.epoch && next.generation>expected.generation)
        clearHits(expected.id); update(next); return true
    }
    @Transaction
    open fun append(expected: BookContentSearchSession,hits: List<BookContentSearchHit>,next: BookContentSearchSession): Boolean {
        val current=get(expected.id) ?: return false
        if(!owns(expected) || !current.running || current.resultCount!=expected.resultCount ||
            current.chapterOrdinal!=expected.chapterOrdinal || current.matchOffset!=expected.matchOffset) return false
        require(next.resultCount==expected.resultCount+hits.size && next.id==expected.id &&
            next.generation==expected.generation && next.epoch==expected.epoch && next.owner==expected.owner)
        hits.forEachIndexed { index,hit -> require(hit.sessionId==expected.id && hit.generation==expected.generation && hit.ordinal==expected.resultCount+index) }
        insertHits(hits); update(next.copy(draft=current.draft,selectedOrdinal=current.selectedOrdinal,
            selectedPosition=current.selectedPosition,selectedEnd=current.selectedEnd,selectedDocumentKey=current.selectedDocumentKey,
            selectedTitleLength=current.selectedTitleLength,selectedIndentLength=current.selectedIndentLength)); return true
    }
    @Transaction
    open fun setRunning(expected: BookContentSearchSession,running: Boolean,error: String?=null): Boolean {
        val current=get(expected.id) ?: return false
        if(!owns(expected)) return false
        if(error!=null && (!current.running || current.complete || current.resultCount!=expected.resultCount ||
            current.chapterOrdinal!=expected.chapterOrdinal || current.matchOffset!=expected.matchOffset)) return false
        update(current.copy(running=running,error=error,updatedAt=System.currentTimeMillis())); return true
    }
    @Transaction
    open fun revoke(expected: BookContentSearchSession,running: Boolean): BookContentSearchSession? {
        val current=get(expected.id) ?: return null
        if(!owns(expected)) return null
        return current.copy(epoch=Math.incrementExact(current.epoch),running=running && !current.complete,error=null).also(::update)
    }
    @Transaction
    open fun select(expected: BookContentSearchSession,ordinal: Int,position: Int,end: Int,key: String,title: Int,indent: Int): Boolean {
        val current=get(expected.id) ?: return false
        if(!owns(expected) || current.running) return false
        update(current.copy(selectedOrdinal=ordinal,selectedPosition=position,selectedEnd=end,selectedDocumentKey=key,
            selectedTitleLength=title,selectedIndentLength=indent)); return true
    }
    @Transaction
    open fun draft(expected: BookContentSearchSession,value: String): Boolean {
        val current=get(expected.id) ?: return false
        if(!owns(expected)) return false
        update(current.copy(draft=value,updatedAt=maxOf(current.updatedAt,System.currentTimeMillis()))); return true
    }
    fun owns(expected: BookContentSearchSession): Boolean = get(expected.id)?.let {
        it.owner==expected.owner && it.epoch==expected.epoch && it.generation==expected.generation
    }==true
}
