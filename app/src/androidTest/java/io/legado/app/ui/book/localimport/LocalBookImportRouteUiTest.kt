package io.legado.app.ui.book.localimport

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavEntry
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.repository.LocalBookImportRepository
import io.legado.app.data.repository.LocalImportDirectory
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.ImportLocalBookRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalBookImportRouteUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.autoBackup, PreferKey.defaultBookTreeUri, "importBookPath", PreferKey.localBookImportSort, PreferKey.bookImportFileName)
    private val saved = keys.associateWith { prefs.all[it] }
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "local-route-$id")
    private val root = File(directory, "root-$id")
    private val repository = LocalBookImportRepository(context, appDb)
    private val bookUrls = linkedSetOf<String>()
    private val groupIds = linkedSetOf<Long>()

    @Before fun setUp() {
        root.mkdirs()
        File(directory, "saved").mkdirs()
        prefs.edit().putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.defaultBookTreeUri, File(directory, "saved").path)
            .putString("importBookPath", root.path)
            .putInt(PreferKey.localBookImportSort, 0).remove(PreferKey.bookImportFileName).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
    }

    @After fun tearDown() {
        bookUrls.forEach { url -> appDb.bookDao.getBook(url)?.let {
            appDb.bookChapterDao.delByBook(url); appDb.bookDao.delete(it); BookHelp.clearCache(it)
        } }
        groupIds.forEach { id -> appDb.bookDao.removeGroup(id); appDb.bookGroupDao.getByID(id)?.let { appDb.bookGroupDao.delete(it) } }
        prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
            null -> remove(key); is String -> putString(key, value); is Int -> putInt(key, value); is Boolean -> putBoolean(key, value)
        } } }.commit()
        LocalConfig.edit().apply { savedLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
        directory.deleteRecursively()
    }

    @Test fun browseSortFilterAndSelectionSurviveRecreationAndResetWhenChangingDirectories() {
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "inside-$id.txt").writeText("Inside text")
        File(root, "book2.txt").writeText("SMALL")
        File(root, "book10.txt").writeText("LARGE ".repeat(500))
        File(root, ".hidden.txt").writeText("HIDDEN")
        File(root, "ignored.json").writeText("{}")
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 3 }
            composeRule.onNodeWithTag("localImportEntry_book2.txt").assertIsDisplayed()
            screenshot("local-import-browser")
            assertEquals(listOf("sub", "book2.txt", "book10.txt"), state(scenario).entries.map { it.name })
            composeRule.onNodeWithTag("localImportEntry_book2.txt").performClick()
            val selection = state(scenario).selected
            assertEquals(1, selection.size)
            scenario.recreate()
            awaitState(scenario) { !it.loading && it.selected == selection }
            composeRule.onNodeWithContentDescription(context.getString(R.string.sort)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.sort_by_size)).performClick()
            awaitState(scenario) { it.entries.map { e -> e.name } == listOf("sub", "book10.txt", "book2.txt") }
            assertEquals(listOf("sub", "book10.txt", "book2.txt"), state(scenario).entries.map { it.name })
            act(scenario) { query("book2") }
            awaitState(scenario) { it.entries.singleOrNull()?.name == "book2.txt" }
            composeRule.onNodeWithTag("localImportEntry_book2.txt").assertIsDisplayed()
            act(scenario) { query("") }
            awaitState(scenario) { it.entries.size == 3 }
            composeRule.onNodeWithTag("localImportEntry_sub").performClick()
            awaitState(scenario) { !it.loading && it.canGoBack && it.entries.singleOrNull()?.name == "inside-$id.txt" }
            assertTrue(state(scenario).selected.isEmpty())
            act(scenario) { goBack() }
            awaitState(scenario) { !it.loading && !it.canGoBack && it.entries.size == 3 }
            assertTrue(state(scenario).selected.isEmpty())
        }
    }

    @Test fun groupedBatchImportKeepsSuccessfulArchiveProductsAndDoesNotMarkFailedFilesAsImported() {
        val good = File(root, "good-$id.txt").apply { writeText("Good text $id") }
        val missing = File(root, "missing-$id.txt").apply { writeText("Missing") }
        val archive = File(root, "archive-$id.zip")
        zip(archive, mapOf("a-$id.txt" to "Archive A $id", "b-$id.txt" to "Archive B $id"))
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 3 }
            missing.delete()
            state(scenario).entries.forEach { composeRule.onNodeWithTag("localImportEntry_${it.name}").performClick() }
            composeRule.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performClick()
            awaitTag("localImportGroupDialog")
            pressBack()
            awaitState(scenario) { it.groupPrompt == null && !it.working }
            assertNull(appDb.bookDao.getBookByFileName(good.name))
            assertEquals(3, state(scenario).selected.size)
            composeRule.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performClick()
            awaitTag("localImportGroupDialog")
            composeRule.onNodeWithTag("localImportGroupCheckbox").performClick()
            composeRule.onNodeWithTag("localImportGroupConfirm").performClick()
            awaitState(scenario) { !it.working && it.selected.isEmpty() && it.entries.count { e -> e.isOnBookShelf } == 2 }
            val group = checkNotNull(appDb.bookGroupDao.getByName(root.name))
            groupIds.add(group.groupId)
            val products = listOf(good.name, "a-$id.txt", "b-$id.txt").map { checkNotNull(appDb.bookDao.getBookByFileName(it)) }
            bookUrls.addAll(products.map { it.bookUrl })
            assertTrue(products.all { it.group and group.groupId != 0L })
            assertFalse(state(scenario).entries.single { it.name == missing.name }.isOnBookShelf)
            assertTrue(state(scenario).entries.single { it.name == archive.name }.isOnBookShelf)
            assertFalse(File(directory, "saved/${missing.name}").exists())
        }
    }

    @Test fun recursiveScanIncludesNestedBooksResetsSelectionAndSkipsDirectoryGrouping() {
        File(root, "first-$id.txt").writeText("First")
        File(root, "nested").apply { mkdirs() }.let { File(it, "second-$id.txt").writeText("Second") }
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 2 }
            composeRule.onNodeWithTag("localImportEntry_first-$id.txt").performClick()
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithTag("legadoTopAppBarMenuAction_0").performClick()
            awaitState(scenario) { !it.loading && it.recursive && it.entries.size == 2 && it.entries.none { e -> e.isDir } }
            assertTrue(state(scenario).selected.isEmpty())
            state(scenario).entries.forEach { composeRule.onNodeWithTag("localImportEntry_${it.name}").performClick() }
            composeRule.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performClick()
            awaitState(scenario) { !it.working && it.entries.all { e -> e.isOnBookShelf } }
            listOf("first-$id.txt", "second-$id.txt").forEach { bookUrls.add(checkNotNull(appDb.bookDao.getBookByFileName(it)).bookUrl) }
            assertNull(appDb.bookGroupDao.getByName(root.name))
            assertTrue(state(scenario).selected.isEmpty())
        }
    }

    @Test fun deleteSelectedFilesUpdatesTheActualDirectoryAndClearsSelection() {
        val deleted = File(root, "delete-$id.txt").apply { writeText("Delete") }
        val retained = File(root, "keep-$id.txt").apply { writeText("Keep") }
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.entries.size == 2 }
            composeRule.onNodeWithTag("localImportEntry_${deleted.name}").performClick()
            composeRule.onAllNodesWithContentDescription(context.getString(R.string.more_menu)).onLast().performClick()
            composeRule.onNodeWithText(context.getString(R.string.delete)).performClick()
            awaitState(scenario) { !it.working && it.entries.size == 1 }
            assertFalse(deleted.exists())
            assertEquals("Keep", retained.readText())
            assertTrue(state(scenario).selected.isEmpty())
        }
    }

    @Test fun newViewModelRestoresNestedDirectoryQuerySelectionAndPendingPickerWithoutRelaunchingIt() {
        val sub = File(root, "nested").apply { mkdirs() }
        val book = File(sub, "restore-$id.txt").apply { writeText("Restored") }
        val uri = Uri.fromFile(book).toString()
        val directories = listOf(root, sub).map { LocalImportDirectory(Uri.fromFile(it).toString(), it.name) }
        val savedState = SavedStateHandle(mapOf("directories" to Json.encodeToString(directories),
            "selection" to arrayListOf(uri), "query" to "restore", "picker" to "save", "pickerLaunched" to true))
        withModel(savedState) { model ->
            main { model.initialize() }
            await { !model.uiState.value.loading && model.uiState.value.entries.size == 1 }
            assertEquals(setOf(uri), model.uiState.value.selected)
            assertEquals("restore", model.uiState.value.query)
            assertTrue(model.uiState.value.canGoBack)
            main { assertFalse(model.claimPicker()); model.picked(null) }
            assertNull(model.uiState.value.picker)
            assertEquals(setOf(uri), model.uiState.value.selected)
            main { assertTrue(model.goBack()) }
            await { !model.uiState.value.loading && !model.uiState.value.canGoBack }
            assertTrue(model.uiState.value.selected.isEmpty())
        }
    }

    @Test fun rebindingAnExistingLocalBookPreservesItsDatabaseIdentityAndReadsTheNewFile() = runBlocking {
        val old = File(directory, "old/rebind-$id.txt").apply { parentFile!!.mkdirs(); writeText("OLD TEXT") }
        val book = LocalBook.importFile(Uri.fromFile(old))
        bookUrls.add(book.bookUrl)
        val identity = appDb.bookDao.getBook(book.bookUrl)!!.copy()
        old.delete()
        val moved = File(root, old.name).apply { writeText("NEW TEXT") }
        val dir = checkNotNull(repository.resolveDirectory(root.path))
        val entry = repository.list(dir).single()
        assertTrue(entry.isOnBookShelf)
        val rebound = checkNotNull(repository.rebind(entry))
        assertEquals(identity, appDb.bookDao.getBook(book.bookUrl))
        assertEquals(book.bookUrl, rebound.bookUrl)
        assertEquals(Uri.fromFile(moved), rebound.getLocalUri())
        assertEquals("NEW TEXT", context.contentResolver.openInputStream(rebound.getLocalUri())!!.bufferedReader().use { it.readText() })
    }

    @Test fun archiveEntrySelectionConfirmsMissingProductsThenImportsOnlyThatEntry() {
        val archive = File(root, "choose-$id.zip")
        zip(archive, mapOf("folder/a-$id.txt" to "FIRST $id", "folder/b-$id.txt" to "SECOND $id"))
        withModel(SavedStateHandle()) { model ->
            main { model.initialize() }
            await { !model.uiState.value.loading && model.uiState.value.entries.size == 1 }
            main { model.open(model.uiState.value.entries.single()) }
            await { !model.uiState.value.working && model.uiState.value.archive?.names?.size == 2 }
            val name = model.uiState.value.archive!!.names.first { it.endsWith("a-$id.txt") }
            main { model.chooseArchive(name) }
            await { model.uiState.value.archive?.confirmName == name }
            assertNull(appDb.bookDao.getBookByFileName("a-$id.txt"))
            main { model.confirmArchive() }
            await { !model.uiState.value.working && model.uiState.value.openBook != null }
            val book = model.uiState.value.openBook!!
            bookUrls.add(book.bookUrl)
            assertTrue(book.originName.endsWith("a-$id.txt"))
            assertNull(appDb.bookDao.getBookByFileName("b-$id.txt"))
            assertTrue(context.contentResolver.openInputStream(book.getLocalUri())!!.bufferedReader().use { it.readText() }.contains("FIRST"))
        }
    }

    @Test fun filenameScriptCancelDiscardsDraftAndSavePersistsTheConfirmedScript() {
        AppConfig.bookImportFileName = "name = src"
        launch().use { scenario ->
            awaitState(scenario) { !it.loading }
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithTag("legadoTopAppBarMenuAction_1").performClick()
            composeRule.onNodeWithTag("localImportFilenameScript").performTextReplacement("name = 'cancelled'")
            scenario.recreate()
            awaitTag("localImportFilenameScript")
            assertEquals("name = 'cancelled'", state(scenario).fileNameScript)
            composeRule.onNodeWithTag("localImportFilenameCancel").performClick()
            assertEquals("name = src", AppConfig.bookImportFileName)
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithTag("legadoTopAppBarMenuAction_1").performClick()
            assertEquals("name = src", state(scenario).fileNameScript)
            composeRule.onNodeWithTag("localImportFilenameScript").performTextReplacement("name = 'saved'")
            composeRule.onNodeWithTag("localImportFilenameSave").performClick()
            awaitState(scenario) { !it.editingScript }
            assertEquals("name = 'saved'", AppConfig.bookImportFileName)
        }
        withModel(SavedStateHandle(mapOf("editingScript" to true, "scriptDraft" to "name = 'restored'"))) { model ->
            main { model.initialize() }
            await { !model.uiState.value.loading }
            assertTrue(model.uiState.value.editingScript)
            assertEquals("name = 'restored'", model.uiState.value.fileNameScript)
            main { model.editScript(false) }
            assertEquals("name = 'saved'", model.uiState.value.fileNameScript)
        }
    }

    @Test fun firstStorageSetupCanUsePrivateStorageAndStillBrowseTheConfiguredImportDirectory() {
        AppConfig.defaultBookTreeUri = null
        File(root, "setup-$id.txt").writeText("Storage setup")
        launch().use { scenario ->
            awaitTag("localBookSaveFolderDialog")
            composeRule.onNodeWithTag("localBookSaveFolderPrivate").performClick()
            awaitState(scenario) { !it.loading && !it.storageSetup && it.entries.size == 1 }
            assertEquals(repository.privateDirectory(), AppConfig.defaultBookTreeUri)
            assertEquals("setup-$id.txt", state(scenario).entries.single().name)
        }
    }

    @Test fun partialArchiveImportKeepsTheGoodProductAndRestoresTheArchiveAsRetryable() {
        val archive = File(root, "partial-$id.zip")
        zip(archive, linkedMapOf("good-$id.txt" to "Good archive text $id", "broken-$id.epub" to "Invalid EPUB"))
        val saved = SavedStateHandle()
        withModel(saved) { model ->
            main { model.initialize() }
            await { !model.uiState.value.loading && model.uiState.value.entries.size == 1 }
            main { model.toggle(model.uiState.value.entries.single()); model.requestImport() }
            await { !model.uiState.value.working && model.uiState.value.selected.isEmpty() && model.uiState.value.message != null }
            val product = checkNotNull(appDb.bookDao.getBookByFileName("good-$id.txt"))
            bookUrls.add(product.bookUrl)
            assertFalse(model.uiState.value.entries.single().isOnBookShelf)
            assertNull(appDb.bookDao.getBookByFileName("broken-$id.epub"))
        }
        // Create a distinct instance from the saved primitives, without a retained ViewModel.
        val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        withModel(restoredState) { restored ->
            main { restored.initialize() }
            await { !restored.uiState.value.loading && restored.uiState.value.entries.size == 1 }
            assertFalse(restored.uiState.value.entries.single().isOnBookShelf)
            main { restored.toggle(restored.uiState.value.entries.single()) }
            assertEquals(1, restored.uiState.value.selected.size)
        }
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "ui-regression/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    private fun launch() = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, ImportLocalBookRoute))
    private fun model(activity: AppActivity): ImportBookViewModel = ViewModelProvider(
        navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(ImportLocalBookRoute) {}.contentKey),
    )[ImportBookViewModel::class.java]
    private fun state(scenario: ActivityScenario<AppActivity>): LocalBookImportUiState {
        lateinit var result: LocalBookImportUiState
        scenario.onActivity { result = model(it).uiState.value }
        return result
    }
    private fun act(scenario: ActivityScenario<AppActivity>, block: ImportBookViewModel.() -> Unit) = scenario.onActivity { model(it).block() }
    private fun awaitState(scenario: ActivityScenario<AppActivity>, condition: (LocalBookImportUiState) -> Boolean) = await { condition(state(scenario)) }
    private fun awaitTag(tag: String) { await { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            SystemClock.sleep(50)
        }
        assertTrue("Local import did not reach the expected state", condition())
        instrumentation.waitForIdleSync()
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun withModel(saved: SavedStateHandle, block: (ImportBookViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: ImportBookViewModel
        main { model = ImportBookViewModel(repository, saved); store.put("local-import", model) }
        try { block(model) } finally { main { store.clear() } }
    }
    private fun zip(file: File, contents: Map<String, String>) = ZipOutputStream(file.outputStream()).use { zip ->
        contents.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
    }
}
