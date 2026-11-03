package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.TxtTocRule
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

data class TextTocRuleImportPreview(
    val rules: List<TxtTocRule>,
    val existingRules: List<TxtTocRule?>,
)

@Singleton
class TextTocRuleImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun preview(source: String): TextTocRuleImportPreview = withContext(Dispatchers.IO) {
        val rules = parse(source.trim(), 0)
        val existing = rules.map { database.txtTocRuleDao.get(it.id) }
        TextTocRuleImportPreview(rules, existing)
    }

    suspend fun import(rules: List<TxtTocRule>) = withContext(Dispatchers.IO) {
        database.txtTocRuleDao.insert(*rules.toTypedArray())
    }

    private suspend fun parse(text: String, depth: Int): List<TxtTocRule> {
        if (depth > 5) throw NoStackTraceException(context.getString(R.string.wrong_format))
        return when {
            text.isJsonObject() -> listOf(GSON.fromJsonObject<TxtTocRule>(text).getOrThrow())
            text.isJsonArray() -> GSON.fromJsonArray<TxtTocRule>(text).getOrThrow()
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
