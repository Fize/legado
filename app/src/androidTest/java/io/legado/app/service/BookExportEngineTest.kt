package io.legado.app.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.legado.app.help.book.BookHelp
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.*
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BookExportEngineTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val directory=File(context.cacheDir,"export-engine-${UUID.randomUUID()}").apply { mkdirs() }
    private fun book()=Book(bookUrl="https://example.invalid/book",origin="https://example.invalid",name="EngineFixture",author="Author").also {
        db.bookDao.insert(it)
        db.bookChapterDao.insert(BookChapter(bookUrl=it.bookUrl,url="volume",title="Volume",isVolume=true))
    }
    private fun options(format: Int=0)=CacheExportOptions(format=format,directory=directory.path,fileName="'result'")
    @After fun cleanup() { db.close(); directory.deleteRecursively() }

    @Test fun txtExportReplacesTheOldFileOnlyAfterWritingACompleteSnapshot()=runBlocking(Dispatchers.IO) {
        val book=book(); val target=File(directory,"result.txt"); target.writeText("OLD")
        val progress=mutableListOf<Int>()
        BookExportEngine(context,db,options(),{ value,_ -> progress.add(value) },{}).export(book)
        assertTrue(target.readText().contains("EngineFixture")); assertFalse(target.readText().contains("OLD"))
        assertTrue(progress.isNotEmpty()); assertEquals(listOf("result.txt"),directory.list()!!.toList())
    }
    @Test fun cancellationWhileRenderingPreservesThePreviousTxtAndRemovesStaging()=runBlocking(Dispatchers.IO) {
        val book=book(); val target=File(directory,"result.txt"); target.writeText("OLD")
        var cancelled=false
        val result=runCatching {
            BookExportEngine(context,db,options(),{ _,_ -> cancelled=true },{ check(!cancelled) }).export(book)
        }
        assertTrue(result.isFailure); assertEquals("OLD",target.readText())
        assertEquals(listOf("result.txt"),directory.list()!!.toList())
    }
    @Test fun cancellationBeforePublicationCannotDestroyAnExistingEpub()=runBlocking(Dispatchers.IO) {
        val book=book(); var cancelled=false
        val target=File(directory,"result.epub"); target.writeText("OLD")
        assertTrue(runCatching { BookExportEngine(context,db,options(1),{ _,_ -> cancelled=true },{ check(!cancelled) }).export(book) }.isFailure)
        assertEquals("OLD",target.readText()); assertEquals(listOf("result.epub"),directory.list()!!.toList())
    }
    @Test fun epubExportProducesAReadableArchiveAndKeepsNoStagingFiles()=runBlocking(Dispatchers.IO) {
        BookExportEngine(context,db,options(1),{ _,_ -> },{}).export(book())
        ZipFile(File(directory,"result.epub")).use {
            assertEquals("application/epub+zip",it.getInputStream(it.getEntry("mimetype")).reader().readText())
            assertNotNull(it.getEntry("META-INF/container.xml"))
        }
        assertEquals(listOf("result.epub"),directory.list()!!.toList())
    }
    @Test fun pdfExportProducesARealDocumentAndReplacesTheOldFile()=runBlocking(Dispatchers.IO) {
        File(directory,"result.pdf").writeText("OLD")
        BookExportEngine(context,db,options(2),{ _,_ -> },{}).export(book())
        val output=File(directory,"result.pdf")
        assertTrue(output.inputStream().bufferedReader().use { it.readLine() }.startsWith("%PDF-"))
        PdfRenderer(ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertTrue(renderer.pageCount>0)
            renderer.openPage(0).use { page ->
                val bitmap=Bitmap.createBitmap(256,362,Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels=IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    assertTrue(pixels.any { Color.alpha(it)>0 && it!=Color.WHITE })
                } finally { bitmap.recycle() }
            }
        }
        assertEquals(listOf("result.pdf"),directory.list()!!.toList())
    }
    @Test fun actualCachedChapterTextAndTitleReachTxtAndEpubWithoutChangingTheDatabase()=runBlocking(Dispatchers.IO) {
        val book=Book(bookUrl="export-content:${UUID.randomUUID()}",origin="fixture-source",name="Cached fixture",author="Author")
        val chapter=BookChapter(bookUrl=book.bookUrl,url="fixture-chapter",title="Chapter one",isVip=true)
        db.bookDao.insert(book); db.bookChapterDao.insert(chapter)
        try {
            assertTrue(BookHelp.saveContent(BookSource(bookSourceUrl=book.origin,bookSourceName="Fixture"),book,chapter,"Alpha chapter body 中文正文"))
            BookExportEngine(context,db,options(),{ _,_ -> },{}).export(book.copy())
            val txt=File(directory,"result.txt").readText()
            assertTrue(txt.contains("Chapter one")); assertTrue(txt.contains("Alpha chapter body 中文正文"))
            BookExportEngine(context,db,options(1),{ _,_ -> },{}).export(book.copy())
            ZipFile(File(directory,"result.epub")).use { zip ->
                val html=zip.entries().asSequence().filter { it.name.endsWith(".html") || it.name.endsWith(".xhtml") }
                    .joinToString("\n") { zip.getInputStream(it).reader(Charsets.UTF_8).use { input -> input.readText() } }
                val text=org.jsoup.Jsoup.parse(html).text()
                assertTrue("EPUB contains cached chapter body: $text",text.contains("Alpha chapter body 中文正文"))
                assertTrue("EPUB contains rendered chapter title: $text",text.contains("Chapter one"))
            }
            assertTrue(db.bookChapterDao.getChapter(book.bookUrl,0)!!.isVip)
        } finally { BookHelp.clearCache(book) }
    }
    @Test fun remoteUploadFailureIsReportedAfterTheLocalExportAndUsesTheCapturedName()=runBlocking(Dispatchers.IO) {
        var filename=""
        val result=runCatching { BookExportEngine(context,db,options().copy(webDav=true),{ _,_ -> },{},
            { uri,name -> filename=name; assertEquals("result.txt",uri.lastPathSegment); error("Upload rejected") }).export(book()) }
        assertTrue(result.isFailure); assertEquals("result.txt",filename)
        assertTrue(File(directory,"result.txt").readText().contains("EngineFixture"))
    }
    @Test fun aChangedDirectoryDuringRenderingCannotReplaceThePreviousExport()=runBlocking(Dispatchers.IO) {
        val book=book(); val target=File(directory,"result.txt").apply { writeText("OLD") }
        val result=runCatching {
            BookExportEngine(context,db,options(),{ _,_ ->
                val chapter=db.bookChapterDao.getChapter(book.bookUrl,0)!!
                db.bookChapterDao.update(chapter.copy(title="Changed directory"))
            },{}).export(book)
        }
        assertTrue(result.isFailure); assertEquals("OLD",target.readText())
        assertEquals(listOf("result.txt"),directory.list()!!.toList())
    }
    @Test fun cancellingAtTheFinalPublicationBoundaryRestoresTheOriginalFile()=runBlocking(Dispatchers.IO) {
        val book=book(); val target=File(directory,"result.txt").apply { writeText("OLD") }
        val result=runCatching {
            BookExportEngine(context,db,options(),{ _,_ -> },{},publication={ error("Ownership revoked") }).export(book)
        }
        assertTrue(result.isFailure); assertEquals("OLD",target.readText())
        assertEquals(listOf("result.txt"),directory.list()!!.toList())
    }
    @Test fun aLocalFileChangedDuringExportKeepsThePreviousOutputAndCanBeReparsedOnRetry()=runBlocking(Dispatchers.IO) {
        val input=File(directory,"source-${UUID.randomUUID()}.txt").apply { writeText("第1章 开始\nOriginal local body\n") }
        val local=Book(bookUrl=input.path,origin=BookType.localTag,originName=input.name,name="Local change fixture",author="Author",
            type=BookType.local or BookType.text,latestChapterTime=0)
        db.bookDao.insert(local)
        val output=File(directory,"result.txt").apply { writeText("PREVIOUS") }
        var changed=false
        val result=runCatching {
            BookExportEngine(context,db,options(),{ _,_ ->
                if(!changed) {
                    changed=true
                    val previous=input.lastModified()
                    input.writeText("第1章 修改\nChanged local body\n")
                    assertTrue(input.setLastModified(previous+1000))
                }
            },{}).export(local)
        }
        assertTrue(result.isFailure); assertEquals("PREVIOUS",output.readText())
        assertTrue(directory.list()!!.none { it.contains(".pending.") || it.contains(".previous.") })
        val current=db.bookDao.getBook(local.bookUrl)!!
        assertTrue(current.latestChapterTime<input.lastModified())
        BookExportEngine(context,db,options(),{ _,_ -> },{}).export(current)
        assertTrue(output.readText().contains("Changed local body"))
        assertFalse(output.readText().contains("Original local body"))
        assertEquals(input.lastModified(),db.bookDao.getBook(local.bookUrl)!!.latestChapterTime)
    }
    @Test fun invalidSplitRangesAndDuplicatePartNamesCannotReplaceExistingFiles()=runBlocking(Dispatchers.IO) {
        val book=book()
        db.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url="volume-two",title="Second volume",index=1,isVolume=true))
        val original=File(directory,"part.epub").apply { writeText("OLD") }
        val custom=options(1).copy(episodeName="'part'")
        assertTrue(runCatching { BookExportEngine(context,db,custom,{ _,_ -> },{}).export(book,"1-99999999999999999",1) }.isFailure)
        assertTrue(runCatching { BookExportEngine(context,db,custom,{ _,_ -> },{}).export(book,"1-2",1) }.isFailure)
        assertEquals("OLD",original.readText()); assertEquals(listOf("part.epub"),directory.list()!!.toList())
    }
    @Test fun splitEpubExportCreatesEveryRequestedPartAsAReadableArchive()=runBlocking(Dispatchers.IO) {
        val book=book()
        db.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url="volume-two",title="Second volume",index=1,isVolume=true))
        val custom=options(1).copy(episodeName="'part-' + epubIndex")
        BookExportEngine(context,db,custom,{ _,_ -> },{}).export(book,"1-2",1)
        assertEquals(setOf("part-1.epub","part-2.epub"),directory.list()!!.toSet())
        directory.listFiles()!!.forEach { file -> ZipFile(file).use { assertNotNull(it.getEntry("META-INF/container.xml")) } }
    }
    @Test fun remoteTargetIdentityChangesWithCredentialsOrDirectoryButIsDeterministic() {
        val values=mapOf(PreferKey.webDavUrl to "https://example.invalid/dav",PreferKey.webDavDir to "Legado",
            PreferKey.webDavAccount to "account",PreferKey.webDavPassword to "secret")
        val target=BookExportWebDavTarget.from(values)
        assertEquals(target.key,BookExportWebDavTarget.from(values).key)
        assertNotEquals(target.key,BookExportWebDavTarget.from(values+(PreferKey.webDavPassword to "other")).key)
        assertNotEquals(target.key,BookExportWebDavTarget.from(values+(PreferKey.webDavDir to "Other")).key)
        assertFalse(target.key.contains("secret"))
        assertTrue(runCatching { BookExportWebDavTarget.from(values+(PreferKey.webDavDir to "../other")) }.isFailure)
    }
}
