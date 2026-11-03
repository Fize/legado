package io.legado.app.ui.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.main.my.MyAction
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Covers migrated read-record Compose behavior without depending on removed view bindings. */
@RunWith(AndroidJUnit4::class)
class ReadRecordHistoryTest {
    private val context by lazy { composeRule.activity }
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "history:$id", name = "History $id", author = "History Author")
    private val removedAuthor = "History Author 精校文字全本"
    private val combinedAuthor = ReadRecordAuthors.merge(book.author, removedAuthor)
    private val preferences by lazy { composeRule.activity.defaultSharedPreferences }
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private var savedRecords: List<ReadRecord> = emptyList()
    private var savedSort: Int? = null
    private var savedSettings = emptyMap<String, Boolean>()

    @get:Rule
    val composeRule = createAndroidComposeRule<AppActivity>()

    @Before
    fun setUp() {
        savedRecords = appDb.readRecordDao.all
        savedPreferences = preferences.all
        savedSort = LocalConfig.all["readRecordSort"] as? Int
        savedSettings = mapOf(
            "simple" to AppConfig.readRecordSimpleLayout,
            "fixed" to AppConfig.readRecordFixedCard,
            "days" to AppConfig.readRecordUseDays,
            "seconds" to AppConfig.readRecordShowSeconds,
        )
        appDb.readRecordDao.clear()
        appDb.bookDao.insert(book)
        LocalConfig.edit().putInt("readRecordSort", 1).commit()
        AppConfig.readRecordSimpleLayout = false
        AppConfig.readRecordFixedCard = false
        AppConfig.readRecordUseDays = true
        AppConfig.readRecordShowSeconds = true
        preferences.edit().putStringSet(PreferKey.myMoreItems, setOf(
            MyAction.TextTocRuleManage.key,
            MyAction.ReplaceRuleManage.key,
            MyAction.DictionaryRuleManage.key,
        )).commit()
        appDb.readRecordDao.insert(
            ReadRecord(
                deviceId = AppConst.androidId,
                bookName = book.name,
                author = combinedAuthor,
                readTime = 25 * 3600_000L,
                lastRead = 1000,
                lastChapterTitle = "Local chapter",
                lastChapterIndex = 6,
            ),
            ReadRecord(
                deviceId = "remote",
                bookName = book.name,
                author = combinedAuthor,
                readTime = 2 * 3600_000L,
                lastRead = 900,
                lastChapterTitle = "Remote chapter",
                lastChapterIndex = 5,
            ),
            ReadRecord(
                deviceId = AppConst.androidId,
                bookName = book.name,
                author = book.author,
                readTime = 5 * 3600_000L,
                lastRead = 800,
                lastChapterTitle = "Known author chapter",
                lastChapterIndex = 4,
            ),
        )
        repeat(60) { index ->
            appDb.readRecordDao.insert(
                ReadRecord(deviceId = "scroll", bookName = "Scroll row $index", readTime = index + 1L),
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.my)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.read_record)).performClick()
        composeRule.activityRule.scenario.recreate()
        awaitText(book.name)
    }

    @After
    fun tearDown() {
        appDb.bookDao.delete(book)
        appDb.readRecordDao.clear()
        if (savedRecords.isNotEmpty()) appDb.readRecordDao.insert(*savedRecords.toTypedArray())
        preferences.edit().clear().apply {
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
                }
            }
        }.commit()
        savedSort?.let { LocalConfig.edit().putInt("readRecordSort", it).commit() }
        AppConfig.readRecordSimpleLayout = savedSettings["simple"] == true
        AppConfig.readRecordFixedCard = savedSettings["fixed"] == true
        AppConfig.readRecordUseDays = savedSettings["days"] == true
        AppConfig.readRecordShowSeconds = savedSettings["seconds"] != false
    }

    @Test
    fun removingLegacyAuthorKeepsReadingTimeAndMergesIntoKnownAuthor() {
        val totalBefore = appDb.readRecordDao.allTime
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.delete))
            .get(0).performClick()
        composeRule.onNodeWithText(context.getString(R.string.read_record_remove_author)).performClick()
        composeRule.onNodeWithText(removedAuthor).performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.read_record_remove_author_confirm, removedAuthor),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()

        composeRule.waitUntil(10_000) {
            appDb.readRecordDao.getRecords(book.name, combinedAuthor).isEmpty()
        }
        val remaining = appDb.readRecordDao.getRecords(book.name, book.author)
        assertEquals(2, remaining.size)
        assertEquals(32 * 3600_000L, remaining.sumOf(ReadRecord::readTime))
        assertEquals(totalBefore, appDb.readRecordDao.allTime)
    }

    @Test
    fun fixedSummaryStaysVisibleWhileTheComposeHistoryListScrolls() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.more_menu)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.read_record_fixed_card)).performClick()
        pressBack()
        val summary = context.getString(R.string.read_record_book_count, appDb.readRecordDao.allShow.size)
        composeRule.onNodeWithText(summary).assertIsDisplayed()
        composeRule.onRoot().performTouchInput {
            repeat(4) { swipeUp() }
        }
        composeRule.onNodeWithText(summary).assertIsDisplayed()
        composeRule.onAllNodesWithText("Scroll row 0").assertCountEquals(0)
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
