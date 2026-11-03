package io.legado.app.ui.book.read

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Actual freeform reader resize, including pagination and drawn line bounds. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class ReaderTaskResizeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun reader(): ReadBookActivity? {
        var result: ReadBookActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>()
                .firstOrNull()
        }
        return result
    }

    @Test
    fun actualResizeReflowsCurrentPageWithoutClippingOrPositionLoss() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        val file = File.createTempFile("reader-resize-owned-", ".txt", context.cacheDir)
        val lines = (0 until 240).joinToString("\n") {
            "READER-RESIZE line $it " +
                "Reading across real split-screen windows without clipped words or controls."
        }
        file.writeText(lines)
        val book = Book(
            bookUrl = file.absolutePath,
            originName = file.name,
            name = file.name,
            charset = "UTF-8",
            type = BookType.local or BookType.text,
            totalChapterNum = 1,
            durChapterPos = 900,
        )
        var activity: ReadBookActivity? = null
        val evidence = JSONObject()
        try {
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "reader-resize-chapter",
                    title = "Reader resize fixture",
                    start = 0L,
                    end = file.length(),
                )
            )
            val launch = shell(
                "am start --windowingMode 5 -n ${context.packageName}/" +
                    "io.legado.app.ui.book.read.ReadBookActivity --es bookUrl ${file.absolutePath}"
            )
            check(!launch.contains("Error") && !launch.contains("Exception")) { launch }
            await(30_000) { activity = reader(); activity?.isInMultiWindowMode == true }
            val host = checkNotNull(activity)
            await(30_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = ReadBook.book?.bookUrl == book.bookUrl &&
                        ReadBook.curTextChapter?.isCompleted == true &&
                        !host.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage &&
                        host.bottomDialog == 0
                }
                ready
            }

            val density = host.resources.displayMetrics.density
            val initialPosition = ReadBook.durChapterPos
            val chapterUrl = checkNotNull(ReadBook.curTextChapter).chapter.url
            val samples = JSONArray()
            for ((widthDp, heightDp) in listOf(
                900 to 600,
                840 to 360,
                600 to 600,
                599 to 600,
                520 to 600,
                360 to 480,
                900 to 600,
            )) {
                val widthPx = (widthDp * density).toInt()
                val heightPx = (heightDp * density).toInt()
                val resize = shell(
                    "am task resize ${host.taskId} 50 50 " +
                        "${50 + widthPx} ${50 + heightPx}"
                )
                check(!resize.contains("Error") && !resize.contains("Exception")) { resize }
                val tolerancePx = (2 * density).toInt()
                await(15_000) {
                    host.window.decorView.width in widthPx - tolerancePx..widthPx + tolerancePx &&
                        host.window.decorView.height in heightPx - tolerancePx..heightPx + tolerancePx
                }

                var trace: JSONObject? = null
                await(30_000) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        assertSame(
                            "Actual task resize replaced the reader",
                            host,
                            ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<ReadBookActivity>()
                                .firstOrNull(),
                        )
                        val readView = host.findViewById<ReadView>(R.id.read_view)
                        val contentView = readView.curPage.findViewById<ContentTextView>(
                            R.id.content_text_view
                        )
                        val page = readView.curPage.textPage
                        val visibleLines = page.lines.filter { it.isVisible(0f) }
                        val maxLineEnd = visibleLines.maxOfOrNull { it.lineEnd } ?: 0f
                        val nextLineEnd = readView.nextPage.textPage.lines
                            .maxOfOrNull { it.lineEnd } ?: 0f
                        val tolerance = 12 * density
                        trace = JSONObject()
                            .put("requested_window_dp", widthDp)
                            .put("requested_height_dp", heightDp)
                            .put("multi_window", host.isInMultiWindowMode)
                            .put("window_px", host.window.decorView.width)
                            .put("window_height_px", host.window.decorView.height)
                            .put("window_dp", contentView.width / density)
                            .put("content_px", contentView.width)
                            .put("provider_width", ChapterProvider.viewWidth)
                            .put("visible_right", ChapterProvider.visibleRight)
                            .put("max_visible_line_end", maxLineEnd.toDouble())
                            .put("next_line_end", nextLineEnd.toDouble())
                            .put("read_position", ReadBook.durChapterPos)
                            .put("initial_read_position", initialPosition)
                            .put("contains_initial_position", page.containPos(initialPosition))
                            .put("chapter_url", ReadBook.curTextChapter?.chapter?.url)
                            .put("initial_chapter_url", chapterUrl)
                            .put("chapter_identity", System.identityHashCode(ReadBook.curTextChapter))
                        File(context.cacheDir, "reader-task-resize-pending.json").writeText(
                            JSONObject()
                                .put("samples", samples)
                                .put("pending", checkNotNull(trace))
                                .toString(2)
                        )
                        ready = ChapterProvider.viewWidth == contentView.width &&
                            page.textChapter === ReadBook.curTextChapter &&
                            page.containPos(initialPosition) &&
                            ReadBook.curTextChapter?.chapter?.url == chapterUrl &&
                            maxLineEnd <= ChapterProvider.visibleRight + tolerance &&
                            nextLineEnd <= ChapterProvider.visibleRight + tolerance
                    }
                    ready
                }
                samples.put(checkNotNull(trace))
            }
            val result = JSONObject()
                .put("task_id", host.taskId)
                .put("activity_identity", System.identityHashCode(host))
                .put("samples", samples)
            assertTrue("Reader resize lost its saved position", initialPosition > 0)
            File(context.cacheDir, "reader-task-resize.json").writeText(result.toString(2))
        } finally {
            instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
        }
    }

    @Test
    fun adaptiveTextColumnsAreComfortableAndDoublePageReturnsToSinglePage() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        val file = File.createTempFile("reader-comfort-owned-", ".txt", context.cacheDir)
        file.writeText(
            (0 until 360).joinToString("\n\n") { index ->
                "COMFORT-$index A readable page keeps a bounded measure, uses the current window, " +
                    "and retains the same source position when a double-page layout becomes single."
            }
        )
        val book = Book(
            bookUrl = file.absolutePath,
            originName = file.name,
            name = file.name,
            charset = "UTF-8",
            type = BookType.local or BookType.text,
            totalChapterNum = 1,
            durChapterPos = 1250,
        )
        val preferences = context.defaultSharedPreferences
        val oldDoublePage = preferences.getString(PreferKey.doublePageHorizontal, null)
        val oldPaddingLeft = ReadBookConfig.paddingLeft
        val oldPaddingRight = ReadBookConfig.paddingRight
        val oldPaddingTop = ReadBookConfig.paddingTop
        val oldPaddingBottom = ReadBookConfig.paddingBottom
        var activity: ReadBookActivity? = null
        try {
            preferences.edit().putString(PreferKey.doublePageHorizontal, "2").commit()
            ReadBookConfig.paddingLeft = 16
            ReadBookConfig.paddingRight = 16
            ReadBookConfig.paddingTop = 16
            ReadBookConfig.paddingBottom = 16
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "reader-comfort-chapter",
                    title = "Reader comfort fixture",
                    start = 0L,
                    end = file.length(),
                )
            )
            val launch = shell(
                "am start --windowingMode 5 -n ${context.packageName}/" +
                    "io.legado.app.ui.book.read.ReadBookActivity --es bookUrl ${file.absolutePath}"
            )
            check(!launch.contains("Error") && !launch.contains("Exception")) { launch }
            await(30_000) { activity = reader(); activity?.isInMultiWindowMode == true }
            val host = checkNotNull(activity)
            await(30_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    val page = host.findViewById<ReadView>(R.id.read_view).curPage.textPage
                    ready = ReadBook.book?.bookUrl == book.bookUrl &&
                        ReadBook.curTextChapter?.isCompleted == true &&
                        !page.isMsgPage && page.lines.size > 3 && host.bottomDialog == 0 &&
                        ChapterProvider.viewWidth > 0
                }
                ready
            }

            val density = host.resources.displayMetrics.density
            val chapterUrl = checkNotNull(ReadBook.curTextChapter).chapter.url
            val position = ReadBook.durChapterPos
            fun resize(widthDp: Int, heightDp: Int) {
                val widthPx = (widthDp * density).toInt()
                val heightPx = (heightDp * density).toInt()
                val result = shell(
                    "am task resize ${host.taskId} 50 50 ${50 + widthPx} ${50 + heightPx}"
                )
                check(!result.contains("Error") && !result.contains("Exception")) { result }
                await(15_000) {
                    host.window.decorView.width in widthPx - (2 * density).toInt()..
                        widthPx + (2 * density).toInt() &&
                        host.window.decorView.height in heightPx - (2 * density).toInt()..
                        heightPx + (2 * density).toInt()
                }
            }

            resize(1280, 800)
            var landscape = JSONObject()
            await(30_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    assertFalse("Actual task resize destroyed the text reader", host.isDestroyed)
                    val page = host.findViewById<ReadView>(R.id.read_view).curPage.textPage
                    val lineEnd = page.lines.filter { it.isVisible(0f) }.maxOfOrNull { it.lineEnd } ?: 0f
                    val columnWidthDp = ChapterProvider.visibleWidth / density
                    landscape = JSONObject()
                        .put("window_width_px", host.window.decorView.width)
                        .put("window_height_px", host.window.decorView.height)
                        .put("provider_width_px", ChapterProvider.viewWidth)
                        .put("provider_height_px", ChapterProvider.viewHeight)
                        .put("double_page", ChapterProvider.doublePage)
                        .put("column_width_dp", columnWidthDp)
                        .put("max_visible_line_end", lineEnd)
                        .put("visible_right", ChapterProvider.visibleRight)
                        .put("read_position", ReadBook.durChapterPos)
                        .put("chapter_url", ReadBook.curTextChapter?.chapter?.url)
                    ready = ChapterProvider.doublePage &&
                        abs(columnWidthDp - 500f) <= 3f &&
                        lineEnd <= ChapterProvider.visibleRight + 12 * density &&
                        page.containPos(position) &&
                        ReadBook.curTextChapter?.chapter?.url == chapterUrl
                }
                ready
            }

            resize(800, 1200)
            var portrait = JSONObject()
            await(30_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    assertFalse("Resize destroyed the text reader", host.isDestroyed)
                    val page = host.findViewById<ReadView>(R.id.read_view).curPage.textPage
                    val lineEnd = page.lines.filter { it.isVisible(0f) }.maxOfOrNull { it.lineEnd } ?: 0f
                    val columnWidthDp = ChapterProvider.visibleWidth / density
                    portrait = JSONObject()
                        .put("window_width_px", host.window.decorView.width)
                        .put("window_height_px", host.window.decorView.height)
                        .put("provider_width_px", ChapterProvider.viewWidth)
                        .put("provider_height_px", ChapterProvider.viewHeight)
                        .put("double_page", ChapterProvider.doublePage)
                        .put("column_width_dp", columnWidthDp)
                        .put("max_visible_line_end", lineEnd)
                        .put("visible_right", ChapterProvider.visibleRight)
                        .put("read_position", ReadBook.durChapterPos)
                        .put("chapter_url", ReadBook.curTextChapter?.chapter?.url)
                    ready = !ChapterProvider.doublePage &&
                        abs(columnWidthDp - 640f) <= 3f &&
                        lineEnd <= ChapterProvider.visibleRight + 12 * density &&
                        page.containPos(position) &&
                        ReadBook.curTextChapter?.chapter?.url == chapterUrl
                }
                ready
            }
            assertTrue(landscape.getBoolean("double_page"))
            assertFalse(portrait.getBoolean("double_page"))
            File(context.cacheDir, "reader-comfort-text-layout.json").writeText(
                JSONObject()
                    .put("task_id", host.taskId)
                    .put("activity_identity", System.identityHashCode(host))
                    .put("landscape", landscape)
                    .put("portrait", portrait)
                    .put("read_position_preserved", true)
                    .put("chapter_preserved", true)
                    .toString(2)
            )
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(context.cacheDir, "reader-comfort-text-portrait.png").outputStream().use {
                        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
            preferences.edit().apply {
                if (oldDoublePage == null) remove(PreferKey.doublePageHorizontal)
                else putString(PreferKey.doublePageHorizontal, oldDoublePage)
            }.commit()
            ReadBookConfig.paddingLeft = oldPaddingLeft
            ReadBookConfig.paddingRight = oldPaddingRight
            ReadBookConfig.paddingTop = oldPaddingTop
            ReadBookConfig.paddingBottom = oldPaddingBottom
            ChapterProvider.upStyle()
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Reader resize condition did not become true", condition())
    }
}
