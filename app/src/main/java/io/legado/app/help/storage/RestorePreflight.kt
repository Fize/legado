package io.legado.app.help.storage

import com.google.gson.JsonElement
import io.legado.app.data.entities.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.jsonPath
import java.io.File

/** Validate present database payloads before the first persistent write, including late files. */
internal fun validateRestorePayloads(root: File) {
    require(selectedBackupFileNames { true }.any { File(root, it).isFile } ||
        backupMediaDirectoryNames.any { name ->
            File(root, name).walkTopDown().any { it.isFile }
        }) { "备份中没有可恢复的文件" }

    validateRestoreList<Book>(root, "bookshelf.json")
    validateRestoreList<BookMemo>(root, "bookMemo.json")
    validateRestoreList<Bookmark>(root, "bookmark.json")
    validateRestoreList<BookHighlight>(root, "highlight.json")
    validateRestoreList<HighlightRule>(root, "highlightRule.json")
    validateRestoreList<BookGroup>(root, "bookGroup.json")
    File(root, "bookSource.json").takeIf { it.isFile }?.let { file ->
        val json = file.readText()
        validateRestoreObjectArray(json, file.name)
        parseRestoredBookSources(json)
    }
    validateRestoreList<ReplaceRule>(root, "replaceRule.json")
    validateRestoreList<SearchKeyword>(root, "searchHistory.json")
    validateRestoreList<RuleSub>(root, "sourceSub.json")
    validateRestoreList<TxtTocRule>(root, "txtTocRule.json")
    validateRestoreList<HttpTTS>(root, "httpTTS.json")
    validateRestoreList<DictRule>(root, "dictRule.json")
    validateRestoreList<KeyboardAssist>(root, "keyboardAssists.json")
    validateRestoreList<ReadRecord>(root, "readRecord.json")
}

internal fun parseRestoredBookSources(json: String): List<BookSource> {
    val entries = GSON.fromJsonArray<JsonElement>(json).getOrThrow()
    val sources = GSON.fromJsonArray<BookSource>(json).getOrElse {
        ImportOldData.fromOldBookSources(json)
    }
    return sources.mapIndexed { index, source ->
        val type = entries[index].asJsonObject.get("bookSourceType")
        // The permissive integer adapter otherwise silently turns legacy AUDIO into 0.
        if (type?.isJsonPrimitive == true && type.asJsonPrimitive.isString &&
            type.asString == "AUDIO"
        ) {
            ImportOldData.fromOldBookSource(jsonPath.parse(entries[index].toString()))
        } else {
            source
        }
    }
}

internal fun parseServerBackup(json: String): List<Server> {
    validateRestoreObjectArray(json, "servers.json")
    return GSON.fromJsonArray<Server>(json).getOrThrow().onEach { server ->
        // Gson can populate null in Kotlin non-null properties (e.g. unknown enum values).
        requireNotNull(server.name)
        requireNotNull(server.type)
    }
}

private inline fun <reified T> validateRestoreList(root: File, name: String) {
    File(root, name).takeIf { it.isFile }?.let { file ->
        val json = file.readText()
        validateRestoreObjectArray(json, name)
        try {
            GSON.fromJsonArray<T>(json).getOrThrow()
        } catch (e: Exception) {
            throw IllegalArgumentException("$name 备份数据无法解析", e)
        }
    }
}

private fun validateRestoreObjectArray(json: String, name: String) {
    try {
        require(GSON.fromJsonArray<JsonElement>(json).getOrThrow().all { it.isJsonObject })
    } catch (e: Exception) {
        throw IllegalArgumentException("$name 备份数据无法解析", e)
    }
}
