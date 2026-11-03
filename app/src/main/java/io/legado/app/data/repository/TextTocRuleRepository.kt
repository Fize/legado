package io.legado.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextTocRuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    fun observeAll(): Flow<List<TxtTocRule>> = database.txtTocRuleDao.observeAll()

    suspend fun save(rule: TxtTocRule) = withContext(Dispatchers.IO) {
        database.txtTocRuleDao.insert(rule)
    }

    suspend fun delete(rules: List<TxtTocRule>) = withContext(Dispatchers.IO) {
        database.txtTocRuleDao.delete(*rules.toTypedArray())
    }

    suspend fun setEnabled(rules: List<TxtTocRule>, enabled: Boolean) = withContext(Dispatchers.IO) {
        database.txtTocRuleDao.insert(*rules.map { it.copy(enable = enabled) }.toTypedArray())
    }

    suspend fun moveToTop(rule: TxtTocRule) = withContext(Dispatchers.IO) {
        database.txtTocRuleDao.update(rule.copy(serialNumber = database.txtTocRuleDao.minOrder - 1))
    }

    suspend fun moveToBottom(rule: TxtTocRule) = withContext(Dispatchers.IO) {
        database.txtTocRuleDao.update(rule.copy(serialNumber = database.txtTocRuleDao.maxOrder + 1))
    }

    suspend fun importDefault() = withContext(Dispatchers.IO) {
        DefaultData.importDefaultTocRules()
    }

    suspend fun createShareFile(rules: List<TxtTocRule>): File = withContext(Dispatchers.IO) {
        File.createTempFile("txtTocRule_", ".json", context.cacheDir).apply {
            writeText(GSON.toJson(rules))
        }
    }
}
