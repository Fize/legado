package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.archiveName
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.book.remoteimport.WebDavFixtureServer
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BookInfoUploadTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val databaseName = "upload-transfer-$id"
    private val file = File(context.cacheDir, "upload $id #漫画.txt")
    private val body = "Stable bytes $id 漫画".toByteArray()
    private val book = Book(bookUrl = file.path, origin = BookType.localTag, originName = file.name,
        name = "Transfer $id", author = "Author $id", type = BookType.local or BookType.text)
    private lateinit var database: AppDatabase
    private lateinit var store: BookInfoUploadPayloadStore
    private lateinit var server: WebDavFixtureServer
    private lateinit var parent: BookInfoRequest
    private var previousRemote: RemoteBookWebDav? = null
    private var previousCronet = false
    private val remote = AtomicReference<ByteArray?>(null)
    private val requests = CopyOnWriteArrayList<WebDavFixtureServer.Request>()
    private val puts get() = requests.filter { it.method == "PUT" }
    private val gets get() = requests.filter { it.method == "GET" }
    private fun repository(effects: BookInfoUploadEffects = BookInfoUploadEffects()) =
        BookInfoUploadRepository(database, store, effects, BookInfoDeleteAuthority(context))
    @Before fun setup() {
        previousCronet = context.defaultSharedPreferences.getBoolean(PreferKey.cronet, false)
        context.defaultSharedPreferences.edit().putBoolean(PreferKey.cronet, false).commit()
        server = WebDavFixtureServer { request ->
            requests += request
            when (request.method) {
                "PROPFIND" -> WebDavFixtureServer.Reply(207, "application/xml", "<d:multistatus xmlns:d=\"DAV:\"/>")
                "GET" -> remote.get()?.let { WebDavFixtureServer.Reply(200, "application/octet-stream", it) }
                    ?: WebDavFixtureServer.Reply(404, "text/plain", "missing")
                "PUT" -> if (request.headers["if-none-match"] == "*" && remote.get() != null)
                    WebDavFixtureServer.Reply(412, "text/plain", "conflict")
                    else { remote.set(request.body); WebDavFixtureServer.Reply(201, "text/plain", "created") }
                else -> WebDavFixtureServer.Reply(200, "text/plain", "")
            }
        }
        previousRemote = AppWebDav.defaultBookWebDav
        AppWebDav.defaultBookWebDav = RemoteBookWebDav("http://127.0.0.1:${server.listeningPort}/dav/", Authorization("user", "password"))
        file.writeBytes(body)
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).addLegadoMigrations().build()
        database.bookDao.insert(book)
        parent = database.bookInfoRequestDao.claim(BookInfoRequest("detail-$id", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
        store = BookInfoUploadPayloadStore(context, UUID.fromString(id)); requests.clear()
    }
    @After fun cleanup() {
        (database.bookInfoUploadDao.pending() + database.bookInfoUploadDao.terminal()).forEach { store.remove(it.payloadKey) }
        database.close(); context.deleteDatabase(databaseName); file.delete()
        File(context.noBackupFilesDir, "book-info-uploads/$id").delete()
        AppWebDav.defaultBookWebDav = previousRemote; server.stop()
        context.defaultSharedPreferences.edit().putBoolean(PreferKey.cronet, previousCronet).commit()
    }
    private suspend fun accept(repo: BookInfoUploadRepository, overwrite: Boolean = false) = repo.accept(parent.navigationOwner(),
        id, book, checkNotNull(BookInfoLibraryRepository(database).inspect(book.bookUrl).target), overwrite)
    private suspend fun interruptAfterRemoteCommit(overwrite: Boolean = false) {
        val repo = repository(object : BookInfoUploadEffects() {
            override suspend fun transfer(command: BookInfoUploadCommand, file: File, recovering: Boolean): String {
                super.transfer(command, file, recovering)
                throw CancellationException("Lost acknowledgement before Room checkpoint")
            }
        })
        accept(repo, overwrite)
        try { repo.execute(id); fail("Worker did not stop") } catch (_: CancellationException) { }
        assertEquals(BookInfoUploadPhase.Transferring, repo.get(id)!!.phase)
    }

    @Test fun snapshotUploadEncodesTheExactFilenameAndUsesConditionalPut() = runBlocking {
        val repo = repository(); accept(repo); file.writeText("Changed original")
        assertEquals(BookInfoUploadStatus.Completed, repo.execute(id)!!.status)
        assertEquals("/dav/${file.name}", puts.single().uri)
        assertEquals("*", puts.single().headers["if-none-match"])
        assertArrayEquals(body, puts.single().body)
        val saved = checkNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals(file.name, saved.copy(type = saved.type or BookType.archive).archiveName)
    }
    @Test fun lostAcknowledgementIsVerifiedWithoutRepeatingPut() = runBlocking {
        interruptAfterRemoteCommit(); file.delete()
        val repo = repository()
        assertEquals(BookInfoUploadStatus.Completed, repo.execute(id)!!.status)
        assertEquals(1, puts.size); assertEquals(1, gets.size)
        assertTrue(database.bookDao.getBook(book.bookUrl)!!.origin.startsWith(BookType.webDavTag))
    }
    @Test fun replayNeverOverwritesADifferentRemoteBodyWithoutNewConfirmation() = runBlocking {
        interruptAfterRemoteCommit(overwrite = true)
        val newer = "Another client wrote newer content".toByteArray(); remote.set(newer)
        val repo = repository()
        assertEquals(BookInfoUploadStatus.Failed, repo.execute(id)!!.status)
        assertEquals(BookInfoLibraryError.Conflict.name, repo.get(id)!!.error)
        assertArrayEquals(newer, remote.get()); assertEquals(1, puts.size)
        accept(repo, overwrite = true)
        assertEquals(BookInfoUploadStatus.Completed, repo.execute(id)!!.status)
        assertEquals(2, puts.size); assertNull(puts.last().headers["if-none-match"])
        assertArrayEquals(body, remote.get())
    }
    @Test fun recoveryFromThePrePutCheckpointCreatesOnlyIfTheRemoteIsStillAbsent() = runBlocking {
        val interrupted = repository(object : BookInfoUploadEffects() {
            override suspend fun transfer(command: BookInfoUploadCommand, file: File, recovering: Boolean): String {
                throw CancellationException("Stopped before HTTP")
            }
        })
        accept(interrupted, overwrite = true)
        try { interrupted.execute(id); fail("Worker did not stop") } catch (_: CancellationException) { }
        assertEquals(BookInfoUploadStatus.Completed, repository().execute(id)!!.status)
        assertEquals(1, gets.size); assertEquals("*", puts.single().headers["if-none-match"])
    }
    @Test fun aConditionalConflictWithIdenticalBytesCanFinishWithoutOverwriting() = runBlocking {
        remote.set(body)
        val repo = repository(); accept(repo)
        assertEquals(BookInfoUploadStatus.Completed, repo.execute(id)!!.status)
        assertEquals("*", puts.single().headers["if-none-match"]); assertEquals(1, gets.size)
    }
    @Test fun changedCredentialsInvalidateAQueuedUploadBeforeAnyPut() = runBlocking {
        val repo = repository(); accept(repo)
        AppWebDav.defaultBookWebDav = RemoteBookWebDav("http://127.0.0.1:${server.listeningPort}/dav/", Authorization("user", "changed"))
        assertEquals(BookInfoUploadStatus.Failed, repo.execute(id)!!.status)
        assertEquals(BookInfoLibraryError.Changed.name, repo.get(id)!!.error)
        assertTrue(puts.isEmpty()); assertEquals(book.origin, database.bookDao.getBook(book.bookUrl)!!.origin)
    }

    @Test fun localAndVpnTransfersDoNotRequirePublicInternetValidation() {
        val constraints = io.legado.app.work.bookInfoRemoteConstraints()
        if (android.os.Build.VERSION.SDK_INT < 28) {
            assertNull(constraints.requiredNetworkRequest)
            assertEquals(androidx.work.NetworkType.CONNECTED, constraints.requiredNetworkType)
            return
        }
        val request = checkNotNull(constraints.requiredNetworkRequest)
        assertTrue(request.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(request.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }
}
