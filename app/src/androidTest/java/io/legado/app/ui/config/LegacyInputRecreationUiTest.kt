package io.legado.app.ui.config

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.action.ViewActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.permission.PermissionActivity
import io.legado.app.lib.permission.Request
import io.legado.app.lib.permission.RequestPlugins
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.CheckSourceService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LegacyInputRecreationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val landscape = InstrumentationRegistry.getArguments().getString("legacyInputLandscape") == "true"
    private val profileSamples = JSONArray()
    private fun <T : Activity> captureProfile(scenario: ActivityScenario<T>, stage: String) {
        var sample = JSONObject()
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { activity ->
                val config = activity.resources.configuration
                val decor = activity.window.decorView
                ready = decor.width > 0 && decor.height > 0 && (!landscape ||
                    (decor.width > decor.height && config.screenWidthDp > config.screenHeightDp &&
                        config.fontScale == 2f && config.densityDpi == 540))
                sample = JSONObject().put("stage", stage).put("width_px", decor.width).put("height_px", decor.height)
                    .put("width_dp", config.screenWidthDp).put("height_dp", config.screenHeightDp)
                    .put("font_scale", config.fontScale).put("density_dpi", config.densityDpi)
            }
            ready
        }
        profileSamples.put(sample)
    }
    private fun <T : Activity> prepareProfile(scenario: ActivityScenario<T>) {
        // Standalone ActivityScenario has a portrait launcher behind the source screen.
        if (landscape) scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        captureProfile(scenario, "opened")
    }
    private fun allowOwnedNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            // This disposable test package is removed by the outer device cleanup.
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"))
                .use { it.readBytes() }
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    private fun dismissBackgroundPermissionPrompt(): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var shown = false
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PermissionActivity>().firstOrNull { !it.isFinishing }
                shown = activity != null
                if (activity != null) assertEquals(Request.TYPE_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    activity.intent.getIntExtra(PermissionActivity.KEY_INPUT_REQUEST_TYPE, -1))
            }
            shown || RequestPlugins.sRequestCallback == null
        }
        if (shown) {
            Espresso.onView(ViewMatchers.withId(android.R.id.button2)).inRoot(RootMatchers.isDialog())
                .perform(ViewActions.click())
            compose.waitUntil(10_000) { RequestPlugins.sRequestCallback == null }
        }
        return shown
    }
    private fun <T : Activity> ActivityScenario<T>.recordFailures(
        name: String, action: (ActivityScenario<T>) -> Unit
    ) = use { scenario ->
        try {
            action(scenario)
        } catch (error: Throwable) {
            val fragments = JSONArray()
            runCatching {
                scenario.onActivity { activity ->
                    (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.fragments?.forEach { fragment ->
                        val dialog = fragment as? androidx.fragment.app.DialogFragment
                        fragments.put(JSONObject().put("class", fragment.javaClass.name)
                            .put("tag", fragment.tag).put("added", fragment.isAdded)
                            .put("removing", fragment.isRemoving).put("lifecycle", fragment.lifecycle.currentState.name)
                            .put("view_attached", fragment.view?.isAttachedToWindow)
                            .put("dialog_showing", dialog?.dialog?.isShowing))
                    }
                }
            }
            File(context.cacheDir, "legacy-input-$name-failure-profiles.json").writeText(
                JSONObject().put("profiles", profileSamples).put("error", error.toString())
                    .put("fragments", fragments).toString(2))
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                val count = roots.fetchSemanticsNodes().size
                File(context.cacheDir, "legacy-input-$name-failure-tree.txt")
                    .writeText((0 until count).joinToString("\n") { roots[it].printToString() })
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            runCatching {
                captureDisplayScreenshot(automation).let { bitmap ->
                    File(context.cacheDir, "legacy-input-$name-failure.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            for (service in listOf("window displays", "input_method")) runCatching {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("dumpsys $service")
                ).use { it.readBytes() }
                check(bytes.size <= 2 * 1024 * 1024)
                File(context.cacheDir, "legacy-input-$name-failure-${service.substringBefore(' ')}.txt").writeBytes(bytes)
            }
            throw error
        }
    }
    private fun editor() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun draft(value: String, field: SemanticsNodeInteraction = editor()) {
        compose.waitUntil(10_000) {
            runCatching {
                val state = field.fetchSemanticsNode().config
                state.getOrNull(SemanticsProperties.EditableText)?.text == value &&
                    state.getOrNull(SemanticsProperties.InputText)?.text == value
            }.getOrDefault(false)
        }
    }
    private fun cancel() = compose.onNode(
        hasText(context.getString(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())
    ).performClick()
    private fun confirm() = compose.onNode(
        hasText(context.getString(R.string.ok)) and hasClickAction() and hasAnyAncestor(isDialog())
    ).performClick()
    private fun openSourceCheck() {
        val menu = compose.onNodeWithTag("selectionOverflow")
        var previous: String? = null
        var stableSince = SystemClock.uptimeMillis()
        // Compose idleness does not wait for the platform IME to finish resizing
        // the window after a dialog closes. Keep the actual pointer target stable.
        compose.waitUntil(10_000) {
            val bounds = runCatching { menu.fetchSemanticsNode().boundsInWindow }.getOrNull()
            val snapshot = bounds?.toString()
            if (snapshot != previous) { previous = snapshot; stableSince = SystemClock.uptimeMillis() }
            bounds != null && bounds.width > 0 && bounds.height > 0 &&
                SystemClock.uptimeMillis() - stableSince >= 500
        }
        menu.performClick()
        val actionText = context.getString(R.string.check_select_source)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(actionText).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(actionText)
            .performScrollTo().assertIsDisplayed().performClick()
    }
    private fun hideConfigurationIme(scenario: ActivityScenario<BookSourceActivity>) {
        fun visible(): Boolean {
            var result = false
            scenario.onActivity { activity ->
                val config = activity.supportFragmentManager.fragments.filterIsInstance<CheckSourceConfig>().single()
                assertTrue("Hiding the IME must retain the configuration dialog", config.dialog?.isShowing == true)
                result = config.dialog?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            return result
        }
        if (visible()) Espresso.pressBack()
        compose.waitUntil(10_000) { !visible() }
    }

    @Test fun sourceCheckKeywordRestoresDraftAndConfirmsOnlySavedTargets() {
        val id = UUID.randomUUID().toString().take(8)
        assertFalse(Debug.isChecking)
        allowOwnedNotifications()
        val requests = AtomicInteger()
        val requestedKeyword = AtomicReference<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                requestedKeyword.set(session.parameters["key"]?.singleOrNull())
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                    "<article><h2>Owned $id</h2><a href='/book'>Read</a></article>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val source = BookSource("$base/keyword-$id", "keyword-$id",
            customOrder = appDb.bookSourceDao.maxOrder + 1, searchUrl = "$base/search?key={{key}}",
            ruleSearch = SearchRule(bookList = "article", name = "h2@text", bookUrl = "a@href"))
        val untouched = source.copy(bookSourceUrl = "$base/untouched-$id",
            bookSourceName = "untouched-$id", customOrder = source.customOrder + 1)
        val originalKeyword = CheckSource.keyword
        val previousConfig = listOf(CheckSource.checkDomain, CheckSource.checkSearch, CheckSource.checkDiscovery,
            CheckSource.checkInfo, CheckSource.checkCategory, CheckSource.checkContent, CheckSource.wSourceComment)
        val previousTimeout = CheckSource.timeout
        val prefs = context.defaultSharedPreferences
        val previousPrefs = listOf(PreferKey.cronet, PreferKey.autoBackup).associateWith { prefs.all[it] }
        assertTrue(prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false).commit())
        CheckSource.checkDomain = false; CheckSource.checkSearch = true; CheckSource.checkDiscovery = false
        CheckSource.checkInfo = false; CheckSource.checkCategory = false; CheckSource.checkContent = false
        CheckSource.wSourceComment = false; CheckSource.timeout = 20000
        appDb.bookSourceDao.insert(source, untouched)
        val originalSource = GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
        val originalUntouched = GSON.toJson(appDb.bookSourceDao.getBookSource(untouched.bookSourceUrl))
        val originalUntouchedState = checkNotNull(appDb.bookSourceDao.getCheckState(untouched.bookSourceUrl))
        var submitted = false
        try {
            ActivityScenario.launch(BookSourceActivity::class.java).recordFailures("keyword") { scenario ->
                prepareProfile(scenario)
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(source.bookSourceName).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                    hasAnySibling(hasText(source.bookSourceName)), useUnmergedTree = true).performClick()
                openSourceCheck()
                draft(originalKeyword)
                val value = "pending keyword $id"
                editor().performTextReplacement(value)
                repeat(2) { scenario.recreate(); captureProfile(scenario, "cancel-recreated-$it"); draft(value) }
                cancel()
                assertEquals(originalKeyword, CheckSource.keyword)
                assertEquals(originalSource, GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)))
                assertFalse(Debug.isChecking)
                assertEquals(0, requests.get())
                openSourceCheck()
                draft(originalKeyword)
                val confirmedKeyword = "checked-$id"
                editor().performTextReplacement(confirmedKeyword)
                repeat(2) { scenario.recreate(); captureProfile(scenario, "confirm-recreated-$it"); draft(confirmedKeyword) }
                submitted = true
                confirm()
                compose.waitUntil(15_000) {
                    !Debug.isChecking && appDb.bookSourceDao.getCheckState(source.bookSourceUrl)?.status == BookSourceCheckState.PASSED
                }
                assertEquals(confirmedKeyword, CheckSource.keyword)
                assertEquals(confirmedKeyword, requestedKeyword.get())
                assertEquals(1, requests.get())
                assertEquals(originalUntouched, GSON.toJson(appDb.bookSourceDao.getBookSource(untouched.bookSourceUrl)))
                assertEquals(originalUntouchedState, appDb.bookSourceDao.getCheckState(untouched.bookSourceUrl))
                val backgroundPromptCancelled = dismissBackgroundPermissionPrompt()
                openSourceCheck()
                draft(confirmedKeyword)
                editor().performTextReplacement("not committed $id")
                compose.onNode(hasText(context.getString(R.string.check_source_config)) and hasClickAction())
                    .performClick()
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity { activity ->
                        visible = activity.supportFragmentManager.fragments.filterIsInstance<CheckSourceConfig>()
                            .any { it.isAdded && it.dialog?.isShowing == true }
                    }
                    visible
                }
                compose.onNodeWithText(context.getString(R.string.check_source_config)).assertIsDisplayed()
                captureProfile(scenario, "configuration-visible")
                val timeout = (CheckSource.timeout / 1000 + 7).toString()
                compose.onNode(hasSetTextAction()).performTextReplacement(timeout)
                draft(timeout, compose.onNode(hasSetTextAction()))
                hideConfigurationIme(scenario)
                val detail = compose.onNode(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
                        hasAnySibling(hasText(context.getString(R.string.source_check_detail))), useUnmergedTree = true)
                if (landscape) detail.performScrollTo()
                detail.assertIsDisplayed().performClick().assertIsOn()
                repeat(2) {
                    scenario.recreate()
                    captureProfile(scenario, "configuration-recreated-$it")
                    draft(timeout, compose.onNode(hasSetTextAction()))
                    hideConfigurationIme(scenario)
                    if (landscape) detail.performScrollTo()
                    detail.assertIsOn()
                }
                compose.onNode(hasText(context.getString(R.string.ok)) and hasClickAction()).assertIsDisplayed()
                compose.onNode(hasText(context.getString(R.string.cancel)) and hasClickAction())
                    .assertIsDisplayed().performClick()
                assertEquals(confirmedKeyword, CheckSource.keyword)
                assertEquals(1, requests.get())
                assertEquals(20000L, CheckSource.timeout)
                assertFalse(CheckSource.wSourceComment)
                File(context.cacheDir, "legacy-input-keyword-recovery.json").writeText(JSONObject()
                    .put("activity_recreations", 6).put("confirmed_keyword", confirmedKeyword)
                    .put("http_keyword", requestedKeyword.get()).put("http_requests", requests.get())
                    .put("selected_check_passed", true).put("untouched_full_record_unchanged", true)
                    .put("untouched_check_state_unchanged", true).put("configuration_visible_on_current_host", true)
                    .put("notification_permission_granted", Build.VERSION.SDK_INT >= 33).put("profiles", profileSamples)
                    .put("background_permission_prompt_cancelled", backgroundPromptCancelled)
                    .put("configuration_pending_timeout", timeout).put("configuration_pending_detail", true)
                    .put("configuration_cancel_kept_original_settings", true)
                    .put("configuration_did_not_commit_draft", true).toString(2))
            }
        } finally {
            if (submitted && Debug.isChecking) {
                context.stopService(Intent(context, CheckSourceService::class.java))
                compose.waitUntil(10_000) { !Debug.isChecking }
            }
            server.stop()
            appDb.bookSourceDao.delete(source, untouched)
            CheckSource.keyword = originalKeyword
            CheckSource.checkDomain = previousConfig[0]; CheckSource.checkSearch = previousConfig[1]
            CheckSource.checkDiscovery = previousConfig[2]; CheckSource.checkInfo = previousConfig[3]
            CheckSource.checkCategory = previousConfig[4]; CheckSource.checkContent = previousConfig[5]
            CheckSource.wSourceComment = previousConfig[6]; CheckSource.timeout = previousTimeout
            assertTrue(prefs.edit().apply { previousPrefs.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value as Boolean)
            } }.commit())
        }
    }

    @Test fun readerCharsetRestoresDraftAndConfirmsOnTheCurrentReader() = verifyReaderCharset(false)

    @Test fun readerGb18030ChineseBodySurvivesDraftRecoveryAndConfirmation() = verifyReaderCharset(true)

    private fun verifyReaderCharset(unicode: Boolean) {
        val file = File.createTempFile("charset-recreation-", ".txt", context.cacheDir)
        val phrase = "中文编码回归，繁體閱讀！扩展𠮷与表情😀。"
        val content = (0..80).joinToString("\n") {
            if (unicode) "$phrase Charset fixture line $it." else "Charset fixture content line $it remains readable."
        }
        val encoding = if (unicode) charset("GB18030") else Charsets.UTF_8
        file.writeBytes(content.toByteArray(encoding))
        val bytes = file.readBytes()
        assertEquals(content, bytes.toString(encoding))
        if (unicode) {
            assertFalse(bytes.contentEquals(content.toByteArray(Charsets.UTF_8)))
            assertEquals(4, "𠮷".toByteArray(encoding).size)
            assertEquals(4, "😀".toByteArray(encoding).size)
        }
        fun readerBody() = ReadBook.curTextChapter?.pages?.joinToString("") { it.text }
            .orEmpty().filterNot(Char::isWhitespace)
        val savedHelp = LocalConfig.all["readMenuHelpVersion"]
        val preferences = context.defaultSharedPreferences
        val savedOrientation = preferences.all[PreferKey.screenOrientation]
        if (landscape) assertTrue(preferences.edit().putString(PreferKey.screenOrientation, "2").commit())
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        val untouched = book.copy(bookUrl = file.absolutePath + "-untouched", name = "Untouched ${file.name}", charset = "UTF-16")
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        appDb.bookDao.insert(book, untouched)
        val originalUntouched = GSON.toJson(appDb.bookDao.getBook(untouched.bookUrl))
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "charset-fixture",
            title = "Charset fixture", start = 0L, end = file.length()))
        try {
            ActivityScenario.launch<ReadBookActivity>(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl)).recordFailures("charset") { scenario ->
                prepareProfile(scenario)
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                        .forEach { it.dismissAllowingStateLoss() }
                }
                compose.waitUntil(20_000) {
                    ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true
                }
                if (unicode) {
                    compose.waitUntil(20_000) { '\uFFFD' in readerBody() && phrase !in readerBody() }
                }
                scenario.onActivity { it.showCharsetConfig() }
                draft("UTF-8")
                val value = "UTF-8 pending draft"
                editor().performTextReplacement(value)
                repeat(2) { scenario.recreate(); captureProfile(scenario, "cancel-recreated-$it"); draft(value) }
                cancel()
                assertEquals(book.bookUrl, ReadBook.book?.bookUrl)
                assertEquals("UTF-8", ReadBook.book?.charset)
                assertEquals("UTF-8", appDb.bookDao.getBook(book.bookUrl)?.charset)
                if (unicode) {
                    assertTrue("Cancellation must keep the deliberately wrong UTF-8 decoding", '\uFFFD' in readerBody())
                    assertFalse(phrase in readerBody())
                }
                assertArrayEquals(bytes, file.readBytes())
                scenario.onActivity { it.showCharsetConfig() }
                draft("UTF-8")
                editor().performTextReplacement("GB18030")
                repeat(2) { scenario.recreate(); captureProfile(scenario, "confirm-recreated-$it"); draft("GB18030") }
                scenario.onActivity { assertSame("Charset action must use the current reader", it, ReadBook.callBack) }
                confirm()
                compose.waitUntil(20_000) {
                    ReadBook.book?.bookUrl == book.bookUrl && ReadBook.book?.charset == "GB18030" &&
                        appDb.bookDao.getBook(book.bookUrl)?.charset == "GB18030" && ReadBook.curTextChapter?.isCompleted == true &&
                        (!unicode || phrase in readerBody() && '\uFFFD' !in readerBody())
                }
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(originalUntouched, GSON.toJson(appDb.bookDao.getBook(untouched.bookUrl)))
                scenario.onActivity { it.showCharsetConfig() }
                draft("GB18030")
                cancel()
                val report = JSONObject()
                    .put("activity_recreations", 4).put("confirmed_charset", "GB18030")
                    .put("current_reader_callback", true).put("book_url", book.bookUrl)
                    .put("database_charset", appDb.bookDao.getBook(book.bookUrl)?.charset)
                    .put("source_file_bytes_unchanged", true).put("untouched_full_record_unchanged", true)
                    .put("profiles", profileSamples)
                    .put("reopened_charset", "GB18030")
                if (unicode) {
                    val body = readerBody()
                    val start = body.indexOf(phrase)
                    assertTrue("Decoded reader body lost the Unicode fixture", start >= 0)
                    report.put("fixture_encoding", "GB18030").put("unicode_expected", phrase)
                        .put("unicode_body_excerpt", body.substring(start, start + phrase.length))
                        .put("wrong_encoding_verified", true).put("unicode_body_confirmed", true)
                }
                File(context.cacheDir, if (unicode) "legacy-input-charset-unicode-recovery.json"
                    else "legacy-input-charset-recovery.json").writeText(report.toString(2))
            }
        } finally {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            appDb.bookDao.delete(untouched)
            TextFile.clear()
            file.delete()
            if (landscape) assertTrue(preferences.edit().apply {
                if (savedOrientation == null) remove(PreferKey.screenOrientation)
                else putString(PreferKey.screenOrientation, savedOrientation as String)
            }.commit())
            LocalConfig.edit().apply {
                if (savedHelp == null) remove("readMenuHelpVersion")
                else putInt("readMenuHelpVersion", savedHelp as Int)
            }.commit()
        }
    }
}
