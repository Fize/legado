package io.legado.app.data.repository

import com.script.rhino.runScriptWithContext
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.constant.AppLog
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.ExploreInfoMapCache
import io.legado.app.utils.InfoMap
import io.legado.app.ui.main.explore.exploreGroupFromQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExploreRepository @Inject constructor() {

    fun observeGroups(): Flow<List<String>> = appDb.bookSourceDao.flowExploreGroups()

    fun observeSources(query: String): Flow<List<BookSourcePart>> {
        val group = exploreGroupFromQuery(query)
        return when {
            query.isBlank() -> appDb.bookSourceDao.flowExplore()
            group != null -> appDb.bookSourceDao.flowGroupExplore(group)
            else -> appDb.bookSourceDao.flowExplore(query)
        }
    }

    suspend fun loadKinds(source: BookSourcePart, refresh: Boolean): List<ExploreKind> =
        withContext(Dispatchers.IO) {
            if (refresh) source.clearExploreKindsCache()
            source.exploreKinds()
        }

    suspend fun source(sourceUrl: String): BookSource? = withContext(Dispatchers.IO) {
        appDb.bookSourceDao.getBookSource(sourceUrl)
    }

    fun infoMap(sourceUrl: String): InfoMap = ExploreInfoMapCache.get(sourceUrl)

    fun saveInfoMap(infoMap: InfoMap) {
        if (infoMap.needSave) infoMap.saveNow()
    }

    fun saveNeededInfoMaps() = ExploreInfoMapCache.saveNeeded()

    suspend fun resolveKindLabel(sourceUrl: String, expression: String, infoMap: InfoMap): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = appDb.bookSourceDao.getBookSource(sourceUrl) ?: return@withContext null
                runScriptWithContext {
                    source.evalJS(expression) { put("infoMap", infoMap) }.toString()
                }
            }.onFailure { AppLog.put("发现控件标签计算失败", it) }.getOrNull()
        }

    suspend fun runKindAction(
        sourceUrl: String,
        action: String,
        infoMap: InfoMap,
        java: Any,
        name: String,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val source: BookSource = appDb.bookSourceDao.getBookSource(sourceUrl) ?: return@withContext
            runScriptWithContext {
                source.evalJS(action) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        }.onFailure { AppLog.put("ExploreUI Button $name JavaScript error", it) }
    }

    suspend fun topSource(source: BookSourcePart) = withContext(Dispatchers.IO) {
        source.customOrder = appDb.bookSourceDao.minOrder - 1
        appDb.bookSourceDao.upOrder(source)
    }

    suspend fun deleteSource(source: BookSourcePart) = withContext(Dispatchers.IO) {
        SourceHelp.deleteBookSource(source.bookSourceUrl)
    }
}
