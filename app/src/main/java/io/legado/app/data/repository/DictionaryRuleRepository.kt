package io.legado.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.DictRule
import io.legado.app.help.DefaultData
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryRuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    fun observeAll(): Flow<List<DictRule>> = database.dictRuleDao.flowAll()

    suspend fun save(rule: DictRule, originalName: String?) = withContext(Dispatchers.IO) {
        if (originalName != null && originalName != rule.name) {
            database.dictRuleDao.getByName(originalName)?.let(database.dictRuleDao::delete)
        }
        database.dictRuleDao.insert(rule)
    }

    suspend fun update(rules: List<DictRule>) = withContext(Dispatchers.IO) {
        database.dictRuleDao.insert(*rules.toTypedArray())
    }

    suspend fun delete(rules: List<DictRule>) = withContext(Dispatchers.IO) {
        database.dictRuleDao.delete(*rules.toTypedArray())
    }

    suspend fun importDefault() = withContext(Dispatchers.IO) {
        DefaultData.importDefaultDictRules()
    }

    suspend fun createShareFile(rules: List<DictRule>): File = withContext(Dispatchers.IO) {
        File.createTempFile("dictRule_", ".json", context.cacheDir).apply {
            writeText(GSON.toJson(rules))
        }
    }
}
