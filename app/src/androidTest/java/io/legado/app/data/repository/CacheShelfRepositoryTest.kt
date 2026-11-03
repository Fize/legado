package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.*
import io.legado.app.data.cache.BookCacheChanges
import io.legado.app.data.entities.*
import io.legado.app.help.book.isAudio
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheShelfRepositoryTest {
    private val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AppDatabase::class.java).addLegadoMigrations().build()
    private val shelf=CacheShelfRepository(db,BookCacheChanges())
    @After fun cleanup() { db.close() }
    @Test fun everyBuiltInAndUserFilterMatchesTheExistingShelfWithoutAudioOrTemporaryBooks()=runBlocking {
        db.bookGroupDao.insert(BookGroup(BookGroup.IdNetNone,"Ungrouped",show=false),BookGroup(1,"First"),BookGroup(2,"Second"))
        val types=listOf(BookType.text,BookType.text or BookType.local,BookType.image,BookType.audio,BookType.video,BookType.text or BookType.updateError,BookType.text or BookType.notShelf)
        val books=types.flatMapIndexed { index,type -> (0L..3L).map { group ->
            Book(bookUrl="book-$index-$group",name="Book $index $group",author="Author",type=type,group=group)
        } }
        books.forEach { db.bookDao.insert(it) }
        val groups=listOf<Long>(BookGroup.IdRoot,BookGroup.IdAll,BookGroup.IdLocal,BookGroup.IdAudio,BookGroup.IdNetNone,
            BookGroup.IdLocalNone,BookGroup.IdVideo,BookGroup.IdError,1,2,4)
        groups.forEach { group ->
            val expected=db.bookDao.flowByGroup(group).first().filterNot { it.isAudio }.map { it.bookUrl }.toSet()
            val actual=shelf.books(group).first().map { it.ref.url }.toSet()
            assertEquals("group=$group",expected,actual)
        }
    }
    @Test fun lightweightRowsKeepTransferIdentityAndDoNotExposeMutableOrParserFields()=runBlocking {
        val book=Book(bookUrl="identity",origin="source",originName="Source",name="Name",author="Author",charset="UTF-8",tocUrl="toc",durChapterIndex=3,totalChapterNum=8,intro="large intro".repeat(100_000))
        db.bookDao.insert(book)
        val rows=shelf.books(BookGroup.IdAll).first(); val row=rows.single()
        assertEquals(transferBookKey(book),row.ref.key); assertEquals(3,row.currentChapter); assertEquals(8,row.total)
        assertTrue(runCatching { (rows as MutableList<*>).clear() }.isFailure)
        db.bookDao.update(book.copy(origin="replacement"))
        assertNotEquals(row.ref.key,shelf.books(BookGroup.IdAll).first().single().ref.key)
    }
    @Test fun deletingAndReimportingABookCannotDisplayItsOldTransferAsTheNewBooksTask()=runBlocking {
        val book=Book(bookUrl="same-url",origin="source",name="Name"); db.bookDao.insert(book)
        val tasks=BookTransferRepository(db)
        tasks.enqueue(listOf(CacheBookRef(book.bookUrl,transferBookKey(book))),"download",BookTransferConfig())
        assertEquals(1,shelf.transfers().first().size)
        db.bookDao.delete(book); db.bookDao.insert(book)
        assertTrue(shelf.transfers().first().isEmpty())
    }
}
