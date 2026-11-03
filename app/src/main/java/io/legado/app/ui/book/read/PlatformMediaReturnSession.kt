package io.legado.app.ui.book.read

import android.content.Intent
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookInfoChildRequest
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.data.repository.MediaHandoffReturns
import io.legado.app.data.repository.MediaHandoffStops
import io.legado.app.data.repository.MediaHandoffTickets
import io.legado.app.help.globalExecutor
import io.legado.app.help.storage.RestoreRecoveryGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.CoroutineContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Retained by the mature host's ViewModel; finishing cannot leave a late claim alive. */
internal class PlatformMediaReturnSession(private val tickets: MediaHandoffTickets = MediaHandoffTickets.default,
    private val returns: MediaHandoffReturns = MediaHandoffReturns.default) {
    companion object {
        private const val HANDOFF_TOKEN = "platformMediaHandoffToken"
        private const val HANDOFF_PENDING = "platformMediaPendingHandoff"
        private val pendingHandoffs = ConcurrentHashMap<String, PendingHandoff>()
    }
    private class PendingHandoff(val id: String, val owner: BookInfoChildRequest?) {
        var stopped = false
        var receiver: PlatformMediaReturnSession? = null
    }
    private var outgoing: PendingHandoff? = null
    private var incoming: PendingHandoff? = null
    private var incomingTicket: String? = null
    private var ownsIncomingTicket = false
    private var incomingIntent: Intent? = null
    private var incomingBinding: MediaHandoffTickets.Binding? = null
    private val retiredReturns = linkedSetOf<MediaHandoffReturns.Record>()
    private val mutableStopped = MutableStateFlow(false)
    val stopEvents = mutableStopped.asStateFlow()
    var handoffStopped: Boolean
        get() = mutableStopped.value
        private set(value) { mutableStopped.value = value }

    /** Bind before queuing preparation, so a stop can revoke even an unclaimed launch. */
    fun receiveHandoff(intent: Intent): Long {
        val generation = synchronized(lock) {
            inputGeneration += 1
            inputRequestId = intent.getStringExtra(PlatformReaderReturnRepository.REQUEST_ID)
            inputBookUrl = intent.getStringExtra("bookUrl")
            inputTicket = intent.getStringExtra(MediaHandoffTickets.EXTRA)
            inputGeneration
        }
        val ticket = intent.getStringExtra(MediaHandoffTickets.EXTRA)
        if (incomingTicket != ticket) {
            retireIncomingHandoff()
            queueRetiredReturns()
            incoming?.let { previous ->
                synchronized(previous) {
                    if (previous.receiver === this) previous.receiver = null
                }
            }
            incoming = null
            incomingTicket = ticket
        }
        synchronized(lock) { incomingIntent = Intent(intent) }
        val id = intent.getStringExtra(HANDOFF_PENDING) ?: return generation
        if (ticket != id) return generation
        intent.removeExtra(HANDOFF_PENDING)
        val pending = pendingHandoffs.remove(id) ?: return generation
        synchronized(pending) {
            incoming = pending
            runCatching { bindIncomingTicket(id, intent) }
                .onFailure { AppLog.put("绑定媒体交接状态失败", it) }
            pending.receiver = this
            if (pending.stopped) stopIncomingHandoff()
        }
        return generation
    }

    /** Only an explicit stop revokes an outgoing launch; ordinary sender destruction does not. */
    fun stopPendingHandoff() {
        val pending = outgoing
        if (pending == null) {
            listOfNotNull(owner, incoming?.owner).distinctBy { it.id }.forEach(::recordStop)
            incomingTicket?.let(::revokeTicket)
            close()
            handoffStopped = true
            return
        }
        synchronized(pending) {
            pending.stopped = true
            revokeTicket(pending.id)
            pending.owner?.let(::recordStop)
            pending.receiver?.stopIncomingHandoff()
            pending.owner?.let(::queueReturn)
        }
    }

    private fun recordStop(owner: BookInfoChildRequest) {
        runCatching { MediaHandoffStops.record(owner) }.onFailure { AppLog.put("保存媒体停止请求失败", it) }
    }

    private fun revokeTicket(id: String) {
        runCatching { tickets.revoke(id) }.onFailure { AppLog.put("清理媒体交接状态失败", it) }
    }

    private fun bindIncomingTicket(id: String, intent: Intent) {
        val binding = tickets.bind(id, intent, this)
        val retained = synchronized(lock) {
            if (!closing && inputTicket == id) {
                incomingBinding = binding
                ownsIncomingTicket = true
                true
            } else false
        }
        if (!retained) tickets.release(binding, this)
    }

    fun retireIncomingHandoff() {
        val (binding, intent) = synchronized(lock) {
            val old = incomingBinding to incomingIntent
            incomingBinding = null
            incomingIntent = null
            ownsIncomingTicket = false
            old
        }
        runCatching {
            val beforeRetire: (MediaHandoffTickets.Binding) -> Unit = { retiring ->
                val id = intent?.getStringExtra(PlatformReaderReturnRepository.REQUEST_ID)
                val bookUrl = intent?.getStringExtra("bookUrl")
                val previousToken = intent?.getStringExtra(HANDOFF_TOKEN)
                // Sender destruction after forwarding must never publish a receiver close.
                if (outgoing == null && !RestoreRecoveryGate.isQuarantined &&
                    !id.isNullOrBlank() && !bookUrl.isNullOrBlank() && !previousToken.isNullOrBlank()) {
                    val record = returns.record(id, bookUrl, previousToken, token, retiring)
                    synchronized(lock) { retiredReturns += record }
                }
            }
            if (binding != null) tickets.release(binding, this, beforeRetire)
            else if (intent != null) tickets.expireUnbound(intent, beforeRetire)
        }.onFailure { AppLog.put("清理媒体交接状态失败", it) }
    }

    fun needsPreparation(intent: Intent) = intent.hasExtra(MediaHandoffTickets.EXTRA) ||
        intent.hasExtra(PlatformReaderReturnRepository.REQUEST_ID) || intent.hasExtra("chapterIndex") || owner != null

    fun isOutgoingHandoff(intent: Intent) = outgoing?.let {
        it.id == intent.getStringExtra(MediaHandoffTickets.EXTRA)
    } == true

    private fun stopIncomingHandoff() {
        if (outgoing != null) stopPendingHandoff()
        close()
        handoffStopped = true
    }
    private val repository by lazy { PlatformReaderReturnRepository(appDb) }
    private val token = UUID.randomUUID().toString()
    private val lock = Any()
    private var closing = false
    private var inputGeneration = 0L
    private var inputRequestId: String? = null
    private var inputBookUrl: String? = null
    private var inputTicket: String? = null

    private fun requireCurrentInput(generation: Long, context: CoroutineContext) {
        context.ensureActive()
        synchronized(lock) {
            if (closing || inputGeneration != generation) throw CancellationException("Media input was superseded")
        }
    }
    private var seekPending = false
    @Volatile private var owner: BookInfoChildRequest? = null
    val requestId: String? get() = owner?.id

    suspend fun prepare(intent: Intent, expectedType: Int,
        generation: Long = receiveHandoff(intent)): PlatformReaderReturnRepository.MediaPreparation? {
        val id = intent.getStringExtra(PlatformReaderReturnRepository.REQUEST_ID)
        val ticket = intent.getStringExtra(MediaHandoffTickets.EXTRA)
        return withContext(globalExecutor.asCoroutineDispatcher()) {
            val context = currentCoroutineContext()
            val requireCurrent = { requireCurrentInput(generation, context) }
            requireCurrent()
            RestoreRecoveryGate.requireBusinessReady()
            ticket?.let {
                try { bindIncomingTicket(it, intent) }
                catch (failure: Exception) {
                    // A closed receipt may have no visible bridge left to reconcile its stop.
                    id?.let(repository::reconcilePendingStop)
                    throw failure
                }
            }
            check(!intent.hasExtra(MediaHandoffTickets.EXTRA) || ticket != null) { "Invalid playback handoff" }
            id?.let { repository.reconcilePendingReturns(it, returns, tickets) }
            // Recovery can retire a proof that was valid when this cold host first bound it.
            ticket?.let { tickets.requireActive(it, intent) }
            if (owner?.id != id) finishBeforeNextRequest()
            if (id == null && intent.hasExtra("chapterIndex")) {
                repository.seekMedia(checkNotNull(intent.getStringExtra("bookUrl")),
                    intent.getIntExtra("chapterIndex", 0), expectedType, requireCurrent)
                // A retained Intent must not seek again after configuration recreation.
                intent.removeExtra("chapterIndex")
            }
            val previousToken = intent.getStringExtra(HANDOFF_TOKEN)
            val prepared = id?.let {
                val key = checkNotNull(intent.getStringExtra("bookUrl"))
                if (previousToken != null && owner?.id != it) repository.transferMedia(it, key, previousToken, token, expectedType, requireCurrent)
                else repository.prepareMedia(it, key, token, expectedType, requireCurrent)
            }
            if (previousToken != null) {
                intent.removeExtra(HANDOFF_TOKEN)
                if (prepared == null) {
                    // A live binding or a validated persistent actor ticket proves this launch.
                    // A legacy saved capability alone cannot reopen a closed receipt.
                    check(incoming?.owner?.id == id || ticket != null) { "Playback handoff expired" }
                    intent.removeExtra(PlatformReaderReturnRepository.REQUEST_ID)
                }
            }
            var obsolete = false
            var staleOwner: BookInfoChildRequest? = null
            val result = synchronized(lock) {
                if (closing || inputGeneration != generation || !context.isActive) {
                    obsolete = true
                    if (closing) prepared?.owner?.let(::queueReturn)
                    else if (prepared != null && inputRequestId == prepared.owner.id &&
                        inputBookUrl == prepared.owner.returnBookUrl && inputTicket == ticket) {
                        // Configuration recreation of the same input can reuse a claim that committed
                        // just before cancellation. It must not return that still-current receipt.
                        owner = prepared.owner
                        seekPending = seekPending || prepared.positionApplied
                    } else staleOwner = prepared?.owner
                    null
                } else {
                    owner = prepared?.owner
                    ticket?.let {
                        ownsIncomingTicket = true
                        runCatching { tickets.accept(it) }.onFailure { AppLog.put("清理旧媒体交接状态失败", it) }
                    }
                    seekPending = seekPending || prepared?.positionApplied == true
                    prepared?.copy(positionApplied = seekPending)
                }
            }
            if (obsolete) {
                // This result never reached a player. Retire it on this queue before a successor
                // claims, rather than enqueueing a late return behind the successor's preparation.
                staleOwner?.takeUnless { RestoreRecoveryGate.isQuarantined }?.let {
                    repository.finish(it)
                    repository.reconcilePendingStop(it.id)
                }
                throw CancellationException("Media input was superseded")
            }
            result
        }
    }

    /** Launch first; a rejected launch leaves the original owner able to finish normally. */
    fun handoffTo(intent: Intent, launch: (Intent) -> Unit) {
        synchronized(lock) {
            RestoreRecoveryGate.requireBusinessReady()
            check(!closing) { "Media host has finished" }
            val current = owner
            intent.removeExtra(PlatformReaderReturnRepository.REQUEST_ID)
            intent.removeExtra(HANDOFF_TOKEN)
            intent.removeExtra(HANDOFF_PENDING)
            intent.removeExtra(MediaHandoffTickets.EXTRA)
            if (current != null) {
                intent.putExtra(PlatformReaderReturnRepository.REQUEST_ID, current.id)
                intent.putExtra(HANDOFF_TOKEN, current.producerToken)
                intent.putExtra("bookUrl", current.returnBookUrl)
            }
            val pending = PendingHandoff(tickets.create(intent, incomingTicket.takeIf { ownsIncomingTicket }), current)
            pendingHandoffs[pending.id] = pending
            intent.putExtra(HANDOFF_PENDING, pending.id)
            intent.putExtra(MediaHandoffTickets.EXTRA, pending.id)
            try {
                launch(intent)
            } catch (failure: Throwable) {
                pendingHandoffs.remove(pending.id)
                revokeTicket(pending.id)
                intent.removeExtra(HANDOFF_PENDING)
                intent.removeExtra(MediaHandoffTickets.EXTRA)
                throw failure
            }
            outgoing = pending
            owner = null
            seekPending = false
            closing = true
        }
    }

    fun abandonForRecovery() {
        incomingTicket?.let(::revokeTicket)
        retireIncomingHandoff()
        outgoing?.let { pendingHandoffs.remove(it.id); revokeTicket(it.id) }
        val abandoned = synchronized(lock) {
            closing = true; owner = null; seekPending = false
            retiredReturns.toList().also { retiredReturns.clear() }
        }
        abandoned.forEach { pending -> runCatching { returns.clear(pending) }
            .onFailure { AppLog.put("清理媒体返回记录失败", it) } }
    }

    fun playbackPositionApplied() {
        synchronized(lock) { seekPending = false }
    }

    fun replaceSource(oldKey: String?, newKey: String, replace: () -> Unit) {
        val current = owner
        if (current == null || oldKey == null) replace()
        else repository.replaceSource(current, oldKey, newKey, replace)
    }

    /** The host enqueues its final save before calling this, on the same persistence queue. */
    fun close() {
        synchronized(lock) { closing = true }
        release()
        // If the receiver exits before its queue slot, the old capability still owns the receipt.
        incoming?.owner?.let(::queueReturn)
        retireIncomingHandoff()
        queueRetiredReturns()
    }

    /** prepare already owns the persistence queue: finish here, before claiming the next request. */
    private fun finishBeforeNextRequest() {
        val previous = synchronized(lock) { owner } ?: return
        repository.finish(previous)
        synchronized(lock) {
            if (owner === previous) {
                owner = null
                seekPending = false
            }
        }
    }

    private fun release() {
        val current = synchronized(lock) { owner.also { owner = null; seekPending = false } } ?: return
        queueReturn(current)
    }

    /** Metadata retirement can precede the host's final save; enqueue only on close/replacement. */
    private fun queueRetiredReturns() {
        val returns = synchronized(lock) { retiredReturns.toList().also { retiredReturns.clear() } }
        for (pending in returns) {
            if (RestoreRecoveryGate.isQuarantined) {
                runCatching { this.returns.clear(pending) }.onFailure { AppLog.put("清理媒体返回记录失败", it) }
                continue
            }
            globalExecutor.execute {
                runCatching {
                    if (RestoreRecoveryGate.isQuarantined) this.returns.clear(pending)
                    else {
                        this.returns.arm(pending)
                        repository.reconcilePendingReturns(pending.id, this.returns, tickets)
                        repository.reconcilePendingStop(pending.id)
                    }
                }.onFailure { AppLog.put("保存准备中的媒体返回结果失败", it) }
            }
        }
    }

    private fun queueReturn(current: BookInfoChildRequest) {
        globalExecutor.execute {
            if (RestoreRecoveryGate.isQuarantined) return@execute
            runCatching {
                repository.finish(current)
                repository.reconcilePendingStop(current.id)
            }.onFailure { AppLog.put("保存媒体返回结果失败", it) }
        }
    }
}
