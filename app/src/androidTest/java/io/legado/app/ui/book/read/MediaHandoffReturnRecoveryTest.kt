package io.legado.app.ui.book.read

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.repository.MediaHandoffReturns
import io.legado.app.data.repository.MediaHandoffTickets
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.help.globalExecutor
import io.legado.app.help.storage.RestoreRecoveryGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaHandoffReturnRecoveryTest {
    @Test fun lostMemoryAfterTicketRetirementRecoversTheOldReceiptWithoutSeeking() = fixture { f ->
        f.session.receiveHandoff(f.intent)
        f.session.retireIncomingHandoff() // Model losing this host before close() queues its return.
        assertEquals(BookInfoChildPhase.Displayed, f.child().phase)
        assertFalse(f.ticketFile.exists())
        f.recover()
        assertEquals(BookInfoChildPhase.Returned, f.child().phase)
        assertEquals(f.sourceToken, f.child().producerToken)
        assertEquals(12000, appDb.bookDao.getBook(f.book.bookUrl)!!.durChapterPos)
        assertTrue(f.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun lostMemoryBeforeTicketDeletionRecoversAndRetiresTheValidatedProof() = fixture { f ->
        val actor = Any()
        val binding = f.tickets.bind(f.ticket, f.intent, actor)
        assertTrue(runCatching {
            f.tickets.release(binding, actor) { retiring ->
                f.returns.record(f.id, f.book.bookUrl, f.sourceToken, UUID.randomUUID().toString(), retiring)
                error("Model process loss after durable record, before metadata deletion")
            }
        }.isFailure)
        assertTrue(f.ticketFile.exists())
        f.recover()
        assertEquals(BookInfoChildPhase.Returned, f.child().phase)
        assertFalse(f.ticketFile.exists())
        assertFalse(File(f.ticketRoot, f.parentTicket + ".json").exists())
    }

    @Test fun aColdActorCannotReopenAfterRecoveryRetiresItsPreviouslyValidProof() = fixture { f ->
        val binding = f.tickets.bind(f.ticket, f.intent, Any())
        f.returns.record(f.id, f.book.bookUrl, f.sourceToken, UUID.randomUUID().toString(), binding)
        val cold = PlatformMediaReturnSession(MediaHandoffTickets(f.ticketRoot), MediaHandoffReturns(f.journalRoot))
        try {
            assertTrue(runCatching { cold.prepare(Intent(f.intent), BookType.video) }.isFailure)
            drain()
            assertEquals(BookInfoChildPhase.Returned, f.child().phase)
            assertEquals(f.sourceToken, f.child().producerToken)
            assertFalse(f.ticketFile.exists())
        } finally { cold.close(); drain() }
    }

    @Test fun aLiveProcessCannotReconcileBeforeTheFinalPositionSave() = fixture { f ->
        f.session.receiveHandoff(f.intent); f.session.retireIncomingHandoff()
        f.repository.reconcilePendingReturns(f.id, f.returns, f.tickets)
        assertEquals(BookInfoChildPhase.Displayed, f.child().phase)
        globalExecutor.submit {
            val book = appDb.bookDao.getBook(f.book.bookUrl)!!
            book.durChapterPos = 41000
            appDb.bookDao.update(book)
        }
        f.session.close(); drain()
        assertEquals(BookInfoChildPhase.Returned, f.child().phase)
        assertEquals(41000, appDb.bookDao.getBook(f.book.bookUrl)!!.durChapterPos)
        assertTrue(f.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun aRecoveredOldReturnCannotFinishANewerProducer() = fixture { f ->
        f.session.receiveHandoff(f.intent); f.session.retireIncomingHandoff()
        val newer = f.repository.prepareMedia(f.id, f.book.bookUrl, UUID.randomUUID().toString(), BookType.video).owner
        f.recover()
        assertEquals(newer, f.child())
        assertTrue(f.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun aCommittedReceiverCanBeReturnedIfItsHostDiedBeforePublishingTheOwner() = fixture { f ->
        f.session.receiveHandoff(f.intent); f.session.retireIncomingHandoff()
        val record = MediaHandoffReturns(f.journalRoot).ready(f.id).single()
        f.repository.transferMedia(f.id, f.book.bookUrl, f.sourceToken, record.receiverToken, BookType.video)
        f.recover()
        assertEquals(BookInfoChildPhase.Returned, f.child().phase)
        assertEquals(record.receiverToken, f.child().producerToken)
    }

    @Test fun quarantineDiscardsTheLivePendingReturnBeforeReady() = fixture { f ->
        f.session.receiveHandoff(f.intent); f.session.retireIncomingHandoff()
        RestoreRecoveryGate.requireRecovery()
        try { f.session.close() } finally { RestoreRecoveryGate.onStartupReady() }
        drain(); f.recover()
        assertEquals(BookInfoChildPhase.Displayed, f.child().phase)
        assertTrue(f.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun corruptReturnEvidenceCannotFinishAReceipt() = fixture { f ->
        f.session.receiveHandoff(f.intent); f.session.retireIncomingHandoff()
        f.journalRoot.listFiles()!!.single().writeText("{\"payload\":\"{}\",\"sha256\":\"wrong\"}")
        assertTrue(runCatching { f.recover() }.isFailure)
        assertEquals(BookInfoChildPhase.Displayed, f.child().phase)
    }

    @Test fun recoveryAfterRoomCommitBeforeJournalCleanupIsIdempotent() = fixture { f ->
        f.session.receiveHandoff(f.intent); f.session.retireIncomingHandoff()
        assertTrue(f.repository.finish(f.child()))
        val committed = f.child()
        f.recover(); f.recover()
        assertEquals(committed, f.child())
        assertTrue(f.journalRoot.listFiles()!!.isEmpty())
    }

    private class Fixture {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "return-recovery-$id", name = id, type = BookType.video)
        val root = File(appCtx.cacheDir, "return-recovery-$id")
        val ticketRoot = File(root, "tickets")
        val journalRoot = File(root, "returns")
        val tickets = MediaHandoffTickets(ticketRoot)
        val returns = MediaHandoffReturns(journalRoot)
        val session = PlatformMediaReturnSession(tickets, returns)
        val repository = PlatformReaderReturnRepository(appDb)
        val sourceToken = UUID.randomUUID().toString()
        val intent = Intent().putExtra("bookUrl", book.bookUrl)
            .putExtra(PlatformReaderReturnRepository.REQUEST_ID, id).putExtra("platformMediaHandoffToken", sourceToken)
        val parentTicket = tickets.create(intent)
        val ticket = tickets.create(intent, parentTicket).also { intent.putExtra(MediaHandoffTickets.EXTRA, it) }
        val ticketFile get() = File(ticketRoot, "$ticket.json")
        fun child() = checkNotNull(appDb.bookInfoRequestDao.child(id))
        // Recreate process-local registries over the actual files. This is not a real SIGKILL.
        fun recover() = repository.reconcilePendingReturns(id, MediaHandoffReturns(journalRoot), MediaHandoffTickets(ticketRoot))
    }

    private fun fixture(test: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        val dao = appDb.bookInfoRequestDao
        try {
            appDb.bookDao.insert(f.book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = f.book.bookUrl, url = "https://${f.id}.invalid/0", index = 0, title = f.id))
            dao.claim(BookInfoRequest(f.id, f.book.bookUrl, f.book.bookUrl, f.id, updatedAt = 1), f.id)
            checkNotNull(dao.begin(f.id, f.id, 0, f.book.bookUrl, f.id, BookInfoChildKind.Reader, BookInfoNavigationPosition(0, 12000), 2))
            f.repository.prepareMedia(f.id, f.book.bookUrl, f.sourceToken, BookType.video)
            test(f)
        } finally {
            RestoreRecoveryGate.onStartupReady()
            f.session.close(); drain()
            dao.close(f.id, f.id)
            appDb.bookChapterDao.delByBook(f.book.bookUrl); appDb.bookDao.delete(f.book)
            f.root.deleteRecursively()
        }
    }

    private fun drain() { globalExecutor.submit {}.get(10, TimeUnit.SECONDS) }
}
