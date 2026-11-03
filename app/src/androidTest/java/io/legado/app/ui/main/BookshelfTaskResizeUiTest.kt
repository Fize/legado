package io.legado.app.ui.main

import android.content.pm.PackageManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.main.bookshelf.BookshelfRootScreen
import io.legado.app.ui.main.bookshelf.BookshelfRootViewModel
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Exercises real WindowManager task bounds, including configurations handled without recreation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class BookshelfTaskResizeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val prefix = "Task resize $id"
    private val oldLayout = AppConfig.bookshelfLayout
    private val oldMargin = AppConfig.bookshelfMargin
    private val oldName = AppConfig.showBookname
    private val preferences = context.defaultSharedPreferences
    private val oldFontScale = preferences.all[PreferKey.fontScale]
    private val oldLanguage = preferences.all[PreferKey.language]
    private lateinit var group: BookGroup
    private var books = emptyList<Book>()
    private var activity: AppActivity? = null
    private val trace = JSONArray()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun resumed(): AppActivity? {
        var result: AppActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>().firstOrNull()
        }
        return result
    }
    @Before fun setup() {
        check(preferences.edit().putInt(PreferKey.fontScale, 12).putString(PreferKey.language, "zh").commit())
        Assume.assumeTrue("Requires a freeform-capable test device", context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        AppConfig.bookshelfLayout = 6; AppConfig.bookshelfMargin = 16; AppConfig.showBookname = 0
        val groupId = (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
        group = BookGroup(groupId, prefix)
        books = (0..71).map { Book(bookUrl = "task-resize-$id-$it", name = "$prefix $it", group = groupId, order = it) }
        appDb.bookGroupDao.insert(group); books.forEach { appDb.bookDao.insert(it) }
        val destination = AppActivity.startIntent(context, AboutRoute).toUri(Intent.URI_INTENT_SCHEME)
        val result = shell("am start --windowingMode 5 $destination")
        check(!result.contains("Error")) { result }
        compose.waitUntil(15_000) { activity = resumed(); activity?.isInMultiWindowMode == true }
        assertEquals(AboutRoute, activity!!.initialDestination)
        instrumentation.runOnMainSync {
            val host = checkNotNull(activity)
            val model = ViewModelProvider(host)[BookshelfRootViewModel::class.java]
            model.openGroup(group)
            host.setContent { MaterialTheme { BookshelfRootScreen({ _, _ -> }, {}, {}, {}, bookshelfViewModel = model) } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("bookshelf_items").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
        books.forEach { appDb.bookDao.delete(it) }
        if (::group.isInitialized) appDb.bookGroupDao.delete(group)
        AppConfig.bookshelfLayout = oldLayout; AppConfig.bookshelfMargin = oldMargin; AppConfig.showBookname = oldName
        preferences.edit().apply {
            if (oldFontScale == null) remove(PreferKey.fontScale) else putInt(PreferKey.fontScale, oldFontScale as Int)
            if (oldLanguage == null) remove(PreferKey.language) else putString(PreferKey.language, oldLanguage as String)
            check(commit())
        }
        File(context.cacheDir, "bookshelf-task-resize.json").writeText(trace.toString(2))
    }
    private fun grid() = compose.onNodeWithTag("bookshelf_items").fetchSemanticsNode()
    private fun itemTitle(node: SemanticsNode): String? {
        val local = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.firstOrNull { it.startsWith(prefix) }
        return local ?: node.children.firstNotNullOfOrNull(::itemTitle)
    }
    private fun resize(widthDp: Int): Int {
        val host = checkNotNull(activity)
        val density = host.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val result = shell("am task resize ${host.taskId} 50 50 ${50 + width} ${50 + (600 * density).toInt()}")
        check(!result.contains("Error") && !result.contains("Exception")) { result }
        compose.waitUntil(10_000) {
            var actual = 0
            instrumentation.runOnMainSync { actual = host.window.decorView.width }
            abs(actual - width) <= density * 2 && abs(compose.onRoot().fetchSemanticsNode().boundsInRoot.width - width) <= density * 2
        }
        assertSame("Task resize unexpectedly recreated the Activity", host, resumed())
        assertTrue("Activity resources retained a stale width after task resize: ${host.resources.configuration.screenWidthDp}dp vs ${host.window.decorView.width / density}dp",
            abs(host.resources.configuration.screenWidthDp - host.window.decorView.width / density) <= 2)
        assertEquals(1.2f, host.resources.configuration.fontScale, 0.01f)
        assertEquals("zh", host.resources.configuration.locales[0].language)
        val root = grid()
        val cells = root.children.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        assertTrue(cells.isNotEmpty())
        cells.forEach {
            assertTrue("Card too narrow: ${it.boundsInRoot.width / density}dp", it.boundsInRoot.width >= 96 * density - 2)
            assertTrue(it.boundsInRoot.left >= root.boundsInRoot.left - 1)
            assertTrue(it.boundsInRoot.right <= root.boundsInRoot.right + 1)
        }
        val columns = cells.map { (it.boundsInRoot.center.x / 2).toInt() }.distinct().size
        trace.put(JSONObject().put("requested_width_dp", widthDp).put("actual_width_px", host.window.decorView.width)
            .put("configuration_width_dp", host.resources.configuration.screenWidthDp).put("columns", columns)
            .put("activity_identity", System.identityHashCode(host)).put("task_id", host.taskId))
        return columns
    }

    @Test fun realTaskResizesReflowCardsAndKeepTheScrolledBookWithoutActivityRecreation() {
        val wide = resize(900)
        compose.onNodeWithTag("bookshelf_items").performScrollToIndex(48)
        compose.waitForIdle()
        val root = grid()
        val anchor = root.children.filter { it.boundsInRoot.top >= root.boundsInRoot.top &&
            it.boundsInRoot.bottom <= root.boundsInRoot.bottom }.firstNotNullOf(::itemTitle)
        for (width in listOf(860, 840, 839, 760, 640, 600, 599, 520, 420, 360, 420, 520, 599, 600, 760, 839, 840, 900)) {
            val columns = resize(width)
            compose.onNodeWithText(anchor).assertIsDisplayed()
            if (width == 360) assertTrue("Narrow window did not reduce columns", columns < wide)
        }
        assertEquals(6, AppConfig.bookshelfLayout)
    }
}
