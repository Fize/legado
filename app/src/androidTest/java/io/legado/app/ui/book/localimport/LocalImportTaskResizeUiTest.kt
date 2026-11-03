package io.legado.app.ui.book.localimport

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.ImportLocalBookRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Production navigation and importer, actual freeform task resize, no setContent replacement. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class LocalImportTaskResizeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "compact-import-$id")
    private val root = File(directory, "input")
    private val files = (0..119).map { File(root, "book-$it-$id.txt") }
    private val preferences = context.defaultSharedPreferences
    private val old = listOf(PreferKey.autoBackup, PreferKey.defaultBookTreeUri, "importBookPath", PreferKey.localBookImportSort, PreferKey.bookImportFileName)
        .associateWith { preferences.all[it] }
    private val localOld = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private var activity: AppActivity? = null
    private val trace = JSONArray()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun resumed(): AppActivity? {
        var found: AppActivity? = null
        instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<AppActivity>().firstOrNull() }
        return found
    }
    private fun model() = ViewModelProvider(navigationViewModelStores(checkNotNull(activity), BookshelfRoute)
        .getOrCreateOwner(NavEntry(ImportLocalBookRoute) {}.contentKey))[ImportBookViewModel::class.java]
    private fun state(): LocalBookImportUiState {
        lateinit var result: LocalBookImportUiState
        instrumentation.runOnMainSync { result = model().uiState.value }
        return result
    }
    @Before fun setup() {
        Assume.assumeTrue("Requires actual freeform window support", context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        root.mkdirs(); File(directory, "saved").mkdirs()
        files.forEach { it.writeText("Chapter 1\nCompact window fixture $id\n".repeat(8)) }
        preferences.edit().putBoolean(PreferKey.autoBackup, false).putString(PreferKey.defaultBookTreeUri, File(directory, "saved").path)
            .putString("importBookPath", root.path).putInt(PreferKey.localBookImportSort, 0).remove(PreferKey.bookImportFileName).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
        files.forEach { file -> appDb.bookDao.getBookByFileName(file.name)?.let { book ->
            BookHelp.clearCache(book); appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
        } }
        preferences.edit().apply { old.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value); is Int -> putInt(key, value)
        } } }.commit()
        LocalConfig.edit().apply { localOld.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
        old.forEach { (key, value) -> assertEquals("Preference not restored: $key", value, preferences.all[key]) }
        directory.deleteRecursively()
        File(context.cacheDir, "compact-import-samples.json").writeText(trace.toString(2))
    }
    private fun resize(widthDp: Int, heightDp: Int) {
        val host = checkNotNull(activity); val density = host.resources.displayMetrics.density
        val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
        val result = shell("am task resize ${host.taskId} 50 50 ${50 + width} ${50 + height}")
        check(!result.contains("Error") && !result.contains("Exception")) { result }
        compose.waitUntil(15_000) {
            var w = 0; var h = 0
            instrumentation.runOnMainSync { w = host.window.decorView.width; h = host.window.decorView.height }
            abs(w - width) <= 2 * density && abs(h - height) <= 2 * density && host.resources.configuration.screenWidthDp == widthDp
        }
        compose.waitForIdle(); assertSame("Resize recreated the importer", host, resumed())
        assertEquals(ImportLocalBookRoute, host.currentDestination)
    }
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "compact-import-$name.png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun deviceBack() {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
    }
    private fun assertNativeMenu(label: String, checked: Boolean, sample: JSONObject) {
        fun containsLabel(node: AccessibilityNodeInfo): Boolean = node.text?.toString() == label ||
            (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsLabel) == true }
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isCheckable && containsLabel(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        var native: AccessibilityNodeInfo? = null
        compose.waitUntil(5_000) {
            native = find(instrumentation.uiAutomation.rootInActiveWindow)
            native?.isChecked == checked
        }
        val node = checkNotNull(native)
        val bounds = Rect().also(node::getBoundsInScreen)
        var caption = 0
        var safeTop = 0
        var windowBottom = 0
        instrumentation.runOnMainSync {
            val decor = checkNotNull(activity).window.decorView
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            caption = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.captionBar()).top
            safeTop = origin[1] + caption
            windowBottom = origin[1] + decor.height
        }
        if (Build.VERSION.SDK_INT >= 35) assertTrue("Freeform caption must be observed", caption > 0)
        assertTrue("Menu is covered by the system caption: $bounds, safe top $safeTop", bounds.top >= safeTop)
        assertTrue("Menu extends below the actual window", bounds.bottom <= windowBottom)
        assertTrue("Native menu hit target is clipped: $bounds", bounds.height() >= 48 * activity!!.resources.displayMetrics.density - 1)
        val samples = sample.optJSONArray("row_menu_native_samples") ?: JSONArray().also {
            sample.put("row_menu_native_samples", it)
        }
        samples.put(JSONObject().put("checkable", node.isCheckable).put("checked", node.isChecked)
            .put("caption_inset_px", caption).put("safe_top_px", safeTop)
            .put("window_bottom_px", windowBottom).put("bounds", bounds.toShortString()))
    }
    @Test fun selectionAndListRemainUsableAcrossShortActualWindows() {
        val uri = AppActivity.startIntent(context, ImportLocalBookRoute).toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace)); val launch = shell("am start --windowingMode 5 $uri")
        check(!launch.contains("Error")) { launch }
        compose.waitUntil(20_000) { activity = resumed(); activity?.isInMultiWindowMode == true }
        compose.waitUntil(20_000) { !state().loading && state().entries.size == files.size }
        resize(600, 600)
        val first = state().entries.first().name
        val second = state().entries[1].name
        compose.onNodeWithTag("localImportEntry_$first").performTouchInput { click() }
        compose.onNodeWithTag("localImportEntry_$second").performTouchInput { click() }
        compose.waitUntil(5_000) { state().selected.size == 2 }
        val selected = state().selected
        for ((index, size) in listOf(360 to 480, 320 to 320, 320 to 240, 600 to 320, 840 to 320, 360 to 480).withIndex()) {
            resize(size.first, size.second)
            assertEquals(selected, state().selected)
            val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
            val row = JSONObject().put("width_dp", size.first).put("height_dp", size.second)
                .put("platform_api", Build.VERSION.SDK_INT)
                .put("fixture", id)
                .put("actual_width_px", activity!!.window.decorView.width).put("actual_height_px", activity!!.window.decorView.height)
                .put("font_scale", activity!!.resources.configuration.fontScale).put("density", activity!!.resources.displayMetrics.density)
                .put("task_id", activity!!.taskId).put("activity_identity", System.identityHashCode(activity))
                .put("list_height_px", list.height).put("selection_kept", true)
            trace.put(row); screenshot("window-$index")
            assertTrue("Importer lost its usable list at ${size.first}x${size.second}: $list", list.height >= 72 * activity!!.resources.displayMetrics.density - 1)
            val listNode = compose.onNode(hasScrollToIndexAction())
            listNode.performScrollToNode(hasTestTag("localImportEntry_${state().entries.last().name}"))
            compose.onNodeWithTag("localImportEntry_${state().entries.last().name}").assertIsDisplayed()
            listNode.performScrollToNode(hasTestTag("localImportEntry_$first"))
            compose.onNodeWithTag("localImportEntry_$first").assertIsDisplayed()
            row.put("last_and_first_reachable", true)
            val fixedActionsVisible = runCatching { compose.onNodeWithTag("localImportSelectionActions").assertIsDisplayed() }.isSuccess
            row.put("directory_entries", files.size).put("fixed_actions_visible", fixedActionsVisible)
            if (!fixedActionsVisible) {
                val anchor = compose.onNodeWithTag("localImportEntry_$first").fetchSemanticsNode().boundsInRoot
                val shortcut = compose.onNodeWithTag("localImportRowActions_$first").assertIsDisplayed().assertIsEnabled()
                val touch = shortcut.fetchSemanticsNode().touchBoundsInRoot
                assertTrue(touch.width >= 48 * activity!!.resources.displayMetrics.density - 1 && touch.height >= 48 * activity!!.resources.displayMetrics.density - 1)
                shortcut.performTouchInput { click() }
                compose.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performScrollTo().performTouchInput { click() }
                compose.waitUntil(5_000) { state().groupPrompt != null }
                compose.onNodeWithTag("localImportGroupDialog").assertIsDisplayed()
                deviceBack(); compose.waitUntil(5_000) { state().groupPrompt == null }
                assertEquals(selected, state().selected)
                val after = compose.onNodeWithTag("localImportEntry_$first").fetchSemanticsNode().boundsInRoot
                assertEquals(anchor.top, after.top, 1f); assertEquals(anchor.bottom, after.bottom, 1f)
                shortcut.performTouchInput { click() }
                compose.onNodeWithText(context.getString(R.string.select_all_count, 2, files.size)).performScrollTo()
                assertNativeMenu(context.getString(R.string.select_all_count, 2, files.size), false, row)
                compose.onNodeWithText(context.getString(R.string.select_all_count, 2, files.size)).performScrollTo().performTouchInput { click() }
                compose.waitUntil(5_000) { state().selected.size == files.size }
                shortcut.performTouchInput { click() }
                compose.onNodeWithText(context.getString(R.string.select_all_count, files.size, files.size)).performScrollTo()
                assertNativeMenu(context.getString(R.string.select_all_count, files.size, files.size), true, row)
                compose.onNodeWithText(context.getString(R.string.select_all_count, files.size, files.size)).performScrollTo().performTouchInput { click() }
                compose.waitUntil(5_000) { state().selected.isEmpty() }
                listNode.performScrollToNode(hasTestTag("localImportEntry_$first"))
                compose.onNodeWithTag("localImportEntry_$first").performTouchInput { click() }
                listNode.performScrollToNode(hasTestTag("localImportEntry_$second"))
                compose.onNodeWithTag("localImportEntry_$second").performTouchInput { click() }
                compose.waitUntil(5_000) { state().selected == selected }
                listNode.performScrollToNode(hasTestTag("localImportEntry_$first"))
                shortcut.performTouchInput { click() }
                compose.onNodeWithText(context.getString(R.string.revert_selection)).performScrollTo().performTouchInput { click() }
                compose.waitUntil(5_000) { state().selected.size == files.size - selected.size }
                val next = state().entries.first { it.uri in state().selected }.name
                listNode.performScrollToNode(hasTestTag("localImportEntry_$next"))
                compose.onNodeWithTag("localImportRowActions_$next").performTouchInput { click() }
                compose.onNodeWithText(context.getString(R.string.revert_selection)).performScrollTo().performTouchInput { click() }
                compose.waitUntil(5_000) { state().selected == selected }
                row.put("row_shortcut_used", true).put("shortcut_main_without_list_scroll", true).put("shortcut_select_all_round_trip", true).put("shortcut_reverse_round_trip", true)
            }
            row.put("quick_actions_available", true)
            fun button(text: String) = compose.onNode(hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))))
            fun reveal(text: String) {
                if (runCatching { button(text).assertIsDisplayed() }.isFailure) listNode.performScrollToNode(hasText(text))
                button(text).assertIsDisplayed().assertIsEnabled()
            }
            val reverse = context.getString(R.string.revert_selection)
            reveal(reverse); button(reverse).performTouchInput { click() }
            compose.waitUntil(5_000) { state().selected.size == files.size - selected.size }
            reveal(reverse); button(reverse).performTouchInput { click() }
            compose.waitUntil(5_000) { state().selected == selected }
            val main = context.getString(R.string.add_to_bookshelf)
            reveal(main); button(main).performTouchInput { click() }
            compose.waitUntil(5_000) { state().groupPrompt != null }
            val confirm = compose.onNodeWithTag("localImportGroupConfirm").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().touchBoundsInRoot
            val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            assertTrue("Group confirmation clipped at $size: $confirm outside $dialog", confirm.height >= 48 * activity!!.resources.displayMetrics.density - 1 && confirm.top >= dialog.top - 1 && confirm.bottom <= dialog.bottom + 1)
            deviceBack(); compose.waitUntil(5_000) { state().groupPrompt == null }
            assertEquals(selected, state().selected)
            val selectionMore = hasContentDescription(context.getString(R.string.more_menu)) and hasAnyAncestor(hasTestTag("localImportSelectionActions"))
            if (runCatching { compose.onNode(selectionMore).assertIsDisplayed() }.isFailure) listNode.performScrollToNode(selectionMore)
            compose.onNode(selectionMore).performTouchInput { click() }
            compose.onNodeWithText(context.getString(R.string.delete)).assertIsDisplayed()
            deviceBack(); assertEquals(selected, state().selected)
            row.put("reverse_round_trip", true).put("main_prompt_cancelled", true).put("delete_menu_reachable", true)
        }
        resize(600, 600)
        compose.onNodeWithTag("legadoTopAppBarSearch").performTouchInput { click() }
        compose.onNode(hasSetTextAction()).performTextInput("book")
        closeSoftKeyboard(); compose.waitUntil(5_000) { state().query == "book" }
        resize(320, 240)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextEquals("book")
        assertEquals(selected, state().selected)
        resize(600, 600)
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextEquals("book")
        compose.onNodeWithTag("legadoTopAppBarNavigation").performTouchInput { click() }
        compose.waitUntil(5_000) { state().query.isEmpty() }
        resize(320, 240)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("localImportEntry_$first"))
        compose.onNodeWithTag("localImportRowActions_$first").performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.add_to_bookshelf)).performScrollTo().performTouchInput { click() }
        compose.waitUntil(5_000) { state().groupPrompt != null }
        compose.onNodeWithText(context.getString(R.string.local_import_without_group)).performTouchInput { click() }
        compose.waitUntil(15_000) { !state().working && state().selected.isEmpty() && state().entries.count { it.isOnBookShelf } == 2 }
        state().message?.let { message ->
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(message).assertIsDisplayed() }.isSuccess }
            screenshot("before-delete")
            trace.getJSONObject(trace.length() - 1).put("post_import_feedback_observed", true)
        }
        compose.waitUntil(10_000) { state().message == null }
        compose.waitForIdle()
        val removed = state().entries.first { !it.isOnBookShelf }.name
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("localImportEntry_$removed"))
        compose.onNodeWithTag("localImportEntry_$removed").performTouchInput { click() }
        compose.waitUntil(5_000) { state().selected.size == 1 }
        compose.onNodeWithTag("localImportRowActions_$removed").performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.delete)).performScrollTo().performTouchInput { click() }
        compose.waitUntil(15_000) { !state().working && state().entries.size == files.size - 1 && state().selected.isEmpty() }
        assertFalse(File(root, removed).exists())
        assertTrue(files.filter { it.name != removed }.all { it.isFile })
        for (name in listOf(first, second)) {
            val imported = checkNotNull(appDb.bookDao.getBookByFileName(name))
            val body = checkNotNull(context.contentResolver.openInputStream(imported.getLocalUri())).bufferedReader().use { it.readText() }
            assertTrue(body.contains(id))
        }
        trace.getJSONObject(trace.length() - 1).put("search_draft_kept", true).put("actual_import_completed", true).put("imported_body_kept", true)
            .put("completed_import_window_width_dp", 320).put("completed_import_window_height_dp", 240)
            .put("row_shortcut_delete_completed", true).put("unselected_files_preserved", true)
    }
}
