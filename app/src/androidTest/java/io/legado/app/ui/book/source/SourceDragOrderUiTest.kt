package io.legado.app.ui.book.source

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.book.source.manage.BookSourceActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Reordering regressions exercise the current Compose row menus and persisted database order. */
@RunWith(AndroidJUnit4::class)
class SourceComposeOrderUiTest {
    private val id = UUID.randomUUID().toString()
    private val group = "Compose reorder $id"
    private val sources = listOf(100, 200, 300).mapIndexed { index, order ->
        BookSource(
            bookSourceUrl = "https://compose-reorder.invalid/$id/$index",
            bookSourceName = "Compose reorder $index",
            bookSourceGroup = group,
            bookSourceComment = "Keep metadata $index",
            customOrder = order,
        )
    }
    private val savedHelp = LocalConfig.all["bookSourceHelpVersion"]

    @get:Rule
    val composeRule = createAndroidComposeRule<BookSourceActivity>()

    @Before
    fun setUp() {
        LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
        appDb.bookSourceDao.delete(*sources.toTypedArray())
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        composeRule.activityRule.scenario.recreate()
        filterToFixtureGroup()
        awaitVisibleOrder(sources.map { it.bookSourceName })
    }

    @After
    fun tearDown() {
        appDb.bookSourceDao.delete(*sources.toTypedArray())
        LocalConfig.edit().apply {
            if (savedHelp is Int) putInt("bookSourceHelpVersion", savedHelp)
            else remove("bookSourceHelpVersion")
        }.commit()
    }

    @Test
    fun movingFilteredSourceToBottomKeepsOtherRowsAndMetadataThenSurvivesRecreation() {
        val before = appDb.bookSourceDao.allPart.map { it.bookSourceUrl }
        val source = sources.first()
        composeRule.onNodeWithTag("bookSourceRowMenu:${source.bookSourceUrl}").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.to_bottom)).performClick()

        val expectedVisible = listOf(sources[1].bookSourceName, sources[2].bookSourceName, source.bookSourceName)
        awaitVisibleOrder(expectedVisible)
        awaitDatabaseOrder(before.filter { it != source.bookSourceUrl } + source.bookSourceUrl)
        assertEquals("Keep metadata 0", appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceComment)
        assertEquals(
            before.filter { it != source.bookSourceUrl },
            appDb.bookSourceDao.allPart.map { it.bookSourceUrl }.filter { it != source.bookSourceUrl },
        )

        composeRule.activityRule.scenario.recreate()
        awaitVisibleOrder(expectedVisible)
    }

    private fun filterToFixtureGroup() {
        composeRule.onNodeWithTag("bookSourceSearch").performClick()
        composeRule.onNodeWithTag("legadoSearchField").performTextInput("group:$group")
    }

    private fun awaitVisibleOrder(names: List<String>) {
        composeRule.waitUntil(15_000) {
            val positions = names.mapNotNull { name ->
                runCatching {
                    composeRule.onNodeWithText(name, substring = true).fetchSemanticsNode().boundsInRoot.top
                }.getOrNull()
            }
            positions.size == names.size && positions.zipWithNext().all { (first, second) -> first < second }
        }
    }

    private fun awaitDatabaseOrder(expected: List<String>) {
        composeRule.waitUntil(15_000) { appDb.bookSourceDao.allPart.map { it.bookSourceUrl } == expected }
    }
}
