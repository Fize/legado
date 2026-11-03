package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.data.entities.ReplaceRule
import splitties.init.appCtx

/** Local-only examples used while editing a replacement rule. */
object ReplacePreviewConfig {

    const val MAX_SAMPLE_LENGTH = 300
    internal const val PREFS_NAME = "replace_preview"
    private const val SAMPLE_PREFIX = "sample_"

    private val preferences by lazy {
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun sample(ruleId: Long): String {
        return preferences.getString(sampleKey(ruleId), "").orEmpty()
    }

    fun saveSample(ruleId: Long, value: String) {
        val normalized = normalizeSample(value)
        preferences.edit {
            if (normalized.isEmpty()) {
                remove(sampleKey(ruleId))
            } else {
                putString(sampleKey(ruleId), normalized)
            }
        }
    }

    fun removeSample(ruleId: Long) {
        preferences.edit {
            remove(sampleKey(ruleId))
        }
    }

    fun normalizeSample(value: String): String {
        val normalized = value.take(MAX_SAMPLE_LENGTH)
        return if (normalized.lastOrNull()?.isHighSurrogate() == true) {
            normalized.dropLast(1)
        } else {
            normalized
        }
    }

    fun withSample(rule: ReplaceRule): ReplaceRule {
        return rule.copy().also { copy ->
            copy.previewText = sample(rule.id).takeIf { it.isNotEmpty() }
        }
    }

    fun withSamples(rules: Iterable<ReplaceRule>): List<ReplaceRule> {
        return rules.map(::withSample)
    }

    /** Persists optional JSON samples against the IDs actually returned by Room. */
    fun saveImportedSamples(
        rules: List<ReplaceRule>,
        insertedIds: List<Long>,
        clearMissing: Boolean = false,
        editor: SharedPreferences.Editor? = null,
    ) {
        val target = editor ?: preferences.edit()
        rules.forEachIndexed { index, rule ->
            val ruleId = insertedIds.getOrNull(index)?.takeIf { it > 0 } ?: rule.id
            if (ruleId <= 0) return@forEachIndexed
            val sample = rule.previewText
            if (sample == null && !clearMissing) return@forEachIndexed
            val normalized = sample?.let(::normalizeSample).orEmpty()
            if (normalized.isEmpty()) target.remove(sampleKey(ruleId))
            else target.putString(sampleKey(ruleId), normalized)
        }
        // Restore supplies a journaled editor; ordinary callers retain asynchronous writes.
        target.apply()
    }

    private fun sampleKey(ruleId: Long): String = "$SAMPLE_PREFIX$ruleId"
}
