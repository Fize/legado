package io.legado.app.ui.book.read.page

import java.util.concurrent.atomic.AtomicLong

internal class ReadPositionVersion {

    private val value = AtomicLong()

    fun snapshot(): Long = value.get()

    fun markChanged() {
        value.incrementAndGet()
    }

    fun isCurrent(snapshot: Long): Boolean = value.get() == snapshot
}
