package io.legado.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.cache.BookCacheChanges
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

internal data class CacheShelfBook(val ref: CacheBookRef,val title: String,val author: String,val local: Boolean,
    val currentChapter: Int,val total: Int)
internal data class CacheShelfGroup(val id: Long,val name: String)
internal data class CacheShelfCount(val cached: Int,val total: Int,val failed: Boolean=false)

@Singleton
class CacheShelfRepository @Inject constructor(private val db: AppDatabase,private val changes: BookCacheChanges) {
    internal fun groups()=db.bookGroupDao.flowAll().map { rows ->
        java.util.Collections.unmodifiableList(rows.map { CacheShelfGroup(it.groupId,it.groupName) })
    }.flowOn(Dispatchers.IO)
    internal fun books(groupId: Long): Flow<List<CacheShelfBook>> {
        val ungrouped="((SELECT coalesce(sum(groupId),0) FROM book_groups WHERE groupId>0 OR groupId=${Long.MIN_VALUE}) & `group`)=0"
        val filter=when(groupId) {
            BookGroup.IdAll -> "1"
            BookGroup.IdRoot -> "type & ${BookType.text}>0 AND type & ${BookType.local}=0 AND $ungrouped AND (SELECT show FROM book_groups WHERE groupId=${BookGroup.IdNetNone})!=1"
            BookGroup.IdLocal -> "type & ${BookType.local}>0"
            BookGroup.IdAudio -> "type & ${BookType.audio}>0"
            BookGroup.IdVideo -> "type & ${BookType.video}>0"
            BookGroup.IdNetNone -> "type & ${BookType.local}=0 AND type & ${BookType.video}=0 AND $ungrouped"
            BookGroup.IdLocalNone -> "type & ${BookType.local}>0 AND $ungrouped"
            BookGroup.IdError -> "type & ${BookType.updateError}>0"
            else -> "(`group` & ?) !=0"
        }
        val arguments=if(filter.contains('?')) arrayOf<Any>(groupId) else emptyArray()
        val query=SimpleSQLiteQuery("SELECT bookUrl,origin,originName,name,author,type,charset,tocUrl,totalChapterNum,durChapterIndex,durChapterTime,latestChapterTime,`order` FROM books WHERE type & ${BookType.notShelf}=0 AND type & ${BookType.audio}=0 AND ($filter)",arguments)
        return db.bookDao.flowCacheShelf(query).map { rows ->
            val sorted=when(AppConfig.getBookSortByGroupId(groupId)) {
                1 -> rows.sortedByDescending { it.latestChapterTime }
                2 -> rows.sortedWith { first,second -> first.name.cnCompare(second.name) }
                3 -> rows.sortedBy { it.order }
                4 -> rows.sortedByDescending { maxOf(it.latestChapterTime,it.durChapterTime) }
                else -> rows.sortedByDescending { it.durChapterTime }
            }
            java.util.Collections.unmodifiableList(sorted.map { row ->
                val book=row.identityBook()
                CacheShelfBook(CacheBookRef(row.bookUrl,transferBookKey(book)),row.name,book.getRealAuthor(),
                    book.isLocal,row.durChapterIndex,row.totalChapterNum)
            })
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }
    internal fun count(ref: CacheBookRef): Flow<CacheShelfCount?> = changes.observe(ref.url).flatMapLatest {
        combine(db.bookDao.observeAnnotationBook(ref.url),db.bookChapterDao.flowCacheCatalog(ref.url).distinctUntilChanged()) { book,chapters ->
            if(book==null || transferBookKey(book)!=ref.key) null else {
                val files=BookHelp.getChapterFiles(book.infoSnapshot())
                CacheShelfCount(chapters.count { it.isVolume || it.fileName() in files },chapters.size)
            }
        }.catch { emit(CacheShelfCount(0,0,true)) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
    internal fun retryCount(ref: CacheBookRef)=changes.changed(ref.url)
    internal fun transfers()=db.bookTransferDao.observeCurrent().map { java.util.Collections.unmodifiableList(it.toList()) }
        .distinctUntilChanged().flowOn(Dispatchers.IO)
}
