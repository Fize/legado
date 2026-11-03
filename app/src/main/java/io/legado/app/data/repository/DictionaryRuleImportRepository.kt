package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.DictRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DictionaryRuleImportPreview(
    val rules: List<DictRule>,
    val existingRules: List<DictRule?>,
)

@Singleton
class DictionaryRuleImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun preview(source: String): DictionaryRuleImportPreview = withContext(Dispatchers.IO) {
        val rules = parse(source.trim(), 0)
        val existing = rules.map { database.dictRuleDao.getByName(it.name) }
        DictionaryRuleImportPreview(rules, existing)
    }

    suspend fun import(rules: List<DictRule>) = withContext(Dispatchers.IO) {
        database.dictRuleDao.insert(*rules.toTypedArray())
    }

    private suspend fun parse(text: String, depth: Int): List<DictRule> {
        if (depth > 5) throw NoStackTraceException("导入内容嵌套过深")
        return when {
            text.isJsonObject() -> listOf(GSON.fromJsonObject<DictRule>(text).getOrThrow())
            text.isJsonArray() -> GSON.fromJsonArray<DictRule>(text).getOrThrow()
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
