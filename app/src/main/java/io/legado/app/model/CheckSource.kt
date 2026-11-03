package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.help.storage.backupRestoreMutex
import io.legado.app.help.storage.restoreWriteGeneration
import io.legado.app.help.storage.RestoreWriteGeneration
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx

object CheckSource {
    internal const val EXTRA_SESSION_ID = "checkSourceSessionId"
    internal const val EXTRA_SELECTED_SOURCES_KEY = "checkSourceSelectedSourcesKey"
    internal const val EXTRA_RESTORE_SEQUENCE = "checkSourceRestoreSequence"
    internal const val EXTRA_RESTORE_PROCESS = "checkSourceRestoreProcess"

    var keyword = "我的"

    //校验设置
    var timeout = CacheManager.getLong("checkSourceTimeout") ?: 180000L
    var wSourceComment = CacheManager.get("wSourceComment")?.toBoolean() ?: true
    var checkDomain = CacheManager.get("checkDomain")?.toBoolean() ?: false
    var checkSearch = CacheManager.get("checkSearch")?.toBoolean() ?: true
    var checkDiscovery = CacheManager.get("checkDiscovery")?.toBoolean() ?: true
    var checkInfo = CacheManager.get("checkInfo")?.toBoolean() ?: true
    var checkCategory = CacheManager.get("checkCategory")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkContent")?.toBoolean() ?: true
    val summary get() = upSummary()

    suspend fun start(
        context: Context,
        sources: List<BookSourcePart>,
        sessionId: Long,
    ): String = startPrepared(context, sources.map { it.bookSourceUrl }, sessionId,
        restoreWriteGeneration.capture()) { sources }

    internal suspend fun startSelected(
        context: Context,
        sourceUrls: Collection<String>,
        sessionId: Long,
        generation: RestoreWriteGeneration.Token,
    ): String = startPrepared(context, sourceUrls, sessionId, generation) {
        sourceUrls.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it) }
    }

    private suspend fun startPrepared(
        context: Context,
        sourceUrls: Collection<String>,
        sessionId: Long,
        generation: RestoreWriteGeneration.Token,
        sources: () -> List<BookSourcePart>,
    ): String {
        Debug.prepareCheckSession(sessionId, sourceUrls.toList())
        var selectedSourcesKey: String? = null
        try {
            val queued = withContext(IO) {
                backupRestoreMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    generation.requireCurrent()
                    if (!Debug.isChecking(sessionId)) throw CancellationException("Source check request was cancelled")
                    appDb.bookSourceDao.beginCheck(sources())
                }
            }
            selectedSourcesKey = IntentData.put(queued)
            context.startService<CheckSourceService> {
                action = IntentAction.start
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SELECTED_SOURCES_KEY, selectedSourcesKey)
                putExtra(EXTRA_RESTORE_SEQUENCE, generation.sequence)
                putExtra(EXTRA_RESTORE_PROCESS, generation.processId)
            }
        } catch (error: Exception) {
            selectedSourcesKey?.let { IntentData.get<Any>(it) }
            Debug.finishChecking(sessionId)
            throw error
        }
        return requireNotNull(selectedSourcesKey)
    }

    fun stop(context: Context, sessionId: Long) {
        context.startService<CheckSourceService> {
            action = IntentAction.stop
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }

    fun resume(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkSourceTimeout", timeout)
        CacheManager.put("wSourceComment", wSourceComment)
        CacheManager.put("checkDomain", checkDomain)
        CacheManager.put("checkSearch", checkSearch)
        CacheManager.put("checkDiscovery", checkDiscovery)
        CacheManager.put("checkInfo", checkInfo)
        CacheManager.put("checkCategory", checkCategory)
        CacheManager.put("checkContent", checkContent)
    }

    private fun upSummary(): String {
        var checkItem = ""
        if (checkDomain) checkItem = "$checkItem ${appCtx.getString(R.string.domain)}"
        if (checkSearch) checkItem = "$checkItem ${appCtx.getString(R.string.search)}"
        if (checkDiscovery) checkItem = "$checkItem ${appCtx.getString(R.string.discovery)}"
        if (checkInfo) checkItem = "$checkItem ${appCtx.getString(R.string.source_tab_info)}"
        if (checkCategory) checkItem = "$checkItem ${appCtx.getString(R.string.chapter_list)}"
        if (checkContent) checkItem = "$checkItem ${appCtx.getString(R.string.main_body)}"
        return appCtx.getString(
            R.string.check_source_config_summary,
            (timeout / 1000).toString(),
            checkItem
        )
    }
}
