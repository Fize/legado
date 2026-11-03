package io.legado.app.ui.book.info

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.BookInfoLibraryError
import io.legado.app.data.repository.BookInfoLibraryRepository
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import io.legado.app.ui.book.remoteimport.WebDavFixtureServer
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import io.legado.app.data.entities.BookInfoRequest
import io.legado.app.data.repository.navigationOwner
import io.legado.app.data.repository.BookInfoLibraryException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class BookInfoLibraryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val previous = listOf(PreferKey.autoBackup, PreferKey.cronet, PreferKey.defaultBookTreeUri).associateWith { prefs.all[it] }
    private val previousLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion", "bookInfoDeleteAlert", "deleteBookOriginal").associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "library-action-$id")
    private lateinit var file: File
    private lateinit var book: Book
    private lateinit var server: WebDavFixtureServer
    private var previousRemote: RemoteBookWebDav? = null
    private val extraBooks = linkedSetOf<String>()
    private val ownedRoutes = mutableListOf<BookInfoRoute>()
    private val requests = CopyOnWriteArrayList<WebDavFixtureServer.Request>()
    private val exists = AtomicBoolean()
    private val rejectPut = AtomicBoolean()
    private val raceConflict = AtomicBoolean()
    private val rejectDelete = AtomicBoolean()
    private var putGate: CountDownLatch? = null
    private val putStarted = CountDownLatch(1)
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val puts get() = requests.filter { it.method == "PUT" }
    private val deletes get() = requests.filter { it.method == "DELETE" }
    private val body get() = "第一章 $id\n" + "正文内容。\n".repeat(40)

    @Before fun setUp() {
        directory.mkdirs(); File(directory, "saved").mkdirs()
        file = File(directory, "book-$id.txt").apply { writeText(body) }
        prefs.edit().putBoolean(PreferKey.autoBackup, false).putBoolean(PreferKey.cronet, false)
            .putString(PreferKey.defaultBookTreeUri, File(directory, "saved").path).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1)
            .putBoolean("bookInfoDeleteAlert", true).putBoolean("deleteBookOriginal", false).commit()
        server = WebDavFixtureServer { request ->
            requests.add(request)
            when (request.method) {
                "PROPFIND" -> {
                    val root = request.uri.trimEnd('/') + "/"
                    val names = mutableListOf(root to true, (root + "other-$id.txt") to false)
                    if (exists.get()) names.add((root + file.name) to false)
                    val xml = "<d:multistatus xmlns:d=\"DAV:\">" + names.joinToString("") { (path, folder) ->
                        "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:displayname>${path.trimEnd('/').substringAfterLast('/')}</d:displayname>" +
                            "<d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><d:getcontenttype>application/octet-stream</d:getcontenttype>" +
                            "<d:getcontentlength>100</d:getcontentlength><d:getlastmodified>Mon, 01 Jan 2024 00:00:01 GMT</d:getlastmodified>" +
                            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    } + "</d:multistatus>"
                    WebDavFixtureServer.Reply(207, "application/xml", xml)
                }
                "PUT" -> {
                    putStarted.countDown()
                    putGate?.await(15, TimeUnit.SECONDS)
                    if (raceConflict.compareAndSet(true, false)) {
                        exists.set(true); WebDavFixtureServer.Reply(412, "text/plain", "Already exists")
                    } else if (rejectPut.get()) WebDavFixtureServer.Reply(500, "text/plain", "Unavailable")
                    else { exists.set(true); WebDavFixtureServer.Reply(201, "text/plain", "Created") }
                }
                "DELETE" -> if (rejectDelete.get()) WebDavFixtureServer.Reply(500, "text/plain", "Unable to delete")
                    else { exists.set(false); WebDavFixtureServer.Reply(204, "text/plain", "") }
                else -> WebDavFixtureServer.Reply(200, "text/plain", "")
            }
        }
        previousRemote = AppWebDav.defaultBookWebDav
        AppWebDav.defaultBookWebDav = RemoteBookWebDav("$base/dav/", Authorization("user", "secret"))
        requests.clear()
        book = Book(bookUrl = file.path, originName = file.name, name = "Library $id", author = "Author $id",
            type = BookType.text or BookType.local, coverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "第一章", index = 0))
    }
    @After fun tearDown() {
        putGate?.countDown()
        (ownedRoutes.map { it.requestId } + "upload-$id").forEach { requestId -> appDb.bookInfoUploadDao.latestForRequest(requestId)?.let { command ->
            appDb.runInTransaction {
                appDb.bookInfoUploadDao.update(command.copy(status = io.legado.app.data.entities.BookInfoUploadStatus.Cancelled))
                appDb.bookInfoUploadDao.release(command.id)
            }
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("book-info-upload-${command.id}").result.get(10, TimeUnit.SECONDS)
            io.legado.app.data.repository.BookInfoUploadPayloadStore(context).remove(command.payloadKey)
            appDb.bookInfoUploadDao.deleteTerminal(command.id)
        } }
        main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ReadBookActivity>().forEach { it.finish() } }
        instrumentation.waitForIdleSync()
        AppWebDav.defaultBookWebDav = previousRemote
        appDb.bookDao.findByName(book.name).forEach { item -> appDb.bookChapterDao.delByBook(item.bookUrl); appDb.bookDao.delete(item); BookHelp.clearCache(item) }
        (extraBooks + book.bookUrl).forEach { key -> appDb.bookDao.getBook(key)?.let { appDb.bookChapterDao.delByBook(it.bookUrl); appDb.bookDao.delete(it); BookHelp.clearCache(it) } }
        server.stop()
        prefs.edit().apply { previous.forEach { (key, value) -> when (value) { null -> remove(key); is String -> putString(key, value); is Boolean -> putBoolean(key, value) } } }.commit()
        LocalConfig.edit().apply { previousLocal.forEach { (key, value) -> when (value) { null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) } } }.commit()
        directory.deleteRecursively()
    }

    @Test fun conditionalUploadSurvivesRotationAndOnlyPatchesRemoteMetadata() {
        putGate = CountDownLatch(1)
        launch().use { scenario ->
            ready(scenario); openUpload(scenario)
            await { puts.size == 1 }
            scenario.recreate()
            compose.onNodeWithTag("library_action_progress").assertIsDisplayed()
            compose.onNodeWithTag("library_action_cancel").assertIsNotEnabled()
            val edited = appDb.bookDao.getBook(book.bookUrl)!!.copy(name = "Edited $id", durChapterPos = 444)
            appDb.bookDao.update(edited)
            putGate!!.countDown()
            await { state(scenario).libraryAction.operation == null && (state(scenario).book?.lastCheckTime ?: 0L) > book.lastCheckTime }
            assertEquals("*", puts.single().headers["if-none-match"])
            assertArrayEquals(file.readBytes(), puts.single().body)
            assertEquals("Edited $id", appDb.bookDao.getBook(book.bookUrl)!!.name)
            assertEquals(444, state(scenario).book!!.durChapterPos)
            assertTrue(state(scenario).book!!.origin.startsWith(BookType.webDavTag + base))
        }
    }

    private fun ownedModel(activity: AppActivity, route: BookInfoRoute) = ViewModelProvider(
        navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(route) {}.contentKey))[BookInfoViewModel::class.java]
    private fun ownedState(scenario: ActivityScenario<AppActivity>, route: BookInfoRoute): BookInfoUiState {
        lateinit var state: BookInfoUiState
        scenario.onActivity { state = ownedModel(it, route).uiState.value }
        return state
    }
    private fun ownedText(scenario: ActivityScenario<AppActivity>, resource: Int): String {
        lateinit var text: String
        scenario.onActivity { text = it.getString(resource) }
        return text
    }
    private fun launchOwned(route: BookInfoRoute): ActivityScenario<AppActivity> {
        ownedRoutes += route
        val scenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route))
        try {
            compose.waitUntil(15_000) { ownedState(scenario, route).let { it.book?.bookUrl == book.bookUrl && it.recoveryReady } }
            val menu = ownedText(scenario, R.string.more_menu)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(menu).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            return scenario
        } catch (error: Throwable) {
            val diagnostic = File(context.cacheDir, "owned-upload-$id.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { diagnostic.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            println("Owned upload diagnostic: ${diagnostic.path}")
            println(compose.onRoot(useUnmergedTree = true).printToString())
            scenario.close(); throw error
        }
    }
    private fun openOwnedUpload(scenario: ActivityScenario<AppActivity>) {
        compose.onNodeWithContentDescription(ownedText(scenario, R.string.more_menu)).performClick()
        compose.onNodeWithText(ownedText(scenario, R.string.upload_to_remote)).performClick()
    }

    @Test fun ownedDestinationUsesOneDurableUploadAcrossRotation() {
        putGate = CountDownLatch(1)
        val route = BookInfoRoute(book.bookUrl)
        launchOwned(route).use { scenario ->
            openOwnedUpload(scenario)
            assertTrue(putStarted.await(10, TimeUnit.SECONDS))
            compose.waitUntil(10_000) { ownedState(scenario, route).pendingUpload != null }
            val command = checkNotNull(appDb.bookInfoUploadDao.latestForRequest(route.requestId))
            assertEquals(io.legado.app.data.entities.BookInfoUploadStatus.Running, command.status)
            scenario.recreate()
            compose.waitUntil(10_000) { ownedState(scenario, route).libraryAction.phase == BookInfoLibraryPhase.Uploading }
            compose.onNodeWithTag("library_action_cancel").assertIsEnabled()
            file.writeText("Original changed after upload confirmation")
            putGate!!.countDown()
            compose.waitUntil(15_000) { ownedState(scenario, route).let { it.libraryAction.operation == null && it.book?.origin?.startsWith(BookType.webDavTag) == true } }
            assertEquals(command.id, appDb.bookInfoUploadDao.latestForRequest(route.requestId)!!.id)
            assertEquals(io.legado.app.data.entities.BookInfoUploadStatus.Completed, appDb.bookInfoUploadDao.get(command.id)!!.status)
            assertArrayEquals(body.toByteArray(), puts.single().body)
            assertEquals("*", puts.single().headers["if-none-match"])
        }
    }

    @Test fun anotherDestinationCanCancelAnUploadAfterTheOriginalEntryCloses() {
        putGate = CountDownLatch(1)
        val first = BookInfoRoute(book.bookUrl)
        val initial = launchOwned(first)
        try {
            openOwnedUpload(initial)
            assertTrue(putStarted.await(10, TimeUnit.SECONDS))
        } finally { initial.close() }
        val command = checkNotNull(appDb.bookInfoUploadDao.latestForRequest(first.requestId))
        val second = BookInfoRoute(book.bookUrl)
        launchOwned(second).use { scenario ->
            compose.waitUntil(10_000) { ownedState(scenario, second).pendingUpload?.id == command.id }
            scenario.recreate()
            compose.waitUntil(10_000) { ownedState(scenario, second).pendingUpload?.id == command.id }
            compose.onNodeWithTag("book_upload_cancel").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { appDb.bookInfoUploadDao.get(command.id)?.status == io.legado.app.data.entities.BookInfoUploadStatus.Cancelled }
            putGate!!.countDown()
            compose.waitUntil(10_000) { ownedState(scenario, second).pendingUpload == null }
            assertNull(appDb.bookInfoUploadDao.lease(book.bookUrl))
            assertEquals(book.origin, appDb.bookDao.getBook(book.bookUrl)!!.origin)
            assertEquals("", appDb.bookInfoUploadDao.get(command.id)!!.target)
        }
    }

    @Test fun existingRemoteApprovalRestoresAndConfirmedUploadOmitsTheConditionalHeader() {
        exists.set(true)
        launch().use { scenario ->
            ready(scenario); openUpload(scenario)
            await { state(scenario).libraryAction.phase == BookInfoLibraryPhase.ConfirmOverwrite }
            scenario.recreate()
            compose.onNodeWithTag("library_overwrite_message").assertIsDisplayed()
            assertTrue(puts.isEmpty())
            compose.onNodeWithTag("library_overwrite_confirm").performClick()
            await { state(scenario).libraryAction.operation == null }
            assertEquals(1, puts.size); assertNull(puts.single().headers["if-none-match"])
        }
    }

    @Test fun overwriteCanBeCancelledWithoutChangingTheFileOrBook() {
        exists.set(true)
        launch().use { scenario ->
            ready(scenario); openUpload(scenario)
            await { state(scenario).libraryAction.phase == BookInfoLibraryPhase.ConfirmOverwrite }
            compose.onNodeWithTag("library_action_cancel").performClick()
            assertTrue(puts.isEmpty()); assertTrue(file.exists())
            assertEquals(book.origin, appDb.bookDao.getBook(book.bookUrl)!!.origin)
        }
    }

    @Test fun racingRemoteCreationRequiresANewExplicitOverwriteApproval() {
        raceConflict.set(true)
        launch().use { scenario ->
            ready(scenario); openUpload(scenario)
            await { state(scenario).libraryAction.phase == BookInfoLibraryPhase.ConfirmOverwrite }
            assertEquals(1, puts.size); assertEquals("*", puts.first().headers["if-none-match"])
            compose.onNodeWithTag("library_overwrite_confirm").performClick()
            await { state(scenario).libraryAction.operation == null }
            assertEquals(2, puts.size); assertNull(puts.last().headers["if-none-match"])
        }
    }

    @Test fun uploadFailureRetainsTheActionAndCanRetry() {
        rejectPut.set(true)
        launch().use { scenario ->
            ready(scenario); openUpload(scenario)
            await { state(scenario).libraryAction.phase == BookInfoLibraryPhase.Error }
            compose.onNodeWithTag("library_action_error").assertIsDisplayed()
            assertEquals(book.lastCheckTime, appDb.bookDao.getBook(book.bookUrl)!!.lastCheckTime)
            rejectPut.set(false)
            compose.onNodeWithTag("library_action_retry").performClick()
            await { state(scenario).libraryAction.operation == null }
            assertEquals(2, puts.size); assertTrue(puts.all { it.headers["if-none-match"] == "*" })
        }
    }

    @Test fun deletionChoicesSurviveRotationAndDeleteOnlyTheExactRemoteFile() {
        exists.set(true)
        launch().use { scenario ->
            ready(scenario); openDelete(scenario)
            compose.onNodeWithTag("library_delete_original").performClick()
            compose.onNodeWithTag("library_delete_remote").performClick()
            scenario.recreate()
            compose.onNodeWithTag("library_delete_original").assertIsOn()
            compose.onNodeWithTag("library_delete_remote").assertIsOn()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300) // Let the platform dialog enter animation finish before visual inspection.
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(context.cacheDir, "book-info-library-dialog.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { screenshot.recycle() }
            compose.onNodeWithTag("library_delete_confirm").performClick()
            await { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertFalse(file.exists()); assertFalse(appDb.bookDao.has(book.bookUrl))
            assertEquals(listOf("/dav/${file.name}"), deletes.map { it.uri })
        }
    }

    @Test fun failedRemoteDeletionLeavesLocalDataAndRetryCanKeepTheOriginalFile() {
        exists.set(true); rejectDelete.set(true)
        launch().use { scenario ->
            ready(scenario); openDelete(scenario)
            compose.onNodeWithTag("library_delete_remote").performClick()
            compose.onNodeWithTag("library_delete_confirm").performClick()
            await { state(scenario).libraryAction.error == BookInfoLibraryError.RemoteDelete }
            assertTrue(file.exists()); assertTrue(appDb.bookDao.has(book.bookUrl))
            compose.onNodeWithTag("library_action_retry").performClick()
            compose.onNodeWithTag("library_delete_remote").assertIsOn()
            compose.onNodeWithTag("library_delete_remote").performClick()
            compose.onNodeWithTag("library_delete_confirm").performClick()
            await { scenario.state == Lifecycle.State.DESTROYED }
            assertTrue(file.exists()); assertFalse(appDb.bookDao.has(book.bookUrl))
            assertEquals(1, deletes.size)
        }
    }

    @Test fun importedFileHandoffUploadsAndThenOpensTheRealReader() {
        launch().use { scenario ->
            ready(scenario)
            val data = "data:text/plain;base64," + Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
            scenario.onActivity {
                model(it).filesController.open(book.bookUrl, upload = true, read = true)
                model(it).filesController.select(BookInfoWebFile(data, "copy.txt"))
            }
            await {
                var resumed = false
                main { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is ReadBookActivity } }
                resumed && io.legado.app.model.ReadBook.book?.name == book.name
            }
            assertEquals(1, puts.size)
            assertArrayEquals(body.toByteArray(), puts.single().body)
            assertTrue(io.legado.app.model.ReadBook.book!!.origin.startsWith(BookType.webDavTag + base))
        }
    }

    @Test fun deletingAnAlreadyMissingOriginalStillRemovesItsShelfEntry() = runBlocking {
        assertTrue(file.delete())
        BookInfoLibraryRepository(appDb).remove(book.bookUrl, deleteOriginal = true, deleteRemote = false, expected = null)
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertFalse(file.exists())
        assertTrue(deletes.isEmpty())
    }

    @Test fun contentUriUploadUsesTheConditionalHeaderAndTransfersTheActualBytes() = runBlocking {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val providerBook = book.copy(bookUrl = uri.toString(), name = "Provider $id")
        extraBooks.add(providerBook.bookUrl)
        appDb.bookDao.insert(providerBook)
        val repository = BookInfoLibraryRepository(appDb)
        val options = repository.inspect(providerBook.bookUrl)
        val target = checkNotNull(options.target)
        assertFalse(repository.remoteExists(providerBook.bookUrl, target))
        val saved = repository.upload(providerBook.bookUrl, target, overwrite = false)
        assertEquals("*", puts.single().headers["if-none-match"])
        assertArrayEquals(file.readBytes(), puts.single().body)
        assertTrue(saved.origin.startsWith(BookType.webDavTag + base))
        assertEquals(book.origin, appDb.bookDao.getBook(book.bookUrl)!!.origin)
    }

    @Test fun disabledConfirmationRemovesTheShelfEntryAndKeepsTheFile() {
        LocalConfig.bookInfoDeleteAlert = false
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity { model(it).requestDelete() }
            await { scenario.state == Lifecycle.State.DESTROYED }
            assertTrue(file.exists()); assertFalse(appDb.bookDao.has(book.bookUrl)); assertTrue(deletes.isEmpty())
        }
    }

    private fun launch() = ActivityScenario.launchActivityForResult<BookInfoActivity>(Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl))
    private fun model(activity: BookInfoActivity) = ViewModelProvider(activity)[BookInfoViewModel::class.java]
    private fun state(scenario: ActivityScenario<BookInfoActivity>): BookInfoUiState {
        lateinit var state: BookInfoUiState
        scenario.onActivity { state = model(it).uiState.value }; return state
    }
    private fun ready(scenario: ActivityScenario<BookInfoActivity>) = await { state(scenario).book != null && state(scenario).chapters.isNotEmpty() && !state(scenario).loading }
    private fun openUpload(scenario: ActivityScenario<BookInfoActivity>) {
        lateinit var more: String; lateinit var label: String
        scenario.onActivity { more = it.getString(R.string.more_menu); label = it.getString(R.string.upload_to_remote) }
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(label).performClick()
    }
    private fun openDelete(scenario: ActivityScenario<BookInfoActivity>) {
        lateinit var label: String
        scenario.onActivity { label = it.getString(R.string.remove_from_bookshelf) }
        compose.onNodeWithText(label).performClick()
        await { state(scenario).libraryAction.phase == BookInfoLibraryPhase.ConfirmDelete }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < end) { compose.mainClock.advanceTimeByFrame(); SystemClock.sleep(50) }
        assertTrue("Library action did not reach the expected state", condition()); instrumentation.waitForIdleSync()
    }
    @Test fun aRevokedDetailOwnerCannotStartAnUpload() = runBlocking {
        val repository = BookInfoLibraryRepository(appDb)
        val parent = appDb.bookInfoRequestDao.claim(BookInfoRequest("upload-$id", book.bookUrl, book.bookUrl, "old", updatedAt = 1), "old")
        try {
            val target = checkNotNull(repository.inspect(book.bookUrl).target)
            appDb.bookInfoRequestDao.claim(parent, "replacement")
            try { repository.upload(book.bookUrl, target, false, parent.navigationOwner(), book); fail("Revoked upload was accepted") }
            catch (error: BookInfoLibraryException) { assertEquals(BookInfoLibraryError.Changed, error.reason) }
            assertTrue(puts.isEmpty()); assertEquals(book.origin, appDb.bookDao.getBook(book.bookUrl)!!.origin)
        } finally { appDb.bookInfoRequestDao.get(parent.id)?.let { appDb.bookInfoRequestDao.close(it.id, it.ownerToken) } }
    }
    @Test fun aConfirmedDurableUploadCanFinishAfterItsDetailOwnerIsRevoked() = runBlocking {
        val repository = BookInfoLibraryRepository(appDb)
        val parent = appDb.bookInfoRequestDao.claim(BookInfoRequest("upload-$id", book.bookUrl, book.bookUrl, "old", updatedAt = 1), "old")
        putGate = CountDownLatch(1)
        val foreground = ActivityScenario.launch<io.legado.app.ui.AppActivity>(io.legado.app.ui.AppActivity.startIntent(context,
            io.legado.app.ui.navigation.AboutRoute))
        try {
            val target = checkNotNull(repository.inspect(book.bookUrl).target)
            val uploading = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { repository.upload(book.bookUrl, target, false, parent.navigationOwner(), book) }
            }
            val started = putStarted.await(10, TimeUnit.SECONDS)
            assertTrue("Upload did not start: ${appDb.bookInfoUploadDao.latestForRequest(parent.id)?.let { "${it.status}/${it.phase}/${it.error}" }}", started)
            appDb.bookInfoRequestDao.claim(parent, "replacement")
            putGate!!.countDown()
            assertTrue(uploading.await().isSuccess)
            assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.origin.startsWith(BookType.webDavTag))
            assertEquals(1, puts.size)
        } finally {
            putGate?.countDown()
            foreground.close()
            appDb.bookInfoRequestDao.get(parent.id)?.let { appDb.bookInfoRequestDao.close(it.id, it.ownerToken) }
        }
    }
    @Test fun changedCredentialsInvalidateTheCapturedRemoteTarget() = runBlocking {
        val repository = BookInfoLibraryRepository(appDb)
        val target = checkNotNull(repository.inspect(book.bookUrl).target)
        AppWebDav.defaultBookWebDav = RemoteBookWebDav("$base/dav/", Authorization("user", "different-secret"))
        try { repository.upload(book.bookUrl, target, false); fail("A changed credential target was accepted") }
        catch (error: BookInfoLibraryException) { assertEquals(BookInfoLibraryError.Changed, error.reason) }
        assertTrue(puts.isEmpty()); assertEquals(book.origin, appDb.bookDao.getBook(book.bookUrl)!!.origin)
    }

}
