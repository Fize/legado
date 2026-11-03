package io.legado.app.help.storage

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Server
import io.legado.app.help.config.LocalConfig
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

/** Real restore calls against an existing, test-owned row; never clears user data. */
@RunWith(AndroidJUnit4::class)
class RestoreFailureSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "https://restore-failure-$id.invalid/book",
        name = "Restore failure $id", author = "Compatibility fixture", durChapterIndex = 7)
    private val savedLastBackup = LocalConfig.lastBackup
    private val savedPassword = LocalConfig.password
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val inputs = mutableListOf<File>()
    private val source = BookSource("https://legacy-source-$id.invalid", "Legacy $id")
    private val server = Server(id = -id.replace("-", "").take(12).toLong(16) - 1,
        name = "Restore server $id")
    private var ownsServerId = false

    @Before fun setUp() {
        assertNull(appDb.serverDao.get(server.id))
        ownsServerId = true
        appDb.bookDao.insert(book)
        LocalConfig.lastBackup = 1234L
        LocalConfig.password = "wrong-fixture-password"
        BackupConfig.ignoreConfig["ignoreCookies"] = false
        BackupConfig.ignoreConfig["ignoreSourceVariables"] = false
    }

    @After fun cleanUp() {
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        if (ownsServerId) appDb.serverDao.delete(server)
        inputs.forEach { it.delete() }
        LocalConfig.lastBackup = savedLastBackup
        LocalConfig.password = savedPassword
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
    }

    @Test fun nonZipIsRejectedWithoutChangingExistingData() = verifyRejected(
        File.createTempFile("invalid-backup-", ".zip", context.cacheDir)
            .also { inputs += it; it.writeText("not a zip archive") })

    @Test fun malformedBookshelfIsRejectedWithoutSuccessTimestamp() = verifyRejected(
        archive("bookshelf.json" to "[{"))

    @Test fun unrelatedZipIsRejectedWithoutChangingExistingData() = verifyRejected(
        archive("readme.txt" to "an unrelated archive"))

    @Test fun nullBookEntryIsRejectedBeforeWritingAnything() = verifyRejected(
        archive("bookshelf.json" to "[null]"))

    @Test fun malformedLaterSourceCannotPartiallyOverwriteExistingBook() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "bookSource.json" to "[{"))

    @Test fun invalidLaterNumericFieldCannotPartiallyOverwriteExistingBook() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "readRecord.json" to
            """[{"readTime":"not-a-number"}]"""))

    @Test fun wrongLegacyCookiePasswordFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "cookies.json" to
            BackupAES("correct-fixture-password").encryptBase64(
                """[{"url":"restore-$id.invalid","cookie":"sid=fixture"}]""")))

    @Test fun wrongLegacyRuntimePasswordFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "runtimeSourceCache.json" to
            BackupAES("correct-fixture-password").encryptBase64(
                """[{"key":"v_$id","value":"fixture","deadline":0}]""")))

    @Test fun malformedPreferenceXmlFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "config.xml" to "<map><string"))

    @Test fun invalidPreferenceRootFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "config.xml" to "<not-a-map/>"))

    @Test fun invalidPreferenceIntegerFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "videoConfig.xml" to
            """<map><int name="fixture" value="not-an-int"/></map>"""))

    @Test fun malformedServersFailBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "servers.json" to "[{"))

    @Test fun wrongLegacyServerPasswordFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "servers.json" to
            BackupAES("correct-fixture-password").encryptBase64(GSON.toJson(listOf(server)))))

    @Test fun unknownServerTypeFailsBeforeBookWrite() = verifyRejected(
        archive("bookshelf.json" to replacementBook(), "servers.json" to
            """[{"id":${server.id},"name":"fixture","type":"UNKNOWN_TYPE"}]"""))

    @Test fun plaintextServersRemainReadableWithAnUnrelatedPassword() = runBlocking {
        Restore.restoreOrThrow(context, archive("servers.json" to GSON.toJson(listOf(server))).toUri())
        assertEquals(server.name, appDb.serverDao.get(server.id)!!.name)
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    @Test fun legacyServersRemainReadableWithTheCorrectPassword() = runBlocking {
        LocalConfig.password = "correct-fixture-password"
        Restore.restoreOrThrow(context, archive("servers.json" to
            BackupAES(LocalConfig.password).encryptBase64(GSON.toJson(listOf(server)))).toUri())
        assertEquals(server.name, appDb.serverDao.get(server.id)!!.name)
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    @Test fun validPartialBackupStillUpdatesExistingBook() = runBlocking {
        Restore.restoreOrThrow(context, archive("bookshelf.json" to replacementBook()).toUri())
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    @Test fun plaintextCookiesRemainReadableWithAnUnrelatedLocalPassword() = runBlocking {
        Restore.restoreOrThrow(context, archive("bookshelf.json" to replacementBook(),
            "cookies.json" to "[]").toUri())
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun legacyCookiesRemainReadableWithTheCorrectPassword() = runBlocking {
        LocalConfig.password = "correct-fixture-password"
        Restore.restoreOrThrow(context, archive("bookshelf.json" to replacementBook(),
            "cookies.json" to BackupAES(LocalConfig.password).encryptBase64("[]")).toUri())
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun legacyAudioSourceConversionIsPreserved() = runBlocking {
        Restore.restoreOrThrow(context, archive("bookSource.json" to
            """[{"bookSourceUrl":"${source.bookSourceUrl}","bookSourceName":"${source.bookSourceName}","bookSourceType":"AUDIO"}]""").toUri())
        assertEquals(1, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceType)
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    private fun replacementBook() = GSON.toJson(listOf(book.copy(durChapterIndex = 29)))

    private fun archive(vararg entries: Pair<String, String>): File =
        File.createTempFile("restore-failure-", ".zip", context.cacheDir).also { file ->
            inputs += file
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun verifyRejected(file: File) = runBlocking {
        val result = runCatching { Restore.restoreOrThrow(context, file.toUri()) }
        assertTrue("Invalid backup must report failure", result.isFailure)
        assertEquals("Failed restore must preserve the existing book", 7,
            appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals("Failed restore must not mark backup successful", 1234L, LocalConfig.lastBackup)
    }
}
