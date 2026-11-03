package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.help.DefaultData
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.jsonPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

sealed interface OnlineImportResolution {
    data class Destination(val type: String, val source: String) : OnlineImportResolution
    data class ReadConfiguration(val key: String) : OnlineImportResolution
    data object Empty : OnlineImportResolution
}

class OnlineImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun errorMessage(error: Throwable): String =
        error.localizedMessage ?: context.getString(R.string.unknown_error)

    suspend fun resolve(link: String): OnlineImportResolution = withContext(Dispatchers.IO) {
        val uri = Uri.parse(link)
        if (!uri.isHierarchical) return@withContext OnlineImportResolution.Empty
        val url = uri.getQueryParameter("src")?.takeIf(String::isNotBlank)
            ?: return@withContext OnlineImportResolution.Empty
        val type = when (uri.path) {
            "/bookSource" -> "bookSource"
            "/replaceRule" -> "replaceRule"
            "/highlightRule" -> "highlightRule"
            "/textTocRule" -> "txtRule"
            "/httpTTS" -> "httpTts"
            "/dictRule" -> "dictRule"
            "/addToBookshelf" -> "addToBookshelf"
            "/importonline" -> when (uri.host) {
                "booksource" -> "bookSource"
                "replace" -> "replaceRule"
                else -> null
            }
            else -> null
        }
        if (type != null) return@withContext OnlineImportResolution.Destination(type, url)
        val response = okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else url(url)
        }
        response.use { body ->
            val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
            val binary = uri.path == "/readConfig" || contentType in setOf(
                "application/zip", "application/octet-stream", "application/x-zip-compressed",
            )
            val file = newPayload(if (binary) "bin" else "json")
            try {
                body.byteStream().use { input -> file.outputStream().use(input::copyTo) }
                if (binary) {
                    check(file.length() > 0)
                    OnlineImportResolution.ReadConfiguration(file.name)
                } else {
                    val map = file.inputStream().use { jsonPath.parse(it).read<Map<String, *>>("$[0]") }
                        ?: file.inputStream().use { jsonPath.parse(it).read("$") }
                    val detected = requireNotNull(jsonImportType(map)) { context.getString(R.string.wrong_format) }
                    OnlineImportResolution.Destination(detected, Uri.fromFile(file).toString())
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    private fun directory(): File = File(context.cacheDir, "online-imports").apply { mkdirs() }

    private fun newPayload(extension: String): File {
        val directory = directory()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        return File(directory, "${UUID.randomUUID()}.$extension")
    }

    private fun readConfiguration(key: String): File {
        require(key.matches(Regex("[a-f0-9-]{36}\\.bin")))
        return File(directory(), key)
    }

    suspend fun canRestore(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { readConfiguration(key).let { it.isFile && it.length() > 0 } }.getOrDefault(false)
    }

    suspend fun discard(key: String) = withContext(Dispatchers.IO) { readConfiguration(key).delete(); Unit }

    suspend fun importReadConfiguration(key: String) = withContext(Dispatchers.IO) {
        val bytes = readConfiguration(key).readBytes()
        // The legacy decoder uses shared temporary paths as well as the config list.
        // Keep decoding and publication inside the same transaction lock.
        synchronized(ReadBookConfig) {
            val config = ReadBookConfig.import(bytes)
            applyImportedReadConfig(
                configs = ReadBookConfig.configList,
                imported = config,
                defaultConfigs = { DefaultData.readConfigs },
                save = ReadBookConfig::saveNow,
                transactionLock = ReadBookConfig,
            )
        }
    }
}
