package io.legado.app.data.repository

import android.util.AtomicFile
import io.legado.app.data.entities.BookInfoChildKind
import io.legado.app.data.entities.BookInfoChildRequest
import io.legado.app.help.storage.RestoreRecoveryGate
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit pending-handoff stops must survive independently of Room's persistence queue. */
internal object MediaHandoffStops {
    internal data class Record(val id: String, val requestId: String, val bookUrl: String,
        val revision: Long, val nonce: String) {
        fun matches(child: BookInfoChildRequest) = child.id == id && child.requestId == requestId &&
            child.bookUrl == bookUrl && child.revision == revision && child.kind == BookInfoChildKind.Reader
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun file(id: String) = AtomicFile(File(appCtx.noBackupFilesDir,
        "media-handoff-stops/${digest(id.toByteArray(Charsets.UTF_8))}.json"))

    @Synchronized fun record(owner: BookInfoChildRequest): Record {
        RestoreRecoveryGate.requireBusinessReady()
        require(owner.kind == BookInfoChildKind.Reader)
        val record = Record(owner.id, owner.requestId, owner.bookUrl, owner.revision, UUID.randomUUID().toString())
        val payload = JSONObject().put("version", 1).put("id", record.id).put("requestId", record.requestId)
            .put("bookUrl", record.bookUrl).put("revision", record.revision).put("nonce", record.nonce).toString()
        val bytes = JSONObject().put("payload", payload).put("sha256", digest(payload.toByteArray(Charsets.UTF_8)))
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262_144)
        val atomic = file(record.id)
        check(atomic.baseFile.parentFile!!.isDirectory || atomic.baseFile.parentFile!!.mkdirs())
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
        check(read(record.id) == record) { "Media stop was not persisted" }
        return record
    }

    @Synchronized fun read(id: String): Record? {
        val atomic = file(id)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            require(input.channel.size() <= 262_144)
            input.readBytes()
        }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = envelope.getString("payload")
        check(digest(payload.toByteArray(Charsets.UTF_8)) == envelope.getString("sha256"))
        val value = JSONObject(payload)
        require(value.getInt("version") == 1 && value.getString("id") == id)
        return Record(id, value.getString("requestId"), value.getString("bookUrl"),
            value.getLong("revision"), value.getString("nonce")).also {
            require(it.id.isNotBlank() && it.requestId.isNotBlank() && it.bookUrl.isNotBlank() && it.revision >= 0)
            require(UUID.fromString(it.nonce).toString() == it.nonce)
        }
    }

    /** Never hold the file monitor while waiting for Room; a newer stop must remain intact. */
    @Synchronized fun clear(record: Record) {
        if (read(record.id) == record) discard(record.id)
    }

    /** Used after the user explicitly closes this exact bridge and its Room transaction commits. */
    @Synchronized fun discard(id: String) {
        val atomic = file(id)
        atomic.delete()
        check(listOf("", ".bak", ".new").none { File(atomic.baseFile.path + it).exists() })
    }
}
