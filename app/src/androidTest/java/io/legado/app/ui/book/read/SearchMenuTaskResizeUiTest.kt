package io.legado.app.ui.book.read

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.ContentSearchResult
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.page.ReadView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Actual freeform reader task with real content-search results. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class SearchMenuTaskResizeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun reader(): ReadBookActivity? {
        var result: ReadBookActivity? = null
        instrumentation.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ReadBookActivity>().firstOrNull() }
        return result
    }
    private fun touch(point: Pair<Float, Float>) {
        val start = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, point.first, point.second, 0)
        val up = MotionEvent.obtain(start, start + 40, MotionEvent.ACTION_UP, point.first, point.second, 0)
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }

    @Test fun searchNavigationSurvivesActualFreeformResizes() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        val help = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
        val file = File.createTempFile("search-window-owned-", ".txt", context.cacheDir)
        file.writeText((0 until 160).joinToString("\n") { "Line $it ${if (it == 10 || it == 90) "needle" else "ordinary"} content." })
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        val trace = JSONArray()
        var host: ReadBookActivity? = null
        try {
            LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "search-window-chapter",
                title = "Search fixture", start = 0L, end = file.length()))
            require(!file.absolutePath.any(Char::isWhitespace))
            val launch = shell("am start --windowingMode 5 -n ${context.packageName}/io.legado.app.ui.book.read.ReadBookActivity --es bookUrl ${file.absolutePath}")
            check(!launch.contains("Error") && !launch.contains("Exception")) { launch }
            compose.waitUntil(30_000) { host = reader(); host?.isInMultiWindowMode == true }
            val activity = checkNotNull(host)
            compose.waitUntil(30_000) {
                var ready = false
                instrumentation.runOnMainSync { ready = ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                    !activity.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && activity.bottomDialog == 0 }
                ready
            }
            instrumentation.runOnMainSync { activity.openSearchActivity("needle") }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("content_search_hit_0").performClick()
            compose.waitUntil(30_000) {
                var ready = false
                instrumentation.runOnMainSync { ready = activity.findViewById<SearchMenu>(R.id.search_menu)?.selectedSearchResult != null }
                ready
            }
            for (widthDp in listOf(360, 599, 600, 839, 840, 900)) {
                val density = activity.resources.displayMetrics.density
                val width = (widthDp * density).toInt(); val height = (600 * density).toInt()
                val resize = shell("am task resize ${activity.taskId} 50 50 ${50 + width} ${50 + height}")
                check(!resize.contains("Error") && !resize.contains("Exception")) { resize }
                compose.waitUntil(15_000) {
                    var ok = false
                    instrumentation.runOnMainSync { ok = abs(activity.window.decorView.width - width) <= 2 * density &&
                        activity.resources.configuration.screenWidthDp == widthDp }
                    ok
                }
                var first: ContentSearchResult? = null
                var upPoint = 0f to 0f
                var downPoint = 0f to 0f
                var widths = emptyList<Int>()
                instrumentation.runOnMainSync {
                    assertTrue("Reader was replaced during resize", ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).contains(activity))
                    val menu = activity.findViewById<SearchMenu>(R.id.search_menu)
                    assertTrue("Search menu is not visible at ${widthDp}dp", menu.bottomMenuVisible)
                    first = checkNotNull(menu.selectedSearchResult)
                    val up = menu.findViewById<View>(R.id.iv_search_content_up)
                    val down = menu.findViewById<View>(R.id.iv_search_content_down)
                    assertTrue(up.isShown && down.isShown && up.isEnabled && down.isEnabled)
                    val actions = listOf(R.id.ll_search_results, R.id.ll_main_menu, R.id.ll_search_exit).map { menu.findViewById<View>(it) }
                    assertTrue(actions.all { it.isShown && it.width >= (48 * density).toInt() })
                    listOf(R.id.tv_search_results, R.id.tv_main_menu, R.id.tv_search_exit).forEach { id ->
                        val label = menu.findViewById<TextView>(id)
                        val layout = label.layout
                        assertTrue("Search menu label is truncated at ${widthDp}dp: ${label.text}",
                            layout != null && layout.getLineEnd(layout.lineCount - 1) == label.text.length &&
                                (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 && layout.getLineWidth(it) <= label.width })
                    }
                    fun center(view: View): Pair<Float, Float> {
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        return (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
                    }
                    upPoint = center(up); downPoint = center(down); widths = actions.map { it.width }
                }
                touch(downPoint)
                compose.waitUntil(10_000) {
                    var changed = false
                    instrumentation.runOnMainSync { changed = activity.findViewById<SearchMenu>(R.id.search_menu)
                        .selectedSearchResult?.let { it.queryIndexInChapter != first?.queryIndexInChapter } == true }
                    changed
                }
                touch(upPoint)
                compose.waitUntil(10_000) {
                    var restored = false
                    instrumentation.runOnMainSync { restored = activity.findViewById<SearchMenu>(R.id.search_menu).selectedSearchResult == first }
                    restored
                }
                instrumentation.runOnMainSync {
                    trace.put(JSONObject().put("width_dp", widthDp).put("window_px", activity.window.decorView.width)
                        .put("font_scale", activity.resources.configuration.fontScale.toDouble())
                        .put("result_position", checkNotNull(first).queryIndexInChapter).put("action_widths", JSONArray(widths))
                        .put("up_touch", JSONArray(listOf(upPoint.first, upPoint.second)))
                        .put("down_touch", JSONArray(listOf(downPoint.first, downPoint.second))))
                }
            }
        } finally {
            File(context.cacheDir, "search-menu-window-trace.json").writeText(trace.toString(2))
            instrumentation.runOnMainSync { host?.takeUnless { it.isDestroyed }?.finish() }
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_content_search_sessions WHERE bookUrl = ?", arrayOf(book.bookUrl))
            appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); file.delete(); TextFile.clear()
            LocalConfig.edit().apply { help.forEach { (key, value) -> if (value == null) remove(key) else putInt(key, value as Int) } }.commit()
        }
    }
}
