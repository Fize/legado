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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BookInfoDeleteCommandTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val databaseName = "delete-command-$id"
    private lateinit var database: AppDatabase
    private lateinit var repository: BookInfoDeleteRepository
    private lateinit var effects: Effects
    private lateinit var parent: BookInfoRequest
    private val book = Book(bookUrl = "file:///owned-$id.txt", origin = BookType.localTag,
        originName = "owned-$id.txt", type = BookType.text or BookType.local, name = "Delete $id", author = "Author $id", durChapterIndex = 2,
        durChapterTitle = "Chapter 3", durChapterPos = 7, durChapterTime = 15)
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).addLegadoMigrations().build()
    private class Effects(database: AppDatabase) : BookInfoDeleteEffects(database) {
        val remote = AtomicInteger(); val original = AtomicInteger(); val cleanup = AtomicInteger(); val callback = AtomicInteger()
        var failRemote = false
        var failOriginal = false
        var cancelOriginal = false
        var remoteBarrier: CompletableDeferred<Unit>? = null
        val remoteEntered = CompletableDeferred<Unit>()
        override suspend fun removeRemote(command: BookInfoDeleteCommand, book: Book) {
            if (remote.incrementAndGet() == 1) { remoteEntered.complete(Unit); remoteBarrier?.await() }
            if (failRemote) throw BookInfoLibraryException(BookInfoLibraryError.RemoteDelete)
        }
        override suspend fun removeOriginal(command: BookInfoDeleteCommand, book: Book) {
            original.incrementAndGet()
            if (cancelOriginal) throw CancellationException("Simulated stopped worker")
            if (failOriginal) throw IOException("Simulated original-file failure")
        }
        override suspend fun cleanup(command: BookInfoDeleteCommand, book: Book) { cleanup.incrementAndGet() }
        override suspend fun callback(book: Book) { callback.incrementAndGet() }
    }
    @Before fun setup() {
        database = open()
        database.bookDao.insert(book)
        database.readRecordDao.insert(ReadRecord(deviceId = io.legado.app.constant.AppConst.androidId,
            bookName = book.name, author = book.author, readTime = 10))
        parent = database.bookInfoRequestDao.claim(BookInfoRequest("detail-$id", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
        effects = Effects(database)
        repository = BookInfoDeleteRepository(database, effects)
    }
    @After fun cleanup() { database.close(); context.deleteDatabase(databaseName) }
    private suspend fun accept(original: Boolean = true, remote: Boolean = true) = repository.accept(parent.navigationOwner(), id, book, original, remote, "target")

    @Test fun acceptanceLocksTheBookAndCompletionPublishesDeletionWithTheDatabaseWrite() = runBlocking {
        accept()
        assertEquals(id, database.bookInfoDeleteDao.lease(book.bookUrl)!!.commandId)
        try {
            BookInfoRepository(database).applyEdit(book.bookUrl, BookInfoEdit.CanUpdate(false), parent.navigationOwner())
            fail("A pending deletion allowed another detail write")
        } catch (_: IllegalStateException) { }
        val completed = checkNotNull(repository.execute(id))
        assertEquals(BookInfoDeleteStatus.Completed, completed.status)
        assertFalse(database.bookDao.has(book.bookUrl))
        assertTrue(database.bookInfoRequestDao.get(parent.id)!!.deleted)
        assertNull(database.bookInfoDeleteDao.lease(book.bookUrl))
        assertEquals(1, effects.remote.get()); assertEquals(1, effects.original.get()); assertEquals(1, effects.callback.get())
        assertNotNull(database.readRecordDao.getRecord(io.legado.app.constant.AppConst.androidId, book.name, book.author))
    }
    @Test fun aRevokedParentCannotAuthorizeADeletion() = runBlocking {
        database.bookInfoRequestDao.claim(parent, "replacement")
        try { accept(); fail("A revoked parent accepted deletion") } catch (_: BookInfoLibraryException) { }
        assertNull(database.bookInfoDeleteDao.get(id)); assertNull(database.bookInfoDeleteDao.lease(book.bookUrl))
        assertTrue(database.bookDao.has(book.bookUrl))
    }
    @Test fun aReplacementAtTheSameUrlInvalidatesTheOldCommand() = runBlocking {
        accept()
        database.bookDao.delete(book)
        database.bookDao.insert(book.copy(name = "Replacement $id"))
        val result = checkNotNull(repository.execute(id))
        assertEquals(BookInfoDeleteStatus.Failed, result.status)
        assertEquals(BookInfoLibraryError.Changed.name, result.error)
        assertEquals("Replacement $id", database.bookDao.getBook(book.bookUrl)!!.name)
        assertEquals(0, effects.remote.get()); assertFalse(database.bookInfoRequestDao.get(parent.id)!!.deleted)
    }
    @Test fun retryResumesAfterTheRemoteCheckpoint() = runBlocking {
        accept(); effects.failOriginal = true
        val failed = checkNotNull(repository.execute(id))
        assertEquals(BookInfoDeleteStatus.Failed, failed.status)
        assertEquals(BookInfoDeletePhase.RemoteRemoved, failed.phase)
        assertTrue(database.bookDao.has(book.bookUrl))
        effects.failOriginal = false
        accept()
        assertEquals(BookInfoDeleteStatus.Completed, repository.execute(id)!!.status)
        assertEquals(1, effects.remote.get()); assertEquals(2, effects.original.get()); assertEquals(1, effects.callback.get())
    }
    @Test fun cancellingAFailedCommandReleasesItsLeaseAndFencesLaterExecution() = runBlocking {
        accept(); effects.failOriginal = true; repository.execute(id)
        assertTrue(repository.cancel(parent.navigationOwner(), id))
        assertEquals(BookInfoDeleteStatus.Cancelled, repository.execute(id)!!.status)
        assertNull(database.bookInfoDeleteDao.lease(book.bookUrl))
        BookInfoRepository(database).applyEdit(book.bookUrl, BookInfoEdit.CanUpdate(false), parent.navigationOwner())
        assertFalse(database.bookDao.getBook(book.bookUrl)!!.canUpdate)
        assertEquals(1, effects.remote.get()); assertEquals(0, effects.callback.get())
    }
    @Test fun aNewExecutorResumesThePersistedCheckpointAfterTheParentHasClosed() = runBlocking {
        accept(); effects.cancelOriginal = true
        try { repository.execute(id); fail("Stopped worker was not cancelled") } catch (_: CancellationException) { }
        assertEquals(BookInfoDeletePhase.RemoteRemoved, database.bookInfoDeleteDao.get(id)!!.phase)
        database.bookInfoRequestDao.close(parent.id, parent.ownerToken)
        database.close(); database = open()
        val resumedEffects = Effects(database)
        val resumed = BookInfoDeleteRepository(database, resumedEffects)
        assertEquals(id, resumed.pending().single().id)
        assertEquals(BookInfoDeleteStatus.Completed, resumed.execute(id)!!.status)
        assertEquals(0, resumedEffects.remote.get()); assertEquals(1, resumedEffects.original.get())
        assertFalse(database.bookDao.has(book.bookUrl)); assertNull(database.bookInfoRequestDao.get(parent.id))
    }
    @Test fun queuedIntentCannotBeSilentlyChanged() = runBlocking {
        accept()
        try { accept(original = false); fail("Queued intent was changed") } catch (_: BookInfoLibraryException) { }
        assertTrue(database.bookInfoDeleteDao.get(id)!!.deleteOriginal)
        assertEquals(BookInfoDeleteStatus.Queued, database.bookInfoDeleteDao.get(id)!!.status)
    }
    @Test fun aLateExecutorCannotPublishOverItsReplacement() = runBlocking {
        accept()
        val barrier = CompletableDeferred<Unit>().also { effects.remoteBarrier = it }
        val old = async(Dispatchers.IO) { repository.execute(id, "old-executor") }
        effects.remoteEntered.await()
        assertEquals(BookInfoDeleteStatus.Completed, repository.execute(id, "new-executor")!!.status)
        barrier.complete(Unit); old.await()
        assertEquals("new-executor", repository.get(id)!!.executionToken)
        assertEquals(1, effects.callback.get())
        assertEquals(1L, database.bookInfoRequestDao.get(parent.id)!!.revision)
    }
    @Test fun deletionPublishesToEveryDetailRequestForTheExactBookKey() = runBlocking {
        database.bookInfoRequestDao.claim(parent.copy(id = "other-$id", ownerToken = "other"), "other")
        accept(); repository.execute(id)
        assertTrue(database.bookInfoRequestDao.get(parent.id)!!.deleted)
        assertTrue(database.bookInfoRequestDao.get("other-$id")!!.deleted)
    }
    @Test fun retryableRemoteFailureKeepsTheAcceptedIntentQueued() = runBlocking {
        accept(); effects.failRemote = true
        val queued = checkNotNull(repository.execute(id, retryRemoteFailure = true))
        assertEquals(BookInfoDeleteStatus.Queued, queued.status)
        assertEquals(BookInfoDeletePhase.Accepted, queued.phase)
        assertEquals("", queued.executionToken)
        assertTrue(database.bookDao.has(book.bookUrl))
        effects.failRemote = false
        assertEquals(BookInfoDeleteStatus.Completed, repository.execute(id)!!.status)
        assertEquals(2, effects.remote.get()); assertEquals(1, effects.original.get())
    }
    @Test fun aCompletedCommandDoesNotRunItsEffectsTwice() = runBlocking {
        accept(); repository.execute(id); repository.execute(id)
        assertEquals("", repository.get(id)!!.snapshot)
        assertNull(repository.get(id)!!.target)
        assertEquals(1, effects.remote.get()); assertEquals(1, effects.original.get()); assertEquals(1, effects.callback.get())
        assertEquals(1L, database.bookInfoRequestDao.get(parent.id)!!.revision)
    }

    @Test fun cancelledCommandsDiscardTheirPrivateSnapshotAndTarget() = runBlocking {
        accept()
        assertTrue(repository.get(id)!!.snapshot.isNotEmpty())
        assertTrue(repository.cancel(parent.navigationOwner(), id))
        val cancelled = checkNotNull(repository.execute(id))
        assertEquals("", cancelled.snapshot); assertNull(cancelled.target)
        assertEquals(0, effects.remote.get())
    }

    @Test fun recoveryMinimizesOldTerminalPayloadsWithoutDiscardingRetryCheckpoints() = runBlocking {
        accept(); effects.failOriginal = true; repository.execute(id)
        val failed = checkNotNull(repository.get(id))
        database.bookInfoDeleteDao.insert(failed.copy(id = "$id-terminal", status = BookInfoDeleteStatus.Cancelled))
        repository.pending()
        assertEquals("", repository.get("$id-terminal")!!.snapshot)
        assertNull(repository.get("$id-terminal")!!.target)
        assertEquals(failed.snapshot, repository.get(id)!!.snapshot)
        assertEquals(failed.target, repository.get(id)!!.target)
        effects.failOriginal = false; accept()
        assertEquals(BookInfoDeleteStatus.Completed, repository.execute(id)!!.status)
        assertEquals(1, effects.remote.get())
    }

    @Test fun terminalRetentionPreservesLiveEntryReceiptsAndEveryPendingOrFailedCommand() = runBlocking {
        accept(); effects.failOriginal = true; repository.execute(id)
        val failed = checkNotNull(repository.get(id))
        val dao = database.bookInfoDeleteDao
        for (index in 0..130) dao.insert(failed.copy(id = "$id-orphan-$index", requestId = "closed-$index",
            status = BookInfoDeleteStatus.Completed, updatedAt = index.toLong()))
        dao.insert(failed.copy(id = "$id-live", status = BookInfoDeleteStatus.Completed, updatedAt = 0))
        dao.insert(failed.copy(id = "$id-queued", requestId = "closed-queued", status = BookInfoDeleteStatus.Queued, updatedAt = 0))
        database.runInTransaction { dao.minimizeTerminal(); dao.pruneTerminal(-1) }
        assertEquals(128, (0..130).count { dao.get("$id-orphan-$it") != null })
        assertNotNull(dao.get("$id-live")); assertNotNull(dao.get("$id-queued")); assertEquals(failed.snapshot, dao.get(id)!!.snapshot)
        database.runInTransaction { dao.pruneTerminal(1000) }
        assertTrue((0..130).all { dao.get("$id-orphan-$it") == null })
        assertNotNull(dao.get("$id-live")); assertNotNull(dao.get("$id-queued")); assertNotNull(dao.get(id))
    }

    @Test fun completedReceiptsRemainCompletedAfterAnInstallationChange() = runBlocking {
        accept(); repository.execute(id)
        val restored = BookInfoDeleteRepository(database, effects, object : BookInfoDeleteAuthority(context) {
            override fun id() = "another-installation"
        })
        assertEquals(BookInfoDeleteStatus.Completed, restored.execute(id)!!.status)
        assertEquals(1, effects.callback.get())
    }

    @Test fun anotherLiveDetailCanObserveAndCancelTheExactPendingCommand() = runBlocking {
        accept()
        val other = database.bookInfoRequestDao.claim(parent.copy(id = "other-$id", ownerToken = "other"), "other")
        database.bookInfoRequestDao.close(parent.id, parent.ownerToken)
        assertEquals(id, repository.observeBook(book.bookUrl).first()!!.id)
        val barrier = CompletableDeferred<Unit>().also { effects.remoteBarrier = it }
        val running = async(Dispatchers.IO) { repository.execute(id) }
        effects.remoteEntered.await()
        assertTrue(repository.cancel(other.navigationOwner(), id))
        assertNull(repository.observeBook(book.bookUrl).first())
        barrier.complete(Unit); running.await()
        assertTrue(database.bookDao.has(book.bookUrl))
        assertEquals(0, effects.original.get()); assertEquals(0, effects.callback.get())
        assertEquals(BookInfoDeleteStatus.Cancelled, repository.get(id)!!.status)
    }

    @Test fun aStaleCommandIdCannotCancelItsSuccessor() = runBlocking {
        accept(); effects.failRemote = true; repository.execute(id)
        val next = "$id-next"
        repository.accept(parent.navigationOwner(), next, book, true, true, "target")
        assertFalse(repository.cancel(parent.navigationOwner(), id))
        assertEquals(next, repository.observeBook(book.bookUrl).first()!!.id)
        assertEquals(BookInfoDeleteStatus.Queued, repository.get(next)!!.status)
        assertEquals(next, database.bookInfoDeleteDao.lease(book.bookUrl)!!.commandId)
    }

    @Test fun aRevokedDetailCannotCancelAnotherDetailsCommand() = runBlocking {
        accept()
        database.bookInfoRequestDao.claim(parent, "replacement")
        try { repository.cancel(parent.navigationOwner(), id); fail("Revoked owner cancelled deletion") }
        catch (_: BookInfoLibraryException) { }
        assertEquals(BookInfoDeleteStatus.Queued, repository.get(id)!!.status)
    }

    @Test fun anotherBookCannotCancelAnUnrelatedCommand() = runBlocking {
        accept()
        val other = database.bookInfoRequestDao.claim(parent.copy(id = "other-$id", bookUrl = "other-book-$id", ownerToken = "other"), "other")
        assertFalse(repository.cancel(other.navigationOwner(), id))
        assertEquals(BookInfoDeleteStatus.Queued, repository.get(id)!!.status)
    }

    @Test fun failedDeletionCanBeCancelledAfterEditingTheSameBookRow() = runBlocking {
        accept(); effects.failRemote = true; repository.execute(id)
        database.bookDao.update(book.copy(name = "Edited $id"))
        assertTrue(repository.cancel(parent.navigationOwner(), id))
        assertEquals("Edited $id", database.bookDao.getBook(book.bookUrl)!!.name)
        assertNull(database.bookInfoDeleteDao.lease(book.bookUrl))
    }

    @Test fun cancellationCannotActOnABookRowReimportedAtTheSameUrl() = runBlocking {
        accept()
        database.bookDao.delete(book); database.bookDao.insert(book)
        assertFalse(repository.cancel(parent.navigationOwner(), id))
        assertTrue(database.bookDao.has(book.bookUrl))
        assertEquals(BookInfoDeleteStatus.Queued, repository.get(id)!!.status)
    }

    @Test fun restoringTheDatabaseWithoutItsInstallationAuthorityCancelsPendingDeletion() = runBlocking {
        accept()
        val restored = BookInfoDeleteRepository(database, effects, object : BookInfoDeleteAuthority(context) {
            override fun id() = "a-different-installation"
        })
        assertTrue(restored.pending().isEmpty())
        assertEquals(BookInfoDeleteStatus.Cancelled, restored.get(id)!!.status)
        assertNull(database.bookInfoDeleteDao.lease(book.bookUrl))
        assertTrue(database.bookDao.has(book.bookUrl)); assertEquals(0, effects.remote.get())
    }
    @Test fun aFailedCommandFromAnotherInstallationAlsoReleasesItsLease() = runBlocking {
        accept(); effects.failOriginal = true; repository.execute(id)
        val restored = BookInfoDeleteRepository(database, effects, object : BookInfoDeleteAuthority(context) {
            override fun id() = "a-different-installation"
        })
        assertTrue(restored.pending().isEmpty())
        assertEquals(BookInfoDeleteStatus.Cancelled, restored.get(id)!!.status)
        assertNull(database.bookInfoDeleteDao.lease(book.bookUrl))
        assertTrue(database.bookDao.has(book.bookUrl))
    }

    @Test fun aTerminalFailureDoesNotLockTheBookAndANewConfirmationCanReplaceIt() = runBlocking {
        accept(); effects.failOriginal = true; repository.execute(id)
        BookInfoRepository(database).applyEdit(book.bookUrl, BookInfoEdit.CanUpdate(false), parent.navigationOwner())
        assertFalse(database.bookDao.getBook(book.bookUrl)!!.canUpdate)
        val next = "$id-next"
        repository.accept(parent.navigationOwner(), next, book, true, true, "target")
        assertEquals(BookInfoDeleteStatus.Cancelled, repository.get(id)!!.status)
        assertEquals(next, database.bookInfoDeleteDao.lease(book.bookUrl)!!.commandId)
        effects.failOriginal = false
        assertEquals(BookInfoDeleteStatus.Completed, repository.execute(next)!!.status)
    }

}
