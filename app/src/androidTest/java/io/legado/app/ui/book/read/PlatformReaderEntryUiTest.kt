package io.legado.app.ui.book.read

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.help.storage.RestoreFileRecovery
import io.legado.app.help.storage.RestoreSettingsTransaction
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.help.storage.backupRestoreMutex
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import kotlinx.coroutines.runBlocking
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlatformReaderEntryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://native-reader-$id.invalid", "Cached fixture")
    private val book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl,
        tocUrl = source.bookSourceUrl + "/toc", name = "Mature reader $id", author = id,
        type = BookType.text, totalChapterNum = 2, durChapterPos = 200)
    private val route = BookInfoRoute(book.bookUrl)
    private var scenario: ActivityScenario<AppActivity>? = null
    private var opened: ReadBookActivity? = null
    @Before fun setup() {
        if (InstrumentationRegistry.getArguments().containsKey("comparisonLabel")) { book.name = "Reader comparison"; book.author = "Fixture author" }
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book)
        repeat(2) { index ->
            val chapter = BookChapter(bookUrl = book.bookUrl, url = book.bookUrl + "/$index", title = "Chapter $index", index = index)
            appDb.bookChapterDao.insert(chapter)
            assertTrue(BookHelp.saveContent(source, book, chapter,
                (0..99).joinToString("\n") { "Paragraph $it with cached reader content and stable positions." }))
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { opened?.takeUnless { it.isDestroyed }?.finish() }
        instrumentation.waitForIdleSync()
        scenario?.close()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        appDb.bookInfoRequestDao.get(route.requestId)?.let { appDb.bookInfoRequestDao.close(it.id, it.ownerToken) }
        appDb.bookDao.delete(book); appDb.bookSourceDao.delete(source); BookHelp.clearCache(book)
    }
    private fun reader(): ReadBookActivity? {
        var result: ReadBookActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>().firstOrNull()
        }
        return result
    }

    @Test fun readerVisualUpgradeAcceptance() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        var label = ""
        scenario!!.onActivity { label = it.getString(R.string.continue_read) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(20_000) {
            opened = reader()
            opened != null && ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true
        }
        val prefix = InstrumentationRegistry.getArguments().getString("comparisonLabel", "current")
        fun capture(stage: String) {
            Thread.sleep(1500)
            instrumentation.uiAutomation.takeScreenshot().let { image ->
                java.io.File(context.getExternalFilesDir(null), "reader-$prefix-$stage.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        }
        val initial = ReadBook.durChapterPos
        capture("body")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.read_view))
            .perform(androidx.test.espresso.action.ViewActions.swipeLeft())
        compose.waitUntil(10_000) { ReadBook.durChapterPos != initial }
        capture("turned")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.read_view))
            .perform(androidx.test.espresso.action.ViewActions.click())
        capture("toolbar")
    }

    @Test fun actualDetailReadingUsesTheMatureHostAndReturnsThroughItsDurableReceipt() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        var label = ""
        scenario!!.onActivity { label = it.getString(R.string.continue_read) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(20_000) {
            opened = reader()
            opened != null && ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter != null &&
                appDb.bookInfoRequestDao.activeChild(route.requestId)?.phase == BookInfoChildPhase.Displayed
        }
        val child = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
        assertNull(appDb.readerSessionDao.get(child.id))
        assertEquals(book.bookUrl, child.returnBookUrl)
        val original = opened!!
        instrumentation.runOnMainSync { original.recreate() }
        compose.waitUntil(20_000) { opened = reader(); opened != null && opened !== original && !opened!!.isDestroyed &&
            ReadBook.callBack === opened && ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter != null }
        instrumentation.runOnMainSync { opened!!.finish() }
        compose.waitUntil(20_000) {
            var atDetails = false
            scenario!!.onActivity { atDetails = it.currentDestination == route }
            atDetails && appDb.bookInfoRequestDao.child(child.id)?.phase == BookInfoChildPhase.Consumed
        }
        assertNotNull(appDb.bookDao.getBook(book.bookUrl))
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    @Test fun detailReaderCatalogEditAndReentryPreserveTheExactAnchorAndConsumeEachReturnOnce() {
        val anchor = 350
        val bookmark = Bookmark(
            bookName = book.name,
            bookAuthor = book.author,
            chapterIndex = 1,
            chapterPos = anchor,
            chapterName = "Chapter 1",
            bookText = "Exact return anchor $id",
        )
        appDb.bookmarkDao.insert(bookmark)
        try {
            scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
            var readingLabel = ""
            var editLabel = ""
            var bookmarkTabLabel = ""
            scenario!!.onActivity {
                readingLabel = it.getString(R.string.continue_read)
                editLabel = it.getString(R.string.edit)
                bookmarkTabLabel = it.getString(R.string.bookmark)
            }

            compose.waitUntil(15_000) { compose.onAllNodesWithText(readingLabel).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(readingLabel).performClick()
            compose.waitUntil(20_000) {
                opened = reader()
                opened != null && ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.curTextChapter?.chapter?.index == 0 &&
                    appDb.bookInfoRequestDao.activeChild(route.requestId)?.phase == BookInfoChildPhase.Displayed
            }
            val firstReader = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
            assertEquals(BookInfoChildKind.Reader, firstReader.kind)
            assertEquals(BookInfoChildPhase.Displayed, firstReader.phase)

            instrumentation.runOnMainSync { opened!!.openChapterList() }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(bookmarkTabLabel).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(bookmarkTabLabel).performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(bookmark.bookText).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(bookmark.bookText).performClick()
            compose.waitUntil(20_000) {
                opened = reader()
                opened != null && ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.durChapterIndex == bookmark.chapterIndex &&
                    ReadBook.durChapterPos == bookmark.chapterPos &&
                    ReadBook.curTextChapter?.chapter?.index == bookmark.chapterIndex
            }
            val catalogReturnReader = opened!!

            instrumentation.runOnMainSync { catalogReturnReader.recreate() }
            compose.waitUntil(20_000) {
                opened = reader()
                opened != null && opened !== catalogReturnReader && !opened!!.isDestroyed &&
                    ReadBook.durChapterIndex == bookmark.chapterIndex &&
                    ReadBook.durChapterPos == bookmark.chapterPos &&
                    ReadBook.curTextChapter?.chapter?.index == bookmark.chapterIndex
            }
            instrumentation.runOnMainSync { opened!!.finish() }
            compose.waitUntil(20_000) {
                var atDetails = false
                scenario!!.onActivity { atDetails = it.currentDestination == route }
                atDetails && appDb.bookInfoRequestDao.child(firstReader.id)?.phase == BookInfoChildPhase.Consumed
            }
            val savedAfterReader = requireNotNull(appDb.bookDao.getBook(book.bookUrl))
            assertEquals(bookmark.chapterIndex, savedAfterReader.durChapterIndex)
            assertEquals(bookmark.chapterPos, savedAfterReader.durChapterPos)

            compose.onNodeWithContentDescription(editLabel).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("book_edit_name").fetchSemanticsNodes().isNotEmpty() }
            val editChild = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
            assertEquals(BookInfoChildKind.Edit, editChild.kind)
            val changedName = "Returned detail $id"
            compose.onNodeWithTag("book_edit_name").performTextReplacement(changedName)
            Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("book_edit_save").performClick()
            compose.waitUntil(20_000) {
                var atDetails = false
                scenario!!.onActivity {
                    atDetails = it.currentDestination == route
                }
                atDetails && appDb.bookDao.getBook(book.bookUrl)?.name == changedName &&
                    appDb.bookInfoRequestDao.child(editChild.id)?.phase == BookInfoChildPhase.Consumed
            }
            compose.onNodeWithText(changedName).assertIsDisplayed()

            compose.onNodeWithText(readingLabel).performClick()
            compose.waitUntil(20_000) {
                opened = reader()
                opened != null && ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.durChapterIndex == bookmark.chapterIndex &&
                    ReadBook.durChapterPos == bookmark.chapterPos &&
                    ReadBook.curTextChapter?.chapter?.index == bookmark.chapterIndex &&
                    appDb.bookInfoRequestDao.activeChild(route.requestId)?.phase == BookInfoChildPhase.Displayed
            }
            val secondReader = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
            assertEquals(BookInfoChildKind.Reader, secondReader.kind)
            assertEquals(BookInfoChildPhase.Displayed, secondReader.phase)
            assertNotEquals(firstReader.id, secondReader.id)

            instrumentation.runOnMainSync { opened!!.finish() }
            compose.waitUntil(20_000) {
                var atDetails = false
                scenario!!.onActivity { atDetails = it.currentDestination == route }
                atDetails && appDb.bookInfoRequestDao.child(secondReader.id)?.phase == BookInfoChildPhase.Consumed
            }
            val finalBook = requireNotNull(appDb.bookDao.getBook(book.bookUrl))
            assertEquals(changedName, finalBook.name)
            assertEquals(bookmark.chapterIndex, finalBook.durChapterIndex)
            assertEquals(bookmark.chapterPos, finalBook.durChapterPos)

            val returns = PlatformReaderReturnRepository(appDb)
            assertFalse(returns.finish(firstReader))
            assertFalse(returns.finish(secondReader))
            assertFalse(returns.acknowledgePosition(secondReader))
            val returnedParent = requireNotNull(appDb.bookInfoRequestDao.get(route.requestId))
            assertNull(appDb.bookInfoRequestDao.consume(
                returnedParent.id,
                returnedParent.ownerToken,
                returnedParent.revision,
                book.bookUrl,
                editChild.id,
                10,
            ))
        } finally {
            appDb.bookmarkDao.delete(bookmark)
        }
    }

    @Test fun matureReaderKeepsItsAnchorWhenLargeScreenIgnoresPortraitLock() {
        io.legado.app.ui.compat.OrientationWindowSession("text-reader").use { windows ->
            val preferences = context.defaultSharedPreferences
            val original = preferences.all[io.legado.app.constant.PreferKey.screenOrientation]
            try {
                preferences.edit().putString(io.legado.app.constant.PreferKey.screenOrientation, "1").commit()
                book.durChapterPos = 3000
                appDb.bookDao.update(book)
                scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
                var label = ""
                scenario!!.onActivity { label = it.getString(R.string.continue_read) }
                compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label).performClick()
                compose.waitUntil(20_000) {
                    opened = reader()
                    opened != null && ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter != null
                }
                compose.waitForIdle()
                val anchor = ReadBook.durChapterPos
                assertTrue("Fixture must open away from chapter start", anchor > 0)
                val child = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
                for ((width, height) in listOf(1280 to 800, 800 to 1280, 900 to 850, 1280 to 800)) {
                    windows.resize(width, height, { reader()!!.also { opened = it } }) {
                        assertEquals(book.bookUrl, ReadBook.book?.bookUrl)
                        assertEquals(0, ReadBook.durChapterIndex)
                        assertEquals("Resize must preserve the raw reading anchor", anchor, ReadBook.durChapterPos)
                        assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child.id)?.phase)
                        org.json.JSONObject().put("openedAnchor", anchor).put("rawAnchor", ReadBook.durChapterPos)
                            .put("chapter", ReadBook.durChapterIndex).put("ownedReturnRequestDisplayed", true)
                    }
                }
                instrumentation.runOnMainSync { opened!!.finish() }
                compose.waitUntil(20_000) { appDb.bookInfoRequestDao.child(child.id)?.phase == BookInfoChildPhase.Consumed }
                assertEquals(anchor, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            } finally {
                preferences.edit().apply {
                    if (original is String) putString(io.legado.app.constant.PreferKey.screenOrientation, original)
                    else remove(io.legado.app.constant.PreferKey.screenOrientation)
                }.commit()
            }
        }
    }

    @Test fun recoveryFailureClosesAnAlreadyVisibleReaderWithoutSavingStaleProgress() {
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(RestoreRecoveryGate.isQuarantined)
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        var label = ""
        scenario!!.onActivity { label = it.getString(R.string.continue_read) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(20_000) {
            opened = reader()
            opened != null && ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter != null
        }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val before = requireNotNull(appDb.bookDao.getBook(book.bookUrl))
        val key = "native-reader-recovery-$id"
        assertFalse(context.defaultSharedPreferences.contains(key))
        try {
            runBlocking {
                backupRestoreMutex.recover {
                    val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
                    transaction.editDefault().putString(key, "pending").apply()
                    instrumentation.runOnMainSync {
                        ReadBook.durChapterPos = 9999
                        RestoreRecoveryGate.requireRecovery()
                    }
                }
            }
            compose.waitUntil(15_000) {
                var recovery = false
                instrumentation.runOnMainSync {
                    recovery = opened!!.isDestroyed && ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it is RestoreRecoveryActivity }
                }
                recovery
            }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            val after = requireNotNull(appDb.bookDao.getBook(book.bookUrl))
            assertEquals(before.durChapterIndex, after.durChapterIndex)
            assertEquals(before.durChapterPos, after.durChapterPos)
            assertEquals(before.durChapterTime, after.durChapterTime)
            assertEquals("pending", context.defaultSharedPreferences.getString(key, null))
        } finally {
            runBlocking { backupRestoreMutex.recover {
                RestoreFileRecovery.recover(context, cleanup = true)
                assertFalse(context.defaultSharedPreferences.contains(key))
                RestoreRecoveryGate.onStartupReady()
            } }
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<RestoreRecoveryActivity>().forEach { it.finish() }
            }
        }
    }

    @Test fun expiredSavedBridgeReturnsWithoutLaunchingAnUnownedReader() {
        val monitor = instrumentation.addMonitor(ReadBookActivity::class.java.name, null, true)
        try {
            scenario = ActivityScenario.launch(AppActivity.startIntent(context, PlatformTextReaderRoute(book.bookUrl, id)))
            compose.waitUntil(10_000) {
                var closed = scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED
                if (!closed) {
                    instrumentation.runOnMainSync {
                        closed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<AppActivity>().any { it.currentDestination == BookshelfRoute }
                    }
                }
                closed
            }
            assertEquals(0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
