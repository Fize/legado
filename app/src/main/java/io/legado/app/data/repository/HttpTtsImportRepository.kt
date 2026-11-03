package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class HttpTtsImportStatus {
    NEW,
    UPDATE,
    EXISTING,
}

data class HttpTtsImportItem(
    val source: HttpTTS,
    val existing: HttpTTS?,
    val selected: Boolean,
) {
    val status: HttpTtsImportStatus
        get() = when {
            existing == null -> HttpTtsImportStatus.NEW
            source.lastUpdateTime > existing.lastUpdateTime -> HttpTtsImportStatus.UPDATE
            else -> HttpTtsImportStatus.EXISTING
        }
}

@Singleton
class HttpTtsImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun preview(source: String): List<HttpTtsImportItem> = withContext(Dispatchers.IO) {
        parse(source.trim(), 0).map { item ->
            val existing = database.httpTTSDao.get(item.id)
            HttpTtsImportItem(
                source = item,
                existing = existing,
                selected = existing == null || item.lastUpdateTime > existing.lastUpdateTime,
            )
        }
    }

    suspend fun import(sources: List<HttpTTS>) = withContext(Dispatchers.IO) {
        database.httpTTSDao.insert(*sources.toTypedArray())
    }

    private suspend fun parse(text: String, depth: Int): List<HttpTTS> {
        if (depth > 5) throw NoStackTraceException("导入内容嵌套过深")
        return when {
            text.isJsonObject() -> listOf(HttpTTS.fromJson(text).getOrThrow())
            text.isJsonArray() -> HttpTTS.fromJsonArray(text).getOrThrow()
            text.isAbsUrl() -> {
                val body = okHttpClient.newCallResponseBody {
                    if (text.endsWith("#requestWithoutUA")) {
                        url(text.substringBeforeLast("#requestWithoutUA"))
                        header(AppConst.UA_NAME, "null")
                    } else {
                        url(text)
                    }
                }.decompressed().text()
                parse(body, depth + 1)
            }
            text.isUri() -> parse(text.toUri().readText(context), depth + 1)
            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }
}
