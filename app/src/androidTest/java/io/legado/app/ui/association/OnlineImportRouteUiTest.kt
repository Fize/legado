package io.legado.app.ui.association

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.OnlineImportRepository
import io.legado.app.data.repository.OnlineImportResolution
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.OnlineImportRoute
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OnlineImportRouteUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val preferenceKeys = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.importReplaceSource)
    private val savedPreferences = preferenceKeys.associateWith { preferences.all[it] }
    private val fixtureName = "Online layout ${UUID.randomUUID()}"
    private val requests = AtomicInteger()
    private lateinit var savedConfigs: List<ReadBookConfig.Config>
    private lateinit var savedShared: ReadBookConfig.Config
    private var savedSharedIndex = -1
    private lateinit var server: NanoHTTPD
    @Volatile private var mime = "application/zip"

    @Before fun setUp() {
        preferences.edit().apply { preferenceKeys.forEach { putBoolean(it, false) } }.commit()
        savedConfigs = GSON.fromJsonArray<ReadBookConfig.Config>(GSON.toJson(ReadBookConfig.configList)).getOrThrow()
        savedSharedIndex = ReadBookConfig.configList.indexOfFirst { it === ReadBookConfig.shareConfig }
        savedShared = ReadBookConfig.shareConfig.copy()
        val config = ReadBookConfig.Config(name = fixtureName, textSize = 31)
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(ReadBookConfig.configFileName))
                zip.write(GSON.toJson(config).toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (session.uri.startsWith("/source/")) {
                    val name = session.uri.substringAfterLast('/')
                    return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                        GSON.toJson(listOf(BookSource("https://$name.invalid", "Online $name"))))
                }
                return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
    }

    @After fun tearDown() {
        server.stop()
        synchronized(ReadBookConfig) {
            ReadBookConfig.configList.clear()
            ReadBookConfig.configList.addAll(savedConfigs)
            ReadBookConfig.shareConfig = savedConfigs.getOrNull(savedSharedIndex) ?: savedShared
            ReadBookConfig.saveNow()
        }
        preferences.edit().apply {
            savedPreferences.forEach { (key, value) ->
                if (value is Boolean) putBoolean(key, value) else remove(key)
            }
        }.commit()
    }

    @Test fun explicitConfigConfirmationSurvivesRecreationAndCancelNeverWrites() {
        val before = GSON.toJson(ReadBookConfig.configList)
        launch("/readConfig").use { scenario ->
            awaitTag("onlineReadConfigConfirmation")
            assertEquals(before, GSON.toJson(ReadBookConfig.configList))
            scenario.recreate()
            awaitTag("onlineReadConfigConfirmation")
            assertEquals(1, requests.get())
            composeRule.onNodeWithTag("onlineReadConfigCancel").performClick()
            await { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertEquals(before, GSON.toJson(ReadBookConfig.configList))
    }

    @Test fun missingSourcesAndOpaqueLinksExitWithoutDownloadingOrChangingSettings() {
        val before = GSON.toJson(ReadBookConfig.configList)
        for (link in listOf("legado://import/auto", "legado:opaque?src=http://127.0.0.1/config")) {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, OnlineImportRoute(link))).use { scenario ->
                await { scenario.state == Lifecycle.State.DESTROYED }
            }
        }
        assertEquals(0, requests.get())
        assertEquals(before, GSON.toJson(ReadBookConfig.configList))
    }

    @Test fun octetStreamWithParametersRequiresConfirmationThenPersistsTheLayout() {
        mime = "application/octet-stream; charset=utf-8"
        launch("/auto").use { scenario ->
            awaitTag("onlineReadConfigConfirmation")
            assertFalse(ReadBookConfig.configList.any { it.name == fixtureName })
            composeRule.onNodeWithTag("onlineReadConfigConfirm").performClick()
            awaitTag("onlineImportResult")
            assertEquals(31, ReadBookConfig.configList.single { it.name == fixtureName }.textSize)
            assertTrue(File(ReadBookConfig.configFilePath).readText().contains(fixtureName))
            scenario.recreate()
            awaitTag("onlineImportResult")
            assertEquals(1, requests.get())
            assertEquals(1, ReadBookConfig.configList.count { it.name == fixtureName })
            composeRule.onNodeWithTag("onlineImportResultConfirm").performClick()
            await { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun aNewViewModelRestoresPendingBytesByKeyWithoutAnotherRequest() {
        val repository = OnlineImportRepository(context)
        val pending = runBlocking { repository.resolve(link("/readConfig")) } as OnlineImportResolution.ReadConfiguration
        lateinit var model: OnLineImportViewModel
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                model = OnLineImportViewModel(repository, SavedStateHandle(mapOf("pendingReadConfigKey" to pending.key)))
                store.put("restored-online-import", model)
                model.initialize(link("/readConfig"))
            }
            await { model.uiState.value.confirmReadConfig }
            assertEquals(1, requests.get())
            assertFalse(ReadBookConfig.configList.any { it.name == fixtureName })
            instrumentation.runOnMainSync { model.cancelReadConfigImport() }
            await { model.uiState.value.finished }
            assertFalse(runBlocking { repository.canRestore(pending.key) })
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun simultaneousAutoDownloadsKeepDistinctContentsAndRouteToTheRealPreview() {
        val repository = OnlineImportRepository(context)
        val results = runBlocking {
            listOf("a", "b").map { name -> async {
                repository.resolve(link("/auto", "/source/$name")) as OnlineImportResolution.Destination
            } }.awaitAll()
        }
        assertNotEquals(results[0].source, results[1].source)
        results.forEachIndexed { index, result ->
            assertEquals("bookSource", result.type)
            val source = GSON.fromJsonArray<BookSource>(File(checkNotNull(Uri.parse(result.source).path)).readText()).getOrThrow().single()
            assertEquals("Online ${if (index == 0) "a" else "b"}", source.bookSourceName)
        }
        launch("/auto", "/source/a").use { scenario ->
            awaitTag("sourceImportRow:0")
            composeRule.onNodeWithText("Online a").assertIsDisplayed()
            scenario.recreate()
            awaitTag("sourceImportRow:0")
            composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
            await { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    private fun link(path: String, endpoint: String = "/config"): String = Uri.Builder()
        .scheme("legado").authority("import").path(path)
        .appendQueryParameter("src", "http://127.0.0.1:${server.listeningPort}$endpoint").build().toString()

    private fun launch(path: String, endpoint: String = "/config") = ActivityScenario.launch<AppActivity>(
        AppActivity.startIntent(context, OnlineImportRoute(link(path, endpoint))),
    )

    private fun awaitTag(tag: String) {
        await { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            SystemClock.sleep(50)
        }
        assertTrue("Online import did not reach the expected state", condition())
        instrumentation.waitForIdleSync()
    }
}
