package io.legado.app.data.repository

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.legado.app.help.storage.RestoreRecoveryGate
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Write before retiring a validated actor; replay only after its final save or process loss. */
internal class MediaHandoffReturns(private val root: File) {
    companion object {
        val default by lazy { MediaHandoffReturns(File(appCtx.noBackupFilesDir, "media-handoff-returns")) }
    }

    internal data class Record(val id: String, val bookUrl: String, val previousToken: String,
        val receiverToken: String, val ticket: String, val parent: String?, val nonce: String)

    // A live host retires metadata before enqueuing its last position save. Observation must wait.
    // A new process has no pending entries and can recover the last committed position immediately.
    private val pending = mutableSetOf<String>()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun file(record: Record) = AtomicFile(File(root, "${digest(record.id)}-${record.nonce}.json"))
    private fun syncDirectory() {
        val descriptor = Os.open(root.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    @Synchronized fun record(id: String, bookUrl: String, previousToken: String,
        receiverToken: String, binding: MediaHandoffTickets.Binding): Record {
        RestoreRecoveryGate.requireBusinessReady()
        val record = Record(id, bookUrl, previousToken, receiverToken, binding.id, binding.parent, UUID.randomUUID().toString())
        validate(record)
        val payload = JSONObject().put("version", 1).put("id", id).put("bookUrl", bookUrl)
            .put("previousToken", previousToken).put("receiverToken", receiverToken).put("ticket", record.ticket)
            .put("parent", record.parent ?: JSONObject.NULL).put("nonce", record.nonce).toString()
        val bytes = JSONObject().put("payload", payload).put("sha256", digest(payload)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262_144)
        check(root.isDirectory || root.mkdirs())
        val atomic = file(record)
        pending += record.nonce
        try {
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                atomic.finishWrite(output)
                syncDirectory()
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            check(read(atomic.baseFile) == record)
        } catch (failure: Throwable) {
            pending -= record.nonce
            throw failure
        }
        return record
    }

    private fun validate(record: Record) {
        require(record.id.isNotBlank() && record.bookUrl.isNotBlank() && record.previousToken.isNotBlank())
        for (uuid in listOfNotNull(record.receiverToken, record.ticket, record.parent, record.nonce)) {
            require(UUID.fromString(uuid).toString() == uuid)
        }
    }

    private fun read(path: File): Record {
        val bytes = AtomicFile(path).openRead().use { input ->
            require(input.channel.size() <= 262_144)
            input.readBytes()
        }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = envelope.getString("payload")
        check(digest(payload) == envelope.getString("sha256"))
        val value = JSONObject(payload)
        require(value.getInt("version") == 1)
        return Record(value.getString("id"), value.getString("bookUrl"), value.getString("previousToken"),
            value.getString("receiverToken"), value.getString("ticket"), value.takeUnless { it.isNull("parent") }?.getString("parent"),
            value.getString("nonce")).also { validate(it); check(file(it).baseFile == path) }
    }

    @Synchronized fun ready(id: String): List<Record> {
        if (!root.isDirectory) return emptyList()
        val prefix = digest(id) + "-"
        val paths = checkNotNull(root.listFiles()).map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            .filter { it.startsWith(prefix) && it.endsWith(".json") }.distinct()
        return paths.map { read(File(root, it)) }.filter { it.id == id && it.nonce !in pending }
    }

    /** Called on the persistence queue, after the host's final position save. */
    @Synchronized fun arm(record: Record) { pending -= record.nonce }

    @Synchronized fun clear(record: Record) {
        val atomic = file(record)
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            check(read(atomic.baseFile) == record)
            atomic.delete()
            check(listOf("", ".bak", ".new").none { File(atomic.baseFile.path + it).exists() })
            syncDirectory()
        }
        pending -= record.nonce
    }
}
