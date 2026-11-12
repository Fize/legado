package io.legado.app.ui.main

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compat.OrientationWindowSession
import io.legado.app.ui.main.bookshelf.BookshelfRootViewModel
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BookshelfDisplayResizeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private lateinit var group: BookGroup
    private lateinit var book: Book
    @Before fun setup() {
        val groupId = (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
        group = BookGroup(groupId, "Resize group $id")
        book = Book(bookUrl = "display-resize://$id", name = "Resize book $id", group = groupId)
        appDb.bookGroupDao.insert(group); appDb.bookDao.insert(book)
    }
    @After fun cleanup() { if (::book.isInitialized) appDb.bookDao.delete(book); if (::group.isInitialized) appDb.bookGroupDao.delete(group) }
    @Test fun expandedAndCompactDisplaysKeepGroupAndSelection() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute)).use { scenario ->
            var host: AppActivity? = null
            scenario.onActivity { activity ->
                host = activity
                val owner = navigationViewModelStores(activity, BookshelfRoute)
                    .getOrCreateOwner(NavEntry(BookshelfRoute) {}.contentKey)
                ViewModelProvider(owner)[BookshelfRootViewModel::class.java].openGroup(group)
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shelf-menu").performClick()
            compose.onNodeWithTag("shelf-menu-select").performClick()
            compose.onNodeWithText(book.name).performClick()
            fun navPositions(): List<androidx.compose.ui.geometry.Rect> = listOf(R.string.bookshelf, R.string.discovery, R.string.my).map { id ->
                var label = ""; scenario.onActivity { label = it.getString(id) }
                compose.onNode(isSelectable() and hasText(label)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
            OrientationWindowSession("bookshelf-stage5").use { window ->
                for ((width, height) in listOf(1200 to 800, 800 to 1200, 900 to 700)) {
                    try { window.resize(width, height, { scenario.onActivity { host = it }; checkNotNull(host) }) {
                        compose.onNodeWithText(book.name).assertIsDisplayed()
                        compose.onNodeWithTag("shelf-selection-add").assertIsEnabled()
                        val nav = navPositions()
                        assertTrue("Expanded display should use side navigation", abs(nav.last().center.y - nav.first().center.y) > abs(nav.last().center.x - nav.first().center.x))
                        JSONObject().put("book", book.name).put("selected", true)
                    } } catch (error: AssertionError) {
                        var state = ""
                        scenario.onActivity { state = "config=${it.resources.configuration}, view=${it.window.decorView.width}x${it.window.decorView.height}" }
                        throw AssertionError("Resize ${width}x$height failed: $state", error)
                    }
                }
            }
            compose.waitUntil(20_000) { var small = false; scenario.onActivity { small = it.resources.configuration.screenWidthDp < 600 }; small }
            compose.onNodeWithText(book.name).assertIsDisplayed()
            compose.onNodeWithTag("shelf-selection-add").assertIsEnabled()
            val nav = navPositions()
            assertTrue("Compact display should use bottom navigation", abs(nav.last().center.x - nav.first().center.x) > abs(nav.last().center.y - nav.first().center.y))
        }
    }
}
