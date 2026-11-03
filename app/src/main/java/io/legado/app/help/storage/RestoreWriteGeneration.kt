package io.legado.app.help.storage

import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class RestoreWorkInvalidatedException : CancellationException("Data restore superseded this operation")

/** Process-local lifetime for work which opts into the restore write boundary. */
internal class RestoreWriteGeneration {
    private val sequence = AtomicLong()
    val processId: String = UUID.randomUUID().toString()

    fun capture(): Token = Token(this, sequence.get())

    /** Called while holding backupRestoreMutex, after restore preflight and before mutation. */
    fun advance() {
        sequence.updateAndGet { previous ->
            check(previous < Long.MAX_VALUE)
            previous + 1
        }
    }

    class Token internal constructor(private val owner: RestoreWriteGeneration, val sequence: Long) {
        val processId get() = owner.processId
        val isCurrent get() = owner.sequence.get() == sequence
        fun requireCurrent() {
            if (!isCurrent) throw RestoreWorkInvalidatedException()
        }
    }
}

internal val restoreWriteGeneration = RestoreWriteGeneration()
