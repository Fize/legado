package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Looper
import android.view.Menu
import android.widget.PopupMenu
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportBookSourceViewModel
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import kotlinx.serialization.json.Json
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.ui.book.read.config.ReaderMenuConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Uses the reader's real menu/import UI and uncached HTTP chapter downloads. */
@RunWith(AndroidJUnit4::class)
class ReaderSourceReimportUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val prefKeys = listOf(PreferKey.readerMenuConfig, PreferKey.preDownloadNum,
        PreferKey.clickActionMC, PreferKey.cronet, PreferKey.autoBackup, PreferKey.onlyLatestBackup, PreferKey.importReplaceSource,
        PreferKey.importRememberGroup, PreferKey.importLastGroup, PreferKey.importLastGroupAdd,
        PreferKey.importKeepName, PreferKey.importKeepGroup, PreferKey.importKeepEnable)
    private val savedPrefs = prefKeys.associateWith { prefs.all[it] }
    private val savedLastBackup = LocalConfig.all["lastBackup"]
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val savedRules = appDb.replaceRuleDao.findEnabledBySourceScope()
    private val savedBackupConfig = HashMap(BackupConfig.ignoreConfig)
    private val id = UUID.randomUUID().toString()
    private val requests = AtomicIntegerArray(3)
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val index = session.uri.substringAfterLast('/').toIntOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
            requests.incrementAndGet(index)
            return newFixedLengthResponse(Response.Status.OK, "text/html",
                "<div id='before'>OLD HTTP body $index</div><div id='after'>UPDATED HTTP body $index</div>")
                .apply { addHeader("Cache-Control", "no-store") }
        }
    }
    private val source = BookSource(bookSourceUrl = "https://reimport.invalid/$id",
        bookSourceName = "Reimport fixture", bookSourceGroup = "Keep group", lastUpdateTime = 12345L,
        ruleContent = ContentRule(content = "#before@text"))
    private val book = Book(bookUrl = "https://reimport.invalid/book/$id",
        tocUrl = "https://reimport.invalid/book/$id/toc", origin = source.bookSourceUrl,
        name = "Reimport $id", author = "Fixture", type = BookType.text, totalChapterNum = 3,
        canUpdate = false).apply { setPageAnim(PageAnim.noAnim); setUseReplaceRule(false) }
    private val replacement = ReplaceRule(name = "Reimport rule $id", pattern = "#before@text",
        replacement = "#after@text", scopeSource = true, scopeContent = false, isRegex = false)
    private lateinit var chapters: List<BookChapter>
    private lateinit var scenario: ActivityScenario<ReadBookActivity>
    private val backupDir = File(context.cacheDir, "reader-reimport-$id")

    @Before fun setUp() {
        prefs.edit().putInt(PreferKey.preDownloadNum, 0).putInt(PreferKey.clickActionMC, 0)
            .putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.onlyLatestBackup, true)
            .putBoolean(PreferKey.importReplaceSource, true).putBoolean(PreferKey.importRememberGroup, false)
            .putBoolean(PreferKey.importKeepName, true).putBoolean(PreferKey.importKeepGroup, true)
            .putBoolean(PreferKey.importKeepEnable, true).commit()
        AppConfig.clickActionMC = 0
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(replacement)
        saveReaderMenuConfig(context, ReaderMenuConfig(primary = listOf("reimportSource"),
            more = ReaderMenuConfig.ALL_KEYS - "reimportSource"))
        server.start()
        chapters = (0..2).map { BookChapter(bookUrl = book.bookUrl,
            url = "http://127.0.0.1:${server.listeningPort}/$it", baseUrl = book.bookUrl,
            index = it, title = "Chapter $it") }
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        // The reader eagerly lays out adjacent chapters; only chapter 2 is deliberately uncached.
        chapters.take(2).forEach { BookHelp.saveText(book, it, "Cached chapter ${it.index}. 保留当前正文和阅读位置。") }
        launchReader()
        awaitReader(0)
        source.bookSourceComment = "Fresh database snapshot $id"
        appDb.bookSourceDao.insert(source)
        assertNotEquals(source.bookSourceComment, ReadBook.bookSource?.bookSourceComment)
    }

    @After fun tearDown() {
        if (::scenario.isInitialized && scenario.state != Lifecycle.State.DESTROYED) scenario.close()
        server.stop()
        chapters.forEach { BookHelp.delContent(book, it) }
        CacheBook.cacheBookMap.remove(book.bookUrl)?.stop()
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.replaceRuleDao.delete(replacement)
        savedRules.forEach { appDb.replaceRuleDao.insert(it) }
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedBackupConfig)
        prefs.edit().apply { savedPrefs.forEach { (key, value) ->
            when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            }
        } }.commit()
        AppConfig.clickActionMC = prefs.getInt(PreferKey.clickActionMC, 0)
        LocalConfig.edit().apply { savedHelp.forEach { (key, value) ->
            if (value == null) remove(key) else putInt(key, value as Int)
        } }.commit()
        LocalConfig.edit().apply {
            if (savedLastBackup == null) remove("lastBackup") else putLong("lastBackup", savedLastBackup as Long)
        }.commit()
        backupDir.deleteRecursively()
    }

    @Test fun currentDatabaseSourceCandidateAndCancelNeverImports() {
        val before = GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
        val current = ReadBook.bookSource
        val position = position()
        openReimport()
        main {
            val vm = importer()
            assertEquals(1, vm.allSources.size)
            assertEquals(source.lastUpdateTime, vm.allSources.single().lastUpdateTime)
            assertEquals(listOf(true), vm.selectStatus)
            assertEquals(listOf(false), vm.updateSourceStatus)
            assertTrue(vm.originalSourceJson(0)!!.contains(source.bookSourceComment!!))
            assertEquals("#after@text", vm.allSources.single().ruleContent?.content)
        }
        main {
            assertTrue(importer().originalSourceJson(0)!!.contains("#before@text"))
            assertTrue(importer().replacedSourceJson(0)!!.contains("#after@text"))
        }
        closeImporter()
        await("cancel closes importer") { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        assertEquals(before, GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)))
        assertSame(current, ReadBook.bookSource)
        assertEquals(position, position())
        assertEquals(0, requests.get(2))
    }

    @Test fun confirmedImportPublishesAcrossRecreationAndNextReaderRequestUsesUpdatedRule() {
        val position = position()
        openReimport()
        val oldVm = main { importer() }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val writer = thread(name = "hold-source-import-transaction") {
            try { appDb.runInTransaction { blocked.countDown(); check(release.await(20, TimeUnit.SECONDS)) } }
            catch (failure: Throwable) { error.set(failure) }
        }
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            // Espresso drains the loading animation after a click, so invoke the same ViewModel
            // action as the Compose footer while the database write is deliberately held.
            instrumentation.runOnMainSync {
                oldVm.importSelect()
                assertTrue("Import must be pending while the database is held", oldVm.uiState.value.isUpdating)
                assertFalse(oldVm.uiState.value.isFinished)
            }
        } finally { release.countDown(); writer.join(5000) }
        error.get()?.let { throw AssertionError("DB fixture failed", it) }
        await("successful import publishes and dismisses the dialog") {
            ReadBook.bookSource?.ruleContent?.content == "#after@text" &&
                it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        assertEquals(position, position())
        val savedBook = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals(book.origin, savedBook.origin)
        assertEquals(book.name, savedBook.name)
        assertEquals(book.author, savedBook.author)
        assertEquals("#after@text", appDb.bookSourceDao.getBookSource(book.origin)!!.ruleContent?.content)
        assertEquals(source.lastUpdateTime, ReadBook.bookSource!!.lastUpdateTime)
        assertEquals(source.bookSourceGroup, ReadBook.bookSource!!.bookSourceGroup)
        assertEquals(0, requests.get(2))
        scenario.recreate()
        awaitReader(0)
        assertEquals("#after@text", ReadBook.bookSource?.ruleContent?.content)
        showMenu()
        onView(withId(R.id.tv_next)).perform(click())
        awaitReader(1)
        await("next uncached reader request finishes") {
            requests.get(2) > 0 && ReadBook.nextTextChapter?.chapter?.index == 2 &&
                ReadBook.nextTextChapter?.isCompleted == true
        }
        assertTrue(BookHelp.getContent(book, chapters[2])!!.contains("UPDATED HTTP body 2"))
        assertFalse(BookHelp.getContent(book, chapters[2])!!.contains("OLD HTTP body"))
        showMenu()
        onView(withId(R.id.tv_next)).perform(click())
        awaitReader(2)
        screenshot("reader-reimport-next-http-chapter")
    }

    @Test fun completedOldImporterCannotReplaceAnotherBooksActiveSource() {
        val otherSource = source.copy(bookSourceUrl = source.bookSourceUrl + "/other", bookSourceName = "Other source")
        val otherBook = book.copy(bookUrl = book.bookUrl + "/other", origin = otherSource.bookSourceUrl,
            name = "Other book $id", totalChapterNum = 1)
        val otherChapter = chapters[0].copy(bookUrl = otherBook.bookUrl)
        appDb.bookSourceDao.insert(otherSource)
        appDb.bookDao.insert(otherBook)
        appDb.bookChapterDao.insert(otherChapter)
        BookHelp.saveText(otherBook, otherChapter, "Other book remains unchanged.")
        try {
            openReimport()
            val pendingVm = main { importer() }
            // The user can open another reader while this importer remains on the back stack.
            main {
                it.startActivity(Intent(it, ReadBookActivity::class.java)
                    .putExtra("bookUrl", otherBook.bookUrl).putExtra("inBookshelf", true))
            }
            await("second real reader completes before the old import") {
                ReadBook.book?.bookUrl == otherBook.bookUrl && it.isInitFinish &&
                    ReadBook.curTextChapter?.chapter?.bookUrl == otherBook.bookUrl &&
                    ReadBook.curTextChapter?.isCompleted == true
            }
            val position = listOf(ReadBook.durChapterIndex, ReadBook.durChapterPos)
            // Execute the retained confirmation with the original dialog VM, as an already queued
            // operation would complete after the current book changes.
            instrumentation.runOnMainSync { pendingVm.importSelect() }
            val importDeadline = SystemClock.uptimeMillis() + 15000
            while (!pendingVm.uiState.value.isFinished && SystemClock.uptimeMillis() < importDeadline) {
                SystemClock.sleep(50)
            }
            assertTrue(pendingVm.uiState.value.isFinished)
            assertEquals("#after@text", appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.ruleContent?.content)
            assertEquals(otherBook.bookUrl, ReadBook.book?.bookUrl)
            assertEquals(otherSource.bookSourceUrl, ReadBook.bookSource?.bookSourceUrl)
            assertEquals("#before@text", ReadBook.bookSource?.ruleContent?.content)
            assertEquals(position, listOf(ReadBook.durChapterIndex, ReadBook.durChapterPos))
        } finally {
            BookHelp.delContent(otherBook, otherChapter)
            CacheBook.cacheBookMap.remove(otherBook.bookUrl)?.stop()
            appDb.bookChapterDao.delByBook(otherBook.bookUrl)
            appDb.bookDao.delete(otherBook)
            appDb.bookSourceDao.delete(otherSource)
        }
    }

    @Test fun menuConfigurationAndSettingsBackupPreserveReimportAction() {
        scenario.onActivity { it.showReaderMenuConfig() }
        await("native reader menu configuration") {
            it.supportFragmentManager.fragments.filterIsInstance<ReaderMenuConfigDialog>().any { f -> f.view != null }
        }
        scenario.onActivity { activity ->
            val configDialog = activity.supportFragmentManager.fragments
                .filterIsInstance<ReaderMenuConfigDialog>().single()
            val config = loadReaderMenuConfig(context)
            val index = (config.primary + config.more).indexOf("reimportSource")
            assertTrue(index >= 0)
            configDialog.setPrimary(index, primary = false, persistNow = true)
        }
        assertEquals(listOf("reimportSource"), loadReaderMenuConfig(context).more.filter { it == "reimportSource" })
        assertFalse("reimportSource" in loadReaderMenuConfig(context).primary)
        scenario.onActivity {
            it.supportFragmentManager.fragments.filterIsInstance<ReaderMenuConfigDialog>().single()
                .dismissAllowingStateLoss()
        }
        scenario.recreate()
        awaitReader(0)
        assertFalse("Reimport is configured outside the primary overflow", "reimportSource" in loadReaderMenuConfig(context).primary)
        invokeReimportAction()
        awaitImporter()
        closeImporter()
        await("reimport destination closes") { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        scenario.close()
        val expected = loadReaderMenuConfig(context)
        runBlocking(Dispatchers.IO) {
            backupDir.mkdirs()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey }
            BackupConfig.ignoreConfig["readConfig"] = false
            Backup.backupLocked(context, backupDir.path, uploadWebDav = false)
            saveReaderMenuConfig(context, ReaderMenuConfig.default())
            Restore.restoreOrThrow(context, File(backupDir, "backup.zip").toUri(), lanTransfer = true)
        }
        assertEquals(expected, loadReaderMenuConfig(context))
    }

    @Test fun deletedSourceDoesNotOpenAnEmptyImporterAndLocalBookHasNoAction() {
        appDb.bookSourceDao.delete(source)
        invokeReimportAction()
        // Wait for the actual Room read rather than accepting an absent dialog before it completes.
        await("missing-source lookup completes") {
            it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !ViewModelProvider(it)[ReadBookViewModel::class.java].sourceReimportLoading
        }
        main {
            assertNull(resumedAppActivity())
            assertNull(ViewModelProvider(it)[ReadBookViewModel::class.java].pendingSourceReimport.value)
            ReadBook.book = ReadBook.book!!.copy(origin = BookType.localTag, type = 0)
            it.refreshReaderMenu()
        }
        assertFalse("Local books hide source reimport in the actual reader menu", readerActionVisible(R.id.menu_reimport_source))
        main { ReadBook.book = book }
    }

    private fun launchReader() {
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
    }

    private fun position() = main { listOf(ReadBook.book?.bookUrl, ReadBook.book?.origin,
        ReadBook.durChapterIndex.toString(), ReadBook.durChapterPos.toString()) }

    private fun importer(activity: AppActivity = checkNotNull(resumedAppActivity())): ImportBookSourceViewModel {
        val route = Json.decodeFromString<AppRoute>(checkNotNull(activity.intent.getStringExtra("initialDestination")))
        val owner = navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(route) {}.contentKey)
        return ViewModelProvider(owner)[ImportBookSourceViewModel::class.java]
    }

    private fun openReimport() {
        invokeReimportAction()
        awaitImporter()
    }

    private fun invokeReimportAction() = scenario.onActivity { activity ->
        val item = PopupMenu(activity, activity.window.decorView).menu.add(
            Menu.NONE, R.id.menu_reimport_source, Menu.NONE, R.string.reimport_book_source,
        )
        // This handler starts the asynchronous lookup but currently returns the superclass result.
        activity.onCompatOptionsItemSelected(item)
    }

    private fun readerActionVisible(id: Int) = main { activity ->
        val menu = androidx.appcompat.view.menu.MenuBuilder(activity)
        activity.onCompatCreateOptionsMenu(menu)
        activity.onPrepareOptionsMenu(menu)
        menu.findItem(id)?.isVisible == true
    }

    private fun awaitImporter() = await("one current-source import candidate") {
        val activity = resumedAppActivity() ?: return@await false
        val vm = importer(activity)
        vm.uiState.value.successCount == 1 && !vm.uiState.value.isUpdating
    }

    private fun closeImporter() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>()
                .firstOrNull()?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun resumedAppActivity(): AppActivity? {
        val lookup = {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>()
                .firstOrNull()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return lookup()
        var result: AppActivity? = null
        instrumentation.runOnMainSync { result = lookup() }
        return result
    }

    private fun showMenu() {
        awaitDraw()
        if (!main { it.findViewById<ReadMenu>(R.id.read_menu).isVisible }) {
            onView(withId(R.id.read_view)).perform(click())
        }
        await("reader menu visible") { it.findViewById<ReadMenu>(R.id.read_menu).isVisible }
    }

    private fun awaitDraw() {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun awaitReader(index: Int) = await("reader chapter $index") {
        val chapter = ReadBook.curTextChapter
        ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == index &&
            chapter?.chapter?.index == index && chapter.isCompleted && it.isInitFinish &&
            !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            if (main(condition)) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out: $description; source=${ReadBook.bookSource?.ruleContent}, chapter=${ReadBook.durChapterIndex}")
    }

    private fun <T> main(block: (ReadBookActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(context.getExternalFilesDir("ui-regression")).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
