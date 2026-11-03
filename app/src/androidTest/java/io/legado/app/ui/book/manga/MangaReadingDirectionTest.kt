package io.legado.app.ui.book.manga

import android.content.Intent
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import androidx.navigation3.runtime.NavEntry
import android.net.Uri
import io.legado.app.constant.AppConst
import io.legado.app.utils.ACache
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import io.legado.app.R
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.repository.MangaReaderPreferences
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class MangaReadingDirectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val mangaPreferencesStore by lazy {
        EntryPointAccessors.fromApplication(
            context,
            MangaReaderPreferencesEntryPoint::class.java,
        ).mangaReaderPreferencesStore()
    }
    private var model: ReadMangaViewModel? = null
    private lateinit var savedMangaPreferences: MangaReaderPreferences
    private val source = BookSource(
        bookSourceUrl = "https://compose-manga-${UUID.randomUUID()}.invalid",
        bookSourceName = "Compose manga fixture",
        bookSourceType = BookSourceType.image,
    )
    private val book = Book(
        bookUrl = "${source.bookSourceUrl}/book",
        tocUrl = "${source.bookSourceUrl}/toc",
        origin = source.bookSourceUrl,
        name = "Compose manga ${UUID.randomUUID()}",
        type = BookType.image,
        totalChapterNum = 3,
        durChapterIndex = 1,
        durChapterPos = 1,
        canUpdate = false,
    )
    private var readerRoute = MangaReaderRoute(book.bookUrl)
    private var scenario: ActivityScenario<AppActivity>? = null
    private var freeformActivity: AppActivity? = null
    private val detailBooks = mutableListOf<Book>()

    @Before
    fun setUp() {
        runBlocking {
            savedMangaPreferences = mangaPreferencesStore.current()
            mangaPreferencesStore.update {
                MangaReaderPreferences(
                    horizontalScrollEnabled = true,
                    doublePageEnabled = false,
                    rightToLeftEnabled = false,
                    titleHidden = true,
                    scaleDisabled = false,
                    pageAnimationDisabled = false,
                    preDownloadCount = 0,
                )
            }
        }
        assertTrue(
            preferences.edit()
                .putBoolean(PreferKey.enableReadRecord, false)
                .putBoolean(PreferKey.syncBookProgress, false)
                .putBoolean(PreferKey.syncBookProgressPlus, false)
                .commit()
        )
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        repeat(3) { chapterIndex ->
            val chapter = BookChapter(
                bookUrl = book.bookUrl,
                url = "${source.bookSourceUrl}/chapter/$chapterIndex",
                title = "Chapter ${chapterIndex + 1}",
                index = chapterIndex,
            )
            appDb.bookChapterDao.insert(chapter)
            val content = (0 until 4).joinToString("\n") { pageIndex ->
                val imageUrl = "${source.bookSourceUrl}/image/$chapterIndex/$pageIndex.png"
                val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xff202020.toInt() + chapterIndex * 0x1010 + pageIndex)
                    val bytes = ByteArrayOutputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                    BookHelp.writeImage(book, imageUrl, bytes)
                } finally {
                    bitmap.recycle()
                }
                "<img src=\"$imageUrl\">"
            }
            assertTrue(BookHelp.saveContent(source, book, chapter, content))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        instrumentation.runOnMainSync {
            freeformActivity?.takeUnless { it.isDestroyed }?.finish()
            freeformActivity = null
        }
        model?.awaitPendingWrites()
        BookHelp.clearCache(book)
        detailBooks.forEach { changed ->
            BookHelp.clearCache(changed); appDb.bookChapterDao.delByBook(changed.bookUrl); appDb.bookDao.delete(changed)
        }
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.openHelper.writableDatabase.execSQL(
            "DELETE FROM reader_sessions WHERE initialBookUrl IN (?, ?, ?)",
            arrayOf(book.bookUrl, "${book.bookUrl}/missing", "${book.bookUrl}/independent"))
        runBlocking { mangaPreferencesStore.update { savedMangaPreferences } }
        assertTrue(preferences.edit().clear().apply {
            savedPreferences.forEach { (key, value) -> putValue(key, value) }
        }.commit())
    }

    @Test
    fun horizontalPagerUsesLogicalPageOrderForLtrAndRtlKeys() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 1)

        toggle(MangaReaderSetting.RightToLeft)
        val previousCommand = model!!.readerUiState.value.commandVersion
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Compose must emit the reader's logical turn command", model!!.readerUiState.value.commandVersion > previousCommand)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 1)
    }

    @Test
    fun defaultChapterCardsAllowTurningBackToImagesAndAcrossChapterBoundaries() {
        runBlocking { mangaPreferencesStore.update { it.copy(titleHidden = false) } }
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 0)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitCard(1, -1)
        compose.onNodeWithText("阅读 Chapter 2").assertIsDisplayed()
        assertEquals(0, checkNotNull(model).snapshot().chapterPosition)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 0)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Chapter 2 1/4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Chapter 2 1/4").assertIsDisplayed()
        repeat(3) { index ->
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitPage(1, index + 1)
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitCard(1, 4)
        assertEquals(3, checkNotNull(model).snapshot().chapterPosition)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitCard(2, -1)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(2, 0)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Chapter 3 1/4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Chapter 3 1/4").assertIsDisplayed()
    }

    private fun awaitCard(chapter: Int, index: Int) {
        compose.waitUntil(20_000) {
            val state = model!!.readerUiState.value
            val item = state.items.getOrNull(state.anchorItemIndex)
            item is io.legado.app.ui.book.manga.entities.ReaderLoading &&
                item.chapterIndex == chapter && item.index == index
        }
    }

    @Test
    fun touchPageTurnsUseTheNewDirectionAfterAnRtlPreferenceChange() {
        launchReader()
        fun tapRight(chapter: Int, page: Int) {
            val description = "Chapter ${chapter + 1} ${page + 1}/4"
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(description).performTouchInput {
                click(Offset(width * 0.9f, height * 0.5f))
            }
            compose.mainClock.advanceTimeBy(500)
        }
        tapRight(1, 1)
        awaitPage(1, 2)
        toggle(MangaReaderSetting.RightToLeft)
        compose.waitUntil(10_000) { model!!.readerUiState.value.rightToLeft }
        tapRight(1, 2)
        awaitPage(1, 1)
        toggle(MangaReaderSetting.RightToLeft)
        compose.waitUntil(10_000) { !model!!.readerUiState.value.rightToLeft }
        tapRight(1, 1)
        awaitPage(1, 2)
    }

    @Test
    fun preloadDialogKeepsItsDraftAcrossRecreationAndPersistsOnlyOnConfirmation() {
        launchReader()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Chapter 2 2/4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Chapter 2 2/4").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500)
        var more = ""
        var preload = ""
        var ok = ""
        scenario!!.onActivity {
            more = it.getString(R.string.reader_menu_more)
            preload = it.getString(R.string.pre_download_m, 0)
            ok = it.getString(android.R.string.ok)
        }
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(preload).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("23")
        assertEquals(0, runBlocking { mangaPreferencesStore.current() }.preDownloadCount)
        scenario!!.recreate()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithText("23").assertIsDisplayed()
        compose.onNodeWithText(ok).performClick()
        compose.waitUntil(10_000) { model!!.readerUiState.value.preDownloadCount == 23 }
        assertEquals(23, runBlocking { mangaPreferencesStore.current() }.preDownloadCount)
        assertEquals(null, model!!.readerUiState.value.dialog)
    }

    @Test
    fun anExplicitMissingBookRequestCannotFallBackToThePreviousReaderBook() {
        launchReader()
        scenario!!.onActivity { activity ->
            readerRoute = MangaReaderRoute("${book.bookUrl}/missing")
            activity.navigateTo(readerRoute)
        }
        compose.waitUntil(10_000) {
            scenario!!.onActivity { model = readerModel(it) }
            model!!.readerUiState.value.loadingErrorMessageRes == R.string.no_book
        }
        assertEquals(null, model!!.snapshot().book)
        assertTrue(model!!.readerUiState.value.items.isEmpty())
        assertEquals(false, model!!.readerUiState.value.canRetryLoading)
        scenario!!.recreate()
        compose.waitUntil(10_000) {
            scenario!!.onActivity { model = readerModel(it) }
            model!!.readerUiState.value.loadingErrorMessageRes == R.string.no_book
        }
        assertEquals(null, model!!.snapshot().book)
        scenario!!.close(); scenario = null
        model!!.awaitPendingWrites()
        appDb.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM reader_sessions WHERE initialBookUrl = ?", arrayOf("${book.bookUrl}/missing")).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun explicitChapterAndPageLaunchArgumentsAreAppliedOnceAndSurviveRecreation() {
        readerRoute = MangaReaderRoute(book.bookUrl, chapterIndex = 2, pageIndex = 2)
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, readerRoute))
        scenario!!.onActivity { model = readerModel(it) }
        awaitPage(2, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(2, 3)
        scenario!!.recreate()
        awaitPage(2, 3)
    }

    @Test
    fun outOfRangeLaunchPositionPersistsTheRenderedPageBeforePause() {
        readerRoute = MangaReaderRoute(book.bookUrl, chapterIndex = 2, pageIndex = 999)
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, readerRoute))
        scenario!!.onActivity { model = readerModel(it) }
        awaitPage(2, 3)
        compose.waitUntil(10_000) {
            appDb.readerSessionDao.get(readerRoute.requestId)?.pageIndex == 3 &&
                appDb.bookDao.getBook(book.bookUrl)?.durChapterPos == 3
        }
    }

    @Test
    fun aSettledImagePersistsItsEntryAndBookPositionBeforeTheActivityPauses() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)?.durChapterPos == 2 }
        appDb.openHelper.readableDatabase.query(
            "SELECT chapterIndex, pageIndex FROM reader_sessions WHERE initialBookUrl = ?",
            arrayOf(book.bookUrl)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0)); assertEquals(2, cursor.getInt(1))
            assertEquals(false, cursor.moveToNext())
        }
    }

    @Test
    fun aFreshViewModelRestoresBookshelfMembershipFromThePersistedBookRatherThanOldLaunchFlags() {
        val preview = book.copy(bookUrl = "${book.bookUrl}/preview", name = "Preview ${book.name}",
            type = BookType.image or BookType.notShelf)
        appDb.bookDao.insert(preview)
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).map { it.copy(bookUrl = preview.bookUrl) }
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        chapters.forEach { chapter ->
            val original = appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index)!!
            BookHelp.saveText(preview, chapter, checkNotNull(BookHelp.getContent(book, original)))
        }
        val request = io.legado.app.data.repository.ReaderOpenRequest(UUID.randomUUID().toString(), preview.bookUrl, inBookshelf = false)
        var first: ReadMangaViewModel? = null
        var restored: ReadMangaViewModel? = null
        try {
            launchReader()
            scenario!!.onActivity {
                first = ViewModelProvider(it)["preview-reader", ReadMangaViewModel::class.java]
                first!!.initialize(request)
            }
            compose.waitUntil(10_000) { first!!.snapshot().book?.bookUrl == preview.bookUrl && !first!!.readerUiState.value.loadingVisible }
            assertEquals(false, first!!.snapshot().inBookshelf)
            scenario!!.onActivity {
                first!!.addCurrentBookToBookshelf()
                restored = ViewModelProvider(it)["restored-preview-reader", ReadMangaViewModel::class.java]
                restored!!.initialize(request)
            }
            compose.waitUntil(10_000) { restored!!.snapshot().book?.bookUrl == preview.bookUrl && !restored!!.readerUiState.value.loadingVisible }
            assertTrue(restored!!.snapshot().inBookshelf)
        } finally {
            scenario?.close(); scenario = null
            restored?.awaitPendingWrites(); first?.awaitPendingWrites()
            BookHelp.clearCache(preview)
            appDb.bookChapterDao.delByBook(preview.bookUrl)
            appDb.bookDao.delete(preview)
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM reader_sessions WHERE id = ?", arrayOf(request.id))
        }
    }

    @Test
    fun closingTheReaderFlushesItsLastPositionAndDisposesOnlyItsDurableEntry() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        scenario!!.close(); scenario = null
        model!!.awaitPendingWrites()
        assertEquals(2, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        appDb.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM reader_sessions WHERE initialBookUrl = ?", arrayOf(book.bookUrl)).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun openingAnotherEntryWaitsForThePreviousReadersQueuedVisiblePosition() {
        launchReader()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocker = io.legado.app.help.globalExecutor.submit { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        var next: ReadMangaViewModel? = null
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitPage(1, 2)
            scenario!!.onActivity {
                next = ViewModelProvider(it)["handoff-reader", ReadMangaViewModel::class.java]
                next!!.initialize(io.legado.app.data.repository.ReaderOpenRequest(UUID.randomUUID().toString(), book.bookUrl))
            }
            assertEquals(null, next!!.snapshot().book)
            release.countDown(); blocker.get(5, java.util.concurrent.TimeUnit.SECONDS)
            compose.waitUntil(10_000) { next!!.snapshot().book?.bookUrl == book.bookUrl && !next!!.readerUiState.value.loadingVisible }
            assertEquals(1, next!!.snapshot().chapterIndex)
            assertEquals(2, next!!.snapshot().chapterPosition)
        } finally { release.countDown(); blocker.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    @Test
    fun catalogSelectionReturnsToTheSameReaderEntryAndConsumesItsDurableResult() {
        launchReader()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chapter 2 2/4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Chapter 2 2/4").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500)
        var catalog = ""
        scenario!!.onActivity { catalog = it.getString(R.string.chapter_list) }
        compose.onNodeWithContentDescription(catalog).performClick()
        compose.waitUntil(10_000) {
            var selected = false
            scenario!!.onActivity { selected = it.currentDestination is BookTocRoute }
            selected
        }
        scenario!!.recreate()
        compose.onNodeWithText("Chapter 3").performClick()
        awaitPage(2, 0)
        scenario!!.onActivity { assertEquals(readerRoute, it.currentDestination) }
        compose.waitUntil(10_000) { appDb.readerSessionDao.pendingCatalog(readerRoute.requestId) == null }
        assertEquals(2, appDb.readerSessionDao.get(readerRoute.requestId)!!.chapterIndex)
    }

    @Test
    fun readerWindowSettingsAreReturnedToTheCatalogAndBookshelf() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, BookshelfRoute))
        scenario!!.onActivity {
            it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.window.attributes = it.window.attributes.apply { screenBrightness = 0.42f }
            it.navigateTo(readerRoute)
        }
        awaitPage(1, 1)
        fun brightness(): Float {
            var value = 0f
            scenario!!.onActivity { value = it.window.attributes.screenBrightness }
            return value
        }
        fun keepOn(): Boolean {
            var value = false
            scenario!!.onActivity { value = it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
            return value
        }
        compose.waitUntil(10_000) { keepOn() }
        scenario!!.onActivity {
            model = readerModel(it)
            model!!.showDialog(MangaReaderDialog.ColorFilter)
            model!!.handleDialogAction(MangaReaderDialogAction.ColorFilter(
                io.legado.app.ui.book.manga.config.MangaColorFilterConfig(l = 77), false))
            model!!.showDialog(null)
            model!!.setMenuVisible(true)
        }
        compose.waitUntil(10_000) { kotlin.math.abs(brightness() - 77 / 255f) < 0.001f }
        var catalogLabel = ""
        scenario!!.onActivity { catalogLabel = it.getString(R.string.chapter_list) }
        compose.onNodeWithContentDescription(catalogLabel).performClick()
        compose.waitUntil(10_000) { !keepOn() }
        assertEquals(0.42f, brightness(), 0.001f)
        scenario!!.onActivity {
            val state = model!!.readerUiState.value
            val late = state.items.filterIsInstance<io.legado.app.ui.book.manga.entities.MangaPage>()
                .first { page -> page.chapterIndex == 1 && page.index == 3 }
            assertEquals(false, model!!.acceptVisibleItem(late, state.contentVersion, state.layoutVersion))
            assertEquals(1, model!!.snapshot().chapterPosition)
        }
        scenario!!.onActivity { model!!.updateReaderState { state -> state.copy(windowBrightness = 220) } }
        compose.waitForIdle()
        assertEquals(0.42f, brightness(), 0.001f)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { keepOn() }
        compose.waitUntil(10_000) { kotlin.math.abs(brightness() - 220 / 255f) < 0.001f }
        scenario!!.onActivity { model!!.setMenuVisible(false) }
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            var atShelf = false
            scenario!!.onActivity { atShelf = it.currentDestination == BookshelfRoute }
            atShelf
        }
        assertEquals(false, keepOn())
        assertEquals(0.42f, brightness(), 0.001f)
    }

    @Test
    fun closingARealReaderPublishesItsDetailReturnAfterItsLastVisiblePage() {
        val parent = UUID.randomUUID().toString()
        val child = UUID.randomUUID().toString()
        val returns = appDb.bookInfoRequestDao
        returns.claim(io.legado.app.data.entities.BookInfoRequest(parent, book.bookUrl, book.bookUrl,
            "detail-owner", updatedAt = 1), "detail-owner")
        returns.begin(parent, "detail-owner", 0, book.bookUrl, child,
            io.legado.app.data.entities.BookInfoChildKind.Reader, null, 2)
        try {
            readerRoute = MangaReaderRoute(book.bookUrl, requestId = child, returnToDetails = true)
            launchReader()
            key(KeyEvent.KEYCODE_PAGE_DOWN); awaitPage(1, 2)
            scenario!!.recreate(); awaitPage(1, 2)
            compose.waitForIdle()
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) {
                returns.child(child)?.phase == io.legado.app.data.entities.BookInfoChildPhase.Returned &&
                    appDb.readerSessionDao.get(child) == null
            }
            assertEquals(2, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertEquals(book.bookUrl, returns.child(child)!!.returnBookUrl)
            assertTrue(returns.child(child)!!.readerInBookshelf)
            assertTrue(returns.consume(parent, "detail-owner", 1, book.bookUrl, child, 10) != null)
        } finally { scenario?.close(); returns.close(parent, "detail-owner") }
    }

    @Test
    fun anExpiredDetailReturnCannotOpenAnUnrelatedStandaloneReader() {
        readerRoute = MangaReaderRoute(book.bookUrl, returnToDetails = true)
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, readerRoute))
        lateinit var expiredMessage: String
        scenario!!.onActivity {
            model = readerModel(it)
            expiredMessage = it.getString(R.string.reader_return_request_expired)
        }
        compose.waitUntil(10_000) {
            model!!.readerUiState.value.loadingErrorMessageRes == R.string.reader_return_request_expired
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(expiredMessage).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithText(expiredMessage).isDisplayed()
        }
        compose.onNodeWithText(expiredMessage).assertIsDisplayed()
        assertEquals(false, model!!.readerUiState.value.canRetryLoading)
        assertNull(appDb.readerSessionDao.get(readerRoute.requestId))
        assertTrue(model!!.readerUiState.value.items.isEmpty())
        assertNull(model!!.snapshot().book)
        assertEquals(1, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test
    fun hardwarePageVolumeAndMenuKeysAreOwnedByTheReaderDestination() {
        launchReader()
        key(KeyEvent.KEYCODE_PAGE_DOWN); awaitPage(1, 2)
        key(KeyEvent.KEYCODE_VOLUME_UP); awaitPage(1, 1)
        key(KeyEvent.KEYCODE_VOLUME_DOWN); awaitPage(1, 2)
        key(KeyEvent.KEYCODE_PAGE_UP); awaitPage(1, 1)
        key(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(10_000) { model!!.readerUiState.value.menuVisible }
        key(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(10_000) { !model!!.readerUiState.value.menuVisible }
        scenario!!.onActivity { assertEquals(readerRoute, it.currentDestination) }
        awaitPage(1, 1)
    }

    @Test
    fun repeatedCatalogClicksCannotInvalidateTheVisibleCatalogRequest() {
        launchReader()
        repeat(3) { round ->
            val chapter = if (round % 2 == 0) 2 else 1
            val chapterTitle = "Chapter ${chapter + 1}"
            scenario!!.onActivity { readerModel(it).setMenuVisible(true) }
            var label = ""
            scenario!!.onActivity { label = it.getString(R.string.chapter_list) }
            compose.waitForIdle()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val blocker = executor.submit { appDb.runInTransaction {
                entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            } }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.onNodeWithContentDescription(label).performClick()
                compose.waitUntil(10_000) { model!!.readerUiState.value.navigationPending }
                compose.onNodeWithContentDescription(label).performClick()
                release.countDown(); blocker.get(5, java.util.concurrent.TimeUnit.SECONDS)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(chapterTitle).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(chapterTitle).performClick()
                awaitPage(chapter, 0)
                compose.waitUntil(10_000) { appDb.readerSessionDao.get(readerRoute.requestId)?.revision == (round + 1) * 2L }
                assertEquals((round + 1) * 2L, appDb.readerSessionDao.get(readerRoute.requestId)!!.revision)
            } finally { release.countDown(); blocker.get(5, java.util.concurrent.TimeUnit.SECONDS); executor.shutdownNow() }
        }
    }

    @Test
    fun doublePageReturnsToABoundedSingleColumnAfterPortraitResize() {
        assumeTrue(
            "Requires a freeform-capable device",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
        )
        runBlocking {
            mangaPreferencesStore.update {
                it.copy(horizontalScrollEnabled = false, doublePageEnabled = true, rightToLeftEnabled = false)
            }
        }
        val uri = AppActivity.startIntent(context, readerRoute).toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace))
        check(!shell("am start --windowingMode 5 $uri").contains("Error"))
        compose.waitUntil(20_000) {
            freeformActivity = resumedApp()
            freeformActivity?.isInMultiWindowMode == true
        }
        val host = checkNotNull(freeformActivity)
        instrumentation.runOnMainSync { model = readerModel(host) }
        compose.waitUntil(20_000) {
            var ready = false
            instrumentation.runOnMainSync {
                model = readerModel(host)
                val visible = visiblePage(host)
                ready = model?.readerUiState?.value?.loadingVisible == false &&
                    visible?.chapterIndex == 1 && visible.index in setOf(0, 1)
            }
            ready
        }
        val density = host.resources.displayMetrics.density

        fun resize(widthDp: Int, heightDp: Int) {
            val widthPx = (widthDp * density).toInt()
            val heightPx = (heightDp * density).toInt()
            val result = shell(
                "am task resize ${host.taskId} 50 50 ${50 + widthPx} ${50 + heightPx}"
            )
            check(!result.contains("Error") && !result.contains("Exception")) { result }
            compose.waitUntil(15_000) {
                kotlin.math.abs(host.window.decorView.width - widthPx) <= 2 * density + 2 &&
                    kotlin.math.abs(host.window.decorView.height - heightPx) <= 2 * density + 2
            }
        }

        resize(1200, 700)
        compose.waitUntil(20_000) {
            val state = model?.readerUiState?.value
            state?.doublePage == true && state.horizontal && host.resources.configuration.screenWidthDp == 1200
        }
        val landscapeState = checkNotNull(model).readerUiState.value
        val landscapePosition = checkNotNull(visiblePage(host)).let { it.chapterIndex to it.index }
        val landscapeDescription = "Chapter ${landscapePosition.first + 1} ${landscapePosition.second + 1}/4"
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(landscapeDescription).fetchSemanticsNodes().isNotEmpty()
        }
        val landscapeBounds = compose.onNodeWithContentDescription(landscapeDescription)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        resize(700, 780)
        compose.waitUntil(20_000) {
            val state = model?.readerUiState?.value
            state?.doublePage == false && state.horizontal == false &&
                host.resources.configuration.screenWidthDp == 700 &&
                host.resources.configuration.screenHeightDp > host.resources.configuration.screenWidthDp
        }
        val singleState = checkNotNull(model).readerUiState.value
        assertEquals(
            "Changing from a spread to one column must not change the source page",
            landscapePosition,
            checkNotNull(visiblePage(host)).let { it.chapterIndex to it.index },
        )
        val singleDescription = "Chapter ${landscapePosition.first + 1} ${landscapePosition.second + 1}/4"
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(singleDescription).fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
        val singleBounds = compose.onNodeWithContentDescription(singleDescription)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Portrait manga page exceeds the 680dp reading column: ${singleBounds.width / density}dp",
            singleBounds.width <= 680 * density + 2,
        )
        assertTrue(
            "Landscape spread did not use two bounded columns",
            landscapeBounds.width <= 640 * density + 2,
        )
        File(context.cacheDir, "reader-comfort-manga-layout.json").writeText(
            JSONObject()
                .put("task_id", host.taskId)
                .put("activity_identity", System.identityHashCode(host))
                .put("landscape", JSONObject()
                    .put("width_dp", 1200)
                    .put("height_dp", 700)
                    .put("double_page", landscapeState.doublePage)
                    .put("horizontal", landscapeState.horizontal)
                    .put("page", JSONObject()
                        .put("chapter", landscapePosition.first)
                        .put("index", landscapePosition.second))
                    .put("image_width_dp", landscapeBounds.width / density))
                .put("portrait", JSONObject()
                    .put("width_dp", 700)
                    .put("height_dp", 780)
                    .put("double_page", singleState.doublePage)
                    .put("horizontal", singleState.horizontal)
                    .put("page", JSONObject()
                        .put("chapter", landscapePosition.first)
                        .put("index", landscapePosition.second))
                    .put("image_width_dp", singleBounds.width / density)
                    .put("maximum_column_dp", 680))
                .toString(2),
        )
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "reader-comfort-manga-portrait.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }

    @Test
    fun backingOutWhileCatalogIsPendingDoesNotNavigateAfterTheTransactionCompletes() {
        launchReader()
        scenario!!.onActivity { readerModel(it).setMenuVisible(true) }
        var label = ""
        scenario!!.onActivity { label = it.getString(R.string.chapter_list) }
        compose.waitForIdle()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val blocker = executor.submit { appDb.runInTransaction {
            entered.countDown(); check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
        } }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithContentDescription(label).performClick()
            compose.waitUntil(10_000) { model!!.readerUiState.value.navigationPending }
            compose.waitForIdle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { !model!!.readerUiState.value.navigationPending }
            release.countDown(); blocker.get(5, java.util.concurrent.TimeUnit.SECONDS)
            compose.waitUntil(10_000) { appDb.readerSessionDao.get(readerRoute.requestId)?.revision == 1L }
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            awaitPage(1, 2)
            compose.waitUntil(10_000) { appDb.readerSessionDao.get(readerRoute.requestId)?.pageIndex == 2 }
            scenario!!.onActivity { assertEquals(readerRoute, it.currentDestination) }
        } finally { release.countDown(); blocker.get(5, java.util.concurrent.TimeUnit.SECONDS); executor.shutdownNow() }
    }

    @Test
    fun switchingBetweenPagerAndWebtoonKeepsTheVisiblePage() {
        launchReader()
        toggle(MangaReaderSetting.Horizontal)
        awaitPage(1, 1)
        scenario!!.onActivity { activity ->
            assertEquals(1, visiblePage(activity)?.index)
        }
        toggle(MangaReaderSetting.Horizontal)
        awaitPage(1, 1)
    }

    @Test
    fun rotationRestoresStableChapterAndPageIdentity() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitPage(1, 2)
        scenario!!.recreate()
        awaitPage(1, 2)
        scenario!!.onActivity { activity ->
            assertTrue(readerModel(activity).readerUiState.value.items.isNotEmpty())
            assertEquals(2, visiblePage(activity)?.index)
        }
    }

    @Test
    fun disablingAndRestoringPagerSnapKeepsTheLatestVisiblePage() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        toggle(MangaReaderSetting.SnapDisabled)
        SystemClock.sleep(400) // Observe the full layout transition, including any late old-page reports.
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 3)
        toggle(MangaReaderSetting.SnapDisabled)
        SystemClock.sleep(400)
        awaitPage(1, 3)
    }

    @Test
    fun changingDirectionDoesNotReplayACompletedPageTurn() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        toggle(MangaReaderSetting.RightToLeft)
        SystemClock.sleep(400)
        awaitPage(1, 2)
        toggle(MangaReaderSetting.RightToLeft)
        SystemClock.sleep(400)
        awaitPage(1, 2)
    }

    @Test
    fun longPressingTheRenderedImageSavesTheExactCachedBytesToTheChosenDirectory() {
        val cache = ACache.get()
        val previous = cache.getAsString(AppConst.imagePathKey)
        val folder = File(context.getExternalFilesDir(null), "manga-save-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            cache.put(AppConst.imagePathKey, Uri.fromFile(folder).toString())
            launchReader()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Chapter 2 2/4").fetchSemanticsNodes().isNotEmpty()
            }
            val original = BookHelp.getImage(book, "${source.bookSourceUrl}/image/1/1.png")
            compose.onNodeWithContentDescription("Chapter 2 2/4").performTouchInput { longClick(center) }
            val saved = File(folder, original.name)
            compose.waitUntil(10_000) { saved.isFile && saved.length() == original.length() }
            org.junit.Assert.assertArrayEquals(original.readBytes(), saved.readBytes())
        } finally {
            if (previous == null) cache.remove(AppConst.imagePathKey) else cache.put(AppConst.imagePathKey, previous)
            folder.deleteRecursively()
        }
    }

    @Test
    fun actualOverflowSettingsPersistAndPreserveTheVisiblePage() {
        launchReader()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Chapter 2 2/4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Chapter 2 2/4").assertIsDisplayed()
            .performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        fun label(id: Int): String {
            var text = ""
            scenario!!.onActivity { text = it.getString(id) }
            return text
        }
        compose.onNodeWithContentDescription(label(R.string.reader_menu_more)).assertIsDisplayed().performClick()
        val horizontal = label(R.string.enable_manga_horizontal_scroll)
        compose.onNodeWithText(horizontal).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { model?.readerUiState?.value?.horizontal == false }
        compose.onNodeWithText(horizontal).assertIsOff()
        assertEquals(false, runBlocking { mangaPreferencesStore.current() }.horizontalScrollEnabled)
        awaitPage(1, 1)
        compose.onNodeWithText(horizontal).performClick()
        compose.waitUntil(10_000) { model?.readerUiState?.value?.horizontal == true }
        compose.onNodeWithText(horizontal).assertIsOn()
        assertEquals(true, runBlocking { mangaPreferencesStore.current() }.horizontalScrollEnabled)
        awaitPage(1, 1)
    }

    @Test
    fun viewModelsKeepIndependentBooksAndPositionsAcrossRecreation() {
        val second = book.copy(bookUrl = "${book.bookUrl}/independent", name = "Independent ${book.name}")
        appDb.bookDao.insert(second)
        val copied = appDb.bookChapterDao.getChapterList(book.bookUrl).map {
            it.copy(bookUrl = second.bookUrl)
        }
        appDb.bookChapterDao.insert(*copied.toTypedArray())
        copied.forEach { chapter ->
            val original = appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index)!!
            BookHelp.saveText(second, chapter, checkNotNull(BookHelp.getContent(book, original)))
        }
        var independent: ReadMangaViewModel? = null
        try {
            launchReader()
            scenario!!.onActivity { activity ->
                independent = ViewModelProvider(activity)["independent-reader", ReadMangaViewModel::class.java]
                independent!!.initialize(io.legado.app.data.repository.ReaderOpenRequest(UUID.randomUUID().toString(), second.bookUrl))
            }
            fun awaitIndependent(chapter: Int, page: Int) {
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario!!.onActivity {
                        val other = independent!!.snapshot()
                        ready = other.book?.bookUrl == second.bookUrl && other.chapterIndex == chapter &&
                            other.chapterPosition == page && !independent!!.readerUiState.value.loadingVisible
                        assertEquals(book.bookUrl, model!!.snapshot().book?.bookUrl)
                    }
                    if (ready) return
                    SystemClock.sleep(50)
                }
                error("Independent ViewModel did not restore its own position")
            }
            awaitIndependent(1, 1)
            scenario!!.onActivity { independent!!.openChapter(2, 2) }
            awaitIndependent(2, 2)
            awaitPage(1, 1)
            scenario!!.recreate()
            awaitPage(1, 1)
            awaitIndependent(2, 2)
            val expected = 0xff202020.toInt() + 0x1010 + 1
            val deadline = SystemClock.uptimeMillis() + 5_000
            var drawn = false
            while (SystemClock.uptimeMillis() < deadline && !drawn) {
                compose.waitForIdle()
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    drawn = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == expected
                    if (drawn) {
                        val folder = checkNotNull(context.getExternalFilesDir("ui-regression")).apply { mkdirs() }
                        File(folder, "manga-independent-viewmodels.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                } finally { bitmap.recycle() }
                if (!drawn) SystemClock.sleep(50)
            }
            assertTrue("The primary reader must actually draw its own cached page after recreation", drawn)
        } finally {
            scenario?.close(); scenario = null
            independent?.awaitPendingWrites()
            BookHelp.clearCache(second)
            appDb.bookChapterDao.delByBook(second.bookUrl)
            appDb.bookDao.delete(second)
        }
    }

    private fun launchReader() {
        scenario = ActivityScenario.launch(
            AppActivity.startIntent(context, readerRoute)
        )
        scenario!!.onActivity { model = readerModel(it) }
        awaitPage(1, 1)
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

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

    private fun infoModel(activity: AppActivity, route: BookInfoRoute) = ViewModelProvider(
        navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(route) {}.contentKey),
    )[io.legado.app.ui.book.info.BookInfoViewModel::class.java]

    private fun openReaderDetails(): BookInfoRoute {
        compose.waitUntil(10_000) { model!!.readerUiState.value.interactive && !model!!.readerUiState.value.navigationPending }
        scenario!!.onActivity { model!!.setMenuVisible(true) }
        compose.onNodeWithText(book.name).performClick()
        var route: BookInfoRoute? = null
        compose.waitUntil(15_000) {
            scenario!!.onActivity { activity ->
                route = (activity.currentDestination as? BookInfoRoute)?.takeIf { it.returnToReader }
                route = route?.takeIf { infoModel(activity, it).uiState.value.let { state -> state.book != null && state.recoveryReady } }
            }
            route != null
        }
        return checkNotNull(route)
    }
    private fun backFromDetails() {
        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(15_000) {
            var ready = false
            scenario!!.onActivity { ready = it.currentDestination == readerRoute && readerModel(it).readerUiState.value.interactive }
            ready
        }
        awaitPage(1, 1)
    }
    private fun changedDetailBook(): Pair<Book, List<BookChapter>> {
        val changed = book.copy(bookUrl = "${book.bookUrl}/detail-source", tocUrl = "${book.tocUrl}/detail-source")
        detailBooks += changed
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).map { it.copy(bookUrl = changed.bookUrl) }
        chapters.forEach { chapter ->
            val content = (0 until 4).joinToString("\n") { page ->
                val url = "${source.bookSourceUrl}/detail-image/${chapter.index}/$page.png"
                val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xff303030.toInt() + chapter.index * 10 + page)
                    val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
                    BookHelp.writeImage(changed, url, bytes)
                } finally { bitmap.recycle() }
                "<img src=\"$url\">"
            }
            assertTrue(BookHelp.saveContent(source, changed, chapter, content))
        }
        return changed to chapters
    }

    @Test fun readerDetailsRoundTripSurvivesRotationAndRejectsOldViewportCallbacks() {
        launchReader()
        val old = model!!.readerUiState.value
        val page = checkNotNull(old.items.getOrNull(old.anchorItemIndex))
        val details = openReaderDetails()
        scenario!!.recreate()
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity { ready = infoModel(it, details).uiState.value.recoveryReady }; ready }
        backFromDetails()
        scenario!!.onActivity {
            assertFalse(readerModel(it).acceptVisibleItem(page, old.contentVersion, old.layoutVersion))
        }
        assertEquals(io.legado.app.data.entities.ReaderDetailPhase.Consumed, appDb.readerDetailDao.get(details.requestId)!!.phase)
        assertEquals(2L, appDb.readerSessionDao.get(readerRoute.requestId)!!.revision)
        assertEquals(1, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun aSourceChangedInDetailsReturnsItsCanonicalBookToTheSameReaderEntry() {
        launchReader()
        val details = openReaderDetails()
        val (changed, chapters) = changedDetailBook()
        scenario!!.onActivity { infoModel(it, details).requestSourceChange(source, changed, chapters) }
        compose.waitUntil(15_000) { var ready = false; scenario!!.onActivity { ready = infoModel(it, details).uiState.value.book?.bookUrl == changed.bookUrl }; ready }
        backFromDetails()
        assertEquals(changed.bookUrl, model!!.snapshot().book!!.bookUrl)
        assertEquals(changed.bookUrl, appDb.readerSessionDao.get(readerRoute.requestId)!!.bookUrl)
        assertFalse(appDb.bookDao.has(book.bookUrl))
        key(KeyEvent.KEYCODE_DPAD_RIGHT); awaitPage(1, 2)
        assertEquals(changed.bookUrl, model!!.snapshot().book!!.bookUrl)
    }

    @Test fun unshelvedDetailsSourceChangeFlushesItsPrivatePreviewBeforeReaderReturn() {
        appDb.bookDao.update(book.copy(type = book.type or BookType.notShelf))
        readerRoute = readerRoute.copy(inBookshelf = false)
        launchReader()
        val details = openReaderDetails()
        val (changed, chapters) = changedDetailBook()
        changed.type = changed.type or BookType.notShelf
        scenario!!.onActivity { infoModel(it, details).requestSourceChange(source, changed, chapters) }
        compose.waitUntil(15_000) { var ready = false; scenario!!.onActivity { ready = infoModel(it, details).uiState.value.book?.bookUrl == changed.bookUrl }; ready }
        assertFalse(appDb.bookDao.has(changed.bookUrl))
        backFromDetails()
        assertEquals(changed.bookUrl, model!!.snapshot().book!!.bookUrl)
        assertTrue(appDb.bookDao.getBook(changed.bookUrl)!!.type and BookType.notShelf != 0)
        assertFalse(appDb.bookDao.has(book.bookUrl))
    }

    @Test fun deletionInTheNewDetailsClosesTheExactOriginalReaderSession() {
        launchReader()
        val details = openReaderDetails()
        scenario!!.onActivity { infoModel(it, details).requestDelete() }
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
            ready = infoModel(it, details).uiState.value.libraryAction.phase in setOf(
                io.legado.app.ui.book.info.BookInfoLibraryPhase.ConfirmDelete, io.legado.app.ui.book.info.BookInfoLibraryPhase.Removing)
        }; ready }
        scenario!!.onActivity { infoModel(it, details).libraryController.confirmDelete() }
        compose.waitUntil(15_000) { !appDb.bookDao.has(book.bookUrl) && appDb.readerSessionDao.get(readerRoute.requestId) == null }
        assertEquals(MangaReaderExit.Deleted, model!!.readerUiState.value.exit)
    }

    private fun readerModel(activity: AppActivity): ReadMangaViewModel = ViewModelProvider(
        navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(readerRoute) {}.contentKey),
    )[ReadMangaViewModel::class.java]

    private fun visiblePage(activity: AppActivity): io.legado.app.ui.book.manga.entities.MangaPage? {
        val state = readerModel(activity).readerUiState.value
        return state.items.getOrNull(state.anchorItemIndex) as? io.legado.app.ui.book.manga.entities.MangaPage
    }

    private fun toggle(setting: MangaReaderSetting) {
        fun value(state: MangaReaderUiState) = when (setting) {
            MangaReaderSetting.RightToLeft -> state.rightToLeftEnabled
            MangaReaderSetting.Horizontal -> state.horizontalScrollEnabled
            MangaReaderSetting.SnapDisabled -> state.horizontalPageSnapDisabled
            else -> error("Unsupported fixture setting")
        }
        val previous = value(model!!.readerUiState.value)
        scenario!!.onActivity { readerModel(it).toggleSetting(setting) }
        compose.waitUntil(10_000) { value(model!!.readerUiState.value) != previous }
    }

    private fun key(code: Int) {
        compose.waitForIdle()
        scenario!!.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun awaitPage(chapter: Int, page: Int) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            compose.waitForIdle()
            var matched = false
            scenario?.onActivity { activity ->
                model = readerModel(activity)
                val visible = visiblePage(activity)
                matched = !readerModel(activity).readerUiState.value.loadingVisible && visible?.chapterIndex == chapter && visible.index == page
            }
            if (matched) return
            SystemClock.sleep(50L)
        }
        val snapshot = checkNotNull(model).snapshot()
        var ui = ""
        scenario?.onActivity { activity ->
            val state = readerModel(activity).readerUiState.value
            ui = "anchor=${state.anchorItemIndex}, versions=${state.contentVersion}/${state.layoutVersion}, " +
                "horizontal=${state.horizontal}, rtl=${state.rightToLeft}, double=${state.doublePage}, snap=${state.pageSnapEnabled}, " +
                "loading=${state.loadingVisible}, titleHidden=${state.titleHidden}, " +
                "items=${state.items.map { it.chapterIndex to it.index }}"
        }
        error("Timed out waiting for manga page $chapter/$page; actual=${snapshot.chapterIndex}/${snapshot.chapterPosition}; $ui")
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) = apply {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
        }
    }
}
