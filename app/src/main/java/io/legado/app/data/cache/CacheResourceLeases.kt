package io.legado.app.data.cache

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Keys identify a resource independently of an Activity, parser instance or file name change. */
data class CacheResourceKey(val kind: String, val identity: String)

/**
 * A lease may cross coroutine dispatchers. Only acquisition, publication and removal take locks;
 * a suspended download never holds a JVM monitor. Entries disappear after their last user leaves.
 */
@Singleton
class CacheResourceLeases @Inject constructor() {
    private class Entry {
        var users = 0
        var active = 0
        var revision = 0L
    }
    private val entries = hashMapOf<CacheResourceKey, Entry>()
    private fun retain(key: CacheResourceKey): Entry = synchronized(entries) {
        entries.getOrPut(key) { Entry() }.also { it.users++ }
    }
    private fun release(key: CacheResourceKey, entry: Entry) = synchronized(entries) {
        check(entry.users > 0)
        entry.users--
        if (entry.users == 0) {
            check(entries[key] === entry)
            entries.remove(key)
        }
    }

    fun acquire(key: CacheResourceKey, mutating: Boolean = false): Closeable {
        val entry = retain(key)
        synchronized(entry) {
            entry.active++
            if (mutating) entry.revision++
        }
        val closed = AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                synchronized(entry) {
                    check(entry.active > 0)
                    entry.active--
                    if (mutating) entry.revision++
                }
                release(key, entry)
            }
        }
    }

    fun <T> use(key: CacheResourceKey, mutating: Boolean = false, block: () -> T): T =
        acquire(key, mutating).use { block() }

    /** Keeps the entry alive while a retention plan is built, without marking the resource active. */
    fun plan(key: CacheResourceKey): Plan {
        val entry = retain(key)
        val revision = synchronized(entry) { entry.revision }
        return Plan(
            remove = { action -> synchronized(entry) {
                if (entry.active > 0 || entry.revision != revision) false
                else { action(); true }
            } },
            release = { release(key, entry) },
        )
    }

    class Plan internal constructor(
        private val remove: (() -> Unit) -> Boolean,
        private val release: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean()
        @Synchronized
        fun removeIfUnchanged(action: () -> Unit): Boolean {
            check(!closed.get())
            return remove(action)
        }
        @Synchronized
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    internal fun entryCount(): Int = synchronized(entries) { entries.size }
}
