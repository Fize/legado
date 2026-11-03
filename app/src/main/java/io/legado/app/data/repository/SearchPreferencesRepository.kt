package io.legado.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class SearchPreferences(
    val scope: String,
    val precision: Boolean,
    val showReadRecord: Boolean,
    val resultFilter: String,
    val parallelism: Int,
)

/** Owns search settings; screens never read preferences or Application state. */
@Singleton
class SearchPreferencesRepository @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.defaultSharedPreferences

    suspend fun current() = withContext(IO) { readSnapshot() }

    private fun readSnapshot(): SearchPreferences {
        val values = preferences.all
        fun boolean(key: String, fallback: Boolean) = when (val value = values[key]) {
            is Boolean -> value
            is String -> value.trim().toBooleanStrictOrNull() ?: fallback
            else -> fallback
        }
        return SearchPreferences(
            values["searchScope"] as? String ?: "",
            boolean(PreferKey.precisionSearch, false),
            boolean(PreferKey.showSearchReadRecord, true),
            values[PreferKey.searchResultFilter] as? String ?: "",
            ((values[PreferKey.threadCount] as? Number)?.toInt() ?: 32).coerceIn(1, AppConst.MAX_THREAD),
        )
    }

    fun observe(): Flow<SearchPreferences> = callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { readSnapshot() }.flowOn(IO)

    suspend fun setPrecision(value: Boolean) = edit { putBoolean(PreferKey.precisionSearch, value) }
    suspend fun setShowReadRecord(value: Boolean) = edit { putBoolean(PreferKey.showSearchReadRecord, value) }
    suspend fun setFilter(value: String) = edit { putString(PreferKey.searchResultFilter, value) }
    suspend fun setScope(value: SearchScopeSpec) {
        if (value.isSource) return
        edit {
            putString("searchScope", value.raw)
            putString("searchGroup", value.groups.singleOrNull().orEmpty())
        }
    }

    private suspend fun edit(change: SharedPreferences.Editor.() -> Unit) = withContext(IO) {
        if (!preferences.edit().apply(change).commit()) throw IOException("Cannot save search settings")
    }
}

data class SearchScopeSpec(val raw: String) {
    val isSource: Boolean get() = raw.contains("::")
    val sourceUrl: String? get() = if (isSource) raw.substringAfter("::") else null
    val sourceLabel: String? get() = if (isSource) raw.substringBefore("::") else null
    val groups: List<String> get() = if (isSource) emptyList() else raw.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    val isAll: Boolean get() = raw.isEmpty()

    companion object {
        fun source(name: String, url: String) = SearchScopeSpec("${name.replace(":", "")}::$url")
        fun groups(names: Collection<String>) = SearchScopeSpec(names.distinct().joinToString(","))
    }
}
