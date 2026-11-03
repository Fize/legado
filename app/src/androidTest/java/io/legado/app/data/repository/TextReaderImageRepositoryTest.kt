package io.legado.app.data.repository

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.cache.*
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.model.TextReaderChapterKey
import io.legado.app.model.TextReaderImage
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class TextReaderImageRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val root = File(context.cacheDir, "image-repository-$id")
    private val store = TextReaderImageStore(root, 1024L * 1024)
    private val resources = CacheResourceLeases()
    private val repository = TextReaderImageRepository(database, resources, store)
    private val source = BookSource("https://reader-image-$id.invalid", "Image fixture")
    private val book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl, name = id, author = id)
    private val owner = ReaderProgressOwner(id, "owner", 0)
    private val key = TextReaderChapterKey(owner, book.bookUrl, book.origin, 0, 1)
    private val first = png(0xff00bb22.toInt())
    private val second = png(0xff2244cc.toInt())
    private var legacyLease: java.io.Closeable? = null
    @Before fun setup() {
        legacyLease = cacheResourceLeases().acquireBook(book)
        database.bookSourceDao.insert(source); database.bookDao.insert(book)
        database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl,
            true, false, updatedAt = System.currentTimeMillis()), owner.token)
    }
    @After fun cleanup() {
        source.removeLoginHeader()
        BookHelp.clearCache(book)
        legacyLease?.close()
        store.clearAll(); root.deleteRecursively(); database.close()
    }
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        return try { bitmap.eraseColor(color); ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
        } } finally { bitmap.recycle() }
    }
    private fun files() = if (root.exists()) root.walkTopDown().count { it.isFile } else 0

    private class Server(val payload: AtomicReference<ByteArray>, gated: Boolean) : NanoHTTPD("127.0.0.1", 0) {
        val entered = CountDownLatch(1); val release = CountDownLatch(if (gated) 1 else 0)
        val count = AtomicInteger(); val headers = ConcurrentLinkedQueue<String>()
        override fun serve(session: IHTTPSession): Response {
            count.incrementAndGet(); headers += session.headers["x-reader-auth"].orEmpty(); entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            val bytes = payload.get()
            return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }
    private suspend fun server(gated: Boolean = false, block: suspend (Server, TextReaderImage) -> Unit) {
        val server = Server(AtomicReference(first), gated).apply { start() }
        try { block(server, TextReaderImage(0, "http://127.0.0.1:${server.listeningPort}/image.png")) }
        finally { server.release.countDown(); server.stop() }
    }

    @Test fun downloadedBytesAreCachedAtomicallyAndReusedByANewRepository() = runBlocking {
        server { server, image ->
            assertArrayEquals(first, repository.load(key, image))
            val restored = TextReaderImageRepository(database, resources, TextReaderImageStore(root, 1024L * 1024))
            assertArrayEquals(first, restored.load(key, image))
            assertEquals(1, server.count.get()); assertEquals(1, files())
            assertFalse(BookHelp.getImage(book, image.source).exists())
        }
    }
    @Test fun anExistingBookImageCacheCanBeReadWithoutANetworkRequest() = runBlocking {
        server { server, image ->
            BookHelp.writeImage(book, image.source, first)
            assertArrayEquals(first, repository.load(key, image))
            assertEquals(0, server.count.get())
        }
    }
    @Test fun forceRetryBypassesBothPrivateAndExistingBookCaches() = runBlocking {
        server { server, image ->
            BookHelp.writeImage(book, image.source, first)
            assertArrayEquals(first, repository.load(key, image))
            server.payload.set(second)
            assertArrayEquals(second, repository.load(key, image, force = true))
            assertArrayEquals(second, repository.load(key, image))
            assertEquals(1, server.count.get())
        }
    }
    @Test fun changedLoginHeadersUseAnotherCacheIdentityAndAreSentToTheServer() = runBlocking {
        server { server, image ->
            val sameSite = source.copy(bookSourceUrl = "http://127.0.0.1:${server.listeningPort}/source-$id")
            database.bookSourceDao.insert(sameSite); database.bookDao.update(book.copy(origin = sameSite.bookSourceUrl))
            val selected = key.copy(origin = sameSite.bookSourceUrl)
            try {
                sameSite.putLoginHeader("{\"X-Reader-Auth\":\"one\"}")
                assertArrayEquals(first, repository.load(selected, image))
                sameSite.putLoginHeader("{\"X-Reader-Auth\":\"two\"}"); server.payload.set(second)
                assertArrayEquals(second, repository.load(selected, image))
                assertEquals(listOf("one", "two"), server.headers.toList()); assertEquals(2, server.count.get())
            } finally { io.legado.app.help.CacheManager.delete("loginHeader_${sameSite.bookSourceUrl}") }
        }
    }
    @Test fun sourceLoginHeadersAreNotForwardedToAnUnrelatedImageHost() = runBlocking {
        server { server, image ->
            source.putLoginHeader("{\"X-Reader-Auth\":\"private-fixture\"}")
            assertArrayEquals(first, repository.load(key, image))
            assertEquals(listOf(""), server.headers.toList())
        }
    }
    @Test fun ownerHandoffDuringDownloadRejectsTheResultAndDoesNotPublishItsCache() = runBlocking {
        server(true) { server, image ->
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key, image) } }
            assertTrue(server.entered.await(15, TimeUnit.SECONDS))
            database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl,
                true, false, updatedAt = System.currentTimeMillis()), "replacement")
            server.release.countDown()
            assertEquals("Reader no longer owns this book", withTimeout(20_000) { pending.await() }.exceptionOrNull()?.message)
            assertEquals(0, files()); assertFalse(BookHelp.getImage(book, image.source).exists())
        }
    }
    @Test fun sourceEditsDuringDownloadRejectOldDecodeInputs() = runBlocking {
        server(true) { server, image ->
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key, image) } }
            assertTrue(server.entered.await(15, TimeUnit.SECONDS))
            database.bookSourceDao.insert(source.copy(header = "{\"X-Changed\":\"yes\"}"))
            server.release.countDown()
            assertEquals("Reader source changed", withTimeout(20_000) { pending.await() }.exceptionOrNull()?.message)
            assertEquals(0, files())
        }
    }
    @Test fun clearingTheImageStoreDuringDownloadCannotBeUndoneByItsLateWrite() = runBlocking {
        server(true) { server, image ->
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key, image) } }
            assertTrue(server.entered.await(15, TimeUnit.SECONDS)); store.clearBook(book.bookUrl)
            server.release.countDown()
            assertEquals("Reader image changed", withTimeout(20_000) { pending.await() }.exceptionOrNull()?.message)
            assertEquals(0, files())
        }
    }
    @Test fun explicitImageInvalidationRejectsAnOlderNetworkResponse() = runBlocking {
        server(true) { server, image ->
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key, image) } }
            assertTrue(server.entered.await(15, TimeUnit.SECONDS)); BookHelp.delImage(book, image.source)
            server.release.countDown()
            assertEquals("Reader image changed", withTimeout(20_000) { pending.await() }.exceptionOrNull()?.message)
            assertEquals(0, files())
        }
    }
    @Test fun aDownloadHoldsAndThenReturnsItsBookResourceLease() = runBlocking {
        server(true) { server, image ->
            val pending = async(Dispatchers.IO) { repository.load(key, image) }
            assertTrue(server.entered.await(15, TimeUnit.SECONDS))
            resources.plan(bookCacheKey(book)).use { plan ->
                assertFalse(plan.removeIfUnchanged { fail("Image resource is active") })
                server.release.countDown(); assertArrayEquals(first, withTimeout(20_000) { pending.await() })
                assertTrue(plan.removeIfUnchanged { })
            }
        }
    }
    @Test fun clearingAnExistingBookCacheAlsoRevokesTheNewPrivateImageCache() {
        val shared = textReaderImageStore()
        shared.open(book.bookUrl).use { lease ->
            assertTrue(shared.write(lease, "owned-fixture", first))
            BookHelp.clearCache(book)
            assertFalse(lease.isCurrent())
            shared.open(book.bookUrl).use { assertNull(shared.read(it, "owned-fixture")) }
        }
    }
    @Test fun deletingAnImagePhysicallyRemovesPrivateVariantsAndRevokesReaders() {
        assertPrivateCacheRevoked {
            BookHelp.delImage(book, source.bookSourceUrl + "/fixture.png")
        }
    }
    @Test fun overwritingAnImageRevokesPrivateVariantsAndPublishesNewBytes() {
        val src = source.bookSourceUrl + "/fixture.png"
        BookHelp.writeImage(book, src, first)
        assertPrivateCacheRevoked { BookHelp.writeImage(book, src, second) }
        assertArrayEquals(second, BookHelp.getImage(book, src).readBytes())
    }
    @Test fun committingImageResourcesRevokesPrivateVariantsAndPublishesNewBytes() {
        val src = source.bookSourceUrl + "/fixture.png"
        BookHelp.writeImage(book, src, first)
        val staging = BookHelp.resourceStagingDir(book)
        try {
            val replacement = File(staging, "fixture.png").apply { writeBytes(second) }
            assertPrivateCacheRevoked {
                BookHelp.commitResources(book, emptyList(), mapOf(src to replacement),
                    mapOf(src to BookHelp.imageSaveVersion(book, src)))
            }
            assertArrayEquals(second, BookHelp.getImage(book, src).readBytes())
        } finally { staging.deleteRecursively() }
    }
    private fun assertPrivateCacheRevoked(invalidate: () -> Unit) {
        val shared = textReaderImageStore()
        shared.open(book.bookUrl).use { lease ->
            assertTrue(shared.write(lease, "version-zero", first))
            invalidate()
            assertFalse(lease.isCurrent())
            shared.open(book.bookUrl).use { assertNull(shared.read(it, "version-zero")) }
            val restored = TextReaderImageStore(File(context.cacheDir, "text-reader-images"), 32L * 1024 * 1024)
            restored.open(book.bookUrl).use { assertNull(restored.read(it, "version-zero")) }
        }
    }
    @Test fun anUnavailablePrivateCacheDoesNotDiscardSuccessfullyDownloadedBytes() = runBlocking {
        server { server, image ->
            root.writeText("blocked fixture path")
            assertArrayEquals(first, repository.load(key, image))
            assertEquals(1, server.count.get()); assertEquals("blocked fixture path", root.readText())
        }
    }
    @Test fun anEmptyPrivateCacheEntryIsReloadedInsteadOfBeingShownAsAnImage() = runBlocking {
        server { server, image ->
            assertArrayEquals(first, repository.load(key, image))
            root.walkTopDown().single { it.isFile && it.extension == "img" }.writeBytes(byteArrayOf())
            server.payload.set(second)
            assertArrayEquals(second, repository.load(key, image))
            assertEquals(2, server.count.get())
        }
    }
}
