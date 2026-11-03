package io.legado.app.ui.book.manage

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
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
        ).use {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.bookshelf_management)).assertIsDisplayed()
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

    @Test
    fun longPressingAShelfBookOpensItsDetailsInsideTheMainActivity() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val owner = io.legado.app.ui.navigation.navigationViewModelStores(activity, io.legado.app.ui.navigation.BookshelfRoute)
                    .getOrCreateOwner(androidx.navigation3.runtime.NavEntry(io.legado.app.ui.navigation.BookshelfRoute) {}.contentKey)
                androidx.lifecycle.ViewModelProvider(owner)[io.legado.app.ui.main.bookshelf.BookshelfRootViewModel::class.java].openGroup(group)
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(book.name).performTouchInput { longClick() }
            composeRule.waitUntil(10_000) { var opened = false; scenario.onActivity { opened = it.currentDestination is io.legado.app.ui.navigation.BookInfoRoute }; opened }
            scenario.onActivity { org.junit.Assert.assertEquals(book.bookUrl, (it.currentDestination as io.legado.app.ui.navigation.BookInfoRoute).bookUrl) }
        }
    }

}
