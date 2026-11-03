package io.legado.app.help.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Controls only its UUID-owned provider tree; no production folders or permissions are used. */
internal class SafSidecarFixture(private val context: Context, private val id: String) {
    private val authority = Uri.parse("content://com.xiaobaitu.reader.debug.test.sidecar.control")
    lateinit var tree: Uri
        private set
    private var created = false
    var providerPid: Int = 0
        private set
    private fun call(method: String, extras: Bundle? = null) =
        checkNotNull(context.contentResolver.call(authority, "owned_$method", id, extras))
    fun create(persistable: Boolean = false) {
        connected(call("create", Bundle().apply { putBoolean("persistable", persistable) }))
        if (persistable) {
            context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            check(hasPersistedGrant())
        }
    }
    fun hasPersistedGrant() = context.contentResolver.persistedUriPermissions.any {
        it.uri == tree && it.isReadPermission && it.isWritePermission
    }
    fun attach() = connected(call("attach"))
    private fun connected(result: Bundle) {
        tree = Uri.parse(result.getString("tree")); created = true; providerPid = result.getInt("pid")
        check(result.getInt("pid") != android.os.Process.myPid()) { "Provider must run outside instrumentation" }
        check(result.getInt("uid") != android.os.Process.myUid()) { "Provider must own a different UID" }
    }
    fun seed(path: String, bytes: ByteArray) { call("seed", Bundle().apply { putString("path", path); putByteArray("bytes", bytes) }) }
    fun arm(mode: String) { call("arm", Bundle().apply { putString("mode", mode) }) }
    fun release() { if (created) call("release") }
    fun revoke() { call("revoke") }
    fun grant() { call("grant") }
    fun duplicate(path: String) { call("duplicate", Bundle().apply { putString("path", path) }) }
    fun allowNewRequests() { call("allow_new_requests") }
    fun snapshot(): Bundle = call("snapshot")
    fun files(): Map<String, ByteArray> = snapshot().getBundle("files")!!.let { values ->
        values.keySet().associateWith { checkNotNull(values.getByteArray(it)) }
    }
    suspend fun awaitBlocked() = withTimeout(10_000) {
        while (snapshot().getString("entered").isNullOrEmpty()) delay(25)
    }
    suspend fun awaitClosed() { if (created) withTimeout(5_000) {
        while (snapshot().let { it.getInt("descriptors") != 0 || it.getInt("activeRenames") != 0 || it.getInt("activeCreates") != 0 }) delay(25)
    } }
    fun close() {
        if (created) {
            release(); runBlocking { awaitClosed() }
            if (hasPersistedGrant()) context.contentResolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            call("dispose"); created = false
        }
    }
}
