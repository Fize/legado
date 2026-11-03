package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.Book
import io.legado.app.data.cache.cacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.appDb
import io.legado.app.constant.BookType
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.repository.MangaReaderDataRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.model.MangaChapterDecoder
import io.legado.app.model.MangaReaderSession
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaSessionOwnershipTest {
    private val data = MangaReaderDataRepository()
    private fun book() = Book(bookUrl = "session://${UUID.randomUUID()}", name = "Session fixture")
    private fun chapter(book: Book, index: Int) = BookChapter(bookUrl = book.bookUrl,
        url = "${book.bookUrl}/$index", index = index, title = "Chapter $index")
    private fun decoded(chapter: BookChapter) = MangaChapter(chapter,
        (0..3).map { MangaPage(chapterIndex = chapter.index, chapterSize = 5,
            mImageUrl = "image:$it", index = it, imageCount = 4) }, 4)

    private suspend fun delayedDecode(change: suspend (MangaReaderSession) -> Unit): MangaReaderSession {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val session = MangaReaderSession(data, object : MangaChapterDecoder(data) {
            override suspend fun decode(chapter: BookChapter, content: String, owner: Book,
                hideTitle: Boolean, chapterSize: Int): MangaChapter {
                entered.complete(Unit); release.await()
                return decoded(chapter)
            }
        })
        val owner = book()
        session.book = owner; session.durChapterIndex = 1; session.durChapterPos = 2
        kotlinx.coroutines.coroutineScope {
            val result = async(Dispatchers.IO) { session.contentLoadFinish(chapter(owner, 1), "body") }
            try {
                withTimeout(5_000) { entered.await() }
                withContext(Dispatchers.Main.immediate) { change(session) }
            } finally { release.complete(Unit) }
            withTimeout(5_000) { result.await() }
        }
        return session
    }

    @Test fun pausedReadingRejectsChapterCallbacksAndRepeatedHandoffsUntilResumed() {
        val session = MangaReaderSession(data)
        val owner = book()
        val enabled = io.legado.app.help.config.AppConfig.enableReadRecord
        val repository = io.legado.app.data.repository.MangaReaderSessionRepository(session)
        fun recorded() = appDb.readRecordDao.getRecord(
            io.legado.app.constant.AppConst.androidId, owner.name, owner.author)?.readTime ?: 0L
        fun drain() { session.executor.submit {}.get(10, TimeUnit.SECONDS) }
        try {
            io.legado.app.help.config.AppConfig.enableReadRecord = true
            // Use a unique identity so cleanup cannot touch another fixture or user record.
            owner.name = "Paused reader ${UUID.randomUUID()}"
            session.book = owner
            repository.beginReading()
            session.readStartTime = System.currentTimeMillis() - 1000
            repository.pauseReading()
            drain()
            val before = recorded()
            assertTrue(before >= 1000)
            // Simulate a long details/background interval without sleeping.
            session.readStartTime = 1L
            repository.pauseReading()
            session.curPageChanged()
            repository.upReadTime()
            repository.beginRequest()
            drain()
            assertEquals(before, recorded())
            assertEquals(1L, session.readStartTime)
            session.book = owner
            repository.beginReading()
            assertTrue(session.readStartTime > 1L)
            session.readStartTime = System.currentTimeMillis() - 1000
            repository.pauseReading()
            drain()
            assertTrue(recorded() >= before + 1000)
        } finally {
            session.book = null
            repository.pauseReading()
            drain()
            appDb.readRecordDao.deleteByBook(owner.name, owner.author)
            io.legado.app.help.config.AppConfig.enableReadRecord = enabled
        }
    }

    @Test fun aChapterTurnDuringParsingPublishesIntoTheCurrentRelativeSlot() = runBlocking {
        val session = delayedDecode { it.durChapterIndex = 2; it.durChapterPos = 0 }
        assertNull(session.curMangaChapter)
        assertEquals(1, session.prevMangaChapter?.chapter?.index)
        assertEquals(0, session.durChapterPos)
    }

    @Test fun sameBookRefreshRejectsContentFromThePreviousGeneration() = runBlocking {
        val session = delayedDecode { it.clearMangaChapter() }
        assertNull(session.curMangaChapter); assertNull(session.prevMangaChapter)
        assertNull(session.nextMangaChapter)
    }

    @Test fun bookReplacementDuringParsingRejectsTheOldBook() = runBlocking {
        val replacement = book()
        val session = delayedDecode { it.book = replacement }
        assertEquals(replacement.bookUrl, session.book?.bookUrl)
        assertNull(session.curMangaChapter); assertNull(session.prevMangaChapter)
    }

    @Test fun leavingTheAdjacentRangeDuringParsingDoesNotPublishOrResetProgress() = runBlocking {
        val session = delayedDecode { it.durChapterIndex = 4; it.durChapterPos = 3 }
        assertNull(session.curMangaChapter); assertNull(session.prevMangaChapter)
        assertEquals(3, session.durChapterPos)
    }

    @Test fun readingContentDoesNotMutateTheStoredPosition() {
        val owner = book(); val session = MangaReaderSession(data)
        session.book = owner; session.durChapterPos = 3
        session.curMangaChapter = MangaChapter(chapter(owner, 0), emptyList(), 0)
        assertEquals(0, session.mangaContents.pos)
        assertEquals(3, session.durChapterPos)
    }

    @Test fun eachOwnerHasIndependentJobsAndClosingOneDoesNotCancelTheOther() {
        val parent = SupervisorJob(); val otherParent = SupervisorJob()
        val first = MangaReaderSession(data); val second = MangaReaderSession(data)
        first.bind(CoroutineScope(parent + Dispatchers.Main.immediate))
        second.bind(CoroutineScope(otherParent + Dispatchers.Main.immediate))
        val firstJob = first.coroutineContext[Job]!!; val secondJob = second.coroutineContext[Job]!!
        try {
            val callback = object : MangaReaderSession.Callback {
                override fun upContent() = Unit
                override fun loadFail(msg: String, retry: Boolean) = Unit
                override fun sureNewProgress(progress: BookProgress) = Unit
                override fun showLoading() = Unit
                override fun startLoad() = Unit
            }
            first.register(callback); first.unregister(callback)
            assertTrue(firstJob.isCancelled); assertTrue(secondJob.isActive); assertTrue(parent.isActive)
            otherParent.cancel()
            assertTrue(secondJob.isCancelled)
        } finally { parent.cancel(); otherParent.cancel() }
    }

    @Test fun closingTheOwnerCancelsABlockedRealNetworkLoadWithoutPublishingOrCachingIt() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                return newFixedLengthResponse("<img src=\"https://images.invalid/late.png\">")
            }
        }
        server.start()
        val source = BookSource(bookSourceUrl = "http://127.0.0.1:${server.listeningPort}",
            bookSourceName = "Owned request fixture", bookSourceType = BookSourceType.image)
        source.getContentRule().content = "@js:result"
        val owner = book().copy(origin = source.bookSourceUrl, type = BookType.image,
            totalChapterNum = 1, canUpdate = false)
        val chapter = chapter(owner, 0).copy(url = "${source.bookSourceUrl}/chapter")
        val parent = SupervisorJob()
        val session = MangaReaderSession(data)
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.defaultSharedPreferences
        val cronet = prefs.all[PreferKey.cronet] as? Boolean
        val callback = object : MangaReaderSession.Callback {
            override fun upContent() { callbacks.incrementAndGet() }
            override fun loadFail(msg: String, retry: Boolean) { callbacks.incrementAndGet() }
            override fun sureNewProgress(progress: BookProgress) = Unit
            override fun showLoading() = Unit
            override fun startLoad() = Unit
        }
        try {
            prefs.edit().putBoolean(PreferKey.cronet, false).commit()
            appDb.bookSourceDao.insert(source); appDb.bookDao.insert(owner); appDb.bookChapterDao.insert(chapter)
            session.bind(CoroutineScope(parent + Dispatchers.Main.immediate))
            session.register(callback); session.resetData(owner); session.loadContent()
            assertTrue("The actual HTTP load must start", entered.await(5, TimeUnit.SECONDS))
            val job = session.coroutineContext[Job]!!
            withContext(Dispatchers.Main.immediate) { session.unregister(callback) }
            withTimeout(5_000) { job.join() }
            release.countDown()
            assertTrue(job.isCancelled)
            assertEquals(0, callbacks.get())
            assertNull(session.curMangaChapter)
            assertNull(BookHelp.getContent(owner, chapter))
        } finally {
            parent.cancel(); release.countDown(); server.stop()
            BookHelp.clearCache(owner)
            appDb.bookChapterDao.delByBook(owner.bookUrl); appDb.bookDao.delete(owner); appDb.bookSourceDao.delete(source)
            prefs.edit().apply {
                if (cronet == null) remove(PreferKey.cronet) else putBoolean(PreferKey.cronet, cronet)
            }.commit()
        }
    }

    @Test fun theRealDecoderUsesTheCapturedBooksOfflineImageAndDimensions() = runBlocking {
        val owner = book(); val chapter = chapter(owner, 0)
        val url = "https://images.invalid/${UUID.randomUUID()}.png"
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        val path = BookHelp.getImage(owner, url)
        val lease = cacheResourceLeases().acquireBook(owner)
        try {
            val bytes = ByteArrayOutputStream().also { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }.toByteArray()
            BookHelp.writeImage(owner, url, bytes)
            val decoded = MangaChapterDecoder(data).decode(chapter, "<img src=\"$url\">", owner, true, 1)
            val page = decoded.pages.single() as MangaPage
            assertEquals(path.absolutePath, page.cachePath)
            assertEquals(160, page.imageWidth); assertEquals(240, page.imageHeight)
            assertEquals(path.absolutePath, mangaImagePath(page))
        } finally { bitmap.recycle(); BookHelp.clearCache(owner); lease.close() }
    }
}
