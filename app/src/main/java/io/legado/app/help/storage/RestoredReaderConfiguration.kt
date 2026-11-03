package io.legado.app.help.storage

import androidx.core.util.AtomicFile
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigArray
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.io.FileOutputStream

internal data class RestoredReaderConfiguration(
    val styles: ByteArray?,
    val shareStyle: ByteArray?,
    val coverRule: String?,
)

internal fun readRestoredReaderConfiguration(root: File, includeStyles: Boolean): RestoredReaderConfiguration {
    fun read(name: String, validate: (String) -> Unit): ByteArray? =
        File(root, name).takeIf { it.isFile }?.let { file ->
            try {
                file.readBytes().also { validate(it.toString(Charsets.UTF_8)) }
            } catch (e: Exception) {
                throw IllegalArgumentException("$name 备份配置无法解析", e)
            }
        }
    val styles = if (includeStyles) read(ReadBookConfig.configFileName) {
        parseReadConfigArray(it).getOrThrow()
    } else null
    val shareStyle = if (includeStyles) read(ReadBookConfig.shareConfigFileName) {
        parseReadConfigObject(it).getOrThrow()
    } else null
    val coverRule = read(BookCover.configFileName) { json ->
        val rule = requireNotNull(GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow())
        requireNotNull(rule.searchUrl)
        requireNotNull(rule.coverRule)
    }?.toString(Charsets.UTF_8)
    return RestoredReaderConfiguration(styles, shareStyle, coverRule)
}

/** The shared restore mutex protects calls; readers continue seeing the old base until commit. */
internal fun writeRestoredConfiguration(target: File, write: (FileOutputStream) -> Unit) {
    require(!target.isDirectory) { "${target.name} 配置路径不是文件" }
    val atomic = AtomicFile(target)
    val output = atomic.startWrite()
    try {
        write(output)
        output.flush()
        output.fd.sync()
        atomic.finishWrite(output)
        // AtomicFile logs rename failures instead of throwing. Never report those as success.
        check(target.isFile && !File(target.path + ".new").exists()) {
            "${target.name} 配置文件保存失败"
        }
    } catch (error: Throwable) {
        atomic.failWrite(output)
        throw error
    }
}
