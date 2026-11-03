package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoUploadCommandTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val databaseName = "upload-command-$id"
    private lateinit var database: AppDatabase
    private lateinit var store: BookInfoUploadPayloadStore
    private lateinit var repository: BookInfoUploadRepository
    private lateinit var effects: Effects
    private lateinit var parent: BookInfoRequest
    private val file = File(context.cacheDir, "upload-command-$id.txt")
    private val body = "Immutable upload $id"
    private val book = Book(bookUrl = file.path, origin = BookType.localTag, originName = file.name,
        name = "Upload $id", author = "Author $id", type = BookType.text or BookType.local, durChapterPos = 7)
    private inner class Effects : BookInfoUploadEffects() {
        val calls = mutableListOf<Pair<Boolean, String>>()
        var fail = false
        var interrupted = false
        var barrier: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun transfer(command: BookInfoUploadCommand, file: File, recovering: Boolean): String {
            calls += recovering to file.readText()
            entered.complete(Unit); barrier?.await()
            if (interrupted) throw CancellationException("Simulated stopped worker")
            if (fail) throw BookInfoLibraryException(BookInfoLibraryError.RemoteUpload)
            return "webDav::uploaded-$id"
        }
    }
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).addLegadoMigrations().build()
    @Before fun setup() {
        file.writeText(body)
        database = open(); database.bookDao.insert(book)
        parent = database.bookInfoRequestDao.claim(BookInfoRequest("detail-$id", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
        store = BookInfoUploadPayloadStore(context, UUID.fromString(id)); effects = Effects()
        repository = BookInfoUploadRepository(database, store, effects, BookInfoDeleteAuthority(context))
    }
    @After fun cleanup() {
        (database.bookInfoUploadDao.pending() + database.bookInfoUploadDao.terminal()).forEach { store.remove(it.payloadKey) }
        database.close(); context.deleteDatabase(databaseName); file.delete()
        File(context.noBackupFilesDir, "book-info-uploads/$id").delete()
    }
    private suspend fun accept(overwrite: Boolean = false) = repository.accept(parent.navigationOwner(), id, book, "target", overwrite)

    @Test fun acceptedUploadUsesImmutableBytesAndPreservesConcurrentMetadataAndReadingProgress() = runBlocking {
        val accepted = accept()
        file.writeText("A changed original")
        database.bookDao.update(book.copy(name = "Edited $id", durChapterPos = 333))
        assertEquals(BookInfoUploadStatus.Completed, repository.execute(id)!!.status)
        assertEquals(listOf(false to body), effects.calls)
        val saved = checkNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals("Edited $id", saved.name); assertEquals(333, saved.durChapterPos)
        assertEquals("webDav::uploaded-$id", saved.origin)
        assertEquals("", repository.get(id)!!.target); assertEquals("", repository.get(id)!!.sourceUri)
        assertNull(database.bookInfoUploadDao.lease(book.bookUrl))
        assertFalse(File(context.noBackupFilesDir, "book-info-uploads/$id/${accepted.payloadKey}.data").exists())
    }
    @Test fun aRevokedOwnerCannotCaptureAnUpload() = runBlocking {
        database.bookInfoRequestDao.claim(parent, "replacement")
        try { accept(); fail("Revoked owner accepted upload") } catch (_: BookInfoLibraryException) { }
        assertNull(repository.get(id)); assertTrue(effects.calls.isEmpty())
    }
    @Test fun deletionAndReimportAtTheSameUrlCannotInheritAnUpload() = runBlocking {
        accept(); database.bookDao.delete(book); database.bookDao.insert(book.copy(name = "Replacement $id"))
        assertEquals(BookInfoUploadStatus.Cancelled, repository.execute(id)!!.status)
        assertEquals(BookInfoLibraryError.Changed.name, repository.get(id)!!.error)
        assertTrue(effects.calls.isEmpty()); assertEquals(book.origin, database.bookDao.getBook(book.bookUrl)!!.origin)
    }
    @Test fun acceptedUploadOutlivesItsParentAndResumesAfterDatabaseReopen() = runBlocking {
        accept(); effects.interrupted = true
        try { repository.execute(id); fail("Worker did not stop") } catch (_: CancellationException) { }
        assertEquals(BookInfoUploadPhase.Transferring, repository.get(id)!!.phase)
        database.bookInfoRequestDao.close(parent.id, parent.ownerToken)
        file.delete()
        database.close(); database = open()
        effects = Effects()
        repository = BookInfoUploadRepository(database, store, effects, BookInfoDeleteAuthority(context))
        assertEquals(id, repository.pending().single().id)
        assertEquals(BookInfoUploadStatus.Completed, repository.execute(id)!!.status)
        assertEquals(listOf(true to body), effects.calls)
    }
    @Test fun retryableNetworkFailureKeepsTheVerificationCheckpoint() = runBlocking {
        accept(); effects.fail = true
        assertEquals(BookInfoUploadStatus.Queued, repository.execute(id, retryNetworkFailure = true)!!.status)
        assertEquals(BookInfoUploadPhase.Transferring, repository.get(id)!!.phase)
        effects.fail = false
        assertEquals(BookInfoUploadStatus.Completed, repository.execute(id)!!.status)
        assertEquals(listOf(false, true), effects.calls.map { it.first })
    }
    @Test fun explicitlyConfirmedRetryCanAuthorizeOverwriteButKeepsTheOriginalBytes() = runBlocking {
        accept(); effects.fail = true; repository.execute(id)
        file.writeText("Changed since confirmation")
        assertTrue(accept(overwrite = true).overwrite)
        assertEquals(BookInfoUploadPhase.Prepared, repository.get(id)!!.phase)
        effects.fail = false; repository.execute(id)
        assertEquals(listOf(false to body, false to body), effects.calls)
    }
    @Test fun queuedOptionsCannotBeChangedWithoutANewConfirmation() = runBlocking {
        accept()
        try { accept(overwrite = true); fail("Queued options changed") } catch (_: BookInfoLibraryException) { }
        assertFalse(repository.get(id)!!.overwrite)
    }
    @Test fun completedUploadIsIdempotentEvenIfItsPrivatePayloadHasBeenRemoved() = runBlocking {
        accept(); repository.execute(id); repository.execute(id)
        assertEquals(BookInfoUploadStatus.Completed, accept().status)
        assertEquals(1, effects.calls.size)
    }
    @Test fun aNewDetailCanCancelTheOldCommandsAndFenceItsLateResponse() = runBlocking {
        val accepted = accept()
        val other = database.bookInfoRequestDao.claim(parent.copy(id = "other-$id", ownerToken = "other"), "other")
        database.bookInfoRequestDao.close(parent.id, parent.ownerToken)
        val barrier = CompletableDeferred<Unit>().also { effects.barrier = it }
        val running = async(Dispatchers.IO) { repository.execute(id) }
        effects.entered.await()
        assertEquals(id, repository.observeBook(book.bookUrl).first()!!.id)
        assertTrue(repository.cancel(other.navigationOwner(), id))
        barrier.complete(Unit); running.await()
        assertEquals(BookInfoUploadStatus.Cancelled, repository.get(id)!!.status)
        assertEquals(book.origin, database.bookDao.getBook(book.bookUrl)!!.origin)
        assertNull(repository.observeBook(book.bookUrl).first())
        assertFalse(File(context.noBackupFilesDir, "book-info-uploads/$id/${accepted.payloadKey}.data").exists())
    }
    @Test fun theInstallationAuthorityDoesNotSurviveDatabaseOnlyRestore() = runBlocking {
        val accepted = accept()
        val restored = BookInfoUploadRepository(database, store, effects, object : BookInfoDeleteAuthority(context) {
            override fun id() = "different-installation"
        })
        assertTrue(restored.pending().isEmpty())
        assertEquals(BookInfoUploadStatus.Cancelled, restored.get(id)!!.status)
        assertNull(database.bookInfoUploadDao.lease(book.bookUrl))
        assertTrue(effects.calls.isEmpty())
        assertFalse(File(context.noBackupFilesDir, "book-info-uploads/$id/${accepted.payloadKey}.data").exists())
    }
    @Test fun aCorruptPrivateSnapshotFailsBeforeAnyNetworkEffect() = runBlocking {
        val accepted = accept()
        File(context.noBackupFilesDir, "book-info-uploads/$id/${accepted.payloadKey}.data").writeBytes(ByteArray(accepted.size.toInt()))
        assertEquals(BookInfoUploadStatus.Failed, repository.execute(id)!!.status)
        assertEquals(BookInfoLibraryError.Storage.name, repository.get(id)!!.error)
        assertTrue(effects.calls.isEmpty())
    }
    @Test fun anOldCommandIdCannotCancelItsSuccessor() = runBlocking {
        accept(); effects.fail = true; repository.execute(id)
        repository.accept(parent.navigationOwner(), "$id-next", book, "target", false)
        assertFalse(repository.cancel(parent.navigationOwner(), id))
        assertEquals("$id-next", database.bookInfoUploadDao.lease(book.bookUrl)!!.commandId)
    }
    @Test fun replacingAnInFlightBooksRowFencesItsResponse() = runBlocking {
        accept()
        val barrier = CompletableDeferred<Unit>().also { effects.barrier = it }
        val running = async(Dispatchers.IO) { repository.execute(id) }
        effects.entered.await()
        database.bookDao.delete(book); database.bookDao.insert(book)
        barrier.complete(Unit); running.await()
        assertEquals(BookInfoUploadStatus.Cancelled, repository.get(id)!!.status)
        assertEquals(book.origin, database.bookDao.getBook(book.bookUrl)!!.origin)
    }
    @Test fun aStolenExecutionCannotPublishOverTheNewExecutor() = runBlocking {
        accept(); effects.interrupted = true
        try { repository.execute(id, "old"); fail("Worker did not stop") } catch (_: CancellationException) { }
        effects.interrupted = false
        assertEquals(BookInfoUploadStatus.Completed, repository.execute(id, "new")!!.status)
        assertEquals("new", repository.get(id)!!.executionToken)
        assertEquals(BookInfoUploadStatus.Completed, repository.execute(id, "old")!!.status)
    }

    @Test fun aStaleOwnedSnapshotCannotRollBackTheUploadedLocation() = runBlocking {
        accept(); repository.execute(id)
        BookInfoRepository(database).saveSnapshot(book.copy(customIntro = "New introduction"), true, owner = parent.navigationOwner())
        val saved = checkNotNull(database.bookDao.getBook(book.bookUrl))
        assertEquals("webDav::uploaded-$id", saved.origin)
        assertEquals("New introduction", saved.customIntro)
    }

    @Test fun recoveryKeepsFailedPayloadsButRemovesOnlyOldUnreferencedFilesInItsOwnNamespace() = runBlocking {
        val command = accept(); effects.fail = true; repository.execute(id)
        val retained = File(context.noBackupFilesDir, "book-info-uploads/$id/${command.payloadKey}.data")
        assertTrue(retained.setLastModified(0))
        val orphan = store.capture(android.net.Uri.fromFile(file))
        store.releasePreparation(orphan.key)
        val orphanFile = File(context.noBackupFilesDir, "book-info-uploads/$id/${orphan.key}.data")
        assertTrue(orphanFile.setLastModified(0))
        assertTrue(repository.pending().isEmpty())
        assertTrue(retained.exists()); assertFalse(orphanFile.exists())
        assertEquals(command.sha256, repository.get(id)!!.sha256)
    }

    @Test fun recoveryFinishesPayloadCleanupWithoutDiscardingALiveEntriesReceipt() = runBlocking {
        val command = accept()
        database.bookInfoUploadDao.update(command.copy(status = BookInfoUploadStatus.Completed, phase = BookInfoUploadPhase.Completed))
        repository.pending()
        val cleaned = checkNotNull(repository.get(id))
        assertEquals(BookInfoUploadStatus.Completed, cleaned.status)
        assertEquals("", cleaned.sourceUri); assertEquals("", cleaned.target)
        assertNull(database.bookInfoUploadDao.lease(book.bookUrl))
        assertFalse(File(context.noBackupFilesDir, "book-info-uploads/$id/${command.payloadKey}.data").exists())
    }
}
