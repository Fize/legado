package io.legado.app.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Immutable private UI snapshots. Only the small reference belongs in Android saved state. */
internal class SourceTargetStateStore(private val root: File) {
    data class Lease internal constructor(val owner: String, val epoch: Long, val taskId: Int)
    private data class Owner(val taskId: Int, val epoch: Long)

    fun create(taskId: Int): Lease = synchronized(lock) {
        val lease = Lease(UUID.randomUUID().toString(), 1, taskId)
        writeOwner(lease)
        lease
    }

    /** A restored host retires the old host's right to save or remove this owner's files. */
    fun claim(reference: String): Lease = synchronized(lock) {
        val (owner, _) = parseReference(reference)
        val previous = readOwner(owner)
        check(previous.epoch < Long.MAX_VALUE)
        Lease(owner, previous.epoch + 1, previous.taskId).also(::writeOwner)
    }

    fun save(lease: Lease, lists: List<List<String>>): String = synchronized(lock) {
        requireCurrent(lease)
        val bytes = encode(lists)
        val hash = digest(bytes)
        val target = File(directory(lease.owner), "$hash.bin")
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) writeAtomic(target, bytes)
        check(target.readBytes().contentEquals(bytes)) { "Source selection snapshot was not saved" }
        "${lease.owner}/$hash"
    }

    /** Exact immutable revision; never substitute the newest selection or all database sources. */
    fun read(reference: String): List<List<String>> = synchronized(lock) {
        val (owner, hash) = parseReference(reference)
        val file = File(directory(owner), "$hash.bin")
        check(file.isFile && file.length() <= MAX_BYTES) { "Source selection snapshot is missing" }
        val bytes = file.readBytes()
        check(digest(bytes) == hash) { "Source selection snapshot is damaged" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == MAGIC)
            val lists = List(input.readInt().also { check(it in 0..MAX_LISTS) }) {
                List(input.readInt().also { check(it in 0..MAX_TARGETS) }) {
                    val count = input.readInt()
                    check(count in 0..input.available())
                    val value = ByteArray(count)
                    input.readFully(value)
                    value.toString(Charsets.UTF_8)
                }
            }
            check(input.available() == 0)
            lists
        }
    }

    fun release(lease: Lease) = synchronized(lock) {
        if (runCatching { readOwner(lease.owner) }.getOrNull() == Owner(lease.taskId, lease.epoch)) {
            check(directory(lease.owner).deleteRecursively())
        }
    }

    /** No time-based expiry: a task still in Recents may legitimately restore an old snapshot. */
    fun pruneRemovedTasks(liveTaskIds: Set<Int>) = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && validOwner(it.name) }.forEach { directory ->
            val owner = runCatching { readOwner(directory.name) }.getOrNull()
            if (owner != null && owner.taskId !in liveTaskIds) check(directory.deleteRecursively())
        }
    }

    private fun requireCurrent(lease: Lease) {
        check(readOwner(lease.owner) == Owner(lease.taskId, lease.epoch)) { "Source selection host was replaced" }
    }

    private fun directory(owner: String): File {
        require(validOwner(owner))
        return File(root, owner)
    }

    private fun readOwner(owner: String): Owner = DataInputStream(File(directory(owner), "owner").inputStream()).use {
        check(it.readInt() == MAGIC)
        val value = Owner(it.readInt(), it.readLong())
        check(value.epoch > 0 && it.read() == -1)
        value
    }

    private fun writeOwner(lease: Lease) {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(MAGIC); it.writeInt(lease.taskId); it.writeLong(lease.epoch) }
        }.toByteArray()
        writeAtomic(File(directory(lease.owner), "owner"), bytes)
    }

    private fun encode(lists: List<List<String>>): ByteArray {
        require(lists.size <= MAX_LISTS)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(lists.size)
            lists.forEach { list ->
                require(list.size <= MAX_TARGETS)
                stream.writeInt(list.size)
                list.forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    check(output.size().toLong() + 4 + bytes.size <= MAX_BYTES) { "Source selection is too large" }
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                }
            }
        }
        return output.toByteArray()
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temporary = File(target.parentFile, ".${UUID.randomUUID()}.new")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(target.readBytes().contentEquals(bytes)) { "Source selection write could not be verified" }
        } finally { temporary.delete() }
    }

    private fun parseReference(reference: String): Pair<String, String> {
        val parts = reference.split('/')
        require(parts.size == 2 && validOwner(parts[0]) && parts[1].matches(Regex("[a-f0-9]{64}")))
        return parts[0] to parts[1]
    }

    private fun validOwner(owner: String) = runCatching { UUID.fromString(owner).toString() == owner }.getOrDefault(false)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val lock = Any()
        private const val MAGIC = 0x53545331
        private const val MAX_LISTS = 4
        private const val MAX_TARGETS = 100_000
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}
