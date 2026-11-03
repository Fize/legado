package io.legado.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.moveRelativeTo
import io.legado.app.utils.renameGroupExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplaceRuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun getRule(id: Long): ReplaceRule? = withContext(Dispatchers.IO) {
        database.replaceRuleDao.findById(id)?.let(ReplacePreviewConfig::withSample)
    }

    fun sampleFor(ruleId: Long): String = ReplacePreviewConfig.sample(ruleId)

    fun saveSample(ruleId: Long, sample: String) {
        ReplacePreviewConfig.saveSample(ruleId, sample)
    }

    suspend fun saveRule(rule: ReplaceRule): ReplaceRule = withContext(Dispatchers.IO) {
        rule.checkValid()
        val toSave = rule.copy()
        if (toSave.order == Int.MIN_VALUE) toSave.order = database.replaceRuleDao.maxOrder + 1
        database.replaceRuleDao.insert(toSave).firstOrNull()?.let { toSave.id = it }
        toSave
    }

    fun observeRules(query: String): Flow<List<ReplaceRule>> = when {
        query.isBlank() -> database.replaceRuleDao.flowAll()
        query == context.getString(io.legado.app.R.string.enabled) -> database.replaceRuleDao.flowEnabled()
        query == context.getString(io.legado.app.R.string.disabled) -> database.replaceRuleDao.flowDisabled()
        query == context.getString(io.legado.app.R.string.no_group) -> database.replaceRuleDao.flowNoGroup()
        query.startsWith("group:") -> database.replaceRuleDao.flowGroupSearch(query.substringAfter("group:"))
        else -> database.replaceRuleDao.flowSearch("%$query%")
    }

    fun observeGroups(): Flow<List<String>> = database.replaceRuleDao.flowGroups()

    fun manualReplaceEnabled(): Boolean = AppConfig.manualReplaceRule
    fun setManualReplaceEnabled(enabled: Boolean) { AppConfig.manualReplaceRule = enabled }

    suspend fun setEnabled(rule: ReplaceRule, enabled: Boolean) = withContext(Dispatchers.IO) {
        database.replaceRuleDao.enable(rule.id, enabled)
    }

    suspend fun setEnabled(rules: List<ReplaceRule>, enabled: Boolean) = withContext(Dispatchers.IO) {
        database.replaceRuleDao.enable(enabled, rules)
    }

    suspend fun delete(rules: List<ReplaceRule>) = withContext(Dispatchers.IO) {
        database.replaceRuleDao.delete(*rules.toTypedArray())
        rules.forEach { ReplacePreviewConfig.removeSample(it.id) }
    }

    suspend fun moveToTop(rule: ReplaceRule) = withContext(Dispatchers.IO) {
        database.replaceRuleDao.update(rule.copy(order = database.replaceRuleDao.minOrder - 1))
    }

    suspend fun moveToBottom(rule: ReplaceRule) = withContext(Dispatchers.IO) {
        database.replaceRuleDao.update(rule.copy(order = database.replaceRuleDao.maxOrder + 1))
    }

    suspend fun moveSelection(rules: List<ReplaceRule>, top: Boolean) = withContext(Dispatchers.IO) {
        var order = if (top) database.replaceRuleDao.minOrder - rules.size else database.replaceRuleDao.maxOrder + 1
        database.replaceRuleDao.update(*rules.map { it.copy(order = order++) }.toTypedArray())
    }

    suspend fun moveRelative(ruleId: Long, targetId: Long, after: Boolean) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = database.replaceRuleDao.all
            val reordered = moveRelativeTo(current, ruleId, targetId, after) { it.id }
            if (reordered != current) {
                database.replaceRuleDao.update(*reordered.mapIndexed { index, item -> item.copy(order = index) }.toTypedArray())
            }
        }
    }

    suspend fun setGroups(rules: List<ReplaceRule>, group: String, add: Boolean) = withContext(Dispatchers.IO) {
        database.replaceRuleDao.update(*rules.map { rule ->
            if (add) rule.copy().addGroup(group) else rule.copy().removeGroup(group)
        }.toTypedArray())
    }

    suspend fun addGroup(group: String) = withContext(Dispatchers.IO) {
        val ungrouped = database.replaceRuleDao.noGroup
        ungrouped.forEach { it.group = group }
        if (ungrouped.isNotEmpty()) database.replaceRuleDao.update(*ungrouped.toTypedArray())
    }

    suspend fun renameGroup(oldGroup: String, newGroup: String?) = withContext(Dispatchers.IO) {
        val rules = database.replaceRuleDao.getByGroup(oldGroup).mapNotNull { rule ->
            rule.group.renameGroupExact(oldGroup, newGroup)?.let { groups -> rule.copy(group = groups) }
        }
        if (rules.isNotEmpty()) database.replaceRuleDao.update(*rules.toTypedArray())
    }

    suspend fun createShareFile(rules: List<ReplaceRule>): File = withContext(Dispatchers.IO) {
        File.createTempFile("replaceRule_", ".json", context.cacheDir).apply {
            writeText(GSON.toJson(ReplacePreviewConfig.withSamples(rules)))
        }
    }

    fun exportJson(rules: List<ReplaceRule>): ByteArray =
        GSON.toJson(ReplacePreviewConfig.withSamples(rules)).toByteArray()
}
