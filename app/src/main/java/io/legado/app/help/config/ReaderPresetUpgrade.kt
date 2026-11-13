package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.utils.GSON
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class ReaderPresetUpgrade(
    val configs: List<ReadBookConfig.Config>,
    val readIndex: Int,
    val comicIndex: Int,
)

// WeRead reference: https://weread.qq.com/web/reader/cc932860813ab67c2g014597
// Observed 2026-11-12: light reading surface #FFFFFF, text #0D141E, system sans-serif,
// container font 18px. Preset spacing is adapted for this reader, not an asserted mobile default.
// Only the reading surface changes. In particular, do not copy status bars, page animations,
// shared underlines, header/footer settings, or night/e-ink preferences into an existing style.
private val readerBodyPresetFields = listOf(
    "bgStr", "bgType", "textColor", "textFont", "titleFont", "textBold", "titleBold",
    "textSize", "letterSpacing", "lineSpacingExtra", "titleLineSpacingExtra", "paragraphSpacing",
    "paddingLeft", "paddingRight", "paddingTop", "paddingBottom", "paragraphIndent",
    "titleSize", "titleMode", "titleColor", "titleTopSpacing", "titleBottomSpacing",
)

internal fun withReaderBodyPreset(
    original: ReadBookConfig.Config,
    preset: ReadBookConfig.Config,
): ReadBookConfig.Config {
    val target = GSON.toJsonTree(original).asJsonObject
    val source = GSON.toJsonTree(preset).asJsonObject
    readerBodyPresetFields.forEach { field -> target.add(field, source.get(field).deepCopy()) }
    return GSON.fromJson(target, ReadBookConfig.Config::class.java)
}

internal fun upgradeReaderPresets(
    configs: List<ReadBookConfig.Config>,
    defaults: List<ReadBookConfig.Config>,
    readIndex: Int,
    comicIndex: Int,
): ReaderPresetUpgrade {
    val weread = defaults.single { it.name == "微信读书" }
    val kindle = defaults.single { it.name == "Kindle" }
    val kept = configs.withIndex().filter { it.value.name !in setOf("预设4", "预设5") }
    val updated = kept.map { (_, config) ->
        if (config.name == weread.name) withReaderBodyPreset(config, weread) else config.copy()
    }.toMutableList()
    if (updated.none { it.name == kindle.name }) {
        // Reuse the existing page chrome, rather than introducing a different header/footer.
        val base = configs.getOrNull(readIndex) ?: weread
        val body = withReaderBodyPreset(base, kindle).copy(name = kindle.name)
        val json = GSON.toJsonTree(body).asJsonObject
        val preset = GSON.toJsonTree(kindle).asJsonObject
        listOf("bgStrNight", "bgTypeNight", "textColorNight").forEach { json.add(it, preset.get(it).deepCopy()) }
        updated += GSON.fromJson(json, ReadBookConfig.Config::class.java)
    }
    fun remap(index: Int): Int = kept.indexOfFirst { it.index == index }.takeIf { it >= 0 }
        ?: updated.indexOfFirst { it.name == weread.name }.takeIf { it >= 0 } ?: 0
    return ReaderPresetUpgrade(updated, remap(readIndex), remap(comicIndex))
}

@Keep
private data class ReaderPresetBackup(val json: String, val readIndex: Int, val comicIndex: Int)

private fun writePresetFile(file: File, contents: String) {
    val pending = File(file.parentFile, "${file.name}.pending")
    try {
        FileOutputStream(pending).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    } finally {
        pending.delete()
    }
}

/**
 * Keep the historical backup separate from an in-flight journal. Only replay a journal whose
 * original/expected contents still match the file, so edits made after a failure survive.
 * The caller marks the upgrade complete together with both selected indices.
 */
internal fun persistReaderPresetUpgrade(
    file: File,
    defaults: List<ReadBookConfig.Config>,
    readIndex: Int,
    comicIndex: Int,
    commitSelection: (Int, Int) -> Boolean,
): ReaderPresetUpgrade {
    val backupFile = File(file.parentFile, "${file.name}.before-body-presets-v1")
    val journalFile = File(file.parentFile, "${file.name}.body-presets-v1-journal")
    val currentJson = file.readText()
    fun upgrade(snapshot: ReaderPresetBackup) = upgradeReaderPresets(
        parseReadConfigArray(snapshot.json).getOrThrow(), defaults, snapshot.readIndex, snapshot.comicIndex)
    val pending = if (journalFile.exists()) {
        GSON.fromJson(journalFile.readText(), ReaderPresetBackup::class.java).takeIf {
            currentJson == it.json || currentJson == GSON.toJson(upgrade(it).configs)
        }
    } else null
    val snapshot = pending ?: ReaderPresetBackup(currentJson, readIndex, comicIndex)
    val upgraded = upgrade(snapshot)
    if (!backupFile.exists()) writePresetFile(backupFile, GSON.toJson(snapshot))
    writePresetFile(journalFile, GSON.toJson(snapshot))
    writePresetFile(file, GSON.toJson(upgraded.configs))
    if (!commitSelection(upgraded.readIndex, upgraded.comicIndex)) {
        writePresetFile(file, snapshot.json)
        journalFile.delete()
        error("Could not save reader preset selection")
    }
    journalFile.delete()
    return upgraded
}

internal fun updateKindleNightPresets(configs: List<ReadBookConfig.Config>, preset: ReadBookConfig.Config): List<ReadBookConfig.Config> =
    configs.map { config ->
        if (config.name != "Kindle") config else {
            val json = GSON.toJsonTree(config).asJsonObject
            val source = GSON.toJsonTree(preset).asJsonObject
            listOf("bgStrNight", "bgTypeNight", "textColorNight").forEach { json.add(it, source.get(it).deepCopy()) }
            GSON.fromJson(json, ReadBookConfig.Config::class.java)
        }
    }

internal fun persistKindleNightUpgrade(file: File, configs: List<ReadBookConfig.Config>, preset: ReadBookConfig.Config): List<ReadBookConfig.Config> {
    val upgraded = updateKindleNightPresets(configs, preset)
    val backup = File(file.parentFile, "${file.name}.before-kindle-night-v1")
    if (!backup.exists()) writePresetFile(backup, file.readText())
    writePresetFile(file, GSON.toJson(upgraded))
    return upgraded
}
