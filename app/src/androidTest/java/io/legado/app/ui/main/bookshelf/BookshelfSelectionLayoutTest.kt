package io.legado.app.ui.main.bookshelf

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookshelfSelectionLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun listSelectionDoesNotOverlapUnreadCount() = checkLayout(0)
    @Test fun compactListSelectionDoesNotOverlapUnreadCount() = checkLayout(1)
    @Test fun gridSelectionDoesNotOverlapUnreadCount() = checkLayout(3)

    private fun checkLayout(layout: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = Book(bookUrl = "selection-layout", name = "A long book title for selection layout", totalChapterNum = 1000)
        var clicks = 0
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.width(if (layout < 2) 320.dp else 100.dp)) {
                            BookshelfRootItem(
                                item = book,
                                uiState = BookshelfRootUiState(layout = layout, showUnread = true, showBooknameMode = 0),
                                selectionMode = true,
                                selected = false,
                                selectionEnabled = true,
                                onBookClick = { clicks++ },
                                onBookLongClick = {},
                                onGroupClick = {},
                                onGroupLongClick = {},
                            )
                        }
                    }
                }
            }
            val selector = compose.onNodeWithTag("shelf-book-selection:${book.bookUrl}")
            val badge = compose.onNodeWithText(book.getUnreadChapterNum().toString(), useUnmergedTree = true)
            assertFalse(selector.fetchSemanticsNode().boundsInRoot.overlaps(badge.fetchSemanticsNode().boundsInRoot))
            selector.performClick()
            compose.runOnIdle { assertEquals(1, clicks) }
        }
    }
}
