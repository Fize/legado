package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ReplaceAnalyzer
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ReplaceRuleImportPreview(
    val rules: List<ReplaceRule>,
    val existingRules: List<ReplaceRule?>,
    val selectedIndexes: Set<Int>,
)

@Singleton
class ReplaceRuleImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    fun observeGroups(): Flow<List<String>> = database.replaceRuleDao.flowGroups()

    suspend fun preview(source: String): ReplaceRuleImportPreview = withContext(Dispatchers.IO) {
        val rules = parse(source.trim(), 0)
        val comparison = database.runInTransaction<ReplaceRuleImportComparison> {
            compareImportedReplaceRules(rules) { ids -> database.replaceRuleDao.findByIds(*ids.toLongArray()) }
        }
        ReplaceRuleImportPreview(
            rules = rules,
            existingRules = comparison.existingRules,
            selectedIndexes = comparison.selectStatus.mapIndexedNotNullTo(linkedSetOf()) { index, selected ->
                index.takeIf { selected }
            },
        )
    }

    suspend fun import(rules: List<ReplaceRule>, groupName: String?, addGroup: Boolean) =
        withContext(Dispatchers.IO) {
            val group = groupName?.trim().orEmpty()
            val selectedRules = rules.map { rule ->
                if (group.isBlank()) rule.copy()
                else if (addGroup) rule.copy().addGroup(group)
                else rule.copy(group = group)
            }
            database.runInTransaction {
                val insertedIds = database.replaceRuleDao.insert(*selectedRules.toTypedArray())
                ReplacePreviewConfig.saveImportedSamples(selectedRules, insertedIds)
            }
        }

    private suspend fun parse(text: String, depth: Int): List<ReplaceRule> {
        if (depth > 5) throw NoStackTraceException(context.getString(R.string.wrong_format))
        return when {
            text.isAbsUrl() -> parseUrl(text, depth)
            text.isJsonArray() -> ReplaceAnalyzer.jsonToReplaceRules(text).getOrThrow()
            text.isJsonObject() -> listOf(ReplaceAnalyzer.jsonToReplaceRule(text).getOrThrow())
            text.isUri() -> parse(text.toUri().readText(context), depth + 1)
            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }

    private suspend fun parseUrl(url: String, depth: Int): List<ReplaceRule> {
        RuleUpdate.cacheReplaceRuleMap.remove(url)?.let { return it }
        val body = okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text("utf-8")
        return parse(body, depth + 1)
    }
}
