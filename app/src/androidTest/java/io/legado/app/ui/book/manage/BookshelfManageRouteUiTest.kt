package io.legado.app.ui.book.manage

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfManageRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfManageRouteUiTest {
    @get:org.junit.Rule(order = 0) val acceptanceTheme = io.legado.app.ui.test.AcceptanceThemeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val group = BookGroup(groupId = 4096L, groupName = "Managed shelf fixture")
    private val book = Book(
        bookUrl = "manage-route://${UUID.randomUUID()}",
        origin = BookType.localTag,
        originName = "Local fixture",
        name = "Managed book fixture",
        author = "Fixture author",
        group = group.groupId,
        type = BookType.local or BookType.text,
    )

    private var previousGroup: BookGroup? = null

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun insertFixtures() = runBlocking {
        previousGroup = appDb.bookGroupDao.getByID(group.groupId)
        appDb.bookGroupDao.insert(group)
        appDb.bookDao.insert(book)
    }

    @After
    fun removeFixtures(): Unit = runBlocking {
        appDb.bookDao.delete(book)
        appDb.bookGroupDao.delete(group)
        previousGroup?.let { appDb.bookGroupDao.insert(it) }
    }

    @Test
    fun typedRouteShowsTheRequestedGroupAndItsBooks() {
        ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookshelfManageRoute(group.groupId)),
        ).use { scenario ->
            var title = ""
            scenario.onActivity { title = it.getString(R.string.bookshelf_management) }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(title).assertIsDisplayed()
            assertTrue(composeRule.onAllNodesWithText(group.groupName).fetchSemanticsNodes().isNotEmpty())
            composeRule.onNodeWithText(book.name).assertIsDisplayed()
            composeRule.onNodeWithText(book.author).assertIsDisplayed()
        }
    }
    @Test
    fun clickingTheManagedTitleOpensItsExactBookInTheSameActivity() {
        val saved = io.legado.app.help.config.AppConfig.openBookInfoByClickTitle
        val duplicate = book.copy(bookUrl = book.bookUrl + "/other", author = "Other fixture author", originName = "Other same-title book")
        appDb.bookDao.insert(duplicate)
        try {
            io.legado.app.help.config.AppConfig.openBookInfoByClickTitle = true
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfManageRoute(group.groupId))).use { scenario ->
                org.junit.Assert.assertNotNull(appDb.bookDao.getBook(book.bookUrl))
                org.junit.Assert.assertNotNull(appDb.bookDao.getBook(duplicate.bookUrl))
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().size == 2 }
                composeRule.onNodeWithTag("bookshelf-manage-title:${duplicate.bookUrl}").performClick()
                composeRule.waitUntil(10_000) { var opened = false; scenario.onActivity { opened = it.currentDestination is io.legado.app.ui.navigation.BookInfoRoute }; opened }
                scenario.onActivity { val detail = it.currentDestination as io.legado.app.ui.navigation.BookInfoRoute
                    org.junit.Assert.assertEquals(duplicate.bookUrl, detail.bookUrl)
                    org.junit.Assert.assertEquals(detail.requestId, detail.previewKey)
                }
            }
        } finally {
            io.legado.app.help.config.AppConfig.openBookInfoByClickTitle = saved
            appDb.bookDao.delete(duplicate)
        }
    }

    private fun enterSelectionAndSelectBook() {
        composeRule.onNodeWithTag("shelf-menu").performClick()
        composeRule.onNodeWithTag("shelf-menu-select").performClick()
        composeRule.onNodeWithTag("shelf-selection-add").assertIsNotEnabled()
        composeRule.onNodeWithText(book.name).performClick()
    }

    @Test
    fun longPressOpensBookDetails() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(book.name).performTouchInput { longClick() }
            composeRule.waitUntil(10_000) {
                var opened = false
                scenario.onActivity {
                    opened = (it.currentDestination as? io.legado.app.ui.navigation.BookInfoRoute)?.bookUrl == book.bookUrl
                }
                opened
            }
        }
    }

    @Test
    fun detailsTransitionKeepsOutgoingShelfAndNavigationBounds() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            var discoveryLabel = ""
            scenario.onActivity { discoveryLabel = it.getString(R.string.discovery) }
            val nav = composeRule.onNode(isSelectable() and hasText(discoveryLabel))
            val navBounds = nav.fetchSemanticsNode().boundsInRoot
            val shelfHeight = composeRule.onNodeWithTag("bookshelf_items").fetchSemanticsNode().boundsInRoot.height
            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.onNodeWithText(book.name).performTouchInput { longClick() }
                composeRule.waitUntil(10_000) {
                    composeRule.mainClock.advanceTimeByFrame()
                    var opened = false
                    scenario.onActivity { opened = it.currentDestination is io.legado.app.ui.navigation.BookInfoRoute }
                    opened
                }
                composeRule.mainClock.advanceTimeByFrame()
                org.junit.Assert.assertEquals(navBounds, nav.fetchSemanticsNode().boundsInRoot)
                org.junit.Assert.assertEquals(shelfHeight, composeRule.onNodeWithTag("bookshelf_items").fetchSemanticsNode().boundsInRoot.height, 1f)
                composeRule.mainClock.advanceTimeBy(1000)
                nav.assertDoesNotExist()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                composeRule.mainClock.advanceTimeBy(1000)
                org.junit.Assert.assertEquals(navBounds, nav.fetchSemanticsNode().boundsInRoot)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun menuSelectsBookAndCancelLeavesItUnchanged() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            enterSelectionAndSelectBook()
            composeRule.onNodeWithTag("shelf-selection-add").assertIsEnabled()
            composeRule.onNodeWithText(book.name).performTouchInput { longClick() }
            composeRule.onNodeWithTag("shelf-selection-add").assertIsNotEnabled()
            composeRule.onNodeWithText(book.name).performClick()
            composeRule.onNodeWithTag("shelf-selection-add").assertIsEnabled()
            composeRule.onNodeWithTag("shelf-selection-cancel").performClick()
            composeRule.onNodeWithTag("shelf-menu").performClick()
            composeRule.onNodeWithTag("shelf-menu-select").performClick()
            composeRule.onNodeWithTag("shelf-selection-add").assertIsNotEnabled()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithTag("shelf-menu").assertIsDisplayed()
            org.junit.Assert.assertEquals(group.groupId, appDb.bookDao.getBook(book.bookUrl)?.group)
        }
    }

    @Test
    fun selectedBookCanBeRemovedFromGroupWithoutRemovingItFromShelf() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            enterSelectionAndSelectBook()
            composeRule.onNodeWithTag("shelf-selection-remove-group").performClick()
            composeRule.onNodeWithTag("shelf-group-choice:${group.groupId}").performClick()
            composeRule.onNodeWithTag("shelf-group-confirm").performClick()
            composeRule.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)?.group == 0L }
            org.junit.Assert.assertNotNull(appDb.bookDao.getBook(book.bookUrl))
        }
    }

    @Test
    fun selectedBookCanBeAddedToHiddenGroup() {
        val hidden = BookGroup(groupId = 8192L, groupName = "Hidden fixture", show = false)
        val previous = appDb.bookGroupDao.getByID(hidden.groupId)
        appDb.bookGroupDao.insert(hidden)
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
                scenario.onActivity { activity ->
                    val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                        .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                    androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
                }
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
                enterSelectionAndSelectBook()
                composeRule.onNodeWithTag("shelf-selection-add").performClick()
                composeRule.onNodeWithTag("shelf-group-choice:${hidden.groupId}").performClick()
                composeRule.onNodeWithTag("shelf-group-confirm").performClick()
                composeRule.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)?.group == (group.groupId or hidden.groupId) }
            }
        } finally {
            appDb.bookGroupDao.delete(hidden)
            previous?.let { appDb.bookGroupDao.insert(it) }
        }
    }

    @Test
    fun removingSelectedBookRequiresConfirmation() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            enterSelectionAndSelectBook()
            composeRule.onNodeWithTag("shelf-selection-remove-shelf").performClick()
            org.junit.Assert.assertNotNull(appDb.bookDao.getBook(book.bookUrl))
            composeRule.onNodeWithTag("shelf-remove-cancel").performClick()
            org.junit.Assert.assertNotNull(appDb.bookDao.getBook(book.bookUrl))
            composeRule.onNodeWithTag("shelf-selection-remove-shelf").performClick()
            composeRule.onNodeWithTag("shelf-remove-confirm").performClick()
            composeRule.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl) == null }
        }
    }

    @Test
    fun selectionSurvivesRotationAndGroupChangeSurvivesRecreation() {
        val preferences = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE)
        val previousTheme = preferences.getString("themeMode", "0")
        preferences.edit().putString("themeMode", "1").commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { io.legado.app.help.config.ThemeConfig.applyDayNightInit(context) }
        val target = BookGroup(groupId = 8192L, groupName = "Recreation fixture")
        val previous = appDb.bookGroupDao.getByID(target.groupId)
        appDb.bookGroupDao.insert(target)
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
                scenario.onActivity { activity ->
                    val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                        .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                    androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
                }
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
                enterSelectionAndSelectBook()
                org.junit.Assert.assertFalse(io.legado.app.help.config.AppConfig.isNightTheme)
                composeRule.onNodeWithTag("shelf-selection-add").assertIsEnabled()
                Thread.sleep(1000)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "bookshelf-light-test.png")).use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                composeRule.waitUntil(10_000) { var landscape = false; scenario.onActivity { landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }; landscape }
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNodeWithTag("shelf-selection-add").assertIsEnabled()
                composeRule.onNodeWithTag("shelf-selection-add").performClick()
                composeRule.onNodeWithTag("shelf-group-choice:${target.groupId}").performClick()
                val lockAcquired = java.util.concurrent.CountDownLatch(1)
                val releaseLock = java.util.concurrent.CountDownLatch(1)
                val locker = Thread {
                    appDb.runInTransaction {
                        lockAcquired.countDown()
                        releaseLock.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                locker.start()
                try {
                    assertTrue(lockAcquired.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    composeRule.onNodeWithTag("shelf-group-confirm").performClick()
                    composeRule.onNodeWithTag("shelf-selection-add").assertIsNotEnabled()
                    scenario.recreate()
                    composeRule.onNodeWithTag("shelf-selection-add").assertIsNotEnabled()
                } finally {
                    releaseLock.countDown()
                    locker.join(5000)
                }
                composeRule.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)?.group == (group.groupId or target.groupId) }
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNodeWithText(book.name).assertIsDisplayed()
            }
        } finally {
            appDb.bookGroupDao.delete(target)
            previous?.let { appDb.bookGroupDao.insert(it) }
            preferences.edit().putString("themeMode", previousTheme).commit()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { io.legado.app.help.config.ThemeConfig.applyDayNightInit(context) }
        }
    }

    @Test
    fun groupingAndRemovalConfirmationSurviveActivityRecreation() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            enterSelectionAndSelectBook()
            composeRule.onNodeWithTag("shelf-selection-add").performClick()
            composeRule.onNodeWithTag("shelf-group-choice:${group.groupId}").performClick()
            composeRule.onNodeWithTag("shelf-group-confirm").assertIsEnabled()
            scenario.recreate()
            composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithTag("shelf-group-confirm").assertIsEnabled() }.isSuccess }
            composeRule.onNodeWithTag("shelf-group-confirm").performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("shelf-selection-add").fetchSemanticsNodes().isEmpty() }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            enterSelectionAndSelectBook()
            composeRule.onNodeWithTag("shelf-selection-remove-shelf").performClick()
            scenario.recreate()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("shelf-remove-cancel").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("shelf-remove-cancel").performClick()
            org.junit.Assert.assertNotNull(appDb.bookDao.getBook(book.bookUrl))
        }
    }

}
