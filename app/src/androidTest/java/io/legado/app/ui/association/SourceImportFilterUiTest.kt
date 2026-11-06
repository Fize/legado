package io.legado.app.ui.association

import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavEntry
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceImportFilterUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<AppActivity>()

    private var importRoute: BookSourceImportRoute? = null

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val prefKeys = listOf(
        PreferKey.importReplaceSource,
        PreferKey.importRememberGroup,
        PreferKey.importLastGroup,
        PreferKey.importLastGroupAdd,
        PreferKey.autoBackup,
    )
    private val savedPrefs = prefKeys.associateWith { prefs.all[it] }
    private val savedRules = appDb.replaceRuleDao.findEnabledBySourceScope()
    private val id = UUID.randomUUID().toString()
    private val urls = arrayListOf<String>()
    private val rule = ReplaceRule(
        name = "Import filter $id",
        pattern = "Edited-$id",
        replacement = "Derived-$id",
        isRegex = false,
        scopeSource = true,
        scopeContent = false,
    )

    @Before
    fun setUp() {
        prefs.edit()
            .remove(PreferKey.importRememberGroup)
            .remove(PreferKey.importLastGroup)
            .remove(PreferKey.importLastGroupAdd)
            .putBoolean(PreferKey.importReplaceSource, false)
            .putBoolean(PreferKey.autoBackup, false)
            .commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(rule)
    }

    @After
    fun tearDown() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<androidx.fragment.app.DialogFragment>()
                .forEach { it.dismissAllowingStateLoss() }
            activity.onBackPressedDispatcher.onBackPressed()
        }
        urls.forEach { appDb.bookSourceDao.delete(it) }
        appDb.replaceRuleDao.delete(rule)
        savedRules.forEach { appDb.replaceRuleDao.insert(it) }
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }
        }.commit()
    }

    @Test
    fun composeFilteringKeepsOriginalIndicesThroughEditReplacementAndRecreation() {
        val candidateUrls = (0..2).map { url("book/candidate-$it") }
        val editedUrl = url("book/edited")
        val candidates = listOf(
            source(candidateUrls[0], "Hidden", "Other", "hidden comment"),
            source(candidateUrls[1], "Twin", "Precise,Shared", "OnlyNeedle"),
            source(candidateUrls[2], "Twin", "PreciseSuffix,Shared", "other comment"),
        )
        appDb.bookSourceDao.insert(source(candidateUrls[2], "Old stored name", "Existing", "old", 1))

        showImport(GSON.toJson(candidates), expectedCount = 3)
        assertSelection(true, true, true)
        search("OnlyNeedle")
        assertRows(1)
        composeRule.onNodeWithTag("sourceImportRow:1").performClick()
        assertSelection(true, false, true)

        composeRule.activityRule.scenario.recreate()
        awaitReady(3)
        assertEquals("OnlyNeedle", modelValue { searchQuery })
        assertRows(1)
        assertSelection(true, false, true)

        val edited = source(editedUrl, "Edited-$id", "Edited group", "edited comment")
        dialogAction { onCodeSave(GSON.toJson(edited), "1") }
        waitForModel { allSources.getOrNull(1)?.bookSourceUrl == editedUrl }
        assertRows()

        search("Edited-$id")
        assertRows(1)
        modelAction { setUseSourceReplacement(true) }
        waitForModel {
            !uiState.value.isUpdating && allSources.getOrNull(1)?.bookSourceName == "Derived-$id"
        }
        search("Derived-$id")
        assertRows(1)

        modelAction {
            selectStatus.indices.forEach { setSelection(it, false) }
            setSelection(1, true)
            groupName = "Imported group"
            isAddGroup = false
        }
        composeRule.onNodeWithTag("sourceImportFooter:ok").performClick()
        waitUntil("Edited source was not imported") { storedGroup(editedUrl) == "Imported group" }

        assertNull(storedGroup(candidateUrls[0]))
        assertNull(storedGroup(candidateUrls[1]))
        assertEquals("Existing", storedGroup(candidateUrls[2]))
        assertEquals("Old stored name", appDb.bookSourceDao.getBookSource(candidateUrls[2])?.bookSourceName)
        assertEquals("Derived-$id", appDb.bookSourceDao.getBookSource(editedUrl)?.bookSourceName)
    }

    @Test
    fun urlImportKeepsSearchAndPreviewAcrossActivityRecreation() {
        val candidates = listOf(
            source(url("auto/hidden"), "Hidden", "Other"),
            source(url("auto/keep"), "Keep", "Wanted"),
        )
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                newFixedLengthResponse(Response.Status.OK, "application/json", GSON.toJson(candidates))
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            showImport("http://127.0.0.1:${server.listeningPort}/source.json", expectedCount = 2)
            search("Keep")
            assertRows(1)
            composeRule.onNodeWithTag("sourceImportRow:1:open").performClick()
            waitUntil("Source preview did not open") { codeDialog() != null }
            assertTrue(codeDialog()!!.currentOriginalCode().contains("auto/keep"))

            composeRule.activityRule.scenario.recreate()
            awaitReady(2)
            waitUntil("Source preview was not restored") { codeDialog() != null }
            assertEquals("Keep", modelValue { searchQuery })
            assertRows(1)
            assertTrue(codeDialog()!!.currentOriginalCode().contains("auto/keep"))
            dialogAction { onBackPressedDispatcher.onBackPressed() }
        } finally {
            server.stop()
        }
    }

    @Test
    fun composeSearchMatchesExactGroupsUngroupedAndLoginSources() {
        val candidates = listOf("未分组", "Ungrouped", "RSS", "rss").mapIndexed { index, group ->
            source(url("book/metadata-$index"), group = group).also { source ->
                when (index) {
                    0 -> {
                        source.mainJs = "function login() {}"
                        source.loginUi = "[{\"name\":\"account\"}]"
                    }
                    1 -> source.loginUrl = "https://login.invalid"
                }
            }
        }
        showImport(GSON.toJson(candidates), expectedCount = 4)
        search("group:RSS")
        assertRows(2)
        search("group:rss")
        assertRows(3)
        search(composeRule.activity.getString(R.string.no_group))
        assertRows(0)
        search(composeRule.activity.getString(R.string.need_login))
        assertRows(0, 1)
    }

    @Test
    fun composeGroupMemoryIsOptInAndControlsImportedGroup() {
        assertFalse("The unset preference must default to OFF", AppConfig.importRememberGroup)
        val first = url("group/default-off")
        showImport(GSON.toJson(listOf(source(first))), expectedCount = 1)
        setGroup("Once", add = false)
        assertNull(AppConfig.importLastGroup)
        composeRule.onNodeWithTag("sourceImportFooter:ok").performClick()
        waitUntil("One-time group was not imported") { storedGroup(first) == "Once" }

        val second = url("group/remember-add")
        showImport(GSON.toJson(listOf(source(second))), expectedCount = 1)
        toggleRememberGroup()
        assertTrue(AppConfig.importRememberGroup)
        setGroup("Remember add", add = true)
        assertEquals("Remember add", AppConfig.importLastGroup)
        assertTrue(AppConfig.importLastGroupAdd)
        composeRule.onNodeWithTag("sourceImportFooter:ok").performClick()
        waitUntil("Remembered additive group was not imported") {
            storedGroup(second) == "Original,Remember add"
        }

        val third = url("group/remember-restored")
        showImport(GSON.toJson(listOf(source(third))), expectedCount = 1)
        assertEquals("Remember add", modelValue { groupName })
        assertTrue(modelValue { isAddGroup })
        toggleRememberGroup()
        assertFalse(AppConfig.importRememberGroup)
        assertNull(AppConfig.importLastGroup)
        assertFalse(AppConfig.importLastGroupAdd)
        assertNull(modelValueOrNull { groupName })
        assertFalse(modelValue { isAddGroup })
        composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
    }

    @Test
    fun reopeningTheSameImportStartsWithFreshSearchAndSelection() {
        val json = GSON.toJson(listOf(source(url("reopen"), "Reopen fixture")))
        showImport(json, expectedCount = 1)
        search("does not match")
        assertRows()
        composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()

        showImport(json, expectedCount = 1)
        assertEquals("", modelValue { searchQuery })
        assertEquals(listOf(true), modelValue { selectStatus })
        assertRows(0)
        composeRule.onNodeWithTag("sourceImportFooter:cancel").performClick()
    }

    private fun showImport(source: String, expectedCount: Int) {
        waitUntil("Previous import dialog did not close") { dialogOrNull() == null }
        composeRule.activityRule.scenario.onActivity { activity ->
            val route = BookSourceImportRoute(source)
            importRoute = route
            activity.navigateTo(route)
        }
        composeRule.waitForIdle()
        awaitReady(expectedCount)
    }

    private fun awaitReady(expectedCount: Int) {
        waitUntil("Import dialog did not become ready") {
            val state = modelValueOrNull {
                allSources.size == expectedCount && !uiState.value.isUpdating &&
                    uiState.value.successCount == expectedCount
            } == true
            state && runCatching {
                composeRule.onNodeWithTag("sourceImportSearch").fetchSemanticsNode()
            }.isSuccess
        }
    }

    private fun search(query: String) {
        composeRule.onNodeWithTag("sourceImportSearch").performTextReplacement(query)
        waitForModel { searchQuery == query }
    }

    private fun assertRows(vararg originalIndices: Int) {
        val expected = originalIndices.toSet()
        composeRule.waitUntil(5_000) {
            (0..3).all { index ->
                val count = composeRule.onAllNodesWithTag("sourceImportRow:$index")
                    .fetchSemanticsNodes().size
                if (index in expected) count == 1 else count == 0
            }
        }
        (0..3).forEach { index ->
            if (index in originalIndices) {
                composeRule.onNodeWithTag("sourceImportRow:$index").assertIsDisplayed()
            } else {
                composeRule.onAllNodesWithTag("sourceImportRow:$index").assertCountEquals(0)
            }
        }
    }

    private fun assertSelection(vararg expected: Boolean) {
        assertEquals(expected.toList(), modelValue { selectStatus.toList() })
    }

    private fun setGroup(value: String, add: Boolean) {
        composeRule.onNodeWithTag("sourceImportGroup").performClick()
        composeRule.onNodeWithTag("sourceImportGroupName").performTextReplacement(value)
        if (modelValue { isAddGroup } != add) {
            composeRule.onNodeWithTag("sourceImportGroupAdd").performClick()
        }
        composeRule.onNodeWithTag("sourceImportGroupConfirm").performClick()
        waitForModel { groupName == value && isAddGroup == add }
    }

    private fun toggleRememberGroup() {
        composeRule.onNodeWithTag("sourceImportOverflow").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.import_remember_group)).performClick()
    }

    private fun dialogOrNull(): BookSourceImportRoute? {
        val route = importRoute ?: return null
        return route.takeIf {
            composeRule.onAllNodesWithTag("sourceImportSearch").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun codeDialog(): CodeDialog? {
        var result: CodeDialog? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            result = activity.supportFragmentManager.fragments.filterIsInstance<CodeDialog>().firstOrNull()
        }
        return result
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
        waitUntil("Import model did not reach the expected state") { modelValueOrNull(condition) == true }
    }

    private fun source(
        url: String,
        name: String = "Group fixture",
        group: String = "Original",
        comment: String = "",
        time: Long = 100,
    ) = BookSource(
        bookSourceUrl = url,
        bookSourceName = name,
        bookSourceGroup = group,
        bookSourceComment = comment,
        searchUrl = "/search",
        lastUpdateTime = time,
    )

    private fun url(path: String) = "https://filter-$id.invalid/$path".also { urls.add(it) }

    private fun storedGroup(url: String): String? =
        appDb.bookSourceDao.getBookSource(url)?.bookSourceGroup

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }
}
