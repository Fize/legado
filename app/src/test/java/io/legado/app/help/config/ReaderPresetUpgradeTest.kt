package io.legado.app.help.config

import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderPresetUpgradeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun defaults(): List<ReadBookConfig.Config> {
        val file = listOf(File("src/main/assets/defaultData/readConfig.json"),
            File("app/src/main/assets/defaultData/readConfig.json")).first { it.exists() }
        return parseReadConfigArray(file.readText()).getOrThrow()
    }

    private fun legacy(): List<ReadBookConfig.Config> = listOf(
        ReadBookConfig.Config(name = "微信读书", bgStr = "#ffc0edc6", textSize = 24,
            lineSpacingExtra = 10, headerPaddingTop = 31, footerPaddingBottom = 29,
            tipColor = 123, showHeaderLine = true, bgStrNight = "#123456", underlineMode = 3),
        ReadBookConfig.Config(name = "预设1"), ReadBookConfig.Config(name = "预设2"),
        ReadBookConfig.Config(name = "预设3"), ReadBookConfig.Config(name = "预设4"),
        ReadBookConfig.Config(name = "预设5"),
        ReadBookConfig.Config(name = "我的主题", textSize = 31, textFont = "custom.ttf"),
    )

    @Test fun `night upgrade changes only Kindle night appearance and preserves backup`() {
        val preset = defaults().single { it.name == "Kindle" }
        val old = legacy() + ReadBookConfig.Config(name = "Kindle", bgStr = "#abcdef", bgStrNight = "#000000", textSize = 29)
        val file = temporary.newFile("night.json")
        val original = GSON.toJson(old)
        file.writeText(original)
        val upgraded = persistKindleNightUpgrade(file, old, preset)
        assertEquals(old.dropLast(1), upgraded.dropLast(1))
        val kindle = upgraded.last()
        assertEquals("#abcdef", kindle.bgStr)
        assertEquals(29, kindle.textSize)
        assertEquals("kindle-night.png", kindle.bgStrNight)
        assertEquals(1, kindle.bgTypeNight)
        assertEquals("#CACBC5", kindle.toMap()["textColorNight"])
        assertEquals(original, File(file.parentFile, "night.json.before-kindle-night-v1").readText())
        assertEquals(upgraded, persistKindleNightUpgrade(file, upgraded, preset))
    }

    @Test fun `bundled presets have five distinct names and readable body spacing`() {
        val styles = defaults()
        assertEquals(listOf("微信读书", "预设1", "预设2", "预设3", "Kindle"), styles.map { it.name })
        val kindle = styles.last()
        assertEquals("#E7E7E2", kindle.bgStr)
        assertEquals("#282828", kindle.toMap()["textColor"])
        assertEquals(20, kindle.textSize)
        assertEquals(17, kindle.lineSpacingExtra)
        assertEquals(26, kindle.paddingLeft)
        assertEquals(26, kindle.paddingRight)
        assertEquals("　　", kindle.paragraphIndent)
        assertEquals("#FFFFFF", styles.first().bgStr)
        assertEquals("#0D141E", styles.first().toMap()["textColor"])
    }

    @Test fun `upgrade preserves other themes and non-body settings while remapping selections`() {
        val old = legacy()
        val result = upgradeReaderPresets(old, defaults(), 6, 2)
        assertEquals(listOf("微信读书", "预设1", "预设2", "预设3", "我的主题", "Kindle"), result.configs.map { it.name })
        assertEquals(old[6], result.configs[result.readIndex])
        assertEquals(old[2], result.configs[result.comicIndex])
        assertEquals(old.subList(1, 4), result.configs.subList(1, 4))
        val updated = result.configs.first()
        assertEquals(18, updated.textSize)
        assertEquals(17, updated.lineSpacingExtra)
        assertEquals(old[0].headerPaddingTop, updated.headerPaddingTop)
        assertEquals(old[0].footerPaddingBottom, updated.footerPaddingBottom)
        assertEquals(old[0].tipColor, updated.tipColor)
        assertEquals(old[0].bgStrNight, updated.bgStrNight)
        assertEquals(old[0].underlineMode, updated.underlineMode)
        assertTrue(updated.showHeaderLine)
        assertEquals(24, old[0].textSize) // Do not mutate the pre-upgrade snapshot.
    }

    @Test fun `removed and invalid selections fall back without duplicating Kindle`() {
        val first = upgradeReaderPresets(legacy(), defaults(), 4, 5)
        assertEquals("微信读书", first.configs[first.readIndex].name)
        assertEquals("微信读书", first.configs[first.comicIndex].name)
        val second = upgradeReaderPresets(first.configs, defaults(), -1, 100)
        assertEquals(first.configs, second.configs)
        assertEquals(1, second.configs.count { it.name == "Kindle" })
        assertEquals(0, second.readIndex)
        assertEquals(0, second.comicIndex)
    }

    @Test fun `existing custom Kindle is retained and a short list remains usable`() {
        val custom = ReadBookConfig.Config(name = "Kindle", textSize = 30)
        val result = upgradeReaderPresets(listOf(custom, ReadBookConfig.Config(name = "预设4")), defaults(), 0, 1)
        assertEquals(listOf(custom), result.configs)
        assertEquals(0, result.readIndex)
        assertEquals(0, result.comicIndex)
        val empty = upgradeReaderPresets(emptyList(), defaults(), 0, 0)
        assertEquals("Kindle", empty.configs.single().name)
    }

    @Test fun `persistence backs up original configuration and retries a failed preference write`() {
        val file = temporary.newFile("readConfig.json")
        val original = GSON.toJson(legacy())
        file.writeText(original)
        assertThrows(IllegalStateException::class.java) {
            persistReaderPresetUpgrade(file, defaults(), 6, 5) { _, _ -> false }
        }
        assertEquals(original, file.readText())
        val backup = File(file.parentFile, "readConfig.json.before-body-presets-v1")
        assertTrue(backup.exists())
        val savedBackup = backup.readText()
        var selected = -1 to -1
        val result = persistReaderPresetUpgrade(file, defaults(), 6, 5) { read, comic ->
            selected = read to comic
            true
        }
        assertEquals(4 to 0, selected)
        assertEquals(result.configs, parseReadConfigArray(file.readText()).getOrThrow())
        assertEquals(savedBackup, backup.readText())
        assertFalse(File(file.parentFile, "readConfig.json.pending").exists())
    }

    @Test fun `edits saved after a failed migration survive the next attempt`() {
        val file = temporary.newFile("readConfig.json")
        file.writeText(GSON.toJson(legacy()))
        assertThrows(IllegalStateException::class.java) {
            persistReaderPresetUpgrade(file, defaults(), 6, 5) { _, _ -> false }
        }
        val changed = legacy() + ReadBookConfig.Config(name = "新增主题", textSize = 33)
        file.writeText(GSON.toJson(changed))
        val retry = persistReaderPresetUpgrade(file, defaults(), 7, 6) { _, _ -> true }
        assertEquals("新增主题", retry.configs[retry.readIndex].name)
        assertEquals(33, retry.configs[retry.readIndex].textSize)
        assertEquals("我的主题", retry.configs[retry.comicIndex].name)
    }

    @Test fun `stale interrupted journal does not replace a newer configuration`() {
        val file = temporary.newFile("readConfig.json")
        file.writeText(GSON.toJson(legacy()))
        assertThrows(IllegalStateException::class.java) {
            persistReaderPresetUpgrade(file, defaults(), 6, 5) { _, _ -> error("interrupted") }
        }
        val changed = parseReadConfigArray(file.readText()).getOrThrow() +
            ReadBookConfig.Config(name = "恢复后新增", textSize = 35)
        file.writeText(GSON.toJson(changed))
        val retry = persistReaderPresetUpgrade(file, defaults(), changed.lastIndex, 4) { _, _ -> true }
        assertEquals("恢复后新增", retry.configs[retry.readIndex].name)
        assertEquals(35, retry.configs[retry.readIndex].textSize)
        assertEquals("我的主题", retry.configs[retry.comicIndex].name)
    }

    @Test fun `interruption after file replacement replays original selection mapping`() {
        val file = temporary.newFile("readConfig.json")
        file.writeText(GSON.toJson(legacy()))
        assertThrows(IllegalStateException::class.java) {
            persistReaderPresetUpgrade(file, defaults(), 6, 2) { _, _ -> error("interrupted") }
        }
        assertEquals("Kindle", parseReadConfigArray(file.readText()).getOrThrow().last().name)
        val resumed = persistReaderPresetUpgrade(file, defaults(), 6, 2) { _, _ -> true }
        assertEquals("我的主题", resumed.configs[resumed.readIndex].name)
        assertEquals("预设2", resumed.configs[resumed.comicIndex].name)
        assertEquals(1, resumed.configs.count { it.name == "Kindle" })
    }

    @Test fun `invalid source does not create backup or change configuration`() {
        val file = temporary.newFile("readConfig.json")
        file.writeText("invalid json")
        assertTrue(runCatching {
            persistReaderPresetUpgrade(file, defaults(), 0, 0) { _, _ -> fail("Must not commit"); true }
        }.isFailure)
        assertEquals("invalid json", file.readText())
        assertFalse(File(file.parentFile, "readConfig.json.before-body-presets-v1").exists())
    }
}
