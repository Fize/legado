package io.legado.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class BookDownloadTaskRunnerTest {
    private val requests=CopyOnWriteArrayList<String>()
    @Volatile private var responseHook: (() -> Unit)?=null
    @Volatile private var imageHook: (() -> Unit)?=null
    @Volatile private var includeImage=false
    private val imageBytes by lazy {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private val server=object: NanoHTTPD("127.0.0.1",0) {
        override fun serve(session: IHTTPSession): Response {
            requests.add(session.uri)
            if(session.uri=="/image.png") {
                imageHook?.invoke()
                return newFixedLengthResponse(Response.Status.OK,"image/png",ByteArrayInputStream(imageBytes),imageBytes.size.toLong())
            }
            responseHook?.invoke()
            val image=if(includeImage) "<img src=\"http://127.0.0.1:$listeningPort/image.png\">" else ""
            return newFixedLengthResponse(Response.Status.OK,"text/html","<html><body>Actual HTTP body ${session.uri}$image</body></html>")
        }
    }
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var chapters: List<BookChapter>
    private val tasks=BookTransferRepository(appDb)
    private val runner=BookDownloadTaskRunner(appDb,tasks,CacheResourceLeases())
    @Before fun setup() {
        server.start(); val id=UUID.randomUUID().toString()
        source=BookSource(bookSourceUrl="http://127.0.0.1:${server.listeningPort}",bookSourceName="Runner $id",ruleContent=ContentRule(content="body@text"))
        book=Book(bookUrl="${source.bookSourceUrl}/book/$id",origin=source.bookSourceUrl,name="Runner $id",canUpdate=false,totalChapterNum=3)
        chapters=(0..2).map { BookChapter(bookUrl=book.bookUrl,url="${source.bookSourceUrl}/chapter/$it",index=it,title="Chapter $it") }
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }
    @After fun cleanup()=runBlocking {
        responseHook=null; imageHook=null
        tasks.cancelIds(appDb.bookTransferDao.active("download").filter { it.bookUrl==book.bookUrl }.map { it.id })
        BookHelp.clearCache(book)
        val owned=appDb.openHelper.readableDatabase.query("SELECT id,batchId FROM book_transfer_tasks WHERE bookUrl=?",arrayOf(book.bookUrl)).use { rows ->
            buildList { while(rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
        }
        owned.forEach { appDb.bookTransferDao.deleteTask(it.first) }
        owned.mapNotNull { it.second }.distinct().forEach { batch ->
            assertTrue(appDb.bookTransferDao.batchTasks(batch).isEmpty())
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?",arrayOf(batch))
        }
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book); appDb.bookSourceDao.delete(source); server.stop()
    }
    private suspend fun claim(start: Int=0,end: Int=-1): BookTransferTask {
        val ids=tasks.enqueue(listOf(CacheBookRef(book.bookUrl,transferBookKey(book))),"download",BookTransferConfig(start=start,end=end,threads=1))
        val batch=tasks.dispatchCandidates(ids).single()
        return tasks.claimNext(checkNotNull(tasks.claimBatch(batch.id,batch.dispatch)))!!
    }
    @Test fun anInclusiveRangeDownloadsActualBodiesAndSkipsAnAlreadyCompleteCache()=runBlocking {
        assertTrue(BookHelp.saveContent(source,book,chapters[1],"Existing body"))
        val task=claim(1,2); runner.run(task)
        assertEquals(listOf("/chapter/2"),requests.toList())
        assertEquals("Existing body",BookHelp.getContent(book,chapters[1]))
        assertTrue(BookHelp.getContent(book,chapters[2])!!.contains("Actual HTTP body /chapter/2"))
        assertNull(BookHelp.getContent(book,chapters[0]))
        val completed=appDb.bookTransferDao.get(task.id)!!
        assertEquals("completed",completed.phase); assertEquals(2,completed.progress); assertEquals(2,completed.total)
    }
    @Test fun cancellationDuringTheHttpResponseCannotPublishTheLateBody()=runBlocking {
        val task=claim(0,0)
        responseHook={ runBlocking { tasks.cancelIds(listOf(task.id)) }; responseHook=null }
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertNull(BookHelp.getContent(book,chapters[0]))
        assertEquals("cancelled",appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun changingTheSourceWhileAResponseIsInFlightRejectsItsBody()=runBlocking {
        val task=claim(0,0)
        responseHook={ source.bookSourceName="Changed source"; appDb.bookSourceDao.update(source); responseHook=null }
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertNull(BookHelp.getContent(book,chapters[0]))
        assertEquals("failed",appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun replacingTheChapterWhileAResponseIsInFlightPreservesTheNewMetadata()=runBlocking {
        val task=claim(0,0)
        responseHook={ appDb.bookChapterDao.update(chapters[0].copy(title="New chapter metadata")); responseHook=null }
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertNull(BookHelp.getContent(book,chapters[0]))
        assertEquals("New chapter metadata",appDb.bookChapterDao.getChapter(book.bookUrl,0)!!.title)
        assertEquals("failed",appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun deletingAndReimportingTheSameUrlDoesNotReviveAnInFlightDownload()=runBlocking {
        val task=claim(0,0)
        responseHook={ appDb.bookDao.delete(book); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray()); responseHook=null }
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertNull(BookHelp.getContent(book,chapters[0])); assertNull(appDb.bookTransferDao.lease(book.bookUrl,"download"))
        assertNotEquals("completed",appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun imageBodiesAreDownloadedAndAnIncompleteImageCacheIsRefetched()=runBlocking {
        includeImage=true; source.ruleContent!!.content="body@html"; appDb.bookSourceDao.update(source)
        assertTrue(BookHelp.saveContent(source,book,chapters[0],"<img src=\"${source.bookSourceUrl}/image.png\">"))
        assertFalse(BookHelp.hasImageContent(book,chapters[0]))
        val task=claim(0,0); runner.run(task)
        assertEquals("completed",appDb.bookTransferDao.get(task.id)!!.phase)
        assertTrue(requests.containsAll(listOf("/chapter/0","/image.png")))
        assertArrayEquals(imageBytes,BookHelp.getImage(book,"${source.bookSourceUrl}/image.png").readBytes())
        assertTrue(BookHelp.hasImageContent(book,chapters[0]))
    }
    @Test fun cancellingDuringAnImageResponseCannotPublishTheLateImage()=runBlocking {
        includeImage=true; source.ruleContent!!.content="body@html"; appDb.bookSourceDao.update(source)
        val task=claim(0,0)
        imageHook={ runBlocking { tasks.cancelIds(listOf(task.id)) }; imageHook=null }
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertTrue(requests.contains("/image.png")); assertFalse(BookHelp.getImage(book,"${source.bookSourceUrl}/image.png").exists())
        assertEquals("cancelled",appDb.bookTransferDao.get(task.id)!!.phase)
    }
}
