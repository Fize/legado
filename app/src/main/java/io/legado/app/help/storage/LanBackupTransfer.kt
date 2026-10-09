package io.legado.app.help.storage

import android.content.Context
import java.io.File
import java.io.Closeable
import kotlin.Result

interface LanBackupSession : Closeable {
    val listeningPort: Int
    val qrText: String
    val descriptor: LanBackupTransfer.Descriptor
}

object LanBackupTransfer {
    class Descriptor {
        val deviceName: String = ""
        val hosts: List<String> = emptyList()
        val port: Int = 0
        val backupBytes: Long = 0
        val uncompressedBytes: Long = 0
        val expiresAt: Long = 0
        val size: Long = 0
    }

    class Session : LanBackupSession {
        override val listeningPort: Int = 0
        override val qrText: String = ""
        override val descriptor = Descriptor()
        override fun close() {}
    }

    class ReceiveResult {
        val file: File = File("")
        val uncompressedBytes: Long = 0
    }

    fun prepare(file: File, deviceName: String): Session {
        return Session()
    }

    fun decodeDescriptor(qrText: String): Result<Descriptor> {
        return Result.failure(Exception("局域网备份已被精简"))
    }

    suspend fun receive(context: Context, qrText: String): ReceiveResult {
        throw Exception("局域网备份已被精简")
    }

    fun requireRestoreSpace(context: Context, bytes: Long) {
    }

    fun requireRestoreMediaSpace(context: Context, zipFile: File, includeBackgrounds: Boolean = false) {
    }
}
