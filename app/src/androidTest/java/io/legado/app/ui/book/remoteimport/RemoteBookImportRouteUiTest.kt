package io.legado.app.ui.book.remoteimport

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.navigation.*
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RemoteBookImportRouteUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.defaultBookTreeUri, PreferKey.remoteServerId,
        PreferKey.webDavAccount, PreferKey.webDavPassword, PreferKey.webDavUrl, PreferKey.webDavDir, PreferKey.bookImportFileName)
    private val saved = keys.associateWith { prefs.all[it] }
    private val localKeys = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")
    private val savedLocal = localKeys.associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "remote-route-$id")
    private val serverId = System.currentTimeMillis()
    private val local = LocalBookImportRepository(context, appDb)
    private val repository = RemoteBookImportRepository(context, appDb, local)
    private val failDownload = AtomicBoolean(false)
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val openedBooks = CopyOnWriteArrayList<String>()
    private lateinit var server: WebDavFixtureServer
    private val nodes = linkedMapOf<String, ByteArray?>()
    private val first get() = "book2-$id.txt"
    private val second get() = "book10-$id.txt"
    private val archive get() = "archive-$id.zip"
    private val nested get() = "inside-$id.txt"
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className != ReadBookActivity::class.java.name) return null
            openedBooks.add(checkNotNull(intent.getStringExtra("bookUrl")))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @Before fun setUp() {
        directory.mkdirs()
        nodes["/dav/"] = null
        nodes["/dav/sub/"] = null
        nodes["/dav/$first"] = "FIRST $id".toByteArray()
        nodes["/dav/$second"] = "SECOND $id".toByteArray()
        nodes["/dav/sub/$nested"] = "NESTED $id".toByteArray()
        nodes["/other/"] = null
        nodes["/other/other-$id.txt"] = "OTHER $id".toByteArray()
        server = WebDavFixtureServer { session ->
                requests.add(session.method to session.uri)
                if (session.headers["authorization"] != Credentials.basic("user", "secret"))
                    return@WebDavFixtureServer WebDavFixtureServer.Reply(401, "text/plain", "Authorization required")
                if (session.method == "PROPFIND") {
                    if (!nodes.containsKey(session.uri)) return@WebDavFixtureServer WebDavFixtureServer.Reply(404, "text/plain", "Missing")
                    val children = nodes.keys.filter { it == session.uri || (it.startsWith(session.uri) && it.removePrefix(session.uri).trimEnd('/').contains('/').not()) }
                    val body = "<d:multistatus xmlns:d=\"DAV:\">" + children.joinToString("") { path ->
                        val content = nodes[path]
                        "<d:response><d:href>$path</d:href><d:propstat><d:prop>" +
                            "<d:displayname>${path.trimEnd('/').substringAfterLast('/')}</d:displayname>" +
                            "<d:resourcetype>${if (content == null) "<d:collection/>" else ""}</d:resourcetype>" +
                            "<d:getcontenttype>${if (content == null) "httpd/unix-directory" else "application/octet-stream"}</d:getcontenttype>" +
                            "<d:getcontentlength>${content?.size ?: 0}</d:getcontentlength>" +
                            "<d:getlastmodified>Mon, 01 Jan 2024 00:00:01 GMT</d:getlastmodified>" +
                            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    } + "</d:multistatus>"
                    return@WebDavFixtureServer WebDavFixtureServer.Reply(207, "application/xml", body)
                }
                if (session.method == "GET") {
                    if (session.uri == "/dav/$second" && failDownload.get())
                        return@WebDavFixtureServer WebDavFixtureServer.Reply(500, "text/plain", "Temporary failure")
                    val bytes = nodes[session.uri] ?: return@WebDavFixtureServer WebDavFixtureServer.Reply(404, "text/plain", "Missing")
                    return@WebDavFixtureServer WebDavFixtureServer.Reply(200, "application/octet-stream", bytes)
                }
                WebDavFixtureServer.Reply(200, "text/plain", "")
        }
        prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.defaultBookTreeUri, directory.path).putLong(PreferKey.remoteServerId, serverId)
            .remove(PreferKey.bookImportFileName).commit()
        appDb.serverDao.insert(Server(serverId, "Fixture $id", config = GSON.toJson(Server.WebDavConfig("$base/dav/", "user", "secret")), sortNumber = 17))
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        instrumentation.addMonitor(monitor)
    }

    @After fun tearDown() {
        instrumentation.removeMonitor(monitor)
        server.stop()
        appDb.bookDao.all.filter { it.originName.contains(id) }.forEach {
            appDb.bookChapterDao.delByBook(it.bookUrl); appDb.bookDao.delete(it); BookHelp.clearCache(it)
        }
        appDb.serverDao.all.filter { it.id == serverId || it.name.contains(id) }.forEach { appDb.serverDao.delete(it) }
        prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
            null -> remove(key); is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Long -> putLong(key, value)
        } } }.commit()
        LocalConfig.edit().apply { savedLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
        directory.deleteRecursively()
    }

    @Test fun browsingSortingFilteringAndRecreationUseTheRemoteDestination() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 3 }
            assertTrue(state(scenario).entries.first().isDir)
            composeRule.onNodeWithContentDescription(context.getString(R.string.sort)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.sort_by_name)).performClick()
            awaitState(scenario) { it.entries.map { e -> e.filename } == listOf("sub", first, second) }
            composeRule.onNodeWithTag("remoteImportEntry_$first").performClick()
            val selection = state(scenario).selected
            scenario.recreate()
            awaitState(scenario) { !it.loading && it.selected == selection }
            act(scenario) { query("book2") }
            awaitState(scenario) { it.entries.singleOrNull()?.filename == first }
            act(scenario) { query("") }
            awaitState(scenario) { it.entries.size == 3 }
            composeRule.onNodeWithTag("remoteImportEntry_sub").performClick()
            awaitState(scenario) { !it.loading && it.canGoBack && it.entries.singleOrNull()?.filename == nested }
            assertTrue(state(scenario).selected.isEmpty())
            act(scenario) { goBack() }
            awaitState(scenario) { !it.loading && !it.canGoBack && it.entries.size == 3 }
            composeRule.onNodeWithTag("remoteImportEntry_$first").assertIsDisplayed()
            screenshot("remote-import-browser")
        }
    }

    @Test fun partialBatchImportPreservesTheSuccessfulBookAndRetriesOnlyTheFailedSelection() {
        failDownload.set(true)
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 3 }
            composeRule.onNodeWithTag("remoteImportEntry_$first").performClick()
            composeRule.onNodeWithTag("remoteImportEntry_$second").performClick()
            composeRule.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performClick()
            awaitState(scenario) { !it.working && it.entries.count { e -> e.isOnBookShelf } == 1 && it.selected.size == 1 }
            val book = checkNotNull(appDb.bookDao.getBookByFileName(first))
            assertTrue(book.origin.startsWith(BookType.webDavTag))
            val source = CustomUrl(book.origin.removePrefix(BookType.webDavTag))
            assertEquals("$base/dav/$first", source.getUrl())
            assertEquals(serverId, (source.getAttr()["serverID"] as Number).toLong())
            assertEquals("FIRST $id", File(directory, first).readText())
            assertNull(appDb.bookDao.getBookByFileName(second))
            assertTrue(state(scenario).selected.single().endsWith(second))
            failDownload.set(false)
            composeRule.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performClick()
            awaitState(scenario) { !it.working && it.entries.count { e -> e.isOnBookShelf } == 2 && it.selected.isEmpty() }
            assertEquals("SECOND $id", File(directory, second).readText())
            assertEquals(1, requests.count { it.first == "GET" && it.second.endsWith(first) })
            assertEquals(2, requests.count { it.first == "GET" && it.second.endsWith(second) })
        }
    }

    @Test fun downloadedArchiveIsRecognizedAfterRefreshAndCanBeDownloadedAgainThenRead() {
        nodes["/dav/$archive"] = zip(mapOf("a-$id.txt" to "Archive A $id", "b-$id.txt" to "Archive B $id"))
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 4 }
            composeRule.onNodeWithTag("remoteImportEntry_$archive").performClick()
            composeRule.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performClick()
            awaitState(scenario) { !it.working && it.entries.single { e -> e.filename == archive }.isOnBookShelf }
            val products = listOf("a-$id.txt", "b-$id.txt").map { checkNotNull(appDb.bookDao.getBookByFileName(it)) }
            act(scenario) { refresh() }
            awaitState(scenario) { !it.loading && it.entries.any { e -> e.filename == archive && e.isOnBookShelf } }
            assertTrue(File(directory, archive).delete())
            composeRule.onNodeWithTag("remoteImportEntry_$archive").performClick()
            awaitTag("remoteImportDownloadConfirm")
            composeRule.onNodeWithTag("remoteImportDownloadConfirm").performClick()
            awaitState(scenario) { !it.working && it.archive?.names?.size == 2 }
            composeRule.onNodeWithText("a-$id.txt").performClick()
            await { openedBooks.contains(products.first().bookUrl) }
            assertEquals(2, requests.count { it.first == "GET" && it.second.endsWith(archive) })
            assertEquals(products.map { it.bookUrl }.toSet(), listOf("a-$id.txt", "b-$id.txt").map { checkNotNull(appDb.bookDao.getBookByFileName(it)).bookUrl }.toSet())
            composeRule.onNodeWithTag("remoteImportEntry_$archive").performClick()
            awaitState(scenario) { !it.working && it.archive?.names?.size == 2 }
            runBlocking { repository.saveServer(RemoteServerDraft(serverId, "Edited $id", "$base/other/", "user", "secret")) }
            awaitState(scenario) { !it.loading && it.entries.singleOrNull()?.filename == "other-$id.txt" && it.archive == null }
            assertNull(state(scenario).redownload)
            assertNull(state(scenario).reimport)
        }
    }

    @Test fun serverEditorRestoresItsDraftAndSelectionRefreshesTheParentThroughRepositoryChanges() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 3 }
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithTag("legadoTopAppBarMenuAction_0").performClick()
            awaitTag("remoteServerConfirm")
            composeRule.onNodeWithContentDescription(context.getString(R.string.add)).performClick()
            awaitTag("remoteServerName")
            composeRule.onNodeWithTag("remoteServerName").performTextReplacement("Created $id")
            composeRule.onNodeWithTag("remoteServerUrl").performTextReplacement("$base/other")
            composeRule.onNodeWithTag("remoteServerUsername").performTextReplacement("user")
            composeRule.onNodeWithTag("remoteServerPassword").performTextReplacement("secret")
            scenario.recreate()
            awaitTag("remoteServerPassword")
            composeRule.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
            awaitTag("remoteServerConfirm")
            val created = checkNotNull(appDb.serverDao.all.firstOrNull { it.name == "Created $id" })
            assertEquals("$base/other/", created.getWebDavConfig()!!.url)
            composeRule.onNodeWithTag("remoteServer_${created.id}").performClick()
            composeRule.onNodeWithTag("remoteServerConfirm").performClick()
            awaitState(scenario) { !it.loading && it.entries.singleOrNull()?.filename == "other-$id.txt" }
            assertEquals(created.id, AppConfig.remoteServerId)
            assertFalse(state(scenario).canGoBack)
        }
    }

    @Test fun selectedServerDeletionUsesTheDefaultAndInvalidEditorUrlsNeverWrite() = runBlocking {
        val before = appDb.serverDao.all.size
        val draft = RemoteServerDraft(name = "Invalid $id", url = "file:///private/config")
        assertTrue(runCatching { repository.saveServer(draft) }.isFailure)
        assertEquals(before, appDb.serverDao.all.size)
        launch(RemoteServersRoute).use { scenario ->
            awaitTag("remoteServerDelete_$serverId")
            composeRule.onNodeWithTag("remoteServerDelete_$serverId").performClick()
            awaitTag("remoteServerDeleteConfirm")
            composeRule.onNodeWithTag("remoteServerDeleteConfirm").performClick()
            await { appDb.serverDao.get(serverId) == null && AppConfig.remoteServerId == DEFAULT_WEBDAV_ID }
            assertNotNull(scenario)
        }
    }

    @Test fun newViewModelRestoresNestedDirectorySelectionSortAndPendingStoragePicker() {
        val nestedPath = "$base/dav/sub/$nested"
        val saved = SavedStateHandle(mapOf("root" to "$base/dav/", "serverId" to serverId,
            "directories" to Json.encodeToString(listOf(RemoteImportDirectory("$base/dav/sub/", "sub"))),
            "selection" to arrayListOf(nestedPath), "query" to "inside", "sort" to RemoteImportSort.Name.name,
            "ascending" to true, "picker" to true, "pickerLaunched" to true))
        val store = ViewModelStore()
        lateinit var model: RemoteBookImportViewModel
        try {
            instrumentation.runOnMainSync { model = RemoteBookImportViewModel(repository, saved); store.put("remote", model); model.initialize() }
            await { !model.uiState.value.loading && model.uiState.value.entries.size == 1 }
            assertEquals(setOf(nestedPath), model.uiState.value.selected)
            assertEquals("inside", model.uiState.value.query)
            assertEquals(RemoteImportSort.Name, model.uiState.value.sort)
            assertTrue(model.uiState.value.canGoBack)
            instrumentation.runOnMainSync { assertFalse(model.claimPicker()); model.picked(null) }
            assertEquals(setOf(nestedPath), model.uiState.value.selected)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun missingDefaultConfigurationStopsLoadingAndStillAllowsServerManagement() {
        prefs.edit().putLong(PreferKey.remoteServerId, DEFAULT_WEBDAV_ID)
            .remove(PreferKey.webDavAccount).remove(PreferKey.webDavPassword).commit()
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.message != null }
            assertTrue(state(scenario).entries.isEmpty())
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithTag("legadoTopAppBarMenuAction_0").performClick()
            awaitTag("remoteServerConfirm")
        }
    }

    @Test fun defaultConnectionBuildsTheBooksCollectionWithoutDiscardingTheUrlQuery() = runBlocking {
        prefs.edit().putLong(PreferKey.remoteServerId, DEFAULT_WEBDAV_ID)
            .putString(PreferKey.webDavAccount, "user").putString(PreferKey.webDavPassword, "secret")
            .putString(PreferKey.webDavUrl, "$base/dav?token=kept").putString(PreferKey.webDavDir, "nested folder").commit()
        val connection = repository.connection()
        assertEquals("$base/dav/nested%20folder/books/?token=kept", connection.root)
        assertTrue(connection.isDefault)
        assertNull(connection.serverId)
        assertTrue(requests.isEmpty())
    }

    @Test fun editingTheSelectedServerPreservesItsIdentityAndResetsTheOldDirectory() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 3 }
            composeRule.onNodeWithTag("remoteImportEntry_sub").performClick()
            awaitState(scenario) { !it.loading && it.canGoBack && it.entries.size == 1 }
            composeRule.onNodeWithTag("remoteImportEntry_$nested").performClick()
            assertEquals(1, state(scenario).selected.size)
            val savedId = runBlocking { repository.saveServer(RemoteServerDraft(serverId, "Edited $id", "$base/other", "user", "secret")) }
            assertEquals(serverId, savedId)
            awaitState(scenario) { !it.loading && !it.canGoBack && it.entries.singleOrNull()?.filename == "other-$id.txt" }
            assertTrue(state(scenario).selected.isEmpty())
            assertEquals(serverId, AppConfig.remoteServerId)
            assertEquals(17, appDb.serverDao.get(serverId)!!.sortNumber)
        }
    }

    @Test fun importsRejectFilesOutsideTheConfiguredServerOriginBeforeMakingARequest() = runBlocking {
        val foreignRequests = AtomicInteger()
        val foreign = WebDavFixtureServer {
            foreignRequests.incrementAndGet()
            WebDavFixtureServer.Reply(200, "text/plain", "Foreign response")
        }
        try {
            val connection = repository.connection()
            val entry = RemoteImportEntry("http://127.0.0.1:${foreign.listeningPort}/foreign-$id.txt", "foreign-$id.txt", false, 16, 0, false)
            val result = repository.importBooks(connection, listOf(entry))
            assertTrue(result.completePaths.isEmpty())
            assertFalse(result.storageDenied)
            assertEquals(0, foreignRequests.get())
            assertFalse(File(directory, entry.filename).exists())
        } finally { foreign.stop() }
    }

    private fun launch(route: AppRoute = RemoteBookRoute) = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route))
    private fun model(activity: AppActivity): RemoteBookImportViewModel = ViewModelProvider(
        navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(RemoteBookRoute) {}.contentKey),
    )[RemoteBookImportViewModel::class.java]
    private fun state(scenario: ActivityScenario<AppActivity>): RemoteBookImportUiState {
        lateinit var state: RemoteBookImportUiState
        scenario.onActivity { state = model(it).uiState.value }
        return state
    }
    private fun act(scenario: ActivityScenario<AppActivity>, block: RemoteBookImportViewModel.() -> Unit) = scenario.onActivity { model(it).block() }
    private fun awaitState(scenario: ActivityScenario<AppActivity>, condition: (RemoteBookImportUiState) -> Boolean) = await { condition(state(scenario)) }
    private fun awaitTag(tag: String) { await { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } } }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) { composeRule.mainClock.advanceTimeByFrame(); SystemClock.sleep(50) }
        assertTrue("Remote import did not reach the expected state", condition())
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "ui-regression/$name.png").apply {
            parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    private fun zip(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
}
