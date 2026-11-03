package io.legado.app.ui.book.read

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.data.repository.MediaHandoffStops
import io.legado.app.data.repository.MediaHandoffTickets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import splitties.init.appCtx
import io.legado.app.help.globalExecutor
import io.legado.app.help.storage.RestoreRecoveryGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class PlatformMediaReturnSessionTest {
    @Test fun aNewMediaRequestCannotBeDisplayedBeforeItsPredecessorIsReturned() = runBlocking {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "media-return-$id", name = "Return $id", type = BookType.audio)
        val first = "first-$id"; val second = "second-$id"
        val trigger = "media_return_${id.replace("-", "")}"
        val session = PlatformMediaReturnSession()
        val db = appDb.openHelper.writableDatabase
        val dao = appDb.bookInfoRequestDao
        fun intent(child: String) = Intent().putExtra("bookUrl", book.bookUrl)
            .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
        try {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl,
                url = "https://$id.invalid/chapter", index = 0, title = "Chapter"))
            for (child in listOf(first, second)) {
                dao.claim(BookInfoRequest(child, book.bookUrl, book.bookUrl, id, updatedAt = 1), id)
                checkNotNull(dao.begin(child, id, 0, book.bookUrl, child, BookInfoChildKind.Reader,
                    BookInfoNavigationPosition(0, 12000), 2))
            }
            checkNotNull(session.prepare(intent(first), BookType.audio))
            assertEquals(BookInfoChildPhase.Displayed, dao.child(first)!!.phase)
            // Enforce the observable ordering at the actual Room write, independently of thread timing.
            // This trigger only matches the two UUID-owned fixture requests.
            db.execSQL("""CREATE TRIGGER "$trigger" BEFORE UPDATE OF phase ON book_info_child_requests
                WHEN NEW.id = '$second' AND NEW.phase = 'Displayed'
                AND EXISTS(SELECT 1 FROM book_info_child_requests WHERE id = '$first' AND phase = 'Displayed')
                BEGIN SELECT RAISE(ABORT, 'previous media request still displayed'); END""")
            checkNotNull(session.prepare(intent(second), BookType.audio))
            assertEquals(BookInfoChildPhase.Returned, dao.child(first)!!.phase)
            assertEquals(BookInfoChildPhase.Displayed, dao.child(second)!!.phase)
            assertEquals(second, session.requestId)
        } finally {
            session.close()
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            db.execSQL("DROP TRIGGER IF EXISTS \"$trigger\"")
            dao.close(first, id); dao.close(second, id)
            appDb.bookDao.delete(book)
        }
    }
    @Test fun handoffMovesTheReceiptWithoutReapplyingTheSeekOrPublishingAReturn() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession(); val replay = PlatformMediaReturnSession()
        try {
            val input = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
            checkNotNull(sender.prepare(input, BookType.video))
            val original = appDb.bookInfoRequestDao.child(child)!!
            book.durChapterPos = 24000; appDb.bookDao.update(book)
            val envelope = Intent()
            sender.handoffTo(envelope) { }
            val oldCapability = Intent(envelope)
            assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child)!!.phase)
            checkNotNull(receiver.prepare(envelope, BookType.video))
            val received = appDb.bookInfoRequestDao.child(child)!!
            assertNotEquals(original.producerToken, received.producerToken)
            assertEquals(24000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            sender.close(); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(received, appDb.bookInfoRequestDao.child(child))
            assertTrue(runCatching { replay.prepare(oldCapability, BookType.video) }.isFailure)
            assertEquals(received, appDb.bookInfoRequestDao.child(child))
            replay.close()
            MediaHandoffTickets.default.requireActive(checkNotNull(envelope.getStringExtra(MediaHandoffTickets.EXTRA)), envelope)
            receiver.close(); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        } finally { sender.close(); receiver.close(); replay.close() }
    }

    @Test fun aRejectedHandoffLaunchKeepsTheOriginalReturnOwner() = handoffFixture { book, child ->
        val session = PlatformMediaReturnSession()
        try {
            checkNotNull(session.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val before = appDb.bookInfoRequestDao.child(child)!!
            assertTrue(runCatching { session.handoffTo(Intent()) { error("Owned rejected launch") } }.isFailure)
            assertEquals(child, session.requestId)
            assertEquals(before, appDb.bookInfoRequestDao.child(child))
            session.close(); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        } finally { session.close() }
    }

    @Test fun closingTheDetailBridgeRevokesItsReceiptWithoutReopeningItOnHandoff() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val intent = Intent(); sender.handoffTo(intent) { }
            PlatformReaderReturnRepository(appDb).closeBridge(child, book.bookUrl)
            val closed = appDb.bookInfoRequestDao.child(child)!!
            assertEquals(BookInfoChildPhase.Returned, closed.phase)
            assertNull(receiver.prepare(intent, BookType.video))
            assertNull(receiver.requestId)
            assertFalse(intent.hasExtra(PlatformReaderReturnRepository.REQUEST_ID))
            assertEquals(closed, appDb.bookInfoRequestDao.child(child))
        } finally { sender.close(); receiver.close() }
    }

    @Test fun stoppingAfterTheReceiverClaimsStillReturnsItsCurrentReceipt() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val launch = Intent(); sender.handoffTo(launch) { }
            checkNotNull(receiver.prepare(launch, BookType.video))
            sender.stopPendingHandoff()
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue(receiver.handoffStopped)
            assertNull(receiver.requestId)
            assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        } finally { sender.close(); receiver.close() }
    }

    @Test fun aLateSenderStopCannotCloseTheReceiversNewRequest() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        val second = "next-$child"; val parentOwner = UUID.randomUUID().toString()
        val dao = appDb.bookInfoRequestDao
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val launch = Intent(); sender.handoffTo(launch) { }
            checkNotNull(receiver.prepare(launch, BookType.video))
            dao.claim(BookInfoRequest(second, book.bookUrl, book.bookUrl, parentOwner, updatedAt = 1), parentOwner)
            checkNotNull(dao.begin(second, parentOwner, 0, book.bookUrl, second, BookInfoChildKind.Reader,
                BookInfoNavigationPosition(0, 21000), 2))
            val nextInput = Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, second)
            // The accepted input is bound before its persistence queue slot, as in the real hosts.
            receiver.receiveHandoff(nextInput)
            sender.stopPendingHandoff()
            checkNotNull(receiver.prepare(nextInput, BookType.video))
            val before = dao.child(second)
            sender.stopPendingHandoff()
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertFalse(receiver.handoffStopped)
            assertEquals(second, receiver.requestId)
            assertEquals(BookInfoChildPhase.Returned, dao.child(child)!!.phase)
            assertEquals(before, dao.child(second))
        } finally {
            sender.close(); receiver.close()
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            dao.close(second, parentOwner)
        }
    }

    @Test fun aRestoredPendingHandoffCanClaimItsStillOpenReceipt() = restoredHandoff(closed = false)

    @Test fun aRestoredPendingHandoffCannotReplayAClosedReceipt() = restoredHandoff(closed = true)

    private fun restoredHandoff(closed: Boolean) = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession()
        val lostReceiver = PlatformMediaReturnSession()
        val restoredReceiver = freshProcessMediaSession()
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val before = appDb.bookInfoRequestDao.child(child)!!
            val launch = Intent(); sender.handoffTo(launch) { }
            // Consume the process-local registry without claiming Room, then discard that host.
            // The restored host receives only the saved Intent, as after process termination.
            lostReceiver.receiveHandoff(launch)
            if (closed) {
                PlatformReaderReturnRepository(appDb).closeBridge(child, book.bookUrl)
                // Legacy saved launches have no independent actor identity proving a live handoff.
                launch.removeExtra(io.legado.app.data.repository.MediaHandoffTickets.EXTRA)
            }
            val stored = appDb.bookInfoRequestDao.child(child)!!
            val result = runCatching { restoredReceiver.prepare(Intent(launch), BookType.video) }
            if (closed) {
                assertTrue("A saved closed handoff must not become ordinary playback", result.isFailure)
                assertNull(restoredReceiver.requestId)
                assertEquals(stored, appDb.bookInfoRequestDao.child(child))
            } else {
                val prepared = checkNotNull(result.getOrThrow())
                assertEquals(child, prepared.owner.id)
                assertNotEquals(before.producerToken, prepared.owner.producerToken)
                assertFalse(prepared.positionApplied)
                assertNull(appDb.bookInfoRequestDao.child(child)!!.position)
            }
        } finally {
            sender.close(); lostReceiver.close(); restoredReceiver.close()
        }
    }

    @Test fun aStopCanRevokeAnUnownedPendingHandoff() = handoffFixture { book, _ ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        try {
            val input = Intent().putExtra("bookUrl", book.bookUrl)
            assertNull(sender.prepare(input, BookType.video))
            val launch = Intent(input); sender.handoffTo(launch) { }
            receiver.receiveHandoff(launch)
            sender.stopPendingHandoff()
            assertTrue("A handoff without a detail receipt must still be stoppable", receiver.handoffStopped)
            assertTrue(runCatching { receiver.prepare(launch, BookType.video) }.isFailure)
        } finally { sender.close(); receiver.close() }
    }

    @Test fun aStoppedRevokedReceiptCannotBecomeOrdinaryPlaybackAfterRestart() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        val restored = PlatformMediaReturnSession()
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val launch = Intent(); sender.handoffTo(launch) { }
            PlatformReaderReturnRepository(appDb).closeBridge(child, book.bookUrl)
            assertNull(receiver.prepare(launch, BookType.video))
            val saved = Intent(launch)
            assertFalse(saved.hasExtra(PlatformReaderReturnRepository.REQUEST_ID))
            sender.stopPendingHandoff()
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue("A stopped handoff must not resume after its receipt was removed",
                runCatching { restored.prepare(saved, BookType.video) }.isFailure)
        } finally { sender.close(); receiver.close(); restored.close() }
    }

    @Test fun anAcceptedUnownedHandoffCanResumeAfterItsReceiptWasClosed() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        val restored = freshProcessMediaSession()
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val launch = Intent(); sender.handoffTo(launch) { }
            PlatformReaderReturnRepository(appDb).closeBridge(child, book.bookUrl)
            assertNull(receiver.prepare(launch, BookType.video))
            val closed = appDb.bookInfoRequestDao.child(child)
            assertNull(restored.prepare(Intent(launch), BookType.video))
            assertEquals(closed, appDb.bookInfoRequestDao.child(child))
        } finally { sender.close(); receiver.close(); restored.close() }
    }

    @Test fun aPendingTicketCanResumeAfterOnlyItsDetailReceiptWasClosed() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val lost = PlatformMediaReturnSession(); val restored = freshProcessMediaSession()
        try {
            checkNotNull(sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video))
            val launch = Intent(); sender.handoffTo(launch) { }
            lost.receiveHandoff(launch)
            PlatformReaderReturnRepository(appDb).closeBridge(child, book.bookUrl)
            val closed = appDb.bookInfoRequestDao.child(child)
            assertNull(restored.prepare(Intent(launch), BookType.video))
            assertEquals(closed, appDb.bookInfoRequestDao.child(child))
        } finally { sender.close(); lost.close(); restored.close() }
    }

    @Test fun anOldUnownedStopCannotCloseANewInputForTheSameBook() = handoffFixture { book, _ ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        val input = Intent().putExtra("bookUrl", book.bookUrl)
        try {
            sender.prepare(Intent(input), BookType.video)
            val launch = Intent(input); sender.handoffTo(launch) { }
            receiver.prepare(launch, BookType.video)
            val next = Intent(input)
            receiver.receiveHandoff(next)
            sender.stopPendingHandoff()
            assertNull(receiver.prepare(next, BookType.video))
            sender.stopPendingHandoff()
            assertFalse(receiver.handoffStopped)
        } finally { sender.close(); receiver.close() }
    }

    @Test fun aTicketCannotBeUsedForAnotherMediaInput() = handoffFixture { book, _ ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        val input = Intent().putExtra("bookUrl", book.bookUrl)
        try {
            sender.prepare(Intent(input), BookType.video)
            val launch = Intent(input); sender.handoffTo(launch) { }
            val original = appDb.bookDao.getBook(book.bookUrl)
            assertTrue(runCatching { receiver.prepare(Intent(launch).putExtra("videoUrl", "https://wrong.invalid/video"), BookType.video) }.isFailure)
            MediaHandoffTickets.default.requireActive(checkNotNull(launch.getStringExtra(MediaHandoffTickets.EXTRA)), launch)
            assertEquals(original, appDb.bookDao.getBook(book.bookUrl))
        } finally { sender.stopPendingHandoff(); sender.close(); receiver.close() }
    }

    @Test fun ticketStorageFailureKeepsTheOriginalPlayerReceiptAndDoesNotLaunch() = handoffFixture { book, child ->
        val root = File(appCtx.cacheDir, "ticket-storage-fault-${UUID.randomUUID()}")
        check(root.createNewFile())
        val sender = PlatformMediaReturnSession(MediaHandoffTickets(root))
        try {
            sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video)
            val before = appDb.bookInfoRequestDao.child(child)
            var launched = false
            assertTrue(runCatching { sender.handoffTo(Intent()) { launched = true } }.isFailure)
            assertFalse(launched)
            assertEquals(child, sender.requestId)
            assertEquals(before, appDb.bookInfoRequestDao.child(child))
        } finally { sender.close(); check(root.delete()) }
    }

    @Test fun aRejectedUnownedLaunchExpiresOnlyItsNewTicket() = handoffFixture { book, _ ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        val input = Intent().putExtra("bookUrl", book.bookUrl)
        var rejected: String? = null
        try {
            sender.prepare(Intent(input), BookType.video)
            assertTrue(runCatching { sender.handoffTo(Intent(input)) {
                rejected = it.getStringExtra(MediaHandoffTickets.EXTRA); error("Owned rejected unowned launch")
            } }.isFailure)
            assertTrue(runCatching { MediaHandoffTickets.default.requireActive(checkNotNull(rejected), input) }.isFailure)
            val next = Intent(input); sender.handoffTo(next) { }
            assertNull(receiver.prepare(next, BookType.video))
        } finally { sender.close(); receiver.close() }
    }

    @Test fun retiringASenderDoesNotExpireItsNextReceiver() = handoffFixture { book, child ->
        val first = PlatformMediaReturnSession(); val second = PlatformMediaReturnSession(); val third = PlatformMediaReturnSession()
        try {
            first.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video)
            val middle = Intent(); first.handoffTo(middle) { }; second.prepare(middle, BookType.video)
            val last = Intent(middle); second.handoffTo(last) { }
            val before = appDb.bookInfoRequestDao.child(child)
            second.close(); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(before, appDb.bookInfoRequestDao.child(child))
            assertNotNull(third.prepare(last, BookType.video))
        } finally { first.close(); second.close(); third.close() }
    }

    @Test fun anUnownedStopReachesALaterReceiverAfterItsSenderRetired() = handoffFixture { book, _ ->
        val first = PlatformMediaReturnSession(); val second = PlatformMediaReturnSession()
        val third = PlatformMediaReturnSession(); val restored = PlatformMediaReturnSession()
        try {
            val input = Intent().putExtra("bookUrl", book.bookUrl)
            first.prepare(Intent(input), BookType.video)
            val middle = Intent(input); first.handoffTo(middle) { }; second.prepare(middle, BookType.video)
            val last = Intent(middle); second.handoffTo(last) { }; third.prepare(last, BookType.video)
            val saved = Intent(last)
            second.close()
            first.stopPendingHandoff()
            assertTrue(third.handoffStopped)
            assertTrue(runCatching { restored.prepare(saved, BookType.video) }.isFailure)
        } finally { first.close(); second.close(); third.close(); restored.close() }
    }

    @Test fun recoveryAbandonsAnUnownedHandoffWithoutReturningAnyRoomReceipt() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val restored = PlatformMediaReturnSession()
        try {
            val input = Intent().putExtra("bookUrl", book.bookUrl)
            sender.prepare(Intent(input), BookType.video)
            val launch = Intent(input); sender.handoffTo(launch) { }
            val before = appDb.bookInfoRequestDao.child(child)
            try {
                RestoreRecoveryGate.requireRecovery()
                sender.abandonForRecovery()
            } finally { RestoreRecoveryGate.onStartupReady() }
            assertTrue(runCatching { restored.prepare(launch, BookType.video) }.isFailure)
            assertEquals(before, appDb.bookInfoRequestDao.child(child))
        } finally { sender.close(); restored.close(); RestoreRecoveryGate.onStartupReady() }
    }

    @Test fun aPersistedStopReturnsTheTransferredOwnerBeforeItCanResume() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val old = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        val current = checkNotNull(repository.transferMedia(child, book.bookUrl, old.producerToken,
            UUID.randomUUID().toString(), BookType.video)).owner
        MediaHandoffStops.record(old)
        assertTrue(runCatching { repository.transferMedia(child, book.bookUrl, current.producerToken,
            UUID.randomUUID().toString(), BookType.video) }.isFailure)
        assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        assertNull(MediaHandoffStops.read(child))
        assertEquals(12000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun stopObservationWaitsForTheFinalQueuedPlaybackSave() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        MediaHandoffStops.record(owner)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val save = globalExecutor.submit {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            val current = checkNotNull(appDb.bookDao.getBook(book.bookUrl))
            current.durChapterPos = 21000
            appDb.bookDao.update(current)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            coroutineScope {
                val observed = async { repository.observe(child).first() }
                try {
                    delay(250)
                    assertFalse("A return must not overtake the final playback save", observed.isCompleted)
                    assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child)!!.phase)
                    release.countDown()
                    assertEquals(BookInfoChildPhase.Returned, withTimeout(5000) { observed.await() }!!.phase)
                } finally { observed.cancel() }
            }
            save.get(5, TimeUnit.SECONDS)
            assertEquals(21000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertNull(MediaHandoffStops.read(child))
        } finally {
            release.countDown()
            save.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun aSavedBridgeReconcilesAStopWithoutOpeningAPlayer() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        MediaHandoffStops.record(owner)
        assertEquals(BookInfoChildPhase.Returned, PlatformReaderReturnRepository(appDb).observe(child).first()!!.phase)
        assertNull(MediaHandoffStops.read(child))
    }

    @Test fun aStaleStopCannotCloseAnotherRevisionOfTheRequest() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        check(owner.revision > 0)
        MediaHandoffStops.record(owner.copy(revision = owner.revision - 1))
        assertFalse(repository.reconcilePendingStop(child))
        assertEquals(owner, appDb.bookInfoRequestDao.child(child))
        assertNull(MediaHandoffStops.read(child))
    }

    @Test fun oldStopCleanupCannotDeleteANewerStop() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        val first = MediaHandoffStops.record(owner)
        val second = MediaHandoffStops.record(owner)
        MediaHandoffStops.clear(first)
        assertEquals(second, MediaHandoffStops.read(child))
        assertTrue(repository.reconcilePendingStop(child))
        assertNull(MediaHandoffStops.read(child))
    }

    @Test fun aFailedStopTransactionKeepsItsRecordForRetry() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        val record = MediaHandoffStops.record(owner)
        val trigger = "media_stop_" + UUID.randomUUID().toString().replace("-", "")
        val db = appDb.openHelper.writableDatabase
        try {
            db.execSQL("""CREATE TRIGGER "$trigger" BEFORE UPDATE OF phase ON book_info_child_requests
                WHEN NEW.id = '$child' AND NEW.phase = 'Returned'
                BEGIN SELECT RAISE(ABORT, 'owned stop transaction failure'); END""")
            assertTrue(runCatching { repository.reconcilePendingStop(child) }.isFailure)
            assertEquals(owner, appDb.bookInfoRequestDao.child(child))
            assertEquals(record, MediaHandoffStops.read(child))
        } finally { db.execSQL("DROP TRIGGER IF EXISTS \"$trigger\"") }
        assertTrue(repository.reconcilePendingStop(child))
        assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        assertNull(MediaHandoffStops.read(child))
    }

    @Test fun aPendingStopSurvivesQuarantineUntilRecoveryAllowsWrites() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        val record = MediaHandoffStops.record(owner)
        try {
            RestoreRecoveryGate.requireRecovery()
            assertTrue(runCatching { repository.reconcilePendingStop(child) }.isFailure)
            assertEquals(record, MediaHandoffStops.read(child))
            assertEquals(owner, appDb.bookInfoRequestDao.child(child))
        } finally { RestoreRecoveryGate.onStartupReady() }
        assertTrue(repository.reconcilePendingStop(child))
        assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        assertNull(MediaHandoffStops.read(child))
    }

    @Test fun aCorruptStopFailsClosedUntilItsBridgeIsExplicitlyClosed() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val owner = repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        MediaHandoffStops.record(owner)
        val hash = MessageDigest.getInstance("SHA-256").digest(child.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val file = File(appCtx.noBackupFilesDir, "media-handoff-stops/$hash.json")
        file.writeText("owned corrupt stop")
        assertTrue(runCatching { repository.prepareMedia(child, book.bookUrl,
            UUID.randomUUID().toString(), BookType.video) }.isFailure)
        assertEquals(owner, appDb.bookInfoRequestDao.child(child))
        assertTrue(file.exists())
        // Exercise the actual bridge ViewModel, including its completed error flow and close retry.
        coroutineScope {
            val model = PlatformReaderReturnViewModel(repository)
            val observed = async { model.observe(child).first() }
            try {
                withTimeout(5000) { while (!model.failed) delay(10) }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { model.close(book.bookUrl, child) }
                assertEquals(BookInfoChildPhase.Returned, withTimeout(5000) { observed.await() }!!.phase)
                assertFalse(model.failed)
            } finally {
                observed.cancel()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { model.viewModelScope.cancel() }
            }
        }
        assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        assertNull(MediaHandoffStops.read(child))
    }

    @Test fun anObsoleteBoundPreparationCannotRebindAfterANewerPlainInput() = handoffFixture { book, child ->
        val session = PlatformMediaReturnSession()
        try {
            val old = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
            val generation = session.receiveHandoff(old)
            session.receiveHandoff(Intent().putExtra("bookUrl", book.bookUrl))
            val result = runCatching { session.prepare(old, BookType.video, generation) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(BookInfoChildPhase.Requested, appDb.bookInfoRequestDao.child(child)!!.phase)
            assertEquals(book, appDb.bookDao.getBook(book.bookUrl))
        } finally { session.close() }
    }

    @Test fun aMediaTransactionRollsBackItsClaimAndSeekWhenItsInputExpiresBeforeCommit() = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val original = appDb.bookInfoRequestDao.child(child)
        var checks = 0
        val result = runCatching {
            repository.prepareMedia(child, book.bookUrl, UUID.randomUUID().toString(), BookType.video) {
                if (++checks == 2) throw CancellationException("Input replaced after SQL writes")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(2, checks)
        assertEquals(original, appDb.bookInfoRequestDao.child(child))
        assertEquals(book, appDb.bookDao.getBook(book.bookUrl))
    }

    @Test fun anInputReplacedWhileWaitingForTheSqliteWriterCannotClaimOrSeekAfterTheLockReleases() = handoffFixture { book, child ->
        val session = PlatformMediaReturnSession()
        val persistenceThread = globalExecutor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
        val writer = android.database.sqlite.SQLiteDatabase.openDatabase(checkNotNull(appDb.openHelper.writableDatabase.path),
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        try {
            writer.beginTransaction()
            coroutineScope {
                val queued = async(Dispatchers.IO) {
                    session.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                        .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video)
                }
                try {
                    withTimeout(5000) {
                        while (!persistenceThread.stackTrace.any {
                            it.className == "android.database.sqlite.SQLiteSession" && it.methodName.startsWith("beginTransaction")
                        }) delay(10)
                    }
                    session.receiveHandoff(Intent().putExtra("bookUrl", book.bookUrl))
                } finally { writer.endTransaction() }
                assertTrue(runCatching { queued.await() }.exceptionOrNull() is CancellationException)
            }
            assertEquals(BookInfoChildPhase.Requested, appDb.bookInfoRequestDao.child(child)!!.phase)
            assertEquals(book, appDb.bookDao.getBook(book.bookUrl))
        } finally {
            if (writer.inTransaction()) writer.endTransaction()
            writer.close(); session.close()
        }
    }

    @Test fun aRetainedHostCanAcceptTheSameSavedTransferWithoutReplayingItsConsumedCapability() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession()
        try {
            sender.prepare(Intent().putExtra("bookUrl", book.bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child), BookType.video)
            val launch = Intent(); sender.handoffTo(launch) { }
            val saved = Intent(launch)
            receiver.prepare(launch, BookType.video)
            val original = checkNotNull(appDb.bookInfoRequestDao.child(child))
            val originalBook = appDb.bookDao.getBook(book.bookUrl)
            assertNotNull(receiver.prepare(saved, BookType.video))
            val current = checkNotNull(appDb.bookInfoRequestDao.child(child))
            assertEquals(original, current.copy(updatedAt = original.updatedAt))
            assertTrue(current.updatedAt >= original.updatedAt)
            assertEquals(originalBook, appDb.bookDao.getBook(book.bookUrl))
            assertEquals(child, receiver.requestId)
        } finally { sender.close(); receiver.close() }
    }

    @Test fun closingAColdUnclaimedActorAlsoRetiresItsDeadSendersTicket() = handoffFixture { book, _ ->
        val tickets = MediaHandoffTickets.default
        val input = Intent().putExtra("bookUrl", book.bookUrl)
        val parent = tickets.create(input); val child = tickets.create(input, parent)
        try {
            MediaHandoffTickets.expire(Intent(input).putExtra(MediaHandoffTickets.EXTRA, child))
            assertTrue(runCatching { tickets.requireActive(child, input) }.isFailure)
            assertTrue("A dead sender must not remain resumable after its cold child closes",
                runCatching { tickets.requireActive(parent, input) }.isFailure)
        } finally { tickets.retire(child); tickets.retire(parent) }
    }

    @Test fun closingAnUnacceptedChildKeepsItsStillLiveSendingActor() = handoffFixture { book, _ ->
        val sender = PlatformMediaReturnSession(); val source = PlatformMediaReturnSession()
        val input = Intent().putExtra("bookUrl", book.bookUrl)
        val first = Intent(input); val second = Intent(input)
        try {
            sender.prepare(Intent(input), BookType.video)
            sender.handoffTo(first) { }; source.prepare(first, BookType.video)
            source.handoffTo(second) { }
            MediaHandoffTickets.expire(second)
            MediaHandoffTickets.default.requireActive(checkNotNull(first.getStringExtra(MediaHandoffTickets.EXTRA)), first)
            assertTrue(runCatching {
                MediaHandoffTickets.default.requireActive(checkNotNull(second.getStringExtra(MediaHandoffTickets.EXTRA)), second)
            }.isFailure)
        } finally { sender.stopPendingHandoff(); sender.close(); source.close() }
    }

    @Test fun aRejectedReplayCannotExpireAnotherLiveReceiversTicket() = handoffFixture { book, child ->
        val sender = PlatformMediaReturnSession(); val receiver = PlatformMediaReturnSession(); val replay = PlatformMediaReturnSession()
        val input = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
        val launch = Intent()
        try {
            sender.prepare(input, BookType.video); sender.handoffTo(launch) { }
            val obsolete = Intent(launch); receiver.prepare(launch, BookType.video)
            val original = appDb.bookInfoRequestDao.child(child)
            assertTrue(runCatching { replay.prepare(obsolete, BookType.video) }.isFailure)
            MediaHandoffTickets.expire(obsolete)
            MediaHandoffTickets.default.requireActive(checkNotNull(launch.getStringExtra(MediaHandoffTickets.EXTRA)), launch)
            assertEquals(original, appDb.bookInfoRequestDao.child(child))
        } finally { sender.close(); receiver.close(); replay.close() }
    }

    @Test fun closingAColdActorDuringTheSqliteWriterWaitRetiresBothTickets() = coldActorWriterWait(close = true)

    @Test fun replacingAColdActorDuringTheSqliteWriterWaitRetiresOnlyItsOldTickets() = coldActorWriterWait(close = false)

    private fun coldActorWriterWait(close: Boolean) = handoffFixture { book, child ->
        val root = File(appCtx.cacheDir, "cold-actor-${UUID.randomUUID()}")
        val tickets = MediaHandoffTickets(root)
        val input = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
        val parent = tickets.create(input); val ticket = tickets.create(input, parent)
        val receiver = PlatformMediaReturnSession(tickets)
        val thread = globalExecutor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
        val writer = android.database.sqlite.SQLiteDatabase.openDatabase(checkNotNull(appDb.openHelper.writableDatabase.path),
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        try {
            writer.beginTransaction()
            coroutineScope {
                val pending = async(Dispatchers.IO) {
                    receiver.prepare(Intent(input).putExtra(MediaHandoffTickets.EXTRA, ticket), BookType.video)
                }
                try {
                    withTimeout(5000) {
                        while (!thread.stackTrace.any {
                            it.className == "android.database.sqlite.SQLiteSession" && it.methodName.startsWith("beginTransaction")
                        }) delay(10)
                    }
                    if (close) receiver.close()
                    else receiver.receiveHandoff(Intent().putExtra("bookUrl", book.bookUrl))
                    assertTrue(runCatching { tickets.requireActive(ticket, input) }.isFailure)
                    assertTrue(runCatching { tickets.requireActive(parent, input) }.isFailure)
                } finally { writer.endTransaction() }
                assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            }
            assertEquals(BookInfoChildPhase.Requested, appDb.bookInfoRequestDao.child(child)!!.phase)
            assertEquals(book, appDb.bookDao.getBook(book.bookUrl))
            if (!close) assertNull(receiver.prepare(Intent().putExtra("bookUrl", book.bookUrl), BookType.video))
        } finally {
            if (writer.inTransaction()) writer.endTransaction()
            writer.close(); receiver.close(); root.deleteRecursively()
        }
    }

    @Test fun closingAColdActorBeforePreparationLeavesNoOrphanAndDoesNotClaimRoom() = handoffFixture { book, child ->
        val root = File(appCtx.cacheDir, "cold-unprepared-${UUID.randomUUID()}")
        val tickets = MediaHandoffTickets(root); val receiver = PlatformMediaReturnSession(tickets)
        val input = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
        val parent = tickets.create(input); val ticket = tickets.create(input, parent)
        try {
            receiver.receiveHandoff(Intent(input).putExtra(MediaHandoffTickets.EXTRA, ticket))
            receiver.close()
            assertTrue(runCatching { tickets.requireActive(ticket, input) }.isFailure)
            assertTrue(runCatching { tickets.requireActive(parent, input) }.isFailure)
            assertEquals(BookInfoChildPhase.Requested, appDb.bookInfoRequestDao.child(child)!!.phase)
            assertEquals(book, appDb.bookDao.getBook(book.bookUrl))
        } finally { receiver.close(); root.deleteRecursively() }
    }

    @Test fun aColdUnpreparedHandoffReturnsItsDeadSendersOpenReceipt() = coldOpenReturn(waitForWriter = false, wrongCapability = false)

    @Test fun aColdHandoffClosedDuringTheWriterWaitReturnsItsDeadSendersOpenReceipt() = coldOpenReturn(waitForWriter = true, wrongCapability = false)

    @Test fun aColdUnpreparedHandoffWithAWrongSourceCapabilityCannotReturnTheReceipt() = coldOpenReturn(waitForWriter = false, wrongCapability = true)

    private fun coldOpenReturn(waitForWriter: Boolean, wrongCapability: Boolean) = handoffFixture { book, child ->
        val repository = PlatformReaderReturnRepository(appDb)
        val sourceToken = UUID.randomUUID().toString()
        val original = repository.prepareMedia(child, book.bookUrl, sourceToken, BookType.video)
        val savedBook = appDb.bookDao.getBook(book.bookUrl)
        val root = File(appCtx.cacheDir, "cold-return-${UUID.randomUUID()}")
        val tickets = MediaHandoffTickets(root); val receiver = PlatformMediaReturnSession(tickets)
        val launch = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
            .putExtra("platformMediaHandoffToken", if (wrongCapability) UUID.randomUUID().toString() else sourceToken)
        val parent = tickets.create(launch); val ticket = tickets.create(launch, parent)
        launch.putExtra(MediaHandoffTickets.EXTRA, ticket)
        try {
            if (waitForWriter) {
                val thread = globalExecutor.submit<Thread> { Thread.currentThread() }.get(10, TimeUnit.SECONDS)
                val writer = android.database.sqlite.SQLiteDatabase.openDatabase(checkNotNull(appDb.openHelper.writableDatabase.path), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                try {
                    writer.beginTransaction()
                    coroutineScope {
                        val preparation = async(Dispatchers.IO) { receiver.prepare(launch, BookType.video) }
                        try {
                            withTimeout(5000) {
                                while (!thread.stackTrace.any { it.className == "android.database.sqlite.SQLiteSession" && it.methodName.startsWith("beginTransaction") }) delay(10)
                            }
                            receiver.retireIncomingHandoff() // Native onDestroy retires metadata before its final save/close.
                            receiver.close()
                        } finally { writer.endTransaction() }
                        assertTrue(runCatching { preparation.await() }.exceptionOrNull() is CancellationException)
                    }
                } finally { if (writer.inTransaction()) writer.endTransaction(); writer.close() }
            } else {
                receiver.receiveHandoff(launch)
                receiver.retireIncomingHandoff()
                receiver.close()
            }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            val result = checkNotNull(appDb.bookInfoRequestDao.child(child))
            assertEquals(if (wrongCapability) BookInfoChildPhase.Displayed else BookInfoChildPhase.Returned, result.phase)
            assertEquals(original.owner.producerToken, result.producerToken)
            assertEquals(savedBook, appDb.bookDao.getBook(book.bookUrl))
            assertTrue(runCatching { tickets.requireActive(ticket, launch) }.isFailure)
            assertTrue(runCatching { tickets.requireActive(parent, launch) }.isFailure)
        } finally { receiver.close(); root.deleteRecursively() }
    }

    @Test fun coldReturnCannotOvertakeTheFinalQueuedPositionSave() = coldReturnFixture { book, child, token, receiver, launch ->
        val trigger = "cold_return_${UUID.randomUUID().toString().replace("-", "")}"
        val db = appDb.openHelper.writableDatabase
        db.execSQL("""CREATE TRIGGER $trigger BEFORE UPDATE OF phase ON book_info_child_requests
            WHEN NEW.id = '$child' AND NEW.phase = 'Returned' BEGIN
            SELECT CASE WHEN (SELECT durChapterPos FROM books WHERE bookUrl = '${book.bookUrl}') != 41000
            THEN RAISE(ABORT, 'Cold return overtook final save') END; END""")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            receiver.receiveHandoff(launch); receiver.retireIncomingHandoff()
            val save = globalExecutor.submit {
                val current = checkNotNull(appDb.bookDao.getBook(book.bookUrl)); current.durChapterPos = 41000
                appDb.bookDao.update(current)
            }
            receiver.close()
            release.countDown(); blocker.get(10, TimeUnit.SECONDS); save.get(10, TimeUnit.SECONDS)
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
            assertEquals(token, appDb.bookInfoRequestDao.child(child)!!.producerToken)
            assertEquals(41000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS); db.execSQL("DROP TRIGGER IF EXISTS $trigger") }
    }

    @Test fun aDeferredColdReturnCannotFinishANewerProducer() = coldReturnFixture { book, child, _, receiver, launch ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            receiver.receiveHandoff(launch); receiver.close()
            val newer = PlatformReaderReturnRepository(appDb).prepareMedia(child, book.bookUrl,
                UUID.randomUUID().toString(), BookType.video).owner
            release.countDown(); blocker.get(10, TimeUnit.SECONDS); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(newer, appDb.bookInfoRequestDao.child(child))
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
    }

    @Test fun aColdReturnCannotUseTheOldProducerForAnotherBook() = coldReturnFixture { book, child, _, receiver, launch ->
        val other = Book(bookUrl = "cold-other-${UUID.randomUUID()}", type = BookType.video)
        appDb.bookDao.insert(other)
        val root = File(appCtx.cacheDir, "cold-other-tickets-${UUID.randomUUID()}")
        val tickets = MediaHandoffTickets(root); val wrong = PlatformMediaReturnSession(tickets)
        try {
            val input = Intent(launch).putExtra("bookUrl", other.bookUrl)
            input.putExtra(MediaHandoffTickets.EXTRA, tickets.create(input))
            wrong.receiveHandoff(input); wrong.close(); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child)!!.phase)
            assertEquals(12000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        } finally { wrong.close(); receiver.close(); root.deleteRecursively(); appDb.bookDao.delete(other) }
    }

    @Test fun aColdReturnCreatedDuringQuarantineIsNotReplayedAfterReady() = coldReturnFixture { _, child, _, receiver, launch ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            receiver.receiveHandoff(launch); receiver.retireIncomingHandoff()
            RestoreRecoveryGate.requireRecovery(); receiver.close()
            RestoreRecoveryGate.onStartupReady()
            release.countDown(); blocker.get(10, TimeUnit.SECONDS); globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child)!!.phase)
        } finally { RestoreRecoveryGate.onStartupReady(); release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
    }

    @Test fun replacingAColdUnpreparedHandoffReturnsOnlyTheOldReceiptAndKeepsTheNewInputUsable() = coldReturnFixture { book, child, _, receiver, launch ->
        receiver.receiveHandoff(launch)
        val plain = Intent().putExtra("bookUrl", book.bookUrl)
        receiver.receiveHandoff(plain)
        assertNull(receiver.prepare(plain, BookType.video))
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Returned, appDb.bookInfoRequestDao.child(child)!!.phase)
        assertEquals(12000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    private fun coldReturnFixture(block: suspend (Book, String, String, PlatformMediaReturnSession, Intent) -> Unit) = handoffFixture { book, child ->
        val token = UUID.randomUUID().toString()
        PlatformReaderReturnRepository(appDb).prepareMedia(child, book.bookUrl, token, BookType.video)
        val root = File(appCtx.cacheDir, "cold-return-controls-${UUID.randomUUID()}")
        val tickets = MediaHandoffTickets(root); val receiver = PlatformMediaReturnSession(tickets)
        val input = Intent().putExtra("bookUrl", book.bookUrl).putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)
            .putExtra("platformMediaHandoffToken", token)
        val parent = tickets.create(input); input.putExtra(MediaHandoffTickets.EXTRA, tickets.create(input, parent))
        try { block(book, child, token, receiver, input) }
        finally { receiver.close(); globalExecutor.submit {}.get(10, TimeUnit.SECONDS); root.deleteRecursively() }
    }

    // Recreate the default ticket store's process-local registry without deleting its persistent files.
    // This models lost process memory only; real SIGKILL recovery is verified separately.
    private fun freshProcessMediaSession() = PlatformMediaReturnSession(
        MediaHandoffTickets(File(appCtx.noBackupFilesDir, "media-handoff-tickets")))

    private fun handoffFixture(block: suspend (Book, String) -> Unit) = runBlocking {
        val id = UUID.randomUUID().toString(); val parent = "handoff-parent-$id"; val child = "handoff-child-$id"
        val book = Book(bookUrl = "room-handoff-$id", name = id, type = BookType.video)
        try {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "https://$id.invalid/0", title = id, index = 0))
            appDb.bookInfoRequestDao.claim(BookInfoRequest(parent, book.bookUrl, book.bookUrl, id, updatedAt = 1), id)
            checkNotNull(appDb.bookInfoRequestDao.begin(parent, id, 0, book.bookUrl, child, BookInfoChildKind.Reader,
                BookInfoNavigationPosition(0, 12000), 2))
            block(book, child)
        } finally {
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            appDb.bookInfoRequestDao.close(parent, id)
            MediaHandoffStops.discard(child)
            appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
        }
    }

}
