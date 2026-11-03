package io.legado.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Private durable payloads are named by request UUID; no JSON bytes or paths cross navigation. */
@Singleton
class HighlightRuleFiles internal constructor(private val context: Context,private val root: File) {
    @Inject constructor(@ApplicationContext context: Context): this(context,File(context.filesDir,"highlight-rule-transfers"))
    private fun file(id: String): File {
        require(UUID.fromString(id).toString()==id)
        return File(root,"$id.json")
    }
    private fun digest(file: File): String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(8192); while(true) { val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) } }
        return hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    }
    fun prepare(id: String,value: HighlightRuleFile): String {
        check(root.isDirectory || root.mkdirs())
        val atomic=AtomicFile(file(id)); val output=atomic.startWrite()
        try {
            val writer=output.writer(Charsets.UTF_8)
            GSON.toJson(value,writer); writer.flush(); atomic.finishWrite(output)
        } catch(error: Throwable) { atomic.failWrite(output); throw error }
        return digest(file(id))
    }
    fun remove(id: String) {
        AtomicFile(file(id)).delete()
        AtomicFile(File(root,"$id.retained")).delete()
    }
    internal suspend fun <T> withMaintenanceLock(block: suspend () -> T): T=maintenance.withLock { block() }
    /** Record completion before releasing the Room reference; a crash can only extend retention. */
    internal fun retain(id: String,now: Long=System.currentTimeMillis()) {
        file(id) // Validate the key even if the payload has already disappeared.
        check(root.isDirectory || root.mkdirs())
        val marker=AtomicFile(File(root,"$id.retained")); val output=marker.startWrite()
        try { output.write(now.toString().toByteArray(Charsets.US_ASCII)); marker.finishWrite(output) }
        catch(error: Throwable) { marker.failWrite(output); throw error }
    }
    /** Called under the maintenance mutex and Room transaction that protects active references. */
    internal fun clean(active: Set<String>,now: Long): Int {
        val entries=root.listFiles() ?: return 0
        val ids=entries.mapNotNull { entry ->
            val name=entry.name.substringBefore('.')
            name.takeIf { runCatching { UUID.fromString(it).toString()==it }.getOrDefault(false) &&
                entry.name.removePrefix(name) in listOf(".json",".json.bak",".json.new",".retained",".retained.bak",".retained.new") }
        }.toSet()
        var removed=0
        for(id in ids) {
            val marker=AtomicFile(File(root,"$id.retained"))
            if(id in active) { marker.delete(); continue }
            val since=runCatching { marker.readFully().toString(Charsets.US_ASCII).toLong() }.getOrNull()
            // Legacy/unpublished payloads start a full grace period when first discovered.
            if(since==null || since<0 || since>now) { retain(id,now); continue }
            if(now-since<RETENTION_MILLIS) continue
            remove(id)
            check(listOf(".json",".json.bak",".json.new",".retained",".retained.bak",".retained.new").none { File(root,id+it).exists() }) { "Cannot remove expired rule payload" }
            removed++
        }
        return removed
    }
    private fun checked(transfer: HighlightRuleTransfer): File=file(transfer.id).also {
        check(it.isFile && digest(it)==transfer.digest) { "Export payload is missing or damaged" }
    }
    fun shareUri(transfer: HighlightRuleTransfer): Uri = FileProvider.getUriForFile(context,AppConst.authority,checked(transfer))
    suspend fun write(transfer: HighlightRuleTransfer,verify: suspend () -> Unit) = writes.withLock {
        val source=checked(transfer); val uri=checkNotNull(transfer.uri).toUri()
        require(uri.scheme=="content")
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        verify()
        checkNotNull(context.contentResolver.openOutputStream(uri,"wt")) { "Cannot open export document" }.use { output ->
            source.inputStream().use { input ->
                val buffer=ByteArray(8192)
                while(true) { verify(); val n=input.read(buffer); if(n<0) break; output.write(buffer,0,n) }
            }
            verify(); output.flush()
        }
    }
    companion object {
        internal const val RETENTION_MILLIS=7L*24*60*60*1000
        private val writes=Mutex()
        private val maintenance=Mutex()
    }
}
