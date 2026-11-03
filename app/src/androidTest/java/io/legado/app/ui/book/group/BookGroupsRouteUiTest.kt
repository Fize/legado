package io.legado.app.ui.book.group

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupDraft
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.info.BookInfoUiState
import io.legado.app.ui.book.info.BookInfoViewModel
import io.legado.app.ui.navigation.BookGroupsRoute
import io.legado.app.ui.navigation.BookshelfManageRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BookGroupsRouteUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val repository = BookGroupRepository(appDb, context)
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = listOf(PreferKey.autoBackup).associateWith { prefs.all[it] }
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private lateinit var originalGroups: List<BookGroup>
    private lateinit var first: BookGroup
    private lateinit var second: BookGroup
    private val book = Book(bookUrl = "groups-ui://$id", name = "Group book $id", author = "Author", origin = "missing",
        tocUrl = "groups-ui://$id/toc", coverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
    private val files = arrayListOf<File>()
    private lateinit var existingCovers: Set<String>

    @Before fun setUp() = runBlocking {
        prefs.edit().putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        originalGroups = appDb.bookGroupDao.all.map { it.copy() }
        existingCovers = File(context.externalFiles, "covers").listFiles().orEmpty().map { it.canonicalPath }.toSet()
        first = repository.save(BookGroupDraft(name = "First $id"))
        second = repository.save(BookGroupDraft(name = "Second $id"))
        book.group = first.groupId
        book.putCustomVariable("Retained $id")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter"))
    }

    @After fun cleanUp() {
        appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); BookHelp.clearCache(book)
        appDb.bookGroupDao.all.filter { it.groupName.contains(id) }.forEach {
            appDb.bookGroupDao.delete(it); appDb.bookDao.clearGroupBit(it.groupId, it.groupId.inv())
        }
        appDb.bookGroupDao.update(*originalGroups.toTypedArray())
        files.forEach { it.delete() }
        prefs.edit().apply { savedPrefs.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
        LocalConfig.edit().apply { savedLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
    }

    @Test fun managementRouteCreatesWithValidationAndRetainsTheDraftAcrossRecreation() {
        launchManage().use { scenario ->
            ready()
            composeRule.onNodeWithTag("group_add").performClick()
            composeRule.onNodeWithTag("group_confirm").performClick()
            await { composeRule.onAllNodesWithTag("group_error").fetchSemanticsNodes().isNotEmpty() }
            val name = "Created $id"
            composeRule.onNodeWithTag("group_name").performTextReplacement(name)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("group_refresh").performClick()
            composeRule.onNodeWithTag("group_refresh").assertIsOff()
            composeRule.onNodeWithTag("group_only_read").performClick()
            composeRule.onNodeWithTag("group_only_read").assertIsOn()
            lateinit var sort: String
            scenario.onActivity { sort = it.resources.getStringArray(R.array.book_sort)[3] }
            composeRule.onNodeWithTag("group_sort").performClick()
            composeRule.onNodeWithText(sort).performClick()
            composeRule.onNodeWithTag("group_refresh").assertIsOff()
            composeRule.onNodeWithTag("group_only_read").assertIsOn()
            composeRule.waitForIdle()
            scenario.recreate()
            composeRule.onNodeWithTag("group_name").assertTextContains(name)
            composeRule.onNodeWithTag("group_refresh").assertIsOff()
            composeRule.onNodeWithTag("group_only_read").assertIsOn()
            composeRule.onNodeWithTag("group_confirm").performClick()
            await { appDb.bookGroupDao.getByName(name) != null }
            val saved = appDb.bookGroupDao.getByName(name)!!
            assertFalse(saved.enableRefresh); assertTrue(saved.onlyUpdateRead); assertEquals(2, saved.bookSort)
            scrollTo(saved.groupId)
            composeRule.onNodeWithTag("group_edit_${saved.groupId}").performClick()
            composeRule.onNodeWithTag("group_name").performTextReplacement("Cancelled $id")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            androidx.test.espresso.Espresso.pressBack()
            ready()
            assertEquals(name, appDb.bookGroupDao.getByID(saved.groupId)!!.groupName)
        }
    }

    @Test fun managementReordersHidesAndDeletesWithBookMembershipCleanup() {
        launchManage().use {
            ready(); scrollTo(second.groupId)
            composeRule.onNodeWithTag("group_up_${second.groupId}").performClick()
            await { appDb.bookGroupDao.getByID(second.groupId)!!.order < appDb.bookGroupDao.getByID(first.groupId)!!.order }
            scrollTo(first.groupId)
            composeRule.onNodeWithTag("group_visible_${first.groupId}").performClick()
            await { !appDb.bookGroupDao.getByID(first.groupId)!!.show }
            composeRule.onNodeWithTag("group_edit_${first.groupId}").performClick()
            composeRule.onNodeWithTag("group_delete").performClick()
            composeRule.onNodeWithTag("group_delete_confirm").assertIsDisplayed()
            assertNotNull(appDb.bookGroupDao.getByID(first.groupId))
            composeRule.onNodeWithTag("group_delete_confirm").performClick()
            await { appDb.bookGroupDao.getByID(first.groupId) == null }
            assertEquals(0L, appDb.bookDao.getBook(book.bookUrl)!!.group)
            assertNotNull(appDb.bookGroupDao.getByID(second.groupId))
        }
    }

    @Test fun detailSelectionCancelsWithoutWritingAndRestoresBeforeConfirming() {
        launchInfo().use { scenario ->
            infoReady(scenario)
            scenario.onActivity { model(it).openGroupSelection() }
            await { state(scenario).groupsEditor?.loading == false }
            scrollTo(second.groupId)
            composeRule.onNodeWithTag("group_row_${second.groupId}").performClick()
            composeRule.onNodeWithTag("group_cancel").performClick()
            await { state(scenario).groupsEditor == null }
            assertEquals(first.groupId, appDb.bookDao.getBook(book.bookUrl)!!.group)
            scenario.onActivity { model(it).openGroupSelection() }
            scrollTo(second.groupId)
            composeRule.onNodeWithTag("group_row_${second.groupId}").assertIsOff().performClick()
            scenario.recreate()
            scrollTo(second.groupId)
            composeRule.onNodeWithTag("group_row_${second.groupId}").assertIsOn()
            composeRule.onNodeWithTag("group_confirm").performClick()
            await { state(scenario).groupsEditor == null }
            assertEquals(first.groupId or second.groupId, appDb.bookDao.getBook(book.bookUrl)!!.group)
        }
    }

    @Test fun selectingAGroupAddsTheEntirePreviewToTheShelfAtomically() {
        book.type = BookType.text or BookType.notShelf; book.group = 0
        appDb.bookDao.update(book)
        launchInfo().use { scenario ->
            infoReady(scenario)
            scenario.onActivity { model(it).openGroupSelection() }
            await { state(scenario).groupsEditor?.loading == false }
            scrollTo(first.groupId)
            composeRule.onNodeWithTag("group_row_${first.groupId}").performClick()
            composeRule.onNodeWithTag("group_confirm").performClick()
            await { state(scenario).groupsEditor == null && state(scenario).inBookshelf && !state(scenario).shelfMutation }
            val saved = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(first.groupId, saved.group)
            assertFalse(saved.isNotShelf)
            assertEquals("Retained $id", saved.getCustomVariable())
            assertEquals(1, appDb.bookChapterDao.getChapterList(book.bookUrl).size)
        }
    }

    @Test fun coverPickerCopiesTheImageAndPreservesTheDraftWhenImportFails() {
        val bad = File(context.cacheDir, "bad-$id.txt").apply { writeText("not an image") }; files.add(bad)
        val image = File(context.cacheDir, "cover-$id.png"); files.add(image)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(id.hashCode() or 0xff000000.toInt())
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val selected = AtomicReference(FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", bad))
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) Instrumentation.ActivityResult(Activity.RESULT_OK,
                    Intent().setData(selected.get()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) else null
        }
        instrumentation.addMonitor(monitor)
        try {
            launchManage().use {
                ready(); composeRule.onNodeWithTag("group_add").performClick()
                val name = "Cover $id"
                composeRule.onNodeWithTag("group_name").performTextReplacement(name)
                composeRule.onNodeWithTag("group_cover_pick").performClick()
                await { composeRule.onAllNodesWithTag("group_error").fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNodeWithTag("group_name").assertTextContains(name)
                selected.set(FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", image))
                composeRule.onNodeWithTag("group_cover_pick").performClick()
                await { runCatching { composeRule.onNodeWithTag("group_cover_preview").assertIsDisplayed() }.isSuccess }
                assertTrue(composeRule.onAllNodes(hasText(context.externalFiles.absolutePath, substring = true)).fetchSemanticsNodes().isEmpty())
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(context.cacheDir, "book-group-editor.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { screenshot.recycle() }
                composeRule.onNodeWithTag("group_confirm").performClick()
                await { appDb.bookGroupDao.getByName(name) != null }
                val cover = File(checkNotNull(appDb.bookGroupDao.getByName(name)!!.cover))
                if (cover.canonicalPath !in existingCovers) files.add(cover)
                assertArrayEquals(image.readBytes(), cover.readBytes())
                image.delete()
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun bookshelfManagementOpensTheNewGroupDestination() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfManageRoute())).use { scenario ->
            lateinit var label: String
            lateinit var groupLabel: String
            scenario.onActivity { label = it.getString(R.string.group_manage); groupLabel = it.getString(R.string.group) }
            await { composeRule.onAllNodes(hasContentDescription(groupLabel)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithContentDescription(groupLabel).performClick()
            composeRule.onNodeWithText(label).performClick()
            ready()
            composeRule.onNodeWithTag("book_groups_screen").assertIsDisplayed()
        }
    }

    @Test fun bookshelfHomeOpensTheNewGroupDestination() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute)).use { scenario ->
            lateinit var label: String
            lateinit var more: String
            scenario.onActivity { label = it.getString(R.string.group_manage); more = it.getString(R.string.more_menu) }
            await { composeRule.onAllNodes(hasContentDescription(more)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithContentDescription(more).performClick()
            composeRule.onNodeWithText(label).performScrollTo().performClick()
            ready()
            composeRule.onNodeWithTag("book_groups_screen").assertIsDisplayed()
        }
    }

    @Test fun aReusedGroupBitRejectsTheOldEditorWithoutLosingItsDraft() {
        launchManage().use { scenario ->
            ready(); scrollTo(first.groupId)
            composeRule.onNodeWithTag("group_edit_${first.groupId}").performClick()
            composeRule.onNodeWithTag("group_name").performTextReplacement("Stale draft $id")
            val replacement = runBlocking {
                repository.delete(first.groupId, first.generation)
                repository.save(BookGroupDraft(name = "Replacement $id"))
            }
            assertEquals(first.groupId, replacement.groupId)
            composeRule.onNodeWithTag("group_confirm").performClick()
            await { composeRule.onAllNodesWithTag("group_error").fetchSemanticsNodes().isNotEmpty() }
            lateinit var message: String
            scenario.onActivity { message = it.getString(R.string.group_changed) }
            composeRule.onNodeWithText(message).assertIsDisplayed()
            composeRule.onNodeWithTag("group_name").assertTextContains("Stale draft $id")
            assertEquals("Replacement $id", appDb.bookGroupDao.getByID(first.groupId)!!.groupName)
            composeRule.onNodeWithTag("group_delete").performScrollTo().performClick()
            composeRule.onNodeWithTag("group_delete_confirm").performClick()
            await { composeRule.onAllNodesWithTag("group_delete_confirm").fetchSemanticsNodes().isEmpty() }
            composeRule.onNodeWithText(message).assertIsDisplayed()
            assertEquals("Replacement $id", appDb.bookGroupDao.getByID(first.groupId)!!.groupName)
            composeRule.onNodeWithTag("group_cancel").performClick()
            ready()
        }
    }

    private fun launchManage() = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookGroupsRoute))
    private fun launchInfo() = ActivityScenario.launch<BookInfoActivity>(Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl))
    private fun model(activity: BookInfoActivity) = ViewModelProvider(activity)[BookInfoViewModel::class.java]
    private fun state(scenario: ActivityScenario<BookInfoActivity>): BookInfoUiState {
        lateinit var result: BookInfoUiState; scenario.onActivity { result = model(it).uiState.value }; return result
    }
    private fun infoReady(scenario: ActivityScenario<BookInfoActivity>) = await { state(scenario).book != null && state(scenario).chapters.size == 1 }
    private fun ready() = await { runCatching { composeRule.onNodeWithTag("group_add").assertIsEnabled() }.isSuccess }
    private fun scrollTo(id: Long) { composeRule.onNodeWithTag("group_list").performScrollToNode(hasTestTag("group_row_$id")) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Group UI did not reach expected state", condition())
        instrumentation.waitForIdleSync()
    }
}
