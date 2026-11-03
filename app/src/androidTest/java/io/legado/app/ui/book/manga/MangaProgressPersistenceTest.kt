package io.legado.app.ui.book.manga

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.MangaReaderDataRepository
import io.legado.app.help.globalExecutor
import io.legado.app.model.MangaReaderSession
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaProgressPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun book() = Book(bookUrl = "progress://${UUID.randomUUID()}", origin = "source",
        name = "Original", author = "Author", durChapterTime = 100, durChapterTitle = "Old")

    @Test fun progressUpdatesPreserveMetadataAndRejectOlderOrDifferentSourceWrites() {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
        try {
            val dao = database.bookDao
            val book = book().copy(name = "Edited title", author = "Edited author", group = 32L)
            dao.insert(book)
            assertEquals(1, dao.updateReaderProgress(book.bookUrl, book.origin, 2, 3, "New chapter", 200))
            val saved = dao.getBook(book.bookUrl)!!
            assertEquals(book.name, saved.name); assertEquals(book.author, saved.author)
            assertEquals(book.group, saved.group)
            assertEquals(2, saved.durChapterIndex); assertEquals(3, saved.durChapterPos)
            assertEquals("New chapter", saved.durChapterTitle)
            assertEquals(0, dao.updateReaderProgress(book.bookUrl, book.origin, 0, 0, "Stale", 199))
            assertEquals(0, dao.updateReaderProgress(book.bookUrl, "old-source", 0, 0, "Stale", 300))
            assertEquals(saved.durChapterPos, dao.getBook(book.bookUrl)!!.durChapterPos)
            assertEquals(200L, dao.getBook(book.bookUrl)!!.durChapterTime)
        } finally { database.close() }
    }

    @Test fun aSyncAcknowledgementOnlyMarksTheExactUploadedProgressAndIdentity() {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
        try {
            val dao = database.bookDao
            val book = book().copy(durChapterIndex = 2, durChapterPos = 3)
            dao.insert(book)
            fun acknowledge(name: String = book.name, chapter: Int = 2, position: Int = 3, time: Long = 100) =
                dao.markReaderProgressSynced(book.bookUrl, book.origin, name, book.author,
                    chapter, position, time, 500)
            assertEquals(0, acknowledge(name = "Old name"))
            assertEquals(0, acknowledge(chapter = 1)); assertEquals(0, acknowledge(position = 2))
            assertEquals(0, acknowledge(time = 99))
            assertEquals(1, acknowledge())
            assertEquals(500L, dao.getBook(book.bookUrl)!!.syncTime)
            assertEquals(1, dao.updateReaderProgress(book.bookUrl, book.origin, 3, 1, "New", 200))
            assertEquals(0, acknowledge())
            assertEquals(3, dao.getBook(book.bookUrl)!!.durChapterIndex)
        } finally { database.close() }
    }

    @Test fun aQueuedProgressWriteCannotRecreateADeletedBook() {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
        try {
            val book = book(); database.bookDao.insert(book); database.bookDao.delete(book)
            assertEquals(0, database.bookDao.updateReaderProgress(book.bookUrl, book.origin, 1, 2, "New", 200))
            assertNull(database.bookDao.getBook(book.bookUrl))
        } finally { database.close() }
    }

    @Test fun aFutureStoredTimestampDoesNotPreventTheActiveSessionSavingProgress() {
        val future = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        val book = book().copy(durChapterTime = future)
        appDb.bookDao.insert(book)
        try {
            val session = MangaReaderSession(MangaReaderDataRepository())
            session.book = book; session.durChapterIndex = 2; session.durChapterPos = 3
            session.saveRead()
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            val saved = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(2, saved.durChapterIndex); assertEquals(3, saved.durChapterPos)
            assertEquals(future, saved.durChapterTime)
        } finally {
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            appDb.bookDao.delete(book)
        }
    }

    @Test fun queuedSessionWritesKeepTheirOriginalDurableOwnerAndNavigationRevision() {
        val book = book()
        val requestId = UUID.randomUUID().toString()
        appDb.bookDao.insert(book)
        val initial = io.legado.app.data.entities.ReaderSessionState(requestId, book.bookUrl, book.bookUrl,
            true, false, updatedAt = 1)
        appDb.readerSessionDao.claim(initial, "old")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val session = MangaReaderSession(MangaReaderDataRepository())
            session.book = book; session.durChapterIndex = 1; session.durChapterPos = 2
            session.progressOwner = io.legado.app.data.repository.ReaderProgressOwner(requestId, "old", 0)
            session.saveRead()
            appDb.readerSessionDao.claim(initial, "new")
            val moved = checkNotNull(appDb.readerSessionDao.moveBook(requestId, "new", book.bookUrl, 3, 1, 10))
            session.progressOwner = io.legado.app.data.repository.ReaderProgressOwner(requestId, "new", moved.revision)
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals(3, appDb.readerSessionDao.get(requestId)!!.chapterIndex)
            session.durChapterIndex = 3; session.durChapterPos = 1; session.saveRead()
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(3, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        } finally {
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            appDb.bookDao.delete(book)
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM reader_sessions WHERE id = ?", arrayOf(requestId))
        }
    }

    @Test fun cancellingAnInitializationQueuedBehindPersistenceCannotLeaveAnOwnerOrRequest() {
        val book = book()
        val requestId = UUID.randomUUID().toString()
        appDb.bookDao.insert(book)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            kotlinx.coroutines.runBlocking {
                val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    io.legado.app.data.repository.ReaderSessionRepository().claim(
                        io.legado.app.data.repository.ReaderOpenRequest(requestId, book.bookUrl), "cancelled")
                }
                job.cancel()
                job.join()
            }
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            assertNull(appDb.readerSessionDao.get(requestId))
            assertFalse(appDb.readerSessionDao.ownsBook(requestId, "cancelled", 0, book.bookUrl))
        } finally {
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            appDb.bookDao.delete(book)
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM reader_sessions WHERE id = ?", arrayOf(requestId))
        }
    }

    @Test fun queuedSessionWritesCaptureBothBookIdentityAndPositionBeforeEnqueue() {
        val first = book(); val second = book()
        appDb.bookDao.insert(first, second)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val session = MangaReaderSession(MangaReaderDataRepository())
            session.book = first; session.durChapterIndex = 1; session.durChapterPos = 2; session.saveRead()
            session.book = second; session.durChapterIndex = 3; session.durChapterPos = 1; session.saveRead()
            appDb.bookDao.insert(first.copy(name = "Edited while queued"))
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            val savedFirst = appDb.bookDao.getBook(first.bookUrl)!!
            val savedSecond = appDb.bookDao.getBook(second.bookUrl)!!
            assertEquals("Edited while queued", savedFirst.name)
            assertEquals(1, savedFirst.durChapterIndex); assertEquals(2, savedFirst.durChapterPos)
            assertEquals(3, savedSecond.durChapterIndex); assertEquals(1, savedSecond.durChapterPos)
        } finally {
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
            appDb.bookDao.delete(first, second)
        }
    }
}
