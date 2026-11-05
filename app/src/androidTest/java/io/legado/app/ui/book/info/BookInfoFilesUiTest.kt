package io.legado.app.ui.book.info

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.ui.book.read.ReadBookActivity
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BookInfoFilesUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.autoBackup, PreferKey.cronet, PreferKey.defaultBookTreeUri, PreferKey.bookImportFileName)
    private val previous = keys.associateWith { prefs.all[it] }
    private val localKeys = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion", "uploadImportedBookToWebDav")
    private val previousLocal = localKeys.associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "online-import-$id")
    private lateinit var source: BookSource
    private lateinit var original: Book
    private lateinit var server: NanoHTTPD
    private var downloadName = "book.txt"
    private var gate: CountDownLatch? = null
    private val failDownload = AtomicBoolean()
    private val requests = AtomicInteger()
    private val imported = linkedSetOf<String>()
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    @Before fun setUp() {
        directory.mkdirs()
        prefs.edit().putBoolean(PreferKey.autoBackup, false).putBoolean(PreferKey.cronet, false)
            .putString(PreferKey.defaultBookTreeUri, directory.path).remove(PreferKey.bookImportFileName).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1)
            .putBoolean("uploadImportedBookToWebDav", false).commit()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/info") return newFixedLengthResponse("<div>Online book $id</div><a class='file' href='/$downloadName'>Download</a>")
                if (session.method == Method.GET) {
                    requests.incrementAndGet()
                    gate?.await(15, TimeUnit.SECONDS)
                }
                if (failDownload.get()) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unavailable")
                val bytes = if (downloadName.endsWith("zip")) zipBytes() else content("TEXT").toByteArray()
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", bytes.inputStream(), bytes.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource(base, "Online source $id", bookSourceType = BookSourceType.file, ruleBookInfo = BookInfoRule(downloadUrls = "a.file@href"))
        appDb.bookSourceDao.insert(source)
        original = Book(bookUrl = "$base/info", origin = base, originName = source.bookSourceName, name = "Online $id", author = "Author $id",
            type = BookType.text or BookType.webFile or BookType.notShelf,
            coverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
        appDb.bookDao.insert(original)
    }
    @After fun tearDown() {
        gate?.countDown(); server.stop()
        main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<ReadBookActivity>().forEach { it.finish() } }
        instrumentation.waitForIdleSync()
        // All imported names retain the unique fixture title, even if a test fails before recording the result.
        appDb.bookDao.findByName(original.name).filter { it.name == original.name }.forEach { imported.add(it.bookUrl) }
        (imported + original.bookUrl).forEach { url -> appDb.bookDao.getBook(url)?.let {
            appDb.bookChapterDao.delByBook(url); appDb.bookDao.delete(it); BookHelp.clearCache(it)
        } }
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply { previous.forEach { (key, value) -> when (value) {
            null -> remove(key); is String -> putString(key, value); is Boolean -> putBoolean(key, value)
        } } }.commit()
        LocalConfig.edit().apply { previousLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
        directory.deleteRecursively()
    }

    @Test fun realEntryUsesComposeAndUploadChoiceSurvivesRotationAndCancel() {
        launch().use { scenario ->
            ready(scenario); openFromFooter(scenario)
            compose.onNodeWithTag("web_files_dialog").assertIsDisplayed()
            compose.onNodeWithTag("web_upload").performClick()
            assertTrue(LocalConfig.uploadImportedBookToWebDav)
            scenario.recreate()
            await { state(scenario).fileImport.open }
            compose.onNodeWithTag("web_upload").assertIsOn()
            compose.onNodeWithTag("web_files_cancel").performClick()
            assertEquals(0, requests.get())
            assertFalse(state(scenario).inBookshelf)
            scenario.onActivity { model(it).openWebFiles() }
            compose.onNodeWithTag("web_upload").assertIsOn()
            compose.onNodeWithTag("web_upload").performClick()
            compose.onNodeWithTag("web_file_0").performClick()
            val local = awaitImported(scenario)
            assertEquals(original.name, local.name)
            assertEquals(original.author, local.author)
            assertTrue(read(local).contains("TEXT"))
            assertEquals(1, requests.get())
        }
    }

    @Test fun inFlightDownloadSurvivesRotationAndOnlyImportsOnce() {
        gate = CountDownLatch(1)
        launch().use { scenario ->
            ready(scenario); openFromFooter(scenario)
            compose.onNodeWithTag("web_file_0").performClick()
            await { requests.get() == 1 && state(scenario).fileImport.busy }
            scenario.recreate()
            compose.onNodeWithTag("web_import_progress").assertIsDisplayed()
            compose.onNodeWithTag("web_files_cancel").assertIsNotEnabled()
            gate!!.countDown()
            val local = awaitImported(scenario)
            assertTrue(read(local).contains("TEXT")); assertEquals(1, requests.get())
        }
    }

    @Test fun archiveDialogRestoresAndImportsOnlyTheExactSelectedPath() {
        downloadName = "book.zip"
        launch().use { scenario ->
            ready(scenario); openFromFooter(scenario)
            compose.onNodeWithTag("web_file_0").performClick()
            await { state(scenario).fileImport.entries.size == 2 && !state(scenario).fileImport.busy }
            scenario.recreate()
            compose.onNodeWithTag("web_archive_dialog").assertIsDisplayed()
            val entries = state(scenario).fileImport.entries
            assertTrue(entries.contains("nested/book.txt"))
            val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(context.cacheDir, "book-info-files.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { shot.recycle() }
            compose.onNodeWithTag("web_archive_${entries.indexOf("nested/book.txt")}").performClick()
            val local = awaitImported(scenario)
            assertTrue(read(local).contains("NESTED")); assertFalse(read(local).contains("ROOT"))
            assertEquals(1, requests.get())
            assertEquals(1, appDb.bookDao.findByName(original.name).count { it.name == original.name })
        }
    }

    @Test fun transferFailureKeepsChoiceAndRetryImportsSuccessfully() {
        failDownload.set(true)
        launch().use { scenario ->
            ready(scenario); openFromFooter(scenario)
            compose.onNodeWithTag("web_file_0").performClick()
            await { state(scenario).fileImport.error != null && !state(scenario).fileImport.busy }
            compose.onNodeWithTag("web_import_error").assertIsDisplayed()
            assertEquals("book.txt", state(scenario).fileImport.selected!!.name)
            assertFalse(state(scenario).shelfMutation)
            assertFalse(state(scenario).book!!.isLocal)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            failDownload.set(false)
            compose.onNodeWithTag("web_files_retry").performClick()
            assertTrue(read(awaitImported(scenario)).contains("TEXT"))
        }
    }

    @Test fun unsupportedFileRequiresConfirmationAndOpensExternallyAfterRotation() {
        downloadName = "book.bin"
        val opened = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_VIEW && intent.type == "*/*") {
                    opened.incrementAndGet(); Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                } else null
        }
        instrumentation.addMonitor(monitor)
        try {
            launch().use { scenario ->
                ready(scenario); openFromFooter(scenario)
                compose.onNodeWithTag("web_file_0").performClick()
                compose.onNodeWithTag("web_unsupported_dialog").assertIsDisplayed()
                assertEquals(0, requests.get())
                scenario.recreate()
                compose.onNodeWithTag("web_open_file").performClick()
                await { opened.get() == 1 && state(scenario).fileImport.completion == null }
                assertFalse(state(scenario).book!!.isLocal)
                assertEquals(1, requests.get())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun readingEntryImportsTheFileAndOpensTheRealReader() {
        launch().use { scenario ->
            ready(scenario)
            lateinit var label: String
            scenario.onActivity { label = it.getString(R.string.start_read) }
            compose.onNodeWithText(label).performClick()
            await { state(scenario).fileImport.open }
            compose.onNodeWithTag("web_file_0").performClick()
            val local = awaitImported(scenario)
            await {
                var resumed = false
                main { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is ReadBookActivity } }
                resumed && io.legado.app.model.ReadBook.book?.bookUrl == local.bookUrl
            }
            assertEquals(local.bookUrl, io.legado.app.model.ReadBook.book?.bookUrl)
            assertEquals(1, requests.get())
        }
    }

    @Test fun newViewModelRecoversAnUnacknowledgedLocalImportAheadOfAnOlderRemotePreview() {
        val saved = SavedStateHandle()
        var localKey = ""
        var oldPreview: BookInfoPreview? = null
        withModel(saved) { model ->
            main { model.initData(original.bookUrl) }
            await { model.uiState.value.webFiles.size == 1 && model.uiState.value.recoveryReady }
            val state = model.uiState.value
            oldPreview = BookInfoPreview(state.book!!, emptyList(), source.bookSourceUrl, false, downloadUrls = state.book.downloadUrls)
            main { model.openWebFiles(); model.filesController.select(model.uiState.value.webFiles.single()) }
            await { model.uiState.value.fileImport.completion?.bookUrl != null }
            localKey = model.uiState.value.fileImport.completion!!.bookUrl!!
            imported.add(localKey)
        }
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val store = BookInfoPreviewStore(context)
        val lease = store.open(saved["previewKey"]!!)
        assertTrue(store.save(lease, 1, oldPreview!!))
        withModel(restored) { model ->
            main { model.initData(original.bookUrl) }
            await { model.uiState.value.book?.bookUrl == localKey && model.uiState.value.inBookshelf }
            assertEquals(localKey, model.uiState.value.fileImport.completion!!.bookUrl)
            assertTrue(model.uiState.value.book!!.isLocal)
            assertEquals(1, requests.get())
            main { model.filesController.consumeCompletion(model.uiState.value.fileImport.completion!!.id) }
        }
        store.discard(lease)
    }

    @Test fun replacingAnOnlinePreviewCannotOverwriteAnUnrelatedIdentityOwner() = runBlocking {
        val blocker = original.copy(bookUrl = "blocker://$id", type = BookType.text, durChapterIndex = 7)
        imported.add(blocker.bookUrl)
        appDb.bookDao.insert(blocker)
        val candidate = Book(bookUrl = "staged://$id", name = "Staged $id", type = BookType.text or BookType.local)
        val result = runCatching { BookInfoRepository(appDb).mergeLocalBook(candidate, original) }
        assertTrue(result.isFailure)
        assertFalse(appDb.bookDao.has(candidate.bookUrl))
        assertEquals(7, appDb.bookDao.getBook(blocker.bookUrl)!!.durChapterIndex)
        assertEquals(blocker.bookUrl, appDb.bookDao.getBook(original.name, original.author)!!.bookUrl)
    }

    @Test fun failedLocalInsertRollsBackTheRemovalOfTheOnlinePreview() = runBlocking {
        val candidate = Book(bookUrl = "staged-failure://$id", name = "Staged $id", type = BookType.text or BookType.local)
        val trigger = "reject_import_" + id.replace("-", "")
        appDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON books WHEN NEW.bookUrl = '${candidate.bookUrl}' BEGIN SELECT RAISE(ABORT, 'Rejected test import'); END")
        try {
            assertTrue(runCatching { BookInfoRepository(appDb).mergeLocalBook(candidate, original) }.isFailure)
            assertEquals(original.name, appDb.bookDao.getBook(original.bookUrl)!!.name)
            assertFalse(appDb.bookDao.has(candidate.bookUrl))
        } finally { appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger") }
    }

    @Test fun reimportKeepsLocalReadingProgressPreviewEditsAndTheActualFileType() = runBlocking {
        val existing = Book(bookUrl = "local-reimport://$id", name = "Old local $id", type = BookType.text or BookType.local,
            durChapterIndex = 7, durChapterPos = 123, durChapterTitle = "Saved position", order = 23)
        imported.add(existing.bookUrl)
        appDb.bookDao.insert(existing)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = existing.bookUrl, url = "stale", title = "Stale"))
        val preview = original.copy(customCoverUrl = "https://example.test/custom-$id.png", group = 2).apply { putCustomVariable("User variable $id") }
        val candidate = Book(bookUrl = existing.bookUrl, name = "Parsed name", origin = "${BookType.localTag}::archive.zip",
            type = BookType.text or BookType.local or BookType.archive)
        val merged = BookInfoRepository(appDb).mergeLocalBook(candidate, preview)
        val saved = appDb.bookDao.getBook(existing.bookUrl)!!
        assertTrue(merged.isLocal)
        assertTrue(saved.type and BookType.archive != 0)
        assertEquals(0, saved.type and (BookType.notShelf or BookType.webFile))
        assertEquals(7, saved.durChapterIndex); assertEquals(123, saved.durChapterPos); assertEquals(23, saved.order)
        assertEquals(2L, saved.group); assertEquals(preview.customCoverUrl, saved.customCoverUrl)
        assertEquals("User variable $id", saved.getCustomVariable())
        assertFalse(appDb.bookDao.has(original.bookUrl))
        assertTrue(appDb.bookChapterDao.getChapterList(existing.bookUrl).isEmpty())
    }

    private fun content(marker: String) = "第一章 $marker $id\n" + ("这是正文内容 $marker。\n".repeat(40))
    private fun zipBytes(): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip -> listOf("book.txt" to "ROOT", "nested/book.txt" to "NESTED").forEach { (name, marker) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content(marker).toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun read(book: Book) = context.contentResolver.openInputStream(book.getLocalUri())!!.bufferedReader().use { it.readText() }
    private fun launch() = ActivityScenario.launch<BookInfoActivity>(Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", original.bookUrl))
    private fun model(activity: BookInfoActivity) = ViewModelProvider(activity)[BookInfoViewModel::class.java]
    private fun state(scenario: ActivityScenario<BookInfoActivity>): BookInfoUiState {
        lateinit var value: BookInfoUiState
        scenario.onActivity { value = model(it).uiState.value }; return value
    }
    private fun ready(scenario: ActivityScenario<BookInfoActivity>) = await { state(scenario).webFiles.size == 1 && !state(scenario).loading }
    private fun openFromFooter(scenario: ActivityScenario<BookInfoActivity>) {
        lateinit var label: String
        scenario.onActivity { label = it.getString(R.string.add_to_bookshelf) }
        compose.onNodeWithText(label).performClick()
        await { state(scenario).fileImport.open }
    }
    private fun awaitImported(scenario: ActivityScenario<BookInfoActivity>): Book {
        await { !state(scenario).fileImport.busy && (state(scenario).book?.isLocal == true || state(scenario).fileImport.error != null) }
        assertNull("Import failed: ${state(scenario).fileImport}", state(scenario).fileImport.error)
        return state(scenario).book!!.also { imported.add(it.bookUrl); assertTrue(appDb.bookDao.has(it.bookUrl)); assertTrue(state(scenario).inBookshelf) }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < end) { compose.mainClock.advanceTimeByFrame(); SystemClock.sleep(50) }
        assertTrue("File import did not reach the expected state", condition())
        instrumentation.waitForIdleSync()
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun withModel(saved: SavedStateHandle, block: (BookInfoViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: BookInfoViewModel
        main { model = BookInfoViewModel(BookInfoRepository(appDb), saved, BookInfoPreviewStore(context), BookGroupRepository(appDb, context), BookInfoLibraryRepository(appDb), CoverSearchRepository(appDb), io.legado.app.data.repository.BookIntroRepository(), io.legado.app.data.repository.BookInfoNavigationRepository(appDb)); store.put("details", model) }
        try { block(model) } finally { main { store.clear() } }
    }
}
