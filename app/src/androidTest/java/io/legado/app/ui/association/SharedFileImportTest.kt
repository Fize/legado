package io.legado.app.ui.association

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.FileAssociationRepository
import io.legado.app.data.repository.FileAssociationResult
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.repository.AddToBookshelfRepository
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.navigation.ImportLocalBookRoute
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.ui.navigation.FileAssociationRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import androidx.navigation3.runtime.NavEntry
import io.legado.app.ui.navigation.AddToBookshelfRoute
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.navigation.OnlineImportRoute
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SharedFileImportTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = HashMap(prefs.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")
        .associateWith { LocalConfig.all[it] }
    private val savedBackupTime = LocalConfig.lastBackup
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "shared-file-$id")
    private val books = arrayListOf<Book>()
    private val sources = arrayListOf<BookSource>()
    private val rules = arrayListOf<HighlightRule>()
    private val replacements = arrayListOf<ReplaceRule>()
    private val dictionaryRules = arrayListOf<DictRule>()
    private val tocRules = arrayListOf<TxtTocRule>()
    private val httpTtsSources = arrayListOf<HttpTTS>()

    @Before fun setUp() {
        directory.mkdirs()
        File(directory, "books").mkdirs()
        prefs.edit().putBoolean(PreferKey.cronet, false)
            .putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.defaultBookTreeUri, File(directory, "books").path)
            .putBoolean(PreferKey.onlyLatestBackup, true).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
    }

    @Test fun savedPreviewRestoresSelectionAndPendingDirectoryInANewViewModel() {
        val files = (1..2).map { index ->
            File(directory, "restore-$index.txt").apply { writeText("Restored shared book $index $id") }
        }
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", it) }
        val repository = FileAssociationRepository(context, appDb)
        val preview = runBlocking {
            repository.resolveUris(uris) as FileAssociationResult.Preview
        }
        val selected = preview.items.last().file.uri
        val store = ViewModelStore()
        lateinit var restored: FileAssociationViewModel
        try {
            instrumentation.runOnMainSync {
                restored = FileAssociationViewModel(repository, SavedStateHandle(mapOf(
                    "localBookStaging" to preview.stagingPath,
                    "openSingleLocalBook" to false,
                    "selectedLocalBooks" to arrayListOf(selected.toString()),
                    "localBookDestination" to true,
                    "importAfterDirectorySelection" to false,
                )))
                store.put("restored-import", restored)
                assertFalse(restored.shouldDispatchInitialIntent())
            }
            await { restored.uiState.value.localBooks?.size == 2 && restored.uiState.value.folderChoice }
            assertEquals(setOf(selected), restored.uiState.value.selectedUris)
            assertEquals(listOf(selected), restored.pendingLocalBooks.map { it.file.uri })
            assertTrue(restored.uiState.value.directoryRequested)
            instrumentation.runOnMainSync { restored.selectLocalBookDirectory(null) }
            assertFalse(restored.uiState.value.directoryRequested)
            assertFalse(restored.uiState.value.folderChoice)
            assertEquals(2, restored.uiState.value.localBooks?.size)
            assertTrue(files.all(File::isFile))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            await { !File(preview.stagingPath).exists() }
        }
    }

    @Test fun partialSharedBookCopyReportsFailureAndKeepsSuccessfulBook() {
        val originals = (1..2).map { index ->
            File(directory, "partial-$index-$id.txt").apply {
                writeText("Partial import $index $id\n".repeat(20))
            }
        }
        val repository = FileAssociationRepository(context, appDb)
        val preview = runBlocking {
            repository.resolveUris(originals.map(::providerUri)) as FileAssociationResult.Preview
        }
        val selected = preview.items.map { it.file.uri }
        val failed = preview.items.first()
        val successful = preview.items.last()
        val store = ViewModelStore()
        lateinit var restored: FileAssociationViewModel
        try {
            instrumentation.runOnMainSync {
                restored = FileAssociationViewModel(repository, SavedStateHandle(mapOf(
                    "localBookStaging" to preview.stagingPath,
                    "openSingleLocalBook" to false,
                    "selectedLocalBooks" to ArrayList(selected.map(Uri::toString)),
                )))
                store.put("partial-import", restored)
            }
            await { restored.uiState.value.localBooks?.size == 2 && restored.pendingLocalBooks.size == 2 }
            assertTrue(File(checkNotNull(failed.file.uri.path)).delete())
            instrumentation.runOnMainSync { restored.confirmLocalBooks() }
            await { restored.uiState.value.finished && !restored.uiState.value.importingBooks }
            assertEquals(1 to 1, restored.uiState.value.copiedBooks)
            assertNull(restored.uiState.value.errorMessage)
            val target = File(directory, "books/${successful.name}")
            val imported = checkNotNull(appDb.bookDao.getBook(target.path))
            books.add(imported)
            assertArrayEquals(originals.last().readBytes(), target.readBytes())
            assertFalse(File(directory, "books/${failed.name}").exists())
            assertTrue(originals.all(File::isFile))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test(timeout = 120_000) fun partialSharedBookCopyShowsActualFailureCount() {
        val archive = File(directory, "partial-ui-$id.zip")
        val first = "partial-ui-failed-$id.txt"
        val second = "partial-ui-success-$id.txt"
        val content = "Partial UI success $id\n".repeat(20).toByteArray()
        writeArchive(archive, mapOf(first to "Missing staged file $id".toByteArray(), second to content))
        val messages = CopyOnWriteArrayList<String>()
        val automation = instrumentation.uiAutomation
        try {
            launchShare(archive, "application/zip").use { scenario ->
                awaitLocalPreview(scenario)
                scenario.onActivity { activity ->
                    val model = associationModel(activity)
                    assertEquals(2, model.pendingLocalBooks.size)
                    val staged = model.pendingLocalBooks.single { it.name == first }.file.uri
                    assertTrue(File(checkNotNull(staged.path)).delete())
                }
                automation.setOnAccessibilityEventListener { event ->
                    val text = (event.text.map(CharSequence::toString) +
                        listOfNotNull(event.contentDescription?.toString())).joinToString(" ")
                    if (text.isNotBlank()) messages.add(text)
                }
                composeRule.onNodeWithTag("sharedLocalBooksConfirm").assertIsDisplayed().performClick()
                val target = File(directory, "books/$second")
                await { appDb.bookDao.has(target.path) }
                books.add(checkNotNull(appDb.bookDao.getBook(target.path)))
                val expected = context.getString(R.string.shared_local_books_copied, 1, 1)
                await { messages.any { expected in it } }
                assertArrayEquals(content, target.readBytes())
                assertFalse(File(directory, "books/$first").exists())
                assertTrue(archive.isFile)
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    @Test fun sharedTextRequestReopensFromItsKeyAfterTheOriginalHostIsDestroyed() {
        val source = BookSource("https://cached-request-$id.invalid", "Cached request $id").apply {
            jsLib = "// shared draft 中文\n".repeat(2_000)
        }
        sources.add(source)
        lateinit var requestRoute: FileAssociationRoute
        val intent = Intent(Intent.ACTION_SEND).setType("application/json")
            .setComponent(FileAssociationContract.component(context))
            .putExtra(Intent.EXTRA_TEXT, GSON.toJson(listOf(source)))
        ActivityScenario.launch<AppActivity>(intent).use { scenario ->
            awaitComposeTag("sourceImportRow:0")
            scenario.onActivity { activity ->
                requestRoute = activity.initialDestination as FileAssociationRoute
                assertTrue(activity.currentDestination is BookSourceImportRoute)
                activity.intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            }
            scenario.recreate()
            awaitComposeTag("sourceImportRow:0")
            composeRule.onNodeWithText(source.bookSourceName).assertIsDisplayed()
            composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, requestRoute)).use { scenario ->
            awaitComposeTag("sourceImportRow:0")
            scenario.onActivity { assertFalse(it.intent.hasExtra(Intent.EXTRA_TEXT)) }
            composeRule.onNodeWithText(source.bookSourceName).assertIsDisplayed()
            composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertFalse(appDb.bookSourceDao.all.any { it.bookSourceUrl == source.bookSourceUrl })
    }

    @Test fun textSearchAliasRemainsSeparateFromTheFileImportAlias() {
        val query = "Shared search $id"
        val aliases = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName), 0,
        ).map { it.activityInfo.name }
        assertTrue(FileAssociationContract.ACTIVITY_ALIAS in aliases)
        val searchAlias = "io.legado.app.receiver.SharedReceiverActivity"
        assertTrue(searchAlias in aliases)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .setComponent(ComponentName(context, searchAlias)).putExtra(Intent.EXTRA_TEXT, query)
        ActivityScenario.launch<AppActivity>(intent).use { scenario ->
            await {
                var ready = false
                scenario.onActivity { activity ->
                    ready = (activity.currentDestination as? SearchRoute)?.query == query
                }
                ready
            }
            composeRule.onNodeWithTag("search_query").assertExists()
        }

    }

    @Test fun historyPayloadRequiresTheSavedRequestKeyWhileFreshImportsRemainAccepted() {
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val intent = Intent(action, Uri.parse("content://owned-history/book.txt"))
                .setComponent(FileAssociationContract.component(context))
            assertTrue(FileAssociationContract.accepts(intent))
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            assertFalse(FileAssociationContract.accepts(intent))
            assertTrue(FileAssociationContract.accepts(intent, restoringRequest = true))
        }
        val search = Intent(Intent.ACTION_SEND).setType("text/plain")
            .setComponent(ComponentName(context, "io.legado.app.receiver.SharedReceiverActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertFalse(FileAssociationContract.accepts(search, restoringRequest = true))
    }

    @Test fun historyViewWithoutTheSavedRequestDoesNotRepeatAnImportedBook() {
        val source = File(directory, "history-$id.txt").apply { writeText("Historical file payload $id") }
        val copied = File(directory, "books/${source.name}").apply { writeBytes(source.readBytes()) }
        val existing = Book(bookUrl = copied.absolutePath, name = source.nameWithoutExtension,
            originName = copied.name, charset = "UTF-8", type = io.legado.app.constant.BookType.local or io.legado.app.constant.BookType.text)
        appDb.bookDao.insert(existing)
        books.add(existing)
        val beforeUrls = appDb.bookDao.allBookUrls.toSet()
        val before = GSON.toJson(appDb.bookDao.getBook(existing.bookUrl))
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(providerUri(source), "text/plain")
            .setComponent(FileAssociationContract.component(context))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        try {
            ActivityScenario.launch<AppActivity>(intent).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        assertTrue(activity.intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0)
                        assertNull(activity.initialDestination)
                        ready = activity.currentDestination == BookshelfRoute
                    }
                    ready
                }
                scenario.recreate()
                scenario.onActivity { assertNull(it.initialDestination) }
                assertEquals(beforeUrls, appDb.bookDao.allBookUrls.toSet())
                assertEquals(before, GSON.toJson(appDb.bookDao.getBook(existing.bookUrl)))
                assertArrayEquals(source.readBytes(), copied.readBytes())
                assertEquals(listOf(copied.name), File(directory, "books").listFiles()!!.map { it.name })
            }
        } finally {
            appDb.bookDao.all.filter { it.bookUrl.startsWith(directory.absolutePath + "/") }
                .filterNot { candidate -> books.any { it.bookUrl == candidate.bookUrl } }.forEach(books::add)
        }
    }

    private val highlightImports=HighlightImportArtifacts()

    @After fun tearDown() {
        closeReaders(); highlightImports.cleanup()
        rules.forEach { rule -> appDb.highlightRuleDao.all.find { it.uuid == rule.uuid }
            ?.let { appDb.highlightRuleDao.delete(it) } }
        replacements.forEach { appDb.replaceRuleDao.delete(it) }
        dictionaryRules.forEach { rule -> appDb.dictRuleDao.getByName(rule.name)?.let(appDb.dictRuleDao::delete) }
        tocRules.forEach { rule -> appDb.txtTocRuleDao.get(rule.id)?.let(appDb.txtTocRuleDao::delete) }
        httpTtsSources.forEach { source -> appDb.httpTTSDao.get(source.id)?.let(appDb.httpTTSDao::delete) }
        books.forEach { book ->
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            BookHelp.clearCache(book)
        }
        sources.forEach { appDb.bookSourceDao.delete(it) }
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        prefs.edit().clear().apply {
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
                }
            }
        }.commit()
        LocalConfig.edit().apply {
            savedLocal.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.commit()
        LocalConfig.lastBackup = savedBackupTime
        directory.deleteRecursively()
    }

    @Test(timeout = 120_000) fun sharedBookListConfirmsThenUsesEnabledSourceSearchAndPersistsTheMatchedBook() {
        val name = "Shared book $id"
        val author = "Shared author"
        val missing = "Missing book $id"
        val mixed = AtomicBoolean()
        val requests = AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (mixed.get()) {
                    if (session.parameters["key"]?.firstOrNull() == missing) return newFixedLengthResponse("<html></html>")
                    Thread.sleep(600)
                }
                return newFixedLengthResponse("<article><h2>$name</h2><span class='author'>$author</span>" +
                    "<a href='/book/$id'>Details</a></article>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source = BookSource("http://127.0.0.1:${server.listeningPort}", "Share fixture",
            customOrder = Int.MIN_VALUE, searchUrl = "/search?key={{key}}",
            ruleSearch = SearchRule(bookList = "article", name = "h2@text", author = ".author@text", bookUrl = "a@href"))
        sources.add(source)
        appDb.bookSourceDao.insert(source)
        val file = File(directory, "renamed-list.json").apply {
            writeText(GSON.toJson(listOf(mapOf("name" to name, "author" to author, "intro" to "intro"))))
        }
        try {
            launchShare(file, "application/json").use { scenario ->
                awaitComposeTag("sharedImportDataDialog")
                composeRule.onNodeWithTag("sharedImportDataConfirm").assertIsDisplayed()
                composeRule.onNodeWithTag("sharedImportDataCancel").assertIsDisplayed()
                assertFalse(appDb.bookDao.has(name, author))
                assertEquals(0, requests.get())
                scenario.recreate()
                awaitComposeTag("sharedImportDataDialog")
                screenshot("share-bookshelf-confirmation")
                lateinit var model: FileAssociationViewModel
                scenario.onActivity { model = associationModel(it) }
                composeRule.onNodeWithTag("sharedImportDataConfirm").performClick()
                await { model.uiState.value.finished == true }
                val book = checkNotNull(appDb.bookDao.getBook(name, author))
                books.add(book)
                assertEquals("${source.bookSourceUrl}/book/$id", book.bookUrl)
                assertEquals(source.bookSourceUrl, book.origin)
                assertTrue(requests.get() > 0)
            }
            val savedEnabled = appDb.bookSourceDao.allEnabled.filter { it.bookSourceUrl != source.bookSourceUrl }
            savedEnabled.forEach { appDb.bookSourceDao.enable(it.bookSourceUrl, false) }
            try {
                appDb.bookDao.delete(books.single())
                mixed.set(true)
                requests.set(0)
                file.writeText(GSON.toJson(listOf(mapOf("name" to missing, "author" to author),
                    mapOf("name" to name, "author" to author))))
                launchShare(file, "application/json").use { scenario ->
                    awaitComposeTag("sharedImportDataDialog")
                    composeRule.onNodeWithTag("sharedImportDataConfirm").performClick()
                    awaitComposeTag("sharedImportErrorDialog")
                    composeRule.onNodeWithTag("sharedImportErrorConfirm").performClick()
                    await { appDb.bookDao.has(name, author) && AppLog.logs.any { it.second.contains(missing) } }
                    assertFalse(appDb.bookDao.has(missing, author))
                    assertTrue(requests.get() >= 2)
                    assertEquals(source.bookSourceUrl, appDb.bookDao.getBook(name, author)!!.origin)
                }
            } finally { savedEnabled.forEach { appDb.bookSourceDao.enable(it.bookSourceUrl, true) } }
        } finally { server.stop() }
    }

    @Test(timeout = 120_000) fun rawAndTypedHighlightFilesUseTheHighlightPreviewWhileReplacementFilesKeepTheirRoute() {
        for (typed in listOf(false, true)) {
            val rule = HighlightRule(name = "Shared highlight $typed", pattern = "target-$id-$typed",
                style = """{"bold":true,"textColor":123456,"fill":123456,"fillShape":"PILL","pillPaddingScale":1.5}""", group = "Share group",
                applyToTitle = true, applyToBody = false, isEnabled = false)
            rules.add(rule)
            val file = File(directory, "unrelated-$typed.json").apply {
                writeText(GSON.toJson(if (typed) HighlightRuleFile(HighlightRuleFile.TYPE, listOf(rule)) else listOf(rule)))
            }
            launchShare(file, "application/octet-stream").use { scenario ->
                awaitHighlightRuleImport()
                composeRule.onNodeWithText(rule.name).assertIsDisplayed()
                assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
                screenshot("share-highlight-$typed")
                composeRule.onNodeWithTag("highlightRuleImportConfirm").performClick()
                await { appDb.highlightRuleDao.all.any { it.uuid == rule.uuid } }
                val saved = appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }
                assertEquals(rule.styleObj(), saved.styleObj())
                assertEquals(rule.pattern, saved.pattern)
                assertEquals(rule.group, saved.group)
                assertEquals(rule.applyToBody, saved.applyToBody)
                assertFalse(saved.isEnabled)
            }
        }
        val rule = ReplaceRule(id = System.currentTimeMillis(), name = "Shared replacement", pattern = id, replacement = "changed")
        replacements.add(rule)
        val file = File(directory, "misleading-highlight-name.json").apply { writeText(GSON.toJson(listOf(rule))) }
        launchShare(file, "application/json").use { scenario ->
            await {
                composeRule.onAllNodesWithTag("replaceRuleImportDialog")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            awaitComposeTag("replaceRuleImportDialog")
            composeRule.onNodeWithText(rule.name).assertIsDisplayed()
            composeRule.onNodeWithTag("replaceRuleImportConfirm").performClick()
            await { appDb.replaceRuleDao.all.any { it.id == rule.id } }
            assertEquals("changed", appDb.replaceRuleDao.all.single { it.id == rule.id }.replacement)
            assertFalse(appDb.highlightRuleDao.all.any { it.pattern == id })
        }
    }

    @Test(timeout = 120_000) fun sharedReplacementRuleFileOpensNavigationImportDestination() {
        val rule = ReplaceRule(
            id = System.currentTimeMillis(),
            name = "Routed replacement $id",
            pattern = "routed-$id",
            replacement = "destination",
        )
        replacements.add(rule)
        val file = File(directory, "replacement-route-$id.json").apply {
            writeText(GSON.toJson(listOf(rule)))
        }
        launchShare(file, "application/json").use {
            await {
                composeRule.onAllNodesWithTag("replaceRuleImportDialog")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            awaitComposeTag("replaceRuleImportDialog")
            composeRule.onNodeWithText(rule.name).assertIsDisplayed()
            screenshot("share-replacement-route-preview")
            composeRule.onNodeWithTag("replaceRuleImportConfirm").performClick()
            await { appDb.replaceRuleDao.all.any { imported -> imported.id == rule.id } }
            assertEquals("destination", appDb.replaceRuleDao.all.single { it.id == rule.id }.replacement)
        }
    }

    @Test(timeout = 120_000) fun sharedDictionaryAndTextTocFilesUseNavigationImportDestinations() {
        val dictRule = DictRule(
            name = "Routed dictionary $id",
            urlRule = "https://example.invalid/?word={{key}}",
            showRule = "$.word",
        )
        dictionaryRules.add(dictRule)
        val dictFile = File(directory, "dictionary-route-$id.json").apply {
            writeText(GSON.toJson(listOf(dictRule)))
        }
        launchShare(dictFile, "application/json").use {
            awaitComposeTag("dictionaryRuleImportDialog")
            composeRule.onNodeWithText(dictRule.name).assertIsDisplayed()
            composeRule.onNodeWithTag("dictionaryRuleImportConfirm").performClick()
            await { appDb.dictRuleDao.getByName(dictRule.name) != null }
        }

        val tocRule = TxtTocRule(
            id = System.currentTimeMillis(),
            name = "Routed TOC rule $id",
            rule = "第\\s*\\d+章",
        )
        tocRules.add(tocRule)
        val tocFile = File(directory, "toc-route-$id.json").apply {
            writeText(GSON.toJson(listOf(tocRule)))
        }
        launchShare(tocFile, "application/json").use {
            awaitComposeTag("textTocRuleImportDialog")
            composeRule.onNodeWithText(tocRule.name).assertIsDisplayed()
            composeRule.onNodeWithTag("textTocRuleImportConfirm").performClick()
            await { appDb.txtTocRuleDao.get(tocRule.id) != null }
        }
    }

    @Test(timeout = 120_000) fun sharedHttpTtsFileUsesNavigationImportDestination() {
        val source = HttpTTS(
            id = System.currentTimeMillis(),
            name = "Routed TTS $id",
            url = "https://example.invalid/tts?text={{speakText}}",
        )
        httpTtsSources.add(source)
        val file = File(directory, "tts-route-$id.json").apply {
            writeText(GSON.toJson(listOf(source)))
        }
        launchShare(file, "application/json").use {
            awaitComposeTag("httpTtsImportDialog")
            composeRule.onNodeWithTag("httpTtsImportItem:0").assertIsDisplayed()
            composeRule.onNodeWithText(source.name).assertIsDisplayed()
            composeRule.onNodeWithTag("httpTtsImportConfirm").performClick()
            await { appDb.httpTTSDao.get(source.id) != null }
        }
    }

    @Test(timeout = 120_000) fun bookSourceImportOpensFromTypedNavigationDestination() {
        ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookSourceImportRoute("invalid book source"))
        ).use { scenario ->
            awaitComposeTag("sourceImportFooter:cancel")
            composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }

    @Test(timeout = 120_000) fun onlineBookSourceLinkUsesTypedNavigationDestination() {
        val source = BookSource(
            "https://online-source-$id.invalid",
            "Online source route $id",
        )
        sources.add(source)
        val responseBody = GSON.toJson(listOf(source))
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                newFixedLengthResponse(Response.Status.OK, "application/json", responseBody)
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val sourceUrl = "http://127.0.0.1:${server.listeningPort}/source.json"
        val importLink = Uri.Builder()
            .scheme("legado")
            .authority("import")
            .appendPath("bookSource")
            .appendQueryParameter("src", sourceUrl)
            .build()
        try {
            ActivityScenario.launch<AppActivity>(
                AppActivity.startIntent(context, OnlineImportRoute(importLink.toString()))
            ).use {
                awaitComposeTag("sourceImportRow:0")
                composeRule.onNodeWithText(source.bookSourceName).assertIsDisplayed()
                composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
            }
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 120_000) fun sharedBookSourceUsesTypedNavigationDestination() {
        val source = BookSource(
            "https://shared-source-$id.invalid",
            "Shared source route $id",
        )
        sources.add(source)
        val file = File(directory, "source-route-$id.json").apply {
            writeText(GSON.toJson(listOf(source)))
        }
        launchShare(file, "application/json").use {
            awaitComposeTag("sourceImportRow:0")
            composeRule.onNodeWithText(source.bookSourceName).assertIsDisplayed()
            composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
        }
    }

    @Test(timeout = 120_000) fun addToBookshelfLinkUsesComposeDestination() = runBlocking {
        val book = Book(
            bookUrl = "https://existing-$id.invalid/book",
            name = "Existing link book $id",
            author = "Route author",
        )
        books.add(book)
        appDb.bookDao.insert(book)
        val resolved = AddToBookshelfRepository(appDb).resolve(book.bookUrl)
        assertTrue(resolved.alreadyInBookshelf)
        assertEquals(book.bookUrl, resolved.book.bookUrl)

        ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, AddToBookshelfRoute("invalid-book-url"))
        ).use {
            await {
                composeRule.onAllNodesWithTag("addToBookshelfClose")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("addToBookshelfClose").assertIsDisplayed()
            composeRule.onNodeWithTag("addToBookshelfClose").performClick()
        }
        Unit
    }

    @Test(timeout = 120_000) fun actualBackupZipConfirmsBeforeRestoringBooksSourcesRulesAndSettings() = runBlocking {
        val source = BookSource("https://shared-backup-$id.invalid", "Backup source")
        val book = Book(bookUrl = "${source.bookSourceUrl}/book", origin = source.bookSourceUrl,
            name = "Backup book $id", author = "Author", durChapterIndex = 7)
        val rule = HighlightRule(name = "Backup rule $id", pattern = id,
            style = """{"bold":true,"fill":123456,"fillShape":"PILL","pillPaddingScale":1.75}""")
        sources.add(source); books.add(book); rules.add(rule)
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.highlightRuleDao.insert(rule)
        val marker = "shared-backup-$id"
        prefs.edit().putString(marker, "restored value").commit()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        listOf(BackupConfig.bookshelfContentKey, BackupConfig.annotationContentKey,
            BackupConfig.sourceContentKey, BackupConfig.settingContentKey)
            .forEach { BackupConfig.ignoreConfig[it] = false }
        val output = File(directory, "backup").apply { mkdirs() }
        Backup.backupLocked(context, output.path, uploadWebDav = false)
        val archive = output.listFiles()!!.single { it.extension == "zip" }
        ZipFile(archive).use { zip ->
            listOf("bookshelf.json", "bookSource.json", "highlightRule.json", "config.xml")
                .forEach { assertNotNull(it, zip.getEntry(it)) }
        }
        val renamed = File(directory, "any-name.zip")
        archive.copyTo(renamed)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.highlightRuleDao.all.find { it.uuid == rule.uuid }!!.let { appDb.highlightRuleDao.delete(it) }
        prefs.edit().remove(marker).commit()
        launchShare(renamed, "application/zip").use { scenario ->
            awaitComposeTag("sharedImportDataDialog")
            composeRule.onNodeWithTag("sharedImportDataConfirm").assertIsDisplayed()
            composeRule.onNodeWithTag("sharedImportDataCancel").assertIsDisplayed()
            assertFalse(appDb.bookDao.has(book.bookUrl))
            screenshot("share-backup-confirmation")
            composeRule.onNodeWithTag("sharedImportDataCancel").performClick()
        }
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertFalse(prefs.contains(marker))
        launchShare(renamed, "application/zip").use { scenario ->
            awaitComposeTag("sharedImportDataDialog")
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = associationModel(it) }
            composeRule.onNodeWithTag("sharedImportDataConfirm").performClick()
            await { model.uiState.value.finished == true }
            assertEquals("restored value", prefs.getString(marker, null))
            assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceName)
            assertEquals(rule.styleObj(), appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }.styleObj())
        }
    }

    @Test(timeout = 120_000) fun sharedTxtEpubPdfAndBookZipCopyDurablyAndOpenTheRealReader() {
        val txt = File(directory, "shared-txt-$id.txt").apply { writeText("Chapter one\n" + "SHARED_TEXT_VISIBLE $id\n".repeat(20)) }
        val epub = File(directory, "shared-epub-$id.epub").apply {
            instrumentation.context.assets.open("issue1074-containers-fragments.epub").use { input -> outputStream().use(input::copyTo) }
        }
        val pdf = File(directory, "shared-pdf-$id.pdf").apply {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 100, 170) }
                page.canvas.drawRect(24f, 24f, 576f, 120f, paint)
                paint.color = Color.WHITE
                paint.textSize = 32f
                page.canvas.drawText("SHARED PDF VISIBLE", 40f, 85f, paint)
                document.finishPage(page)
                outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        listOf(txt to "text/plain", epub to "application/epub+zip", pdf to "application/pdf").forEach { (file, mime) ->
            val expected = file.readBytes()
            launchShare(file, mime).use { scenario ->
                confirmLocalPreview(scenario)
                val copied = File(directory, "books/${file.name}")
                await { copied.isFile && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertArrayEquals(expected, copied.readBytes())
                assertTrue(file.delete())
                openReader(book)
                awaitReader(book)
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                assertTrue(chapters.isNotEmpty())
                assertFalse(LocalBook.getContent(book, chapters.first()).isNullOrBlank())
                if (file.extension == "pdf") await { pdfMarkerIsVisible() }
                screenshot("share-reader-${copied.extension}")
                closeReaders()
            }
        }
        val archive = File(directory, "book-container.zip")
        val chapter = "Ordinary archive text $id"
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("archive-$id.txt")); zip.write(chapter.toByteArray()); zip.closeEntry()
        }
        launchShare(archive, "application/zip").use { scenario ->
            confirmLocalPreview(scenario)
            val path = File(directory, "books/archive-$id.txt").path
            await { appDb.bookDao.has(path) }
            val book = appDb.bookDao.getBook(path)!!
            openReader(book)
            books.add(book)
            awaitReader(book)
            val renderedPages = ReadBook.curTextChapter!!.pages.map { it.text }
            screenshot("share-reader-zip")
            assertEquals(chapter, File(path).readText())
            assertTrue("Expected archive text <$chapter>; rendered pages: $renderedPages",
                renderedPages.any { it.contains(chapter) })
            closeReaders()
        }
    }

    @Test(timeout = 120_000) fun firstSharedBookRetainsItsSelectedBatchAcrossRecreationAndTheFolderResult() {
        prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
        val file = File(directory, "first-share-$id.txt").apply { writeText("FIRST_SHARED_STREAM $id\n".repeat(15)) }
        val folderRequests = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                folderRequests.incrementAndGet()
                val activity = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED)
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                    .filterIsInstance<AppActivity>().single()
                val model = associationModel(activity)
                assertEquals(1, model.pendingLocalBooks.size)
                assertTrue(model.choosingLocalBookDirectory)
                assertTrue(!model.uiState.value.localBooks.isNullOrEmpty())
                return Instrumentation.ActivityResult(Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(File(directory, "books"))))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launchShare(file, "text/plain").use { scenario ->
                awaitLocalPreview(scenario)
                assertEquals(0, folderRequests.get())
                scenario.recreate()
                scenario.onActivity { activity ->
                    val model = associationModel(activity)
                    assertFalse(model.uiState.value.localBooks.isNullOrEmpty())
                }
                awaitLocalPreview(scenario)
                confirmLocalPreview(scenario)
                awaitComposeTag("sharedLocalBookFolderDialog")
                screenshot("share-local-folder-after-confirmation")
                composeRule.onNodeWithTag("sharedLocalBookFolderSelect").performClick()
                val copied = File(directory, "books/${file.name}")
                await { copied.exists() && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertEquals(1, folderRequests.get())
                assertEquals(file.readText(), copied.readText())
                openReader(book)
                awaitReader(book)
                assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains("FIRST_SHARED_STREAM") })
                closeReaders()
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test(timeout = 120_000) fun saveFolderMenusUpdateTheSharedSettingWithoutMovingExistingBooks() {
        AppConfig.importBookPath = directory.path
        val oldFile = File(directory, "books/old-menu-$id.txt").apply { writeText("ORIGINAL MENU BOOK") }
        val oldBook = LocalBook.importFile(Uri.fromFile(oldFile)).also(books::add)
        val newDirectory = File(directory, "changed").apply { mkdirs() }
        val finalDirectory = File(directory, "preview-changed").apply { mkdirs() }
        var destination = newDirectory
        val requests = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                requests.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, ImportLocalBookRoute)).use {
                composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
                composeRule.onNodeWithTag("legadoTopAppBarMenuAction_2").performClick()
                awaitComposeTag("localBookSaveFolderDialog")
                screenshot("local-import-save-folder-menu")
                composeRule.onNodeWithTag("localBookSaveFolderSelect").performClick()
                await { AppConfig.defaultBookTreeUri == Uri.fromFile(newDirectory).toString() }
            }
            destination = finalDirectory
            val source = File(directory, "menu-share-$id.txt").apply { writeText("MENU SHARED BOOK") }
            launchShare(source, "text/plain").use { scenario ->
                awaitLocalPreview(scenario)
                composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
                composeRule.onNodeWithTag("legadoTopAppBarMenuAction_0").performClick()
                awaitComposeTag("sharedLocalBookFolderDialog")
                screenshot("share-local-preview-save-folder-menu")
                composeRule.onNodeWithTag("sharedLocalBookFolderSelect").performClick()
                await { AppConfig.defaultBookTreeUri == Uri.fromFile(finalDirectory).toString() }
                assertTrue(finalDirectory.listFiles()!!.isEmpty())
                confirmLocalPreview(scenario)
                val copy = File(finalDirectory, source.name)
                await { appDb.bookDao.has(copy.path) }
                books.add(appDb.bookDao.getBook(copy.path)!!)
                assertArrayEquals(source.readBytes(), copy.readBytes())
            }
            assertEquals(2, requests.get())
            assertEquals("ORIGINAL MENU BOOK", oldFile.readText())
            assertEquals(oldBook, appDb.bookDao.getBook(oldBook.bookUrl))
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test(timeout = 120_000) fun sharedPreviewSaveFolderMenuUsesComposeAndPersistsSelection() {
        val destination = File(directory, "compose-save-folder").apply { mkdirs() }
        val source = File(directory, "compose-menu-share-$id.txt").apply { writeText("COMPOSE MENU BOOK") }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launchShare(source, "text/plain").use { scenario ->
                awaitLocalPreview(scenario)
                scenario.onActivity {
                    associationModel(it)
                        .requestLocalBookDirectory(false)
                }
                awaitComposeTag("sharedLocalBookFolderDialog")
                composeRule.onNodeWithTag("sharedLocalBookFolderSelect").performClick()
                await { AppConfig.defaultBookTreeUri == Uri.fromFile(destination).toString() }
                assertTrue(destination.listFiles()!!.isEmpty())
                confirmLocalPreview(scenario)
                val copy = File(destination, source.name)
                await { appDb.bookDao.has(copy.path) }
                books.add(appDb.bookDao.getBook(copy.path)!!)
                assertArrayEquals(source.readBytes(), copy.readBytes())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 120_000) fun baseDialogKeepsItsTagForQueuedDuplicateRequestsAndRecreation() {
        val file = File(directory, "dialog-tag-$id.txt").apply { writeText("DIALOG TEST") }
        launchShare(file, "text/plain").use { scenario ->
            awaitLocalPreview(scenario)
            scenario.onActivity { activity ->
                val manager = activity.supportFragmentManager
                val dialog = SharedImportTagTestDialog()
                dialog.show(manager, "retainedTag")
                dialog.show(manager, "retainedTag")
                manager.executePendingTransactions()
                assertSame(dialog, manager.findFragmentByTag("retainedTag"))
                assertEquals(1, manager.fragments.filterIsInstance<SharedImportTagTestDialog>().size)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val manager = activity.supportFragmentManager
                val dialog = manager.findFragmentByTag("retainedTag") as SharedImportTagTestDialog
                dialog.show(manager, "retainedTag")
                manager.executePendingTransactions()
                assertEquals(1, manager.fragments.filterIsInstance<SharedImportTagTestDialog>().size)
                dialog.dismissNow()
                dialog.show(manager, "retainedTag")
                manager.executePendingTransactions()
                assertSame(dialog, manager.findFragmentByTag("retainedTag"))
                dialog.dismissNow()
            }
        }
    }

    @Test(timeout = 120_000) fun archivePreviewsEverySupportedTypeAndOnlyImportsTheSelection() {
        val pdfFile = File(directory, "batch-$id.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            pdfFile.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val contents = linkedMapOf(
            "alpha-$id.txt" to "ALPHA $id".toByteArray(),
            "omit-$id.pdf" to pdfFile.readBytes(),
            "nested/gamma-$id.TXT" to "GAMMA $id".toByteArray(),
            "batch-$id.epub" to instrumentation.context.assets.open("issue1074-containers-fragments.epub").use { it.readBytes() },
            pdfFile.name to pdfFile.readBytes(),
            "notes.json" to "{}".toByteArray(), "picture.jpg" to byteArrayOf(1, 2, 3))
        val archive = File(directory, "five-books-$id.zip")
        writeArchive(archive, contents)
        val original = archive.readBytes()
        val previousBook = ReadBook.book?.bookUrl
        lateinit var previewCover: File
        lateinit var omittedCover: File
        lateinit var coverBytes: ByteArray
        lateinit var acceptedCover: File
        launchShare(archive, "application/zip").use { scenario ->
            awaitLocalPreview(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = associationModel(it) }
            val items = model.uiState.value.localBooks!!
            assertEquals(5, items.size)
            assertEquals(setOf("txt", "epub", "pdf"), items.map { it.file.name.substringAfterLast('.').lowercase() }.toSet())
            assertEquals(previousBook, ReadBook.book?.bookUrl)
            items.forEach { assertFalse(appDb.bookDao.has(it.preview!!.name, it.preview.author)) }
            val omitted = items.indexOfFirst { it.file.name.startsWith("omit-") }
            previewCover = File(checkNotNull(items.single { it.file.name == pdfFile.name }.preview!!.coverUrl))
            omittedCover = File(checkNotNull(items[omitted].preview!!.coverUrl))
            assertTrue(previewCover.length() > 0)
            assertTrue(omittedCover.length() > 0)
            coverBytes = previewCover.readBytes()
            val omittedBook = items[omitted].preview!!
            val label = if (omittedBook.author.isBlank()) omittedBook.name
                else "${omittedBook.name} / ${omittedBook.author}"
            composeRule.onNodeWithText(label).performClick()
            assertEquals(4, model.selectedLocalBooks.size)
            scenario.recreate()
            awaitLocalPreview(scenario)
            scenario.onActivity { activity ->
                val restoredModel = associationModel(activity)
                assertEquals(4, restoredModel.selectedLocalBooks.size)
            }
            assertEquals(4, model.selectedLocalBooks.size)
            screenshot("share-local-archive-selection")
            val selected = items.filter { it.file.uri in model.selectedLocalBooks }
            confirmLocalPreview(scenario)
            await { model.uiState.value.finished == true }
            assertEquals(previousBook, ReadBook.book?.bookUrl)
            selected.forEach { item ->
                val book = appDb.bookDao.getBook(File(directory, "books/${item.file.name}").path)!!
                books.add(book)
                assertEquals(item.preview!!.name, book.name)
                assertArrayEquals(contents.entries.single { File(it.key).name == item.file.name }.value,
                    File(book.bookUrl).readBytes())
                if (item.file.name == pdfFile.name) {
                    assertEquals(LocalBook.getCoverPath(book), book.coverUrl)
                    acceptedCover = File(book.coverUrl!!)
                    assertNotEquals(previewCover.path, acceptedCover.path)
                }
            }
            assertFalse(appDb.bookDao.has(items[omitted].preview!!.name, items[omitted].preview!!.author))
            assertFalse(File(directory, "books/${items[omitted].file.name}").exists())
            assertArrayEquals(original, archive.readBytes())
            assertEquals(File(directory, "books").path, AppConfig.defaultBookTreeUri)
        }
        await { !previewCover.exists() && !omittedCover.exists() }
        assertArrayEquals(coverBytes, acceptedCover.readBytes())
        lateinit var cancelledCover: File
        launchShare(pdfFile, "application/pdf").use { scenario ->
            awaitLocalPreview(scenario)
            scenario.onActivity {
                val preview = associationModel(it)
                    .uiState.value.localBooks!!.single().preview!!
                cancelledCover = File(preview.coverUrl!!)
                assertTrue(cancelledCover.length() > 0)
                assertFalse(appDb.bookDao.has(preview.name, preview.author))
            }
            composeRule.onNodeWithTag("sharedLocalBooksCancel").performClick()
        }
        await { !cancelledCover.exists() }
        assertArrayEquals(coverBytes, acceptedCover.readBytes())
        assertArrayEquals(original, archive.readBytes())
    }

    @Test(timeout = 120_000) fun multipleSharesKeepAllThreeIdentityConflictsAndOriginalFiles() {
        AppConfig.bookImportFileName = "name='Shared identity $id';author='Shared author';"
        val existingFile = File(directory, "books/one.txt").apply { writeText("EXISTING BOOK") }
        val existing = LocalBook.importFile(Uri.fromFile(existingFile)).also(books::add)
        val missingOriginal = Book(bookUrl = File(directory, "books/two.txt").path,
            originName = "two.txt", name = "Missing original $id", author = "Preserved author")
        appDb.bookDao.insert(missingOriginal)
        books.add(missingOriginal)
        val originals = listOf("one.txt", "two.txt", "three.txt").mapIndexed { index, name ->
            File(directory, name).apply { writeText("SHARED COPY $index $id") }
        }
        val unsupported = File(directory, "ignore.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val uris = (originals + unsupported).map(::providerUri)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").setPackage(context.packageName)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("books", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        assertTrue(context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == FileAssociationContract.ACTIVITY_ALIAS })
        intent.component = FileAssociationContract.component(context)
        ActivityScenario.launch<AppActivity>(intent).use { scenario ->
            awaitLocalPreview(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = associationModel(it) }
            val preview = model.uiState.value.localBooks!!.map { it.preview!! }
            assertEquals(listOf(2, 3, 4).map { "${existing.name} ($it)" }, preview.map { it.name })
            assertEquals(3, preview.size)
            preview.forEach { assertFalse(appDb.bookDao.has(it.name, it.author)) }
            screenshot("share-local-multiple-conflicts")
            confirmLocalPreview(scenario)
            await { model.uiState.value.finished == true }
            preview.forEachIndexed { index, item ->
                val copy = appDb.bookDao.getBook(item.name, item.author)!!
                books.add(copy)
                assertNotEquals(existing.bookUrl, copy.bookUrl)
                assertArrayEquals(originals[index].readBytes(), File(copy.bookUrl).readBytes())
                assertTrue(originals[index].isFile)
            }
            assertEquals("EXISTING BOOK", existingFile.readText())
            assertEquals(existing, appDb.bookDao.getBook(existing.bookUrl))
            assertEquals(missingOriginal.name, appDb.bookDao.getBook(missingOriginal.bookUrl)!!.name)
            assertFalse(File(missingOriginal.bookUrl).exists())
            assertFalse(File(directory, "books/ignore.png").exists())
        }
    }

    @Test(timeout = 120_000) fun archiveRejectsBooksMixedWithRecognizedRulesBeforeWritingEitherType() {
        val rule = HighlightRule(name = "Mixed archive $id", pattern = id, style = "{\"bold\":true}")
        val archive = File(directory, "mixed-types-$id.zip")
        writeArchive(archive, linkedMapOf("book-$id.txt" to "BOOK $id".toByteArray(),
            "highlightRule.json" to GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE, listOf(rule))).toByteArray()))
        val original = archive.readBytes()
        launchShare(archive, "application/zip").use { scenario ->
            awaitComposeTag("sharedMixedTypesDialog")
            scenario.onActivity { activity ->
                val model = associationModel(activity)
                assertNull(model.uiState.value.localBooks)
            }
            assertTrue(File(directory, "books").listFiles()!!.isEmpty())
            assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
            screenshot("share-local-mixed-types-rejected")
            composeRule.onNodeWithTag("sharedMixedTypesConfirm").performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertArrayEquals(original, archive.readBytes())
        }
    }

    @Test(timeout = 120_000) fun archiveCombinesSameCategoryJsonIntoItsExistingSelectionPreview() {
        val imported = (1..3).map { index ->
            HighlightRule(name = "Archive highlight $index $id", pattern = "$index-$id", style = "{\"bold\":true}")
        }
        rules.addAll(imported)
        val archive = File(directory, "rules-$id.zip")
        writeArchive(archive, linkedMapOf("unrelated.json" to GSON.toJson(imported.take(2)).toByteArray(),
            "typed.json" to GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE, imported.drop(2))).toByteArray(),
            "ignored.json" to "{}".toByteArray(), "picture.jpg" to byteArrayOf(1, 2)))
        val original = archive.readBytes()
        launchShare(archive, "application/zip").use { scenario ->
            awaitHighlightRuleImport()
            await {
                imported.all { rule ->
                    composeRule.onAllNodesWithText(rule.name).fetchSemanticsNodes().isNotEmpty()
                }
            }
            imported.forEach { rule ->
                composeRule.onNodeWithText(rule.name).assertIsDisplayed()
                assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
            }
            screenshot("share-local-archive-json-preview")
            composeRule.onNodeWithTag("highlightRuleImportConfirm").performClick()
            await { imported.all { rule -> appDb.highlightRuleDao.all.any { it.uuid == rule.uuid } } }
            imported.forEach { rule ->
                assertEquals(rule.styleObj(), appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }.styleObj())
            }
            assertArrayEquals(original, archive.readBytes())
        }
    }

    @Test(timeout = 120_000) fun unsupportedArchiveReportsTheFormatAndEndsInsteadOfLoadingForever() {
        val archive = File(directory, "no-books-$id.zip")
        writeArchive(archive, linkedMapOf("readme.md" to "No books".toByteArray(), "data.bin" to byteArrayOf(1)))
        launchShare(archive, "application/zip").use { scenario ->
            lateinit var model: FileAssociationViewModel
            scenario.onActivity { model = associationModel(it) }
            await {
                composeRule.onAllNodesWithTag("sharedImportErrorDialog").fetchSemanticsNodes().isNotEmpty()
            }
            awaitComposeTag("sharedImportErrorDialog")
            assertNull(model.uiState.value.localBooks)
            assertTrue(archive.isFile)
            screenshot("share-local-unsupported-archive")
            composeRule.onNodeWithTag("sharedImportErrorConfirm").performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }

    @Test(timeout = 120_000) fun cancelledPathReturnsToPreviewAndExplicitDefaultIsSharedWithOpenWith() {
        val privateDirectory = File(context.filesDir, "books")
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.component?.className == HandleFileActivity::class.java.name)
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null
        }
        instrumentation.addMonitor(monitor)
        try {
            for (firstOpen in listOf(false, true)) for (choice in listOf("dismiss", "picker")) {
                prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
                // Exercise both directions: SHARE remembers the default for VIEW and vice versa.
                for (round in 0..1) {
                    val open = if (round == 0) firstOpen else !firstOpen
                    val file = File(directory, "private-$firstOpen-$choice-$round-$id.txt").apply {
                        writeText("PRIVATE COPY $choice $id\n".repeat(20))
                    }
                    val copy = File(privateDirectory, file.name)
                    val scenario = if (open) {
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(providerUri(file), "text/plain")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .setComponent(FileAssociationContract.component(context))
                        ActivityScenario.launch<AppActivity>(intent)
                    } else launchShare(file, "text/plain")
                    scenario.use {
                        if (!open) confirmLocalPreview(scenario)
                        if (round == 0) {
                            awaitComposeTag("sharedLocalBookFolderDialog")
                            if (open) scenario.onActivity {
                                val model = associationModel(it)
                                assertNull(model.uiState.value.localBooks)
                            }
                            if (choice == "dismiss") composeRule
                                .onNodeWithTag("sharedLocalBookFolderCancel").performClick()
                            else composeRule.onNodeWithTag("sharedLocalBookFolderSelect").performClick()
                            awaitLocalPreview(scenario)
                            scenario.onActivity {
                                val model = associationModel(it)
                                assertFalse(model.uiState.value.directoryRequested == true)
                                assertFalse(model.choosingLocalBookDirectory)
                                assertFalse(model.uiState.value.importingBooks == true)
                            }
                            assertFalse(copy.exists())
                            assertFalse(appDb.bookDao.has(copy.path))
                            assertNull(AppConfig.defaultBookTreeUri)
                            scenario.recreate()
                            confirmLocalPreview(scenario)
                            awaitComposeTag("sharedLocalBookFolderDialog")
                            screenshot("share-local-explicit-path-$firstOpen-$choice")
                            composeRule.onNodeWithTag("sharedLocalBookFolderPrivate").performClick()
                        }
                        await { copy.isFile && appDb.bookDao.has(copy.path) }
                        val book = appDb.bookDao.getBook(copy.path)!!.also(books::add)
                        assertEquals(Uri.fromFile(privateDirectory).toString(), AppConfig.defaultBookTreeUri)
                        assertArrayEquals(file.readBytes(), copy.readBytes())
                        assertTrue(file.isFile)
                        if (open) {
                            awaitReader(book)
                            assertTrue(ReadBook.curTextChapter!!.pages.any { it.text.contains("PRIVATE COPY $choice") })
                            closeReaders()
                        }
                        assertTrue(copy.delete())
                    }
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)

    private fun writeArchive(file: File, contents: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun associationModel(activity: AppActivity): FileAssociationViewModel {
        val route = checkNotNull(activity.initialDestination as? FileAssociationRoute)
        val owner = navigationViewModelStores(activity, BookshelfRoute)
            .getOrCreateOwner(NavEntry(route) {}.contentKey)
        return ViewModelProvider(owner)[FileAssociationViewModel::class.java]
    }

    private fun awaitLocalPreview(scenario: ActivityScenario<AppActivity>) {
        var state = ""
        try {
            await {
                var ready = false
                scenario.onActivity { activity ->
                    val model = associationModel(activity)
                    state = "batch=${model.uiState.value.localBooks?.size}; selected=${model.selectedLocalBooks.size}; " +
                        "pending=${model.pendingLocalBooks.size}; destination=${model.uiState.value.directoryRequested}; " +
                        "error=${model.uiState.value.errorMessage}"
                    ready = !model.uiState.value.localBooks.isNullOrEmpty()
                }
                ready && composeRule.onAllNodesWithTag("sharedLocalBooksDialog")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: AssertionError) {
            screenshot("share-local-preview-failure-$id")
            throw AssertionError(state, error)
        }
    }

    private fun confirmLocalPreview(scenario: ActivityScenario<AppActivity>) {
        awaitLocalPreview(scenario)
        composeRule.onNodeWithTag("sharedLocalBooksConfirm")
            .assertIsDisplayed()
            .performClick()
    }

    private fun openReader(book: Book) {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    private fun launchShare(file: File, mime: String): ActivityScenario<AppActivity> {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mime).setPackage(context.packageName)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        assertTrue(context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == FileAssociationContract.ACTIVITY_ALIAS })
        intent.component = FileAssociationContract.component(context)
        return ActivityScenario.launch(intent)
    }

    private fun awaitReader(book: Book) {
        await {
            var readerVisible = false
            instrumentation.runOnMainSync {
                readerVisible = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ReadBookActivity>().any { it.window.decorView.hasWindowFocus() }
            }
            ReadBook.book?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.chapter?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.curTextChapter?.pages?.isNotEmpty() == true && readerVisible
        }
    }

    private fun awaitDialog(scenario: ActivityScenario<AppActivity>) {
        await {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                    .any { dialog ->
                        dialog.dialog?.isShowing == true &&
                            dialog.dialog?.window?.decorView?.hasWindowFocus() == true &&
                            dialog.view?.isShown == true
                    }
            }
            ready
        }
    }

    private fun awaitHighlightRuleImport() {
        awaitComposeTag("highlightRuleImportDialog")
    }

    private fun awaitComposeTag(tag: String) {
        await {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun closeReaders() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            SystemClock.sleep(50)
        }
        assertTrue("Shared import did not reach the expected database/reader state", condition())
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "ui-regression/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    private fun pdfMarkerIsVisible(): Boolean {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        return try {
            (0 until bitmap.height step 4).sumOf { y ->
                (0 until bitmap.width step 4).count { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.red(pixel) < 40 && Color.green(pixel) in 80..120 && Color.blue(pixel) > 150
                }
            } > 100
        } finally { bitmap.recycle() }
    }
}

class SharedImportTagTestDialog : BaseDialogFragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View = View(requireContext())

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit
}
