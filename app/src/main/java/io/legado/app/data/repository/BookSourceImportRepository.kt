package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.AppDatabase
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.RuleUpdate
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class BookSourceImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun sourceGroups(): List<String> = withContext(Dispatchers.IO) {
        database.bookSourceDao.allGroups()
    }

    internal suspend fun prepareCandidates(
        sources: List<BookSource>,
        automatic: Boolean,
        manualRuleIds: Map<Int, List<Long>>,
    ): List<BookSourceImportCandidate> {
        val rules = database.replaceRuleDao.findEnabledBySourceScope()
        return sources.mapIndexed { index, source ->
            val applicable = rulesFor(source, rules)
            prepareBookSourceImportCandidate(
                source,
                if (automatic) applicable else applicable.filter { it.id in manualRuleIds[index].orEmpty() },
            )
        }
    }

    internal suspend fun compareSources(
        sources: List<BookSource>,
    ): List<Pair<BookSourcePart?, ImportBookSourceStatus>> = sources.map { source ->
        compareSource(source)
    }

    internal suspend fun compareSource(
        source: BookSource,
    ): Pair<BookSourcePart?, ImportBookSourceStatus> {
        val local = database.bookSourceDao.getBookSourcePart(source.bookSourceUrl)
        return local to resolveImportBookSourceStatus(source.lastUpdateTime, local?.lastUpdateTime)
    }

    internal suspend fun prepareEditedCandidate(
        source: BookSource,
        automatic: Boolean,
        selectedRuleIds: List<Long>,
    ): Triple<BookSourceImportCandidate, BookSourcePart?, ImportBookSourceStatus> {
        val rules = database.replaceRuleDao.findEnabledBySourceScope()
        val applicableRules = rulesFor(source, rules)
        val candidate = prepareBookSourceImportCandidate(
            source,
            if (automatic) applicableRules else applicableRules.filter { it.id in selectedRuleIds },
        )
        val active = candidate.source(useReplacement = automatic || selectedRuleIds.isNotEmpty())
        val local = database.bookSourceDao.getBookSourcePart(active.bookSourceUrl)
        return Triple(
            candidate,
            local,
            resolveImportBookSourceStatus(active.lastUpdateTime, local?.lastUpdateTime),
        )
    }

    internal suspend fun refreshCandidates(
        candidates: List<BookSourceImportCandidate>,
        editedIndex: Int,
        editedSource: BookSource?,
        manualRuleIds: Map<Int, List<Long>>?,
    ): List<BookSourceImportCandidate> = refreshBookSourceImportCandidates(
        candidates,
        editedIndex,
        editedSource,
        database.replaceRuleDao.findEnabledBySourceScope(),
        manualRuleIds,
    )

    fun enabledSourceReplacementRules() = database.replaceRuleDao.findEnabledBySourceScope()

    suspend fun publishSources(sources: List<BookSource>, reimportSourceUrl: String?): BookSource? {
        SourceHelp.insertBookSource(*sources.toTypedArray())
        ContentProcessor.upReplaceRules()
        return reimportSourceUrl?.let { database.bookSourceDao.getBookSource(it) }
    }

    private fun rulesFor(
        source: BookSource,
        rules: List<io.legado.app.data.entities.ReplaceRule>,
    ) = rules.filter { it.matchesSourceImport(source.bookSourceName.orEmpty(), source.bookSourceUrl.orEmpty()) }

    suspend fun readSources(source: String): List<BookSource> = withContext(Dispatchers.IO) {
        val input = source.trim()
        when {
            input.isJsonObject() || input.isJsonArray() -> readBookSourceJson(parseBookSourceJson(input))
            input.isAbsUrl() -> importSourceUrl(input)
            input.isUri() -> {
                val uri = Uri.parse(input)
                val text = uri.inputStream(context).getOrThrow().bufferedReader().use { it.readText() }
                parseSourceText(text)
            }
            else -> listOf(extractJsSource(input))
        }
    }

    private suspend fun importSourceUrl(url: String): List<BookSource> {
        RuleUpdate.cacheBookSourceMap[url]?.let { cached ->
            RuleUpdate.cacheBookSourceMap.remove(url)
            return cached
        }
        val text = okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use { it.bufferedReader().readText() }
        return parseSourceText(text)
    }

    private suspend fun parseSourceText(text: String): List<BookSource> {
        val input = text.trim()
        return if (input.isJsonArray() || input.isJsonObject()) {
            readBookSourceJson(parseBookSourceJson(input, allowSourceUrls = false))
        } else {
            listOf(extractJsSource(input))
        }
    }

    private suspend fun readBookSourceJson(json: BookSourceImportJson): List<BookSource> = when (json) {
        is BookSourceImportJson.Sources -> json.items
        is BookSourceImportJson.SourceUrls -> buildList {
            json.items.forEach { addAll(importSourceUrl(it)) }
        }
    }

    private suspend fun extractJsSource(text: String): BookSource = runCatchingCancellable {
        JsSourceConfig.extract(text, coroutineContext)
    }.getOrElse {
        throw NoStackTraceException(
            "${context.getString(R.string.wrong_format)}\n${it.localizedMessage}"
        )
    }
}
