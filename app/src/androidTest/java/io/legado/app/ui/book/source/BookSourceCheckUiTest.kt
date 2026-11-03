package io.legado.app.ui.book.source

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.RootMatchers
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject
import java.util.UUID
import java.util.zip.ZipFile

/** Regression coverage for the Compose book source manager and persisted check settings. */
@RunWith(AndroidJUnit4::class)
class BookSourceCheckUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val id = UUID.randomUUID().toString()
    private val group = "Check UI $id"
    private val sources = (0..2).map { index ->
        BookSource(
            bookSourceUrl = "https://check.invalid/$id/$index",
            bookSourceName = "Check fixture $index",
            bookSourceGroup = if (index < 2) group else "Other $id",
            bookSourceComment = "Original comment",
        )
    }
    private val savedHelp = LocalConfig.all["bookSourceHelpVersion"]
    private val savedShowStatus = preferences.all[PreferKey.showSourceCheckStatus] as? Boolean
    private val savedBlockNavigation = preferences.all[PreferKey.blockSourceNavigation] as? Boolean
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private val savedFlags = listOf(
        CheckSource.checkDomain, CheckSource.checkSearch, CheckSource.checkDiscovery,
        CheckSource.checkInfo, CheckSource.checkCategory, CheckSource.checkContent,
    )
    private var archive: File? = null

    @get:Rule
    val composeRule = createAndroidComposeRule<BookSourceActivity>()

    @Before
    fun setup() {
        preferences.edit().remove(PreferKey.showSourceCheckStatus).commit()
        preferences.edit().remove(PreferKey.blockSourceNavigation).commit()
        AppConfig.showSourceCheckStatus = false
        AppConfig.blockSourceNavigation = false
        LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
        appDb.bookSourceDao.delete(*sources.toTypedArray())
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(sources[0].bookSourceName, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun cleanup() {
        Debug.currentCheckSession()?.let { CheckSource.stop(context, it) }
        waitUntil { !Debug.isChecking }
        archive?.parentFile?.deleteRecursively()
        appDb.bookSourceDao.delete(*sources.toTypedArray())
        LocalConfig.edit().apply {
            if (savedHelp is Int) putInt("bookSourceHelpVersion", savedHelp)
            else remove("bookSourceHelpVersion")
        }.commit()
        preferences.edit().apply {
            if (savedShowStatus == null) remove(PreferKey.showSourceCheckStatus)
            else putBoolean(PreferKey.showSourceCheckStatus, savedShowStatus)
            if (savedBlockNavigation == null) remove(PreferKey.blockSourceNavigation)
            else putBoolean(PreferKey.blockSourceNavigation, savedBlockNavigation)
        }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
        CheckSource.checkDomain = savedFlags[0]
        CheckSource.checkSearch = savedFlags[1]
        CheckSource.checkDiscovery = savedFlags[2]
        CheckSource.checkInfo = savedFlags[3]
        CheckSource.checkCategory = savedFlags[4]
        CheckSource.checkContent = savedFlags[5]
    }

    @Test
    fun statusFilterIntersectsGroupAndSurvivesActivityRecreation() {
        val dao = appDb.bookSourceDao
        val queued = dao.beginCheck(sources.map { dao.getBookSourcePart(it.bookSourceUrl)!! })
        dao.completeCheck(queued[0], true, "", 1)
        dao.completeCheck(queued[2], true, "", 1)
        toggleCheckStatus()
        search("group:$group")

        composeRule.onNodeWithTag("bookSourceCheckFilter2").performScrollTo().performClick()
        awaitSourceNames(sources.take(1).map { it.bookSourceName })
        composeRule.activityRule.scenario.recreate()
        awaitSourceNames(sources.take(1).map { it.bookSourceName })

        composeRule.onNodeWithTag("bookSourceCheckFilter1").performScrollTo().performClick()
        awaitSourceNames(listOf(sources[1].bookSourceName))
        toggleCheckStatus()
        awaitSourceNames(sources.take(2).map { it.bookSourceName })
        composeRule.onAllNodesWithText(uiString(R.string.source_check_needed)).assertCountEquals(0)
        composeRule.activityRule.scenario.recreate()
        awaitSourceNames(sources.take(2).map { it.bookSourceName })
    }

    @Test
    fun filteredSelectionCountsAndActionsMatchVisibleTargetsAcrossRecreation() {
        val dao = appDb.bookSourceDao
        val queued = dao.beginCheck(listOf(dao.getBookSourcePart(sources[0].bookSourceUrl)!!))
        assertTrue(dao.completeCheck(queued.single(), true, "", 1))
        val untouched = sources.filterIndexed { index, _ -> index != 1 }
            .associate { it.bookSourceUrl to GSON.toJson(dao.getBookSource(it.bookSourceUrl)) }
        fun checkbox(index: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
            composeRule.onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText(sources[index].bookSourceName, substring = true))
            return composeRule.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                    hasAnySibling(hasText(sources[index].bookSourceName, substring = true)),
                useUnmergedTree = true,
            )
        }
        fun count(selected: Int, total: Int) {
            val label = uiString(R.string.select_all_count, selected, total)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithText(label).assertIsDisplayed()
            for (action in listOf(R.string.delete, R.string.revert_selection)) {
                val node = composeRule.onNodeWithText(uiString(action))
                if (selected == 0) node.assertIsNotEnabled() else node.assertIsEnabled()
            }
        }

        search("group:$group")
        awaitSourceNames(sources.take(2).map { it.bookSourceName })
        checkbox(0).performClick()
        count(1, 2)
        toggleCheckStatus()
        composeRule.onNodeWithTag("bookSourceCheckFilter1").performScrollTo().performClick()
        awaitSourceNames(listOf(sources[1].bookSourceName))
        count(0, 1)
        composeRule.activityRule.scenario.recreate()
        count(0, 1)
        checkbox(1).assertIsOff()

        val failedFilter = composeRule.onNodeWithTag("bookSourceCheckFilter3")
        val beforeScroll = failedFilter.fetchSemanticsNode().boundsInRoot
        failedFilter.performScrollTo()
        val afterScroll = failedFilter.fetchSemanticsNode().boundsInRoot
        File(context.cacheDir, "source-filter-chip-profile.json").writeText(
            JSONObject().put("before_scroll", beforeScroll.toString())
                .put("after_scroll", afterScroll.toString())
                .put("root", composeRule.onRoot().fetchSemanticsNode().boundsInRoot.toString())
                .toString(2))
        failedFilter.assertIsDisplayed().performClick()
        awaitSourceNames(emptyList())
        count(0, 0)
        composeRule.activityRule.scenario.recreate()
        count(0, 0)
        composeRule.onNodeWithTag("bookSourceCheckFilter0").performScrollTo().performClick()
        awaitSourceNames(sources.take(2).map { it.bookSourceName })
        count(1, 2)
        checkbox(0).assertIsOn()
        checkbox(1).assertIsOff()

        composeRule.onNodeWithTag("bookSourceCheckFilter1").performScrollTo().performClick()
        awaitSourceNames(listOf(sources[1].bookSourceName))
        val selectAll = composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                hasAnySibling(hasText(uiString(R.string.select_all_count, 0, 1))),
            useUnmergedTree = true,
        )
        selectAll.performClick()
        count(1, 1)
        checkbox(1).assertIsOn()
        composeRule.activityRule.scenario.recreate()
        count(1, 1)
        composeRule.onNodeWithText(uiString(R.string.delete)).performClick()
        Espresso.onView(ViewMatchers.withId(android.R.id.button1))
            .inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
        waitUntil { dao.getBookSource(sources[1].bookSourceUrl) == null }
        count(0, 0)
        untouched.forEach { (url, before) -> assertEquals(before, GSON.toJson(dao.getBookSource(url))) }
        composeRule.onNodeWithTag("bookSourceCheckFilter0").performScrollTo().performClick()
        awaitSourceNames(listOf(sources[0].bookSourceName))
        count(0, 1)
        checkbox(0).assertIsOff()
    }

    @Test
    fun bookshelfCountsUpdateFromBookChangesAndSelectionRemains() {
        search("group:$group")
        awaitSourceNames(sources.take(2).map { it.bookSourceName })
        val books = (0..2).map { index ->
            Book(
                bookUrl = "https://count.invalid/$id/$index",
                name = "Count $id $index",
                origin = sources[if (index == 2) 1 else 0].bookSourceUrl,
                type = if (index == 2) BookType.text or BookType.notShelf else BookType.text,
            )
        }
        try {
            composeRule.onNodeWithText(sources[0].bookSourceName, substring = true).performClick()
            appDb.bookDao.insert(*books.toTypedArray())
            awaitBookshelfCounts(2, 0)
            books[0].origin = sources[1].bookSourceUrl
            appDb.bookDao.update(books[0])
            awaitBookshelfCounts(1, 1)
            appDb.bookDao.delete(books[1])
            awaitBookshelfCounts(0, 1)
            val selectedCount = uiString(R.string.select_all_count, 1, 2)
            composeRule.onNodeWithText(selectedCount).assertIsDisplayed()
        } finally {
            appDb.bookDao.delete(*books.toTypedArray())
        }
    }

    @Test
    fun checkSettingsAreVisibleInComposeMenuAndIncludedInRealBackup(): Unit = runBlocking {
        assertFalse(AppConfig.showSourceCheckStatus)
        assertFalse(AppConfig.blockSourceNavigation)
        composeRule.onAllNodesWithText(uiString(R.string.source_check_all)).assertCountEquals(0)

        composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        composeRule.onNodeWithText(uiString(R.string.show_source_check_status)).performClick()
        composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        composeRule.onNodeWithText(uiString(R.string.block_source_navigation)).performClick()
        assertTrue(AppConfig.showSourceCheckStatus)
        assertTrue(AppConfig.blockSourceNavigation)
        composeRule.onNodeWithText(uiString(R.string.source_check_all)).assertIsDisplayed()

        composeRule.activityRule.scenario.close()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey }
        val backup = Backup.backupForLanTransferLocked(context).also { archive = it }
        ZipFile(backup).use { zip ->
            val xml = checkNotNull(zip.getEntry("config.xml"))
                .let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            assertTrue(xml.contains("name=\"showSourceCheckStatus\" value=\"true\""))
            assertTrue(xml.contains("name=\"blockSourceNavigation\" value=\"true\""))
        }
        AppConfig.showSourceCheckStatus = false
        AppConfig.blockSourceNavigation = false
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertTrue(AppConfig.showSourceCheckStatus)
        assertTrue(AppConfig.blockSourceNavigation)
    }

    @Test
    fun realCheckServicePersistsFailureAndSuccessWithoutChangingSourceMetadata() = runBlocking {
        CheckSource.checkDomain = false
        CheckSource.checkDiscovery = false
        CheckSource.checkInfo = false
        CheckSource.checkCategory = false
        CheckSource.checkContent = false
        CheckSource.checkSearch = true
        val dao = appDb.bookSourceDao
        val url = sources[0].bookSourceUrl
        var session = Debug.tryStartCheckSession()!!
        CheckSource.start(context, listOf(dao.getBookSourcePart(url)!!), session)
        waitUntil { !Debug.isChecking(session) }
        assertEquals("FAILED", dao.getCheckState(url)!!.status)
        assertEquals(group, dao.getBookSource(url)!!.bookSourceGroup)
        assertEquals("Original comment", dao.getBookSource(url)!!.bookSourceComment)

        CheckSource.checkSearch = false
        session = Debug.tryStartCheckSession()!!
        CheckSource.start(context, listOf(dao.getBookSourcePart(url)!!), session)
        waitUntil { !Debug.isChecking(session) }
        assertEquals("PASSED", dao.getCheckState(url)!!.status)
    }

    private fun toggleCheckStatus() {
        composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        composeRule.onNodeWithText(uiString(R.string.show_source_check_status)).performClick()
    }

    private fun search(query: String) {
        composeRule.onNodeWithTag("bookSourceSearch").performClick()
        composeRule.onNodeWithText(uiString(R.string.search_book_source)).performTextInput(query)
    }

    private fun awaitSourceNames(expected: List<String>) {
        try {
            // A real IME or a short window can virtualize all but one row. Check
            // the exact collection size, then scroll to and inspect each target.
            composeRule.waitUntil(15_000) {
                if (expected.isEmpty()) composeRule.onAllNodesWithText(uiString(R.string.empty))
                    .fetchSemanticsNodes().isNotEmpty()
                else composeRule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
                    .singleOrNull()?.config?.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount == expected.size
            }
            expected.forEach { name ->
                composeRule.waitUntil(15_000) {
                    runCatching {
                        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name, substring = true))
                        composeRule.onNodeWithText(name, substring = true).assertIsDisplayed()
                        true
                    }.getOrDefault(false)
                }
            }
        } catch (error: Throwable) {
            throw AssertionError("Expected source names: $expected\n" + composeRule.onRoot(useUnmergedTree = true).printToString(), error)
        }
    }

    private fun awaitBookshelfCounts(vararg expected: Int) {
        composeRule.waitUntil(15_000) {
            expected.indices.all { index ->
                composeRule.onAllNodesWithText(
                    uiString(R.string.source_bookshelf_count, expected[index]),
                    substring = false,
                ).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun uiString(resId: Int, vararg args: Any): String =
        composeRule.activity.getString(resId, *args)

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            android.os.SystemClock.sleep(50)
        }
        assertTrue("Condition did not become true", predicate())
    }
}
