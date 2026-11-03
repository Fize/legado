package io.legado.app.help.storage

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.CacheManager
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderConfigurationRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "https://reader-config-$id.invalid/book", name = "Config $id",
        author = "Compatibility fixture", durChapterIndex = 7)
    private val lastBackup = LocalConfig.lastBackup
    private val ignore = HashMap(BackupConfig.ignoreConfig)
    private val files = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
    private val originalFiles = files.associateWith { if (it.isFile) it.readBytes() else null }
    private val originalStyles = ReadBookConfig.configList.toList()
    private val originalShareStyle = ReadBookConfig.shareConfig
    private val coverKey = "legadoCoverRuleConfig"
    private val originalCover = appDb.cacheDao.get(coverKey)
    private val originalCoverMemory = CacheManager.getFromMemory(coverKey)
    private val legacyCache = ACache.get(cacheDir = false)
    private val legacyFile = legacyCache.file(DirectLinkUpload.ruleFileName)
    private val originalLegacy = legacyFile?.readBytes()
    private val inputs = mutableListOf<File>()

    @Before fun setUp() {
        appDb.bookDao.insert(book)
        LocalConfig.lastBackup = 1234L
        BackupConfig.ignoreConfig["readConfig"] = false
    }

    @After fun cleanUp() {
        appDb.bookDao.delete(book)
        originalFiles.forEach { (file, bytes) ->
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
        ReadBookConfig.configList.clear()
        ReadBookConfig.configList.addAll(originalStyles)
        ReadBookConfig.shareConfig = originalShareStyle
        if (originalCover == null) appDb.cacheDao.delete(coverKey) else appDb.cacheDao.insert(originalCover)
        CacheManager.deleteMemory(coverKey)
        originalCoverMemory?.let { CacheManager.putMemory(coverKey, it) }
        if (originalLegacy == null) legacyCache.remove(DirectLinkUpload.ruleFileName)
        else checkNotNull(legacyFile).writeBytes(originalLegacy)
        inputs.forEach(File::delete)
        LocalConfig.lastBackup = lastBackup
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(ignore)
    }

    @Test fun malformedReadStylesFailBeforeBookOrFileWrites() = rejected("readConfig.json", "[{")
    @Test fun wrongReadStylesShapeFailsBeforeBookOrFileWrites() = rejected("readConfig.json", "{}")
    @Test fun malformedShareStyleFailsBeforeBookOrFileWrites() = rejected("shareReadConfig.json", "{")
    @Test fun wrongShareStyleShapeFailsBeforeBookOrFileWrites() = rejected("shareReadConfig.json", "[]")
    @Test fun malformedCoverRuleFailsBeforeBookOrFileWrites() = rejected("coverRule.json", "{")
    @Test fun missingCoverRuleFieldsFailBeforeBookOrFileWrites() = rejected("coverRule.json", "{}")

    @Test fun ignoredReadStylesDoNotBlockAValidPartialRestore() = runBlocking {
        BackupConfig.ignoreConfig["readConfig"] = true
        Restore.restoreOrThrow(context, archive("readConfig.json" to "[{", "shareReadConfig.json" to "{").toUri())
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertFilesUnchanged()
    }

    @Test fun obsoleteDirectLinkRuleIsIgnoredWithoutChangingItsCache() = runBlocking {
        Restore.restoreOrThrow(context, archive(DirectLinkUpload.ruleFileName to "not a rule").toUri())
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        val actual = legacyCache.file(DirectLinkUpload.ruleFileName)?.readBytes()
        assertArrayEquals(originalLegacy, actual)
    }

    @Test fun validStylesAndDisabledCoverRuleRemainRestorable() = runBlocking {
        val read = GSON.toJson(listOf(originalShareStyle))
        val share = GSON.toJson(originalShareStyle)
        Restore.restoreOrThrow(context, archive("readConfig.json" to read,
            "shareReadConfig.json" to share,
            "coverRule.json" to """{"enable":false,"searchUrl":"","coverRule":""}""").toUri())
        assertEquals(read, files[0].readText())
        assertEquals(share, files[1].readText())
        assertEquals(1, ReadBookConfig.configList.size)
        assertFalse(BookCover.getCoverRule().enable)
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    private fun rejected(name: String, content: String) = runBlocking {
        val result = runCatching { Restore.restoreOrThrow(context, archive(name to content).toUri()) }
        assertTrue("Invalid configuration must fail: $name", result.isFailure)
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(1234L, LocalConfig.lastBackup)
        assertEquals(originalCover, appDb.cacheDao.get(coverKey))
        assertFilesUnchanged()
    }

    private fun assertFilesUnchanged() {
        originalFiles.forEach { (file, bytes) ->
            assertArrayEquals(file.name, bytes, if (file.isFile) file.readBytes() else null)
        }
    }

    private fun archive(vararg entries: Pair<String, String>): File =
        File.createTempFile("reader-config-", ".zip", context.cacheDir).also { file ->
            inputs += file
            ZipOutputStream(file.outputStream()).use { zip ->
                (listOf("bookshelf.json" to GSON.toJson(listOf(book.copy(durChapterIndex = 29)))) + entries)
                    .forEach { (name, text) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                    }
            }
        }
}
