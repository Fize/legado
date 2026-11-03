package io.legado.app.ui.lifecycle

import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Opt-in host probe. It binds real task resize, Activity recreation and an actual
 * host SIGKILL to one production shelf -> mature text reader path and one anchor.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleContinuityUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences

    private fun arguments(): Pair<String, String> {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("lifecycleContinuity") == "yes") {
            "Run through tools/check_android_lifecycle_continuity.py"
        }
        val id = requireNotNull(args.getString("lifecycleContinuityId"))
        require(UUID.fromString(id).toString() == id)
        return id to requireNotNull(args.getString("lifecycleContinuityMode"))
    }

    private fun fixtureRoot(id: String) =
        File(context.filesDir, "compat-lifecycle-continuity/$id").apply { check(mkdirs() || isDirectory) }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    private fun resumedApp(): AppActivity? {
        var result: AppActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>()
                .firstOrNull()
        }
        return result
    }

    private fun resumedReader(): ReadBookActivity? {
        var result: ReadBookActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>()
                .firstOrNull()
        }
        return result
    }

    private fun capturePreferences() = JSONObject().apply {
        for (key in listOf(PreferKey.autoBackup, PreferKey.showBookshelfRecentReading)) {
            put("pref:$key", preferences.all[key] ?: JSONObject.NULL)
        }
        for (key in listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")) {
            put("local:$key", LocalConfig.all[key] ?: JSONObject.NULL)
        }
    }

    private fun configureDeterministicShelf() {
        check(preferences.edit()
            .putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.showBookshelfRecentReading, true)
            .commit())
        check(LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putInt("readHelpVersion", 1)
            .putInt("readMenuHelpVersion", 1)
            .commit())
    }

    private fun restorePreferences(snapshot: JSONObject) {
        preferences.edit().apply {
            for (key in listOf(PreferKey.autoBackup, PreferKey.showBookshelfRecentReading)) {
                val value = snapshot.opt("pref:$key")
                if (value == null || value == JSONObject.NULL) {
                    remove(key)
                } else if (value is Boolean) {
                    putBoolean(key, value)
                } else {
                    error("Unexpected preference type for $key")
                }
            }
        }.commit()
        LocalConfig.edit().apply {
            for (key in listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")) {
                val value = snapshot.opt("local:$key")
                if (value == null || value == JSONObject.NULL) {
                    remove(key)
                } else if (value is Boolean) {
                    putBoolean(key, value)
                } else if (value is Int) {
                    putInt(key, value)
                } else {
                    error("Unexpected LocalConfig type for $key")
                }
            }
        }.commit()
    }

    private fun createFixture(id: String): Pair<Book, BookChapter> {
        val root = fixtureRoot(id)
        val file = File(root, "lifecycle-continuity.txt")
        file.writeText(
            (0 until 360).joinToString("\n\n") { index ->
                "LIFECYCLE-$id-$index A stable reading anchor across task resize, " +
                    "Activity recreation and an actual process death."
            },
        )
        val book = Book(
            bookUrl = file.absolutePath,
            originName = file.name,
            name = "Lifecycle continuity $id",
            author = "owned lifecycle fixture",
            charset = "UTF-8",
            type = BookType.local or BookType.text,
            latestChapterTime = Long.MAX_VALUE - 2,
            totalChapterNum = 1,
            durChapterIndex = 0,
            durChapterTitle = "Lifecycle chapter",
            durChapterPos = 1600,
            durChapterTime = Long.MAX_VALUE - 1,
        )
        val chapter = BookChapter(
            bookUrl = book.bookUrl,
            url = "lifecycle-continuity-$id",
            title = "Lifecycle chapter",
            index = 0,
            start = 0,
            end = file.length(),
        )
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        return book to chapter
    }

    private fun waitForReader(book: Book, expectedAnchor: Int? = null): ReadBookActivity {
        lateinit var activity: ReadBookActivity
        compose.waitUntil(30_000) {
            activity = resumedReader() ?: return@waitUntil false
            var ready = false
            instrumentation.runOnMainSync {
                val view = activity.findViewById<ReadView>(R.id.read_view)
                val page = view.curPage.textPage
                ready = ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.curTextChapter?.isCompleted == true &&
                    !page.isMsgPage &&
                    page.lines.isNotEmpty() &&
                    (expectedAnchor == null || page.containPos(expectedAnchor))
            }
            ready
        }
        return activity
    }

    private fun openFromBookshelf(book: Book): Pair<ActivityScenario<AppActivity>, ReadBookActivity> {
        val scenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute))
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("bookshelf_continue_reading").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bookshelf_continue_reading").assertIsDisplayed().performClick()
        return scenario to waitForReader(book)
    }

    private fun pageContains(activity: ReadBookActivity, url: String, chapterUrl: String, anchor: Int): Boolean {
        var contains = false
        instrumentation.runOnMainSync {
            if (ReadBook.book?.bookUrl == url &&
                ReadBook.curTextChapter?.chapter?.url == chapterUrl
            ) {
                contains = activity.findViewById<ReadView>(R.id.read_view).curPage.textPage.containPos(anchor)
            }
        }
        return contains
    }

    @Test
    fun prepareActualResizeRecreationAndHoldForHostKill() {
        val (id, mode) = arguments()
        assertEquals("resize", mode)
        assertTrue(
            "The actual resize phase requires freeform window management",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
        )
        prepare(id, "resize")
    }

    @Test
    fun prepareAvailableRecreationAndHoldForHostKill() {
        val (id, mode) = arguments()
        assertEquals("recreate", mode)
        prepare(id, "recreate")
    }

    private fun prepare(id: String, mode: String) {
        val root = fixtureRoot(id)
        val preferenceSnapshot = capturePreferences()
        configureDeterministicShelf()
        val (book, chapter) = createFixture(id)
        File(root, "fixture.json").writeText(
            JSONObject()
                .put("id", id)
                .put("book_url", book.bookUrl)
                .put("preferences", preferenceSnapshot)
                .toString(2),
        )
        val marker = JSONObject()
            .put("id", id)
            .put("status", "running")
            .put("pid", Process.myPid())
            .put("book_url", book.bookUrl)
            .put("book_name", book.name)
            .put("chapter_index", chapter.index)
            .put("chapter_url", chapter.url)
            .put("mode", mode)
            .put("preferences", preferenceSnapshot)

        val opened: ReadBookActivity
        if (mode == "resize") {
            val destination = AppActivity.startIntent(context, BookshelfRoute)
                .toUri(Intent.URI_INTENT_SCHEME)
            val result = shell("am start --windowingMode 5 $destination")
            check(!result.contains("Error") && !result.contains("Exception")) { result }
            compose.waitUntil(25_000) { resumedApp()?.isInMultiWindowMode == true }
            compose.waitUntil(25_000) {
                compose.onAllNodesWithTag("bookshelf_continue_reading").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("bookshelf_continue_reading").assertIsDisplayed().performClick()
            opened = waitForReader(book)
        } else {
            val openedFromShelf = openFromBookshelf(book)
            opened = openedFromShelf.second
        }

        var anchor = 0
        var beforeResizeIdentity = 0
        var afterResizeIdentity = 0
        var beforeRecreateIdentity = 0
        var afterRecreateIdentity = 0
        instrumentation.runOnMainSync {
            anchor = ReadBook.durChapterPos
            beforeResizeIdentity = System.identityHashCode(opened)
            afterResizeIdentity = beforeResizeIdentity
        }
        assertTrue("The fixture must start at a non-zero anchor", anchor > 0)
        assertTrue(
            "The initial page must contain the selected anchor",
            pageContains(opened, book.bookUrl, chapter.url, anchor),
        )

        if (mode == "resize") {
            val density = opened.resources.displayMetrics.density
            val widthPx = (1000 * density).toInt()
            val heightPx = (600 * density).toInt()
            val beforeWidth = opened.window.decorView.width
            val beforeHeight = opened.window.decorView.height
            val result = shell(
                "am task resize ${opened.taskId} 50 50 " +
                    "${50 + widthPx} ${50 + heightPx}",
            )
            check(!result.contains("Error") && !result.contains("Exception")) { result }
            val tolerancePx = (2 * density).toInt()
            compose.waitUntil(20_000) {
                opened.window.decorView.width in widthPx - tolerancePx..widthPx + tolerancePx &&
                    opened.window.decorView.height in heightPx - tolerancePx..heightPx + tolerancePx
            }
            compose.waitUntil(25_000) {
                pageContains(opened, book.bookUrl, chapter.url, anchor)
            }
            assertSame("Actual task resize replaced the reader", opened, resumedReader())
            instrumentation.runOnMainSync { afterResizeIdentity = System.identityHashCode(opened) }
            assertEquals(beforeResizeIdentity, afterResizeIdentity)
            marker
                .put("actual_resize", true)
                .put("task_id", opened.taskId)
                .put("window_before", JSONObject().put("width_px", beforeWidth).put("height_px", beforeHeight))
                .put("window_after", JSONObject().put("width_px", opened.window.decorView.width)
                    .put("height_px", opened.window.decorView.height))
        } else {
            marker.put("actual_resize", false)
        }

        instrumentation.runOnMainSync {
            beforeRecreateIdentity = System.identityHashCode(opened)
            opened.recreate()
        }
        compose.waitUntil(30_000) {
            val replacement = resumedReader()
            var ready = false
            instrumentation.runOnMainSync {
                ready = replacement != null &&
                    replacement !== opened &&
                    !replacement.isDestroyed &&
                    replacement === ReadBook.callBack &&
                    ReadBook.book?.bookUrl == book.bookUrl &&
                    ReadBook.curTextChapter?.chapter?.url == chapter.url &&
                    replacement.findViewById<ReadView>(R.id.read_view)
                        .curPage.textPage.containPos(anchor)
            }
            ready
        }
        val recreated = checkNotNull(resumedReader())
        instrumentation.runOnMainSync { afterRecreateIdentity = System.identityHashCode(recreated) }
        assertNotEquals(beforeRecreateIdentity, afterRecreateIdentity)
        assertSame("The recreated Activity must be current", recreated, resumedReader())
        marker
            .put("activity_identity_before_resize", beforeResizeIdentity)
            .put("activity_identity_after_resize", afterResizeIdentity)
            .put("activity_identity_before_recreate", beforeRecreateIdentity)
            .put("activity_identity_after_recreate", afterRecreateIdentity)
            .put("anchor_chapter_index", chapter.index)
            .put("anchor_chapter_url", chapter.url)
            .put("anchor_position", anchor)
            .put("status", "ready_for_host_kill")
        File(root, "ready.json").writeText(marker.toString(2))

        // The host must verify this exact PID and send SIGKILL. A timeout fails the
        // test instead of letting normal teardown masquerade as process death.
        check(!CountDownLatch(1).await(60, TimeUnit.SECONDS)) {
            "Host did not terminate the prepared app process"
        }
    }

    @Test
    fun verifyRecoveryAfterRealProcessDeath() {
        val (id, _) = arguments()
        val root = fixtureRoot(id)
        val marker = JSONObject(File(root, "ready.json").readText())
        val oldPid = marker.getInt("pid")
        assertNotEquals("Recovery must run in a fresh process", oldPid, Process.myPid())
        val book = requireNotNull(appDb.bookDao.getBook(marker.getString("book_url")))
        val chapterUrl = marker.getString("anchor_chapter_url")
        val anchor = marker.getInt("anchor_position")
        var scenario: ActivityScenario<AppActivity>? = null
        val result = JSONObject()
            .put("id", id)
            .put("old_pid", oldPid)
            .put("new_pid", Process.myPid())
            .put("entry", "production bookshelf continue-reading")
        try {
            val openedFromShelf = openFromBookshelf(book)
            scenario = openedFromShelf.first
            val opened = openedFromShelf.second
            compose.waitUntil(30_000) {
                pageContains(opened, book.bookUrl, chapterUrl, anchor)
            }
            assertSame(opened, resumedReader())
            assertEquals(book.bookUrl, ReadBook.book?.bookUrl)
            assertEquals(marker.getInt("anchor_chapter_index"), ReadBook.durChapterIndex)
            assertTrue(ReadBook.durChapterPos > 0)
            result
                .put("activity_identity", System.identityHashCode(opened))
                .put("chapter_index", ReadBook.durChapterIndex)
                .put("chapter_url", ReadBook.curTextChapter?.chapter?.url)
                .put("reader_position", ReadBook.durChapterPos)
                .put("anchor_position", anchor)
                .put("anchor_page_restored", true)
                .put("status", "passed")
            File(root, "recovered.json").writeText(result.toString(2))
        } finally {
            scenario?.close()
            finishOpenActivities()
        }
    }

    @Test
    fun cleanupFixtureAfterFailure() {
        val (id, _) = arguments()
        finishOpenActivities()
        cleanup(id)
    }

    private fun finishOpenActivities() {
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
            resumed.filterIsInstance<ReadBookActivity>().forEach { it.finish() }
            resumed.filterIsInstance<AppActivity>().forEach { it.finish() }
        }
    }

    private fun cleanup(id: String) {
        val root = fixtureRoot(id)
        val ready = File(root, "ready.json")
        val fixture = File(root, "fixture.json")
        val metadataFile = listOf(ready, fixture).firstOrNull(File::isFile)
        val metadata = metadataFile?.let { JSONObject(it.readText()) }
        val textFile = File(root, "lifecycle-continuity.txt")
        val url = metadata?.optString("book_url")?.takeIf(String::isNotBlank)
            ?: textFile.absolutePath
        if (root.exists()) {
            val book = appDb.bookDao.getBook(url)
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            appDb.bookChapterDao.delByBook(url)
            book?.let {
                appDb.bookDao.delete(it)
                BookHelp.clearCache(it)
            }
            metadata?.optJSONObject("preferences")?.let(::restorePreferences)
        }
        TextFile.clear()
        check(root.deleteRecursively() || !root.exists())
    }
}
