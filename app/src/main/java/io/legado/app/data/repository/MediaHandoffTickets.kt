package io.legado.app.data.repository

import android.content.Intent
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.legado.app.constant.AppLog
import io.legado.app.help.storage.RestoreRecoveryGate
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/** A handoff stays revocable after its optional detail receipt has been removed. */
internal class MediaHandoffTickets(private val root: File) {
    companion object {
        const val EXTRA = "platformMediaHandoffTicket"
        val default by lazy { MediaHandoffTickets(File(appCtx.noBackupFilesDir, "media-handoff-tickets")) }

        /** Expiring an actor is metadata cleanup, including while business recovery is quarantined. */
        fun expire(intent: Intent?) {
            intent?.getStringExtra(EXTRA)?.let {
                runCatching { default.expireUnbound(checkNotNull(intent)) }.onFailure { AppLog.put("清理媒体交接状态失败", it) }
            }
        }
    }

    internal data class Binding(val id: String, val parent: String?)
    private val activeActors = mutableMapOf<String, WeakReference<Any>>()

    private fun activeActor(id: String): Any? = activeActors[id]?.get().also {
        if (it == null) activeActors.remove(id)
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun identity(intent: Intent): String {
        val fields = JSONArray()
        for (key in listOf("bookUrl", "videoUrl", "sourceKey", "record")) {
            fields.put(intent.getStringExtra(key) ?: JSONObject.NULL)
        }
        fields.put(intent.getIntExtra("sourceType", 0))
        return digest(fields.toString().toByteArray(Charsets.UTF_8))
    }

    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(root, "$id.json"))
    }

    private fun syncDirectory() {
        val descriptor = Os.open(root.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    @Synchronized fun create(intent: Intent, parent: String? = null): String {
        RestoreRecoveryGate.requireBusinessReady()
        parent?.let { requireActive(it, intent) }
        check(root.isDirectory || root.mkdirs())
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("version", 1).put("id", id).put("identity", identity(intent))
            .put("parent", parent ?: JSONObject.NULL).toString()
        val bytes = JSONObject().put("payload", payload).put("sha256", digest(payload.toByteArray(Charsets.UTF_8)))
            .toString().toByteArray(Charsets.UTF_8)
        val atomic = file(id)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            syncDirectory()
            requireActive(id, intent)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            atomic.delete()
            throw failure
        }
        return id
    }

    private fun read(id: String): JSONObject {
        val bytes = file(id).openRead().use { input ->
            require(input.channel.size() <= 4096)
            input.readBytes()
        }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = envelope.getString("payload")
        check(digest(payload.toByteArray(Charsets.UTF_8)) == envelope.getString("sha256"))
        val value = JSONObject(payload)
        require(value.getInt("version") == 1 && value.getString("id") == id)
        require(value.getString("identity").matches(Regex("[0-9a-f]{64}")))
        if (!value.isNull("parent")) file(value.getString("parent"))
        return value
    }

    private fun readMatching(id: String, intent: Intent): JSONObject {
        val value = read(id)
        check(value.getString("identity") == identity(intent)) { "Playback handoff belongs to another input" }
        return value
    }

    @Synchronized fun requireActive(id: String, intent: Intent) { readMatching(id, intent) }

    /** Bind before waiting for Room. A second live host cannot retire another host's proof. */
    @Synchronized fun bind(id: String, intent: Intent, actor: Any): Binding {
        check(activeActor(id).let { it == null || it === actor }) { "Playback handoff already has a live receiver" }
        val value = readMatching(id, intent)
        activeActors[id] = WeakReference(actor)
        return Binding(id, value.takeUnless { it.isNull("parent") }?.getString("parent"))
    }

    /** The cached parent survives the child's deletion, including close while Room is blocked. */
    @Synchronized fun release(binding: Binding, actor: Any, beforeRetire: (Binding) -> Unit = {}): Boolean {
        if (activeActor(binding.id) !== actor) return false
        val retiring = binding.copy(parent = binding.parent?.takeIf { activeActor(it) == null })
        beforeRetire(retiring)
        retire(retiring.id)
        retiring.parent?.let(::retire)
        activeActors.remove(binding.id)
        return true
    }

    /** A failed or pre-content launch may only expire a ticket with no live receiver. */
    @Synchronized fun expireUnbound(intent: Intent, beforeRetire: (Binding) -> Unit = {}): Boolean {
        val id = intent.getStringExtra(EXTRA) ?: return false
        if (activeActor(id) != null) return false
        val value = try { readMatching(id, intent) } catch (_: FileNotFoundException) { return false }
        val parent = value.takeUnless { it.isNull("parent") }?.getString("parent")
        val retiring = Binding(id, parent?.takeIf { activeActor(it) == null })
        beforeRetire(retiring)
        retire(id)
        retiring.parent?.let(::retire)
        return true
    }

    /** Once its successor is accepted, the old actor cannot be the saved playback destination. */
    @Synchronized fun accept(id: String) {
        val record = read(id)
        if (!record.isNull("parent")) retire(record.getString("parent"))
    }

    @Synchronized fun revoke(id: String) {
        file(id)
        val children = mutableMapOf<String, MutableList<String>>()
        val entries = if (root.isDirectory) checkNotNull(root.listFiles()) else emptyArray()
        for (entry in entries) {
            if (!entry.name.endsWith(".json")) continue
            val key = entry.name.removeSuffix(".json")
            val record = runCatching { read(key) }.getOrNull() ?: continue // Corrupt records already fail closed.
            if (!record.isNull("parent")) children.getOrPut(record.getString("parent")) { mutableListOf() }.add(key)
        }
        // Keep lineage after a sender retires; a stop must still reach a later unowned receiver.
        val ids = linkedSetOf(id)
        val queue = mutableListOf(id)
        var index = 0
        while (index < queue.size) {
            children[queue[index++]].orEmpty().forEach { if (ids.add(it)) queue.add(it) }
        }
        ids.forEach(::retire)
    }

    @Synchronized fun retire(id: String) {
        val atomic = file(id)
        atomic.delete()
        check(listOf("", ".bak", ".new").none { File(atomic.baseFile.path + it).exists() })
        if (root.isDirectory) syncDirectory()
    }
}
