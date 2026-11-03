package io.legado.app.help.storage

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookType
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.service.AudioCacheService
import io.legado.app.model.AudioCacheKey
import io.legado.app.ui.AppActivity
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RestoreAudioCacheTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val normalRestore = NormalRestoreFixture(context, token)
    private val root = File(context.cacheDir, "audio-recovery-$token")
    private val tree get() = Uri.fromFile(root).toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val release = CountDownLatch(1)
    private val tail = CountDownLatch(1)
    private val firstStreamChunk = CountDownLatch(1)
    @Volatile private var mediaArrived = CountDownLatch(1)
    private val tocArrived = CountDownLatch(1)
    private val infoArrived = CountDownLatch(1)
    private val mediaRequests = AtomicInteger()
    @Volatile private var holdMedia = false
    @Volatile private var holdToc = false
    @Volatile private var holdInfo = false
    @Volatile private var stream = false
    private var server: NanoHTTPD? = null
    private lateinit var book: Book
    private lateinit var source: BookSource
    private lateinit var chapter: BookChapter
    private var seeded = false
    private var ownsRoot = false
    private var ownsBook = false
    private var ownsSource = false
    private val serviceJobs = mutableListOf<Job>()
    private var ownsJournal = false
    private var scenario: ActivityScenario<AppActivity>? = null
    private val prefs = context.defaultSharedPreferences
    private val previous = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.audioCacheTreeUri).associateWith { prefs.all[it] }
    private val privacy = LocalConfig.all["privacyPolicyOk"]
    private val wav = ByteBuffer.allocate(44 + 32768).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + 32768); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(32768)
        repeat(32768) { put((it % 127).toByte()) }
    }.array()

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(running())
        check(!root.exists()); check(root.mkdirs()); ownsRoot = true
        check(prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.audioCacheTreeUri, tree).commit())
        check(LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit())
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val base = "http://127.0.0.1:$listeningPort"
                return when (session.uri) {
                    "/media.wav", "/other.wav" -> {
                        mediaRequests.incrementAndGet(); mediaArrived.countDown()
                        if (holdMedia && !release.await(20, TimeUnit.SECONDS))
                            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                        val body = if (stream) stagedBody() else wav.inputStream()
                        newFixedLengthResponse(Response.Status.OK, "audio/wav", body, wav.size.toLong())
                    }
                    "/toc" -> {
                        tocArrived.countDown()
                        if (holdToc && !release.await(20, TimeUnit.SECONDS))
                            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                        newFixedLengthResponse("<a href='$base/media.wav'>Owned chapter</a>")
                    }
                    "/book/$token" -> {
                        infoArrived.countDown()
                        if (holdInfo && !release.await(20, TimeUnit.SECONDS))
                            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                        newFixedLengthResponse("<h1>Updated $token</h1><a href='$base/toc'>Toc</a>")
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
                }
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val base = "http://127.0.0.1:${server!!.listeningPort}"
        source = BookSource(bookSourceUrl = "$base/$token", bookSourceName = token, bookSourceType = BookSourceType.audio,
            ruleBookInfo = BookInfoRule(name = "h1@text", tocUrl = "a@href"),
            ruleToc = TocRule(chapterList = "a", chapterName = "text", chapterUrl = "href"))
        book = Book(bookUrl = "$base/book/$token", name = token, author = token, origin = source.bookSourceUrl,
            type = BookType.audio, tocUrl = "$base/toc", totalChapterNum = 1)
        chapter = BookChapter(bookUrl = book.bookUrl, url = "$base/media.wav", title = "Owned chapter", index = 0)
        assertNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)); assertNull(appDb.bookDao.getBook(book.bookUrl))
        appDb.bookSourceDao.insert(source); ownsSource = true
        appDb.bookDao.insert(book); ownsBook = true
        appDb.bookChapterDao.insert(chapter)
        seeded = true
        scenario = ActivityScenario.launch(Intent(context, AppActivity::class.java))
    }

    @After fun cleanup() = runCleanupSteps(
        {
            release.countDown(); tail.countDown(); scope.cancel()
            if (seeded) {
                instrumentation.runOnMainSync { trackWorkerIfPresent(); AudioCacheService.stop(context) }
                await { !running() }
            }
        },
        { runBlocking { serviceJobs.forEach { it.join() }; scope.coroutineContext[Job]?.join() }; server?.stop() },
        { normalRestore.cleanup() },
        {
            if (ownsJournal) runBlocking { backupRestoreMutex.recover {
                RestoreFileRecovery.recover(context, cleanup = true); RestoreRecoveryGate.onStartupReady()
            } }
        },
        {
            if (ownsBook) { appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book) }
            if (ownsSource) appDb.bookSourceDao.delete(source)
        },
        {
            check(prefs.edit().apply { previous.forEach { (key, value) -> when (value) {
                null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
                else -> error("Unexpected owned preference type")
            } } }.commit())
            check(LocalConfig.edit().apply {
                if (privacy == null) remove("privacyPolicyOk") else putBoolean("privacyPolicyOk", privacy as Boolean)
            }.commit())
        },
        {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<RestoreRecoveryActivity>().forEach(Activity::finish)
            }
            scenario?.close(); if (ownsRoot) check(root.deleteRecursively() || !root.exists())
        },
    )

    @Test fun quarantinedStartDoesNotAcceptAnotherCacheTask() {
        quarantine()
        assertFalse(AudioCacheService.start(context, book.bookUrl, 0, 0))
        assertEquals(0, mediaRequests.get())
    }

    @Test fun coldForegroundStartDoesNotDownloadOrPublishWhileQuarantined() {
        quarantine()
        context.startForegroundService(Intent(context, AudioCacheService::class.java).apply {
            action = "audioCacheRange"; putExtra("bookUrl", book.bookUrl); putExtra("start", 0); putExtra("end", 0)
        })
        await { !running() }; SystemClock.sleep(300)
        Log.i("RestoreAudioCache", "cold requests=${mediaRequests.get()} files=${files()}")
        assertEquals(0, mediaRequests.get()); assertTrue(files().isEmpty())
    }

    @Test fun managerRejectsDownloadBeforeNetworkWhenQuarantined() {
        quarantine()
        val failure = runCatching { cache() }.exceptionOrNull()
        Log.i("RestoreAudioCache", "entry failure=$failure requests=${mediaRequests.get()} files=${files()}")
        assertTrue(failure is CancellationException); assertEquals(0, mediaRequests.get()); assertTrue(files().isEmpty())
    }

    @Test fun quarantineDoesNotDeleteThePreviouslyCommittedAudio() {
        cache(); val before = files()
        quarantine()
        val removed = runBlocking { AudioCacheManager.removeCachedChapter(tree, book.bookUrl, chapter) }
        Log.i("RestoreAudioCache", "delete blocked=$removed before=$before after=${files()}")
        assertFalse(removed); assertEquals(before, files())
    }

    @Test fun lateManagerResponseCannotReplaceThePreviousAudio() {
        val old = cache(); val before = files()
        mediaArrived = CountDownLatch(1); holdMedia = true
        val work = scope.async { AudioCacheManager.cacheChapter(tree, source, book, chapter).getOrThrow() }
        assertTrue(mediaArrived.await(10, TimeUnit.SECONDS))
        quarantine(); release.countDown()
        val failure = runCatching { runBlocking { work.await() } }.exceptionOrNull()
        Log.i("RestoreAudioCache", "late failure=$failure before=$before after=${files()}")
        assertTrue(failure is CancellationException); assertEquals(before, files())
        assertEquals(old, AudioCacheManager.getCachedUriString(tree, book.bookUrl, chapter))
    }

    @Test fun quarantineStopsFurtherStreamWritesBeforeTheFinalCommit() {
        cache(); val committed = files(); stream = true
        val work = scope.async { AudioCacheManager.cacheChapter(tree, source, book, chapter).getOrThrow() }
        assertTrue(firstStreamChunk.await(10, TimeUnit.SECONDS))
        await { root.walkTopDown().any { it.isFile && it.name.endsWith(".part") && it.length() > 0 } }
        val partial = root.walkTopDown().single { it.isFile && it.name.endsWith(".part") }
        val firstSize = partial.length()
        quarantine()
        val beforeSize = partial.length()
        release.countDown()
        // The server withholds the third chunk: commit-only guarding must not pass this check.
        await { work.isCompleted || partial.length() > beforeSize }
        Log.i("RestoreAudioCache", "stream firstBytes=$firstSize quarantinedBytes=$beforeSize afterBytes=${partial.length()} completed=${work.isCompleted}")
        assertTrue("Staging received bytes after quarantine", partial.length() <= beforeSize)
        assertTrue(work.isCompleted)
        val failure = runCatching { runBlocking { work.await() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(committed, files())
    }

    @Test fun lateTableOfContentsCannotPopulateTheChapterTable() {
        appDb.bookChapterDao.delByBook(book.bookUrl); holdToc = true
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0))
        assertTrue(tocArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        quarantineBeforeTheMainCollectorCanCancel()
        Log.i("RestoreAudioCache", "toc chapters=${appDb.bookChapterDao.getChapterCount(book.bookUrl)}")
        assertEquals(0, appDb.bookChapterDao.getChapterCount(book.bookUrl)); assertEquals(0, mediaRequests.get())
    }

    @Test fun lateBookInfoCannotReplaceThePersistedBook() {
        appDb.bookChapterDao.delByBook(book.bookUrl); book.tocUrl = ""; appDb.bookDao.insert(book); holdInfo = true
        val before = appDb.bookDao.getBook(book.bookUrl)!!
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0))
        assertTrue(infoArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        quarantineBeforeTheMainCollectorCanCancel()
        val after = appDb.bookDao.getBook(book.bookUrl)!!
        Log.i("RestoreAudioCache", "info before=${before.name}/${before.tocUrl} after=${after.name}/${after.tocUrl}")
        assertEquals(before.name, after.name); assertEquals(before.tocUrl, after.tocUrl)
        assertEquals(0, appDb.bookChapterDao.getChapterCount(book.bookUrl))
    }

    @Test fun readyServicePublishesTheExactAudioAndMarker() {
        appDb.bookChapterDao.delByBook(book.bookUrl); book.tocUrl = ""; appDb.bookDao.insert(book)
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0))
        assertTrue(mediaArrived.await(10, TimeUnit.SECONDS)); await { !running() }
        assertEquals(token, appDb.bookDao.getBook(book.bookUrl)!!.name)
        assertEquals(chapter.url.replace("media.wav", "toc"), appDb.bookDao.getBook(book.bookUrl)!!.tocUrl)
        assertEquals(1, appDb.bookChapterDao.getChapterCount(book.bookUrl))
        val storedChapter = checkNotNull(appDb.bookChapterDao.getChapter(book.bookUrl, 0))
        assertEquals(chapter.url, storedChapter.url)
        val uri = checkNotNull(AudioCacheManager.getCachedUriString(tree, book.bookUrl, storedChapter))
        assertArrayEquals(wav, File(Uri.parse(uri).path!!).readBytes())
        assertEquals(chapter.url, AudioCacheManager.getCachedAudio(tree, book.bookUrl, storedChapter)?.playUrl)
        assertEquals(2, files().size)
    }

    @Test fun quarantineDiscardsTheQueuedChapterTask() {
        holdMedia = true
        appDb.bookChapterDao.insert(chapter.copy(index = 1, url = chapter.url.replace("media.wav", "other.wav")))
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0))
        assertTrue(mediaArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        assertTrue(AudioCacheService.start(context, book.bookUrl, 1, 1))
        instrumentation.waitForIdleSync()
        quarantine(); release.countDown(); await { !running() }
        runBlocking { serviceJobs.forEach { it.join() } }
        Log.i("RestoreAudioCache", "queued task requests=${mediaRequests.get()} files=${files()}")
        assertEquals(1, mediaRequests.get()); assertTrue(files().isEmpty())
    }

    @Test fun replacementAndRemovalWaitForTheRestoreLock() = runBlocking {
        val old = cache()
        val stale = File(File(Uri.parse(old).path!!).parentFile!!, "tmp_${AudioCacheKey.from(chapter).value}_owned.part")
        stale.writeText(token); check(stale.setLastModified(System.currentTimeMillis() - 7200000))
        val current = cache(); assertNotEquals(old, current); assertFalse(stale.exists())
        assertArrayEquals(wav, File(Uri.parse(current).path!!).readBytes()); assertEquals(2, files().size)
        var work: Deferred<Boolean>? = null
        backupRestoreMutex.recover {
            work = scope.async(start = CoroutineStart.UNDISPATCHED) {
                AudioCacheManager.removeCachedChapter(tree, book.bookUrl, chapter)
            }
            Log.i("RestoreAudioCache", "delete while restore lock held completed=${work!!.isCompleted} files=${files()}")
            assertFalse(work!!.isCompleted); assertEquals(2, files().size)
        }
        assertTrue(work!!.await()); assertTrue(files().isEmpty())
    }

    private fun cache(): String = runBlocking { AudioCacheManager.cacheChapter(tree, source, book, chapter).getOrThrow() }

    @Test fun successfulRestoreRejectsTheLateManagerResultAndAllowsANewRequest() = runBlocking {
        val old = cache(); val before = files(); mediaArrived = CountDownLatch(1); holdMedia = true
        val work = scope.async { AudioCacheManager.cacheChapter(tree, source, book, chapter).getOrThrow() }
        assertTrue(mediaArrived.await(10, TimeUnit.SECONDS)); restoreOwnedBook(); release.countDown()
        val failure = runCatching { runBlocking { work.await() } }.exceptionOrNull()
        Log.i("RestoreGeneration", "manager failure=$failure before=$before after=${files()}")
        assertTrue(failure is CancellationException); assertEquals(before, files())
        assertEquals("Restored $token", appDb.bookDao.getBook(book.bookUrl)!!.name)
        holdMedia = false
        val current = runBlocking { AudioCacheManager.cacheChapter(tree, source,
            appDb.bookDao.getBook(book.bookUrl)!!, chapter).getOrThrow() }
        assertNotEquals(old, current); assertArrayEquals(wav, File(Uri.parse(current).path!!).readBytes())
    }

    @Test fun successfulRestoreStopsTheNextStreamWrite() = runBlocking {
        cache(); val committed = files(); stream = true
        val work = scope.async { AudioCacheManager.cacheChapter(tree, source, book, chapter).getOrThrow() }
        assertTrue(firstStreamChunk.await(10, TimeUnit.SECONDS))
        await { root.walkTopDown().any { it.isFile && it.name.endsWith(".part") && it.length() > 0 } }
        val partial = root.walkTopDown().single { it.isFile && it.name.endsWith(".part") }
        restoreOwnedBook(); val size = partial.length(); release.countDown()
        await { work.isCompleted || partial.length() > size }
        Log.i("RestoreGeneration", "stream before=$size after=${partial.length()} completed=${work.isCompleted}")
        assertTrue(partial.length() <= size); assertTrue(work.isCompleted)
        assertTrue(runCatching { runBlocking { work.await() } }.exceptionOrNull() is CancellationException)
        assertEquals(committed, files())
    }

    @Test fun successfulRestoreRejectsTheLateBookInfo() = runBlocking {
        appDb.bookChapterDao.delByBook(book.bookUrl); book.tocUrl = ""; appDb.bookDao.insert(book); holdInfo = true
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0)); assertTrue(infoArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        restoreOwnedBook(); release.countDown(); await { !running() }; runBlocking { serviceJobs.forEach { it.join() } }
        val after = appDb.bookDao.getBook(book.bookUrl)!!
        Log.i("RestoreGeneration", "late info name=${after.name} toc=${after.tocUrl}")
        assertEquals("Restored $token", after.name); assertEquals("", after.tocUrl)
        assertEquals(0, appDb.bookChapterDao.getChapterCount(book.bookUrl))
    }

    @Test fun successfulRestoreRejectsTheLateChapterTable() = runBlocking {
        appDb.bookChapterDao.delByBook(book.bookUrl); holdToc = true
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0)); assertTrue(tocArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        restoreOwnedBook(); release.countDown(); await { !running() }; runBlocking { serviceJobs.forEach { it.join() } }
        Log.i("RestoreGeneration", "late toc chapters=${appDb.bookChapterDao.getChapterCount(book.bookUrl)}")
        assertEquals(0, appDb.bookChapterDao.getChapterCount(book.bookUrl)); assertEquals(0, mediaRequests.get())
    }

    @Test fun removalQueuedBehindARealRestoreDoesNotDeleteOldAudio() = runBlocking {
        cache(); val before = files(); var restored: Deferred<Unit>? = null; var removed: Deferred<Boolean>? = null
        backupRestoreMutex.recover {
            restored = scope.async(start = CoroutineStart.UNDISPATCHED) { restoreOwnedBook() }
            removed = scope.async(start = CoroutineStart.UNDISPATCHED) {
                AudioCacheManager.removeCachedChapter(tree, book.bookUrl, chapter)
            }
            assertFalse(restored!!.isCompleted); assertFalse(removed!!.isCompleted)
        }
        restored!!.await(); val value = removed!!.await()
        Log.i("RestoreGeneration", "queued delete result=$value before=$before after=${files()}")
        assertFalse(value); assertEquals(before, files())
    }

    @Test fun successfulRestoreDropsTheAlreadyQueuedAudioTask() = runBlocking {
        holdMedia = true
        appDb.bookChapterDao.insert(chapter.copy(index = 1, url = chapter.url.replace("media.wav", "other.wav")))
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0)); assertTrue(mediaArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        assertTrue(AudioCacheService.start(context, book.bookUrl, 1, 1)); instrumentation.waitForIdleSync()
        restoreOwnedBook(); release.countDown(); await { !running() }; runBlocking { serviceJobs.forEach { it.join() } }
        Log.i("RestoreGeneration", "old queue requests=${mediaRequests.get()} files=${files()}")
        assertEquals(1, mediaRequests.get()); assertTrue(files().isEmpty())
    }

    @Test fun aNewServiceRequestCanReplaceTheStillWaitingOldRequest() = runBlocking {
        holdMedia = true
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0)); assertTrue(mediaArrived.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { trackWorkerIfPresent() }
        restoreOwnedBook(); holdMedia = false
        assertTrue(AudioCacheService.start(context, book.bookUrl, 0, 0))
        await { mediaRequests.get() >= 2 && AudioCacheManager.getCachedUriString(tree, book.bookUrl, chapter) != null && !running() }
        val uri = checkNotNull(AudioCacheManager.getCachedUriString(tree, book.bookUrl, chapter))
        assertArrayEquals(wav, File(Uri.parse(uri).path!!).readBytes()); assertEquals(2, files().size)
    }

    @Test fun anInvalidPreflightDoesNotInvalidateAnExistingDownload() = runBlocking {
        holdMedia = true
        val work = scope.async { AudioCacheManager.cacheChapter(tree, source, book, chapter).getOrThrow() }
        assertTrue(mediaArrived.await(10, TimeUnit.SECONDS))
        assertTrue(runCatching { runBlocking { normalRestore.restore(mapOf("bookshelf.json" to "[{")) } }.isFailure)
        assertFalse(RestoreRecoveryGate.isQuarantined); release.countDown()
        val uri = runBlocking { work.await() }; assertArrayEquals(wav, File(Uri.parse(uri).path!!).readBytes())
    }

    private suspend fun restoreOwnedBook() {
        val restored = appDb.bookDao.getBook(book.bookUrl)!!.copy(name = "Restored $token", durChapterPos = 77)
        normalRestore.restore(mapOf("bookshelf.json" to GSON.toJson(listOf(restored))))
        assertEquals("Restored $token", appDb.bookDao.getBook(book.bookUrl)!!.name)
        assertEquals(77, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    private fun quarantineBeforeTheMainCollectorCanCancel() {
        val completed = CountDownLatch(1)
        val cause = AtomicReference<Throwable?>()
        serviceJobs.last().invokeOnCompletion { cause.set(it); completed.countDown() }
        instrumentation.runOnMainSync {
            runBlocking(Dispatchers.IO) { quarantine() }
            release.countDown()
            assertTrue("Audio worker did not finish while Main was occupied", completed.await(3, TimeUnit.SECONDS))
            Log.i("RestoreAudioCache", "worker causes=${generateSequence(cause.get()) { it.cause }.map { it.javaClass.name }.toList()}")
            assertTrue(cause.get() is CancellationException)
            assertTrue(generateSequence(cause.get()) { it.cause }.any { it is RestoreRecoveryRequiredException })
        }
        await { !running() }; runBlocking { serviceJobs.forEach { it.join() } }
    }
    @Suppress("UNCHECKED_CAST")
    private fun trackWorkerIfPresent() {
        val services = LifecycleHelp::class.java.getDeclaredField("services").apply { isAccessible = true }
            .get(LifecycleHelp) as List<WeakReference<*>>
        services.mapNotNull { it.get() }.filterIsInstance<AudioCacheService>().forEach {
            (AudioCacheService::class.java.getDeclaredField("workerJob").apply { isAccessible = true }.get(it) as? Job)
                ?.let(serviceJobs::add)
        }
    }
    private fun files(): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }
    private fun quarantine() = runBlocking { backupRestoreMutex.recover {
        val pending = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false); ownsJournal = true
        check(pending.editDefault().putString("audio-cache-$token", "pending").commit()); RestoreRecoveryGate.requireRecovery()
    } }
    @Suppress("DEPRECATION")
    private fun running(): Boolean = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == AudioCacheService::class.java.name }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < until) { "Owned audio fixture did not reach the expected state" }; SystemClock.sleep(25) }
    }
    private fun stagedBody(): InputStream = object : InputStream() {
        private var offset = 0
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset >= 8192) { firstStreamChunk.countDown(); if (!release.await(20, TimeUnit.SECONDS)) throw IOException("fixture first gate timeout") }
            if (offset >= 16384 && !tail.await(20, TimeUnit.SECONDS)) throw IOException("fixture tail gate timeout")
            if (offset >= wav.size) return -1
            val count = minOf(length, 8192, wav.size - offset); wav.copyInto(buffer, start, offset, offset + count); offset += count
            return count
        }
    }
}
