package io.legado.app.ui.association

import io.legado.app.data.repository.*

import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.navigation3.runtime.NavEntry
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadBook
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SourceManualReplacementUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<AppActivity>()

    private var importRoute: BookSourceImportRoute? = null

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(
        PreferKey.manualReplaceRule,
        PreferKey.importReplaceSource,
        PreferKey.autoBackup,
        PreferKey.importRememberGroup,
    )
    private val savedPrefs = keys.associateWith { prefs.all[it] }
    private val savedRules = appDb.replaceRuleDao.all
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private val id = UUID.randomUUID().toString()
    private val files = arrayListOf<File>()
    private val urls = (0..1).map { "https://manual-$id.invalid/$it" }
    private val rules = listOf(
        ReplaceRule(
            id = 9123601,
            name = "Mixed source and body",
            pattern = "Seed",
            replacement = "Seed+",
            scopeSource = true,
            scopeTitle = true,
            scopeContent = true,
            isRegex = false,
            order = 1,
        ),
        ReplaceRule(
            id = 9123602,
            name = "Ordered scoped source",
            pattern = "+",
            replacement = "++",
            scope = "Seed0",
            scopeSource = true,
            scopeContent = false,
            isRegex = false,
            order = 2,
        ),
        ReplaceRule(
            id = 9123603,
            name = "Excluded source",
            pattern = "Seed0",
            replacement = "Wrong",
            excludeScope = urls[0],
            scopeSource = true,
            scopeContent = false,
            isRegex = false,
            order = 3,
        ),
        ReplaceRule(
            id = 9123604,
            name = "No matching text",
            pattern = "Absent",
            replacement = "Wrong",
            scopeSource = true,
            scopeContent = false,
            isRegex = false,
            order = 4,
        ),
        ReplaceRule(
            id = 9123605,
            name = "Disabled source",
            pattern = "Seed",
            replacement = "Wrong",
            scopeSource = true,
            scopeContent = false,
            isEnabled = false,
            isRegex = false,
            order = 5,
        ),
        ReplaceRule(
            id = 9123606,
            name = "Body only",
            pattern = "Seed",
            replacement = "Body",
            scopeSource = false,
            scopeContent = true,
            isRegex = false,
            order = 6,
        ),
    )

    @Before
    fun setup() {
        prefs.edit()
            .remove(PreferKey.manualReplaceRule)
            .putBoolean(PreferKey.importReplaceSource, true)
            .putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.importRememberGroup, false)
            .commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(*rules.toTypedArray())
    }

    @After
    fun cleanup() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                .forEach { it.dismissAllowingStateLoss() }
            activity.onBackPressedDispatcher.onBackPressed()
        }
        appDb.replaceRuleDao.delete(*rules.toTypedArray())
        appDb.replaceRuleDao.insert(*savedRules.toTypedArray())
        urls.forEach { appDb.bookSourceDao.delete(it) }
        files.forEach { it.delete() }
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) ->
                if (value is Boolean) putBoolean(key, value) else remove(key)
            }
        }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test
    fun composeManualAndEffectiveDialogsUseRawCandidatesAndRetainSelection() {
        AppConfig.importReplaceSource = false
        showImport()
        assertNames("Seed0", "Seed1")
        modelAction { setSelection(1, false) }

        openImportMenu("sourceImportMenu:manualRules")
        rules.take(4).forEach {
            composeRule.onNodeWithTag("manualSourceRule:${it.id}").assertIsDisplayed()
        }
        rules.drop(4).forEach {
            composeRule.onAllNodesWithTag("manualSourceRule:${it.id}").assertCountEquals(0)
        }
        composeRule.onNodeWithTag("manualSourceRules:selectAll").performClick()
        composeRule.activityRule.scenario.recreate()
        awaitReady()
        rules.take(4).forEach {
            composeRule.onNodeWithTag("manualSourceRule:${it.id}").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("manualSourceRules:ok").performClick()
        waitForModel { !uiState.value.isUpdating }
        assertNames("Seed++0", "Seed+1")
        assertEquals(listOf(true, false), modelValue { selectStatus.toList() })

        openImportMenu("sourceImportMenu:effectiveRules")
        composeRule.onNodeWithTag("effectiveSourceRule:${rules[0].id}").assertIsDisplayed()
        composeRule.onNodeWithTag("effectiveSourceRule:${rules[1].id}").assertIsDisplayed()
        rules.drop(2).forEach {
            composeRule.onAllNodesWithTag("effectiveSourceRule:${it.id}").assertCountEquals(0)
        }
        dismissChild<EffectiveReplacesDialog>()

        dialogAction { onCodeSave(GSON.toJson(source(0, "Edited Seed0")), "0") }
        waitForModel { !uiState.value.isUpdating && originalSourceJson(0)?.contains("Edited Seed0") == true }
        dialogAction { onManualSourceRulesSelected(listOf(rules[0].id), "0") }
        waitForModel { !uiState.value.isUpdating }
        assertNames("Edited Seed+0", "Seed+1")
        assertEquals(listOf(rules[0].id), modelValue { effectiveRuleIds(0) })

        dialogAction { onManualSourceRulesSelected(listOf(rules[0].id), "0") }
        waitForModel { !uiState.value.isUpdating }
        assertNames("Edited Seed+0", "Seed+1")
        modelAction {
            setSelection(0, true)
            setSelection(1, false)
        }
        composeRule.onNodeWithTag("sourceImportFooter:ok").performClick()
        waitUntil("Manual replacement import was not persisted") {
            appDb.bookSourceDao.getBookSource(urls[0])?.bookSourceName == "Edited Seed+0"
        }
        assertNull(appDb.bookSourceDao.getBookSource(urls[1]))
    }

    @Test
    fun automaticSourceOptionControlsComposeMenusAndKeepsManualRules() {
        showImport()
        assertNames("Seed++0", "Seed+1")
        composeRule.onNodeWithTag("sourceImportOverflow").performClick()
        composeRule.onNodeWithTag("sourceImportMenu:autoReplace").assertIsEnabled().performClick()
        waitForModel { !uiState.value.isUpdating && !automaticSourceReplacement }
        assertNames("Seed0", "Seed1")
        composeRule.onNodeWithTag("sourceImportMenu:manualRules").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("manualSourceRule:${rules[0].id}").performClick()
        composeRule.onNodeWithTag("manualSourceRules:ok").performClick()
        waitForModel { !uiState.value.isUpdating }
        assertNames("Seed+0", "Seed+1")
        assertFalse(AppConfig.importReplaceSource)

        composeRule.onNodeWithTag("sourceImportOverflow").performClick()
        composeRule.onNodeWithTag("sourceImportMenu:autoReplace").performClick()
        waitForModel { !uiState.value.isUpdating && automaticSourceReplacement }
        assertNames("Seed++0", "Seed+1")
        assertEquals(listOf(rules[0].id), modelValue { selectedManualRuleIds(0) })
        composeRule.onNodeWithTag("sourceImportMenu:manualRules").assertIsNotEnabled()

        composeRule.onNodeWithTag("sourceImportMenu:autoReplace").performClick()
        waitForModel { !uiState.value.isUpdating && !automaticSourceReplacement }
        assertNames("Seed+0", "Seed+1")
        assertEquals(listOf(rules[0].id), modelValue { selectedManualRuleIds(0) })
        assertFalse(AppConfig.manualReplaceRule)
        composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
    }

    @Test
    fun failedAutomaticModeSwitchRestoresManualResultAndPreference() {
        AppConfig.importReplaceSource = false
        showImport()
        dialogAction { onManualSourceRulesSelected(listOf(rules[0].id), "-1") }
        waitForModel { !uiState.value.isUpdating }
        assertNames("Seed+0", "Seed+1")

        withBlockedSourceRules(fail = true) { entered, release ->
            modelAction { setUseSourceReplacement(true) }
            assertTrue("The source-rule query was not reached", entered.await(15, TimeUnit.SECONDS))
            assertTrue(modelValue { uiState.value.isUpdating })
            release.countDown()
            waitForModel { !uiState.value.isUpdating }
            assertNames("Seed+0", "Seed+1")
            assertFalse(AppConfig.importReplaceSource)
            assertFalse(modelValue { automaticSourceReplacement })
            assertTrue(modelValue {
                uiState.value.errorMessage?.contains("Injected source-rule read failure") == true
            })
        }

        modelAction { setUseSourceReplacement(true) }
        waitForModel { !uiState.value.isUpdating && automaticSourceReplacement }
        assertNames("Seed++0", "Seed+1")
    }

    @Test
    fun legacyReaderManualModeComposeToggleBackupAndSourceModeStayIndependent() = runBlocking {
        assertFalse(AppConfig.manualReplaceRule)
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.my)).performClick()
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.replace_purify)).performClick()
        waitUntil("Replace rule Compose screen did not appear") {
            runCatching {
                composeRule.onNodeWithTag("replaceRuleOverflow").fetchSemanticsNode()
            }.isSuccess
        }
        repeat(2) { expectedIndex ->
            composeRule.onNodeWithTag("replaceRuleOverflow").performClick()
            composeRule.onNodeWithTag("replaceRuleManualMode").performClick()
            val expected = expectedIndex == 0
            waitUntil("Reader manual mode did not change") {
                AppConfig.manualReplaceRule == expected
            }
        }
        composeRule.onNodeWithTag("replaceRuleOverflow").performClick()
        composeRule.onNodeWithTag("replaceRuleManualMode").performClick()
        waitUntil("Reader manual mode was not re-enabled") { AppConfig.manualReplaceRule }

        BackupConfig.contentKeys.forEach {
            BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey
        }
        val archive = Backup.backupForLanTransferLocked(context).also { files.add(it) }
        ZipFile(archive).use { zip ->
            val xml = zip.getInputStream(checkNotNull(zip.getEntry("config.xml")))
                .bufferedReader().use { it.readText() }
            assertTrue(xml.contains("name=\"manualReplaceRule\" value=\"true\""))
        }
        AppConfig.manualReplaceRule = false
        Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
        assertTrue(AppConfig.manualReplaceRule)

        for (readerManual in listOf(false, true)) {
            for (sourceAutomatic in listOf(false, true)) {
                AppConfig.manualReplaceRule = readerManual
                AppConfig.importReplaceSource = sourceAutomatic
                val book = Book(
                    bookUrl = "https://reader-$id.invalid",
                    name = "Reader $id",
                    origin = "Origin $id",
                )
                book.setUseReplaceRule(true)
                val chapter = BookChapter(
                    bookUrl = book.bookUrl,
                    url = "chapter",
                    title = "Seed title",
                )
                val processed = ReadBook.processChapterContent(book, chapter, "Seed body")
                assertEquals(if (readerManual) "Seed title" else "Seed+ title", processed.first)
                val text = processed.second.toString()
                assertEquals(
                    "Source import mode must not change reader behavior",
                    !readerManual,
                    text.contains("Body+"),
                )
                assertEquals(readerManual, text.contains("Seed body"))

                val candidate = prepareBookSourceImportCandidate(
                    source(0),
                    if (sourceAutomatic) rules.filter { it.isEnabled && it.scopeSource } else emptyList(),
                )
                assertEquals(
                    if (sourceAutomatic) "Seed++0" else "Seed0",
                    candidate.source(sourceAutomatic).bookSourceName,
                )
            }
        }
        val readerCandidates = appDb.replaceRuleDao.findManualCandidates().map { it.id }
        assertTrue(rules[0].id in readerCandidates)
        assertTrue(rules[5].id in readerCandidates)
        assertTrue(rules.subList(1, 5).none { it.id in readerCandidates })
    }

    private fun showImport() {
        waitUntil("Previous import dialog did not close") { dialogOrNull() == null }
        composeRule.activityRule.scenario.onActivity { activity ->
            val route = BookSourceImportRoute(GSON.toJson((0..1).map(::source)))
            importRoute = route
            activity.navigateTo(route)
        }
        composeRule.waitForIdle()
        awaitReady()
    }

    private fun awaitReady() {
        waitUntil("Import preview did not become ready") {
            modelValueOrNull {
                allSources.size == 2 && !uiState.value.isUpdating && uiState.value.successCount == 2
            } == true && runCatching {
                composeRule.onNodeWithTag("sourceImportOverflow").fetchSemanticsNode()
            }.isSuccess
        }
    }

    private fun openImportMenu(tag: String) {
        composeRule.onNodeWithTag("sourceImportOverflow").performClick()
        composeRule.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun assertNames(vararg expected: String) {
        assertEquals(expected.toList(), modelValue { allSources.map { it.bookSourceName } })
    }

    private inline fun <reified T : DialogFragment> dismissChild() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<T>().last().dismissAllowingStateLoss()
        }
        waitUntil("${T::class.simpleName} did not close") { childOrNull<T>() == null }
    }

    private inline fun <reified T : DialogFragment> childOrNull(): T? {
        var result: T? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            result = activity.supportFragmentManager.fragments.filterIsInstance<T>().lastOrNull()
        }
        return result
    }

    private fun dialogOrNull(): BookSourceImportRoute? {
        val route = importRoute ?: return null
        return route.takeIf {
            composeRule.onAllNodesWithTag("sourceImportSearch").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dialogAction(action: AppActivity.() -> Unit) {
        composeRule.activityRule.scenario.onActivity { activity -> activity.action() }
    }

    private fun modelAction(action: ImportBookSourceViewModel.() -> Unit) {
        modelValue(action)
    }

    private fun <T> modelValue(action: ImportBookSourceViewModel.() -> T): T =
        checkNotNull(modelValueOrNull(action))

    private fun <T> modelValueOrNull(action: ImportBookSourceViewModel.() -> T): T? {
        val route = importRoute ?: return null
        var result: T? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            val owner = navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(route) {}.contentKey)
            result = ViewModelProvider(owner)[ImportBookSourceViewModel::class.java].action()
        }
        return result
    }

    private fun waitForModel(condition: ImportBookSourceViewModel.() -> Boolean) {
        waitUntil("Import model did not reach the expected state") {
            modelValueOrNull(condition) == true
        }
    }

    private fun withBlockedSourceRules(
        fail: Boolean,
        action: (CountDownLatch, CountDownLatch) -> Unit,
    ) {
        val delegate = appDb.replaceRuleDao
        val field = appDb.javaClass.declaredFields.single {
            it.name.contains("replaceRuleDao", ignoreCase = true)
        }
        field.isAccessible = true
        val original = field.get(appDb)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            io.legado.app.data.dao.ReplaceRuleDao::class.java.classLoader,
            arrayOf(io.legado.app.data.dao.ReplaceRuleDao::class.java),
        ) { _, method, args ->
            if (method.name == "findEnabledBySourceScope") {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) {
                    "Source-rule comparison was not released"
                }
                check(!fail) { "Injected source-rule read failure" }
            }
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw error.targetException
            }
        }
        try {
            field.set(appDb, if (original is Lazy<*>) lazyOf(proxy) else proxy)
            action(entered, release)
        } finally {
            release.countDown()
            field.set(appDb, original)
        }
    }

    private fun source(index: Int, name: String = "Seed$index") = BookSource(
        bookSourceUrl = urls[index],
        bookSourceName = name,
        searchUrl = "/search",
    )

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }
}
