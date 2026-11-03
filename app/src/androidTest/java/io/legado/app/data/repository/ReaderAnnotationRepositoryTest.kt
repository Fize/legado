package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.TextReaderDocument
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderAnnotationRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val entries=TextReaderEntryRepository(db)
    private val content=TextReaderContentRepository(db,CacheResourceLeases())
    private val repo=ReaderAnnotationRepository(db,content,entries)
    private val id=UUID.randomUUID().toString()
    private val book=Book(bookUrl="fixture://annotations-$id",origin="source-$id",name=id,author=id,
        readConfig=Book.ReadConfig(useReplaceRule=false))
    private val chapter=BookChapter(bookUrl=book.bookUrl,url="fixture://chapter-$id",index=0,title="Title")
    private val owner=ReaderProgressOwner(id,"owner",0)
    private lateinit var entry: TextReaderEntry
    private lateinit var document: TextReaderDocument
    private val oldTitle=ReadBookConfig.titleMode
    @Before fun setup()=runBlocking {
        ReadBookConfig.titleMode=0; db.bookDao.insert(book); db.bookChapterDao.insert(chapter)
        BookHelp.saveText(book,chapter,"First amber 😀 phrase\nSecond paragraph")
        val session=db.readerSessionDao.claim(ReaderSessionState(id,book.bookUrl,book.bookUrl,true,false,updatedAt=1),owner.token)
        entry=entries.entry(session,0,0)
        document=entries.document(entry,checkNotNull(content.cachedContent(book.bookUrl,book.origin,0,entry.processing)))
    }
    @After fun cleanup() { BookHelp.clearCache(book); db.close(); ReadBookConfig.titleMode=oldTitle }
    private suspend fun draft(highlight: Boolean=false): ReaderAnnotationDraft {
        val at=document.text.indexOf("amber")
        return repo.create(owner,entry,document,chapter.title,at,at+"amber 😀".length,highlight)
    }
    @Test fun bookmarkCreationPreservesUnicodeCoordinatesAndDoesNotWriteProgress()=runBlocking {
        val draft=draft(); repo.update(owner,draft.id,draft.bookText,"A note"); repo.save(owner,draft.id)
        val saved=db.bookmarkDao.getByBook(book.name,book.author).single()
        assertEquals("amber 😀",saved.bookText); assertEquals("A note",saved.content)
        assertEquals(document.storagePosition(document.text.indexOf("amber")),saved.chapterPos)
        assertEquals(0,db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertNull(repo.restore(owner,book.bookUrl,book.origin))
    }
    @Test fun highlightsRetainSourceIdentityAndBothCanonicalEnds()=runBlocking {
        val draft=draft(true); repo.save(owner,draft.id)
        val saved=db.bookHighlightDao.getByBook(book.bookUrl).single()
        assertEquals(chapter.url,saved.chapterUrl); assertEquals(document.storageTitleLength,saved.layoutTitleLength)
        assertEquals(draft.position,saved.chapterPos); assertEquals(draft.end,saved.chapterPosEnd)
        assertEquals(0x66FFEB3B,saved.styleObj().fill)
    }
    @Test fun draftsRestoreWithANewRepositoryAndReaderOwner()=runBlocking {
        val draft=draft(); repo.update(owner,draft.id,"Edited excerpt","Persistent note")
        db.readerSessionDao.claim(ReaderSessionState(id,book.bookUrl,book.bookUrl,true,false,updatedAt=2),"replacement")
        val next=ReaderAnnotationRepository(db,content,entries).restore(owner.copy(token="replacement"),book.bookUrl,book.origin)!!
        assertEquals(draft.id,next.id); assertEquals("Edited excerpt",next.bookText); assertEquals("Persistent note",next.note)
        assertTrue(runCatching { repo.update(owner,draft.id,"old","old") }.isFailure)
    }
    @Test fun aNewDraftRevokesOldEditorCallbacks()=runBlocking {
        val old=draft(); val next=draft()
        assertTrue(runCatching { repo.update(owner,old.id,"old","old") }.isFailure)
        assertTrue(runCatching { repo.discard(owner,old.id) }.isFailure)
        assertEquals(next.id,repo.restore(owner,book.bookUrl,book.origin)!!.id)
    }
    @Test fun changedContentRejectsANewAnnotationWithoutLosingItsDraft()=runBlocking {
        val draft=draft(); BookHelp.saveText(book,chapter,"Completely edited body")
        assertTrue(runCatching { repo.save(owner,draft.id) }.isFailure)
        assertTrue(db.bookmarkDao.getByBook(book.name,book.author).isEmpty())
        assertEquals(draft.id,repo.restore(owner,book.bookUrl,book.origin)!!.id)
    }
    @Test fun changesToSourceOrChapterIdentityRejectThePendingWrite()=runBlocking {
        val draft=draft(); db.bookChapterDao.insert(chapter.copy(url="different"))
        assertTrue(runCatching { repo.save(owner,draft.id) }.isFailure)
        db.bookChapterDao.insert(chapter); db.bookDao.update(book.copy(origin="other-source"))
        assertTrue(runCatching { repo.save(owner,draft.id) }.isFailure)
    }
    @Test fun editingPreservesTheExistingBookmarkIdentityAndPosition()=runBlocking {
        val draft=draft(); repo.save(owner,draft.id)
        val row=repo.observe(book.bookUrl).first().single()
        val editor=repo.edit(owner,entry,row); repo.update(owner,editor.id,"Changed quote","Changed note"); repo.save(owner,editor.id)
        val saved=db.bookmarkDao.get(row.time)!!
        assertEquals(row.position,saved.chapterPos); assertEquals("Changed quote",saved.bookText)
        assertEquals("Changed note",saved.content); assertEquals(1,db.bookmarkDao.getByBook(book.name,book.author).size)
    }
    @Test fun anExternalEditCannotBeOverwrittenOrDeletedByAnOldEditor()=runBlocking {
        val draft=draft(); repo.save(owner,draft.id)
        val row=repo.observe(book.bookUrl).first().single()
        val editor=repo.edit(owner,entry,row)
        db.bookmarkDao.update(db.bookmarkDao.get(row.time)!!.copy(content="Other window"))
        assertTrue(runCatching { repo.save(owner,editor.id) }.isFailure)
        assertTrue(runCatching { repo.delete(owner,entry,row) }.isFailure)
        assertEquals("Other window",db.bookmarkDao.get(row.time)!!.content)
    }
    @Test fun discardingADraftLeavesItsOriginalBookmarkUntouched()=runBlocking {
        val draft=draft(); repo.save(owner,draft.id)
        val row=repo.observe(book.bookUrl).first().single()
        val editor=repo.edit(owner,entry,row); repo.update(owner,editor.id,"Changed","Changed"); repo.discard(owner,editor.id)
        assertEquals(row.text,db.bookmarkDao.get(row.time)!!.bookText)
    }
    @Test fun closingTheReaderDeletesOnlyItsDraft()=runBlocking {
        val draft=draft(); repo.save(owner,draft.id); draft()
        db.readerSessionDao.close(id,owner.token)
        assertNull(db.readerAnnotationDao.draft(id))
        assertEquals(1,db.bookmarkDao.getByBook(book.name,book.author).size)
    }
    @Test fun repeatedSaveDoesNotCreateDuplicateBookmarks()=runBlocking {
        val draft=draft(); repo.save(owner,draft.id)
        assertTrue(runCatching { repo.save(owner,draft.id) }.isFailure)
        assertEquals(1,db.bookmarkDao.getByBook(book.name,book.author).size)
    }
    @Test fun highlightingTheTitleOrAnEmptyRangeIsRejected()=runBlocking {
        assertTrue(runCatching { repo.create(owner,entry,document,chapter.title,0,1,true) }.isFailure)
        val at=document.text.indexOf("amber")
        assertTrue(runCatching { repo.create(owner,entry,document,chapter.title,at,at,true) }.isFailure)
    }
    @Test fun editingAHighlightNotePreservesEveryStoredStyleChannel()=runBlocking {
        val draft=draft(true); repo.save(owner,draft.id)
        val original=db.bookHighlightDao.getByBook(book.bookUrl).single().copy(style="{\"fill\":1728046875,\"bold\":true,\"fontPath\":\"custom.ttf\",\"futureChannel\":42}")
        db.bookHighlightDao.update(original)
        val row=repo.observe(book.bookUrl).first().single()
        val editor=repo.edit(owner,entry,row); repo.update(owner,editor.id,editor.bookText,"Updated highlight note"); repo.save(owner,editor.id)
        val saved=db.bookHighlightDao.get(original.time)!!
        assertEquals(original.style,saved.style); assertEquals("Updated highlight note",saved.note)
        assertEquals(original.chapterPosEnd,saved.chapterPosEnd)
    }
    @Test fun deletingAHighlightDoesNotDeleteABookmarkWithTheSameTimestamp()=runBlocking {
        val first=draft(true); repo.save(owner,first.id)
        val highlight=db.bookHighlightDao.getByBook(book.bookUrl).single()
        db.bookmarkDao.insert(Bookmark(highlight.time,book.name,book.author,0,3,"Title","Other","Bookmark note"))
        val row=repo.observe(book.bookUrl).first().single { it.highlight }
        repo.delete(owner,entry,row)
        assertNull(db.bookHighlightDao.get(highlight.time)); assertNotNull(db.bookmarkDao.get(highlight.time))
    }
    @Test fun oversizedInputCannotEraseThePreviouslyCommittedDraft()=runBlocking {
        val draft=draft(); repo.update(owner,draft.id,draft.bookText,"Kept note")
        assertTrue(runCatching { repo.update(owner,draft.id,draft.bookText,"x".repeat(ReaderAnnotationRepository.MAX_TEXT+1)) }.isFailure)
        assertEquals("Kept note",repo.restore(owner,book.bookUrl,book.origin)!!.note)
    }

    @Test fun styleChangesAndNoteUpdatesShareTheSameDurableDraftWithoutDroppingEither()=runBlocking {
        val draft=draft(true)
        repo.updateStyle(owner,draft.id,io.legado.app.help.HighlightStyle(underline=io.legado.app.help.HighlightStyle.Underline(io.legado.app.help.HighlightStyle.Kind.WAVY,0xffff0000.toInt(),4f,12f)))
        repo.update(owner,draft.id,draft.bookText,"Styled note")
        val restored=ReaderAnnotationRepository(db,content,entries).restore(owner,book.bookUrl,book.origin)!!
        assertEquals("Styled note",restored.note)
        repo.save(owner,draft.id)
        val saved=db.bookHighlightDao.getByBook(book.bookUrl).single()
        assertEquals(io.legado.app.help.HighlightStyle.Kind.WAVY,saved.styleObj().underline!!.kind)
        assertEquals(12f,saved.styleObj().underline!!.distance)
    }
    @Test fun anOldStyleCallbackCannotModifyANewerEditor()=runBlocking {
        val old=draft(true); val next=draft(true)
        assertTrue(runCatching { repo.updateStyle(owner,old.id,io.legado.app.help.HighlightStyle(bold=true)) }.isFailure)
        assertEquals(next.style,repo.restore(owner,book.bookUrl,book.origin)!!.style)
    }
    @Test fun aBookmarkDraftRejectsHighlightStyles()=runBlocking {
        val draft=draft()
        assertTrue(runCatching { repo.updateStyle(owner,draft.id,io.legado.app.help.HighlightStyle(bold=true)) }.isFailure)
        assertEquals("",repo.restore(owner,book.bookUrl,book.origin)!!.style)
    }
    @Test fun anOversizedFontIdentityCannotReplaceTheValidStyle()=runBlocking {
        val draft=draft(true)
        assertTrue(runCatching { repo.updateStyle(owner,draft.id,io.legado.app.help.HighlightStyle(fontPath="x".repeat(2049))) }.isFailure)
        assertEquals(draft.style,repo.restore(owner,book.bookUrl,book.origin)!!.style)
    }

}
