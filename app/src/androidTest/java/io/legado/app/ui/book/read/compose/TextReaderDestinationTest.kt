package io.legado.app.ui.book.read.compose

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.io.ByteArrayOutputStream
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TextReaderDestinationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://text-destination-$id.invalid", "Text fixture")
    private val book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl,
        name = "Text fixture $id", author = id, totalChapterNum = 2,
        durChapterPos = 1500, readConfig = Book.ReadConfig(useReplaceRule = false))
    private val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl,
        url = book.bookUrl + "/$it", title = "Text chapter ${it + 1}", index = it) }
    private val body = (0..159).joinToString("\n") { "Paragraph $it alpha beta gamma delta epsilon." }
    private val originalTitle = ReadBookConfig.titleMode
    private val originalFont = ReadBookConfig.textSize
    private val originalAdapt = AppConfig.adaptSpecialStyle
    private val backgroundConfig = ReadBookConfig.getConfig(ReadBookConfig.readStyleSelect)
    private val originalBackground = backgroundConfig.copy()
    private val originalBackgroundAlpha = ReadBookConfig.bgAlpha
    private var scenario: ActivityScenario<AppActivity>? = null
    @Before fun setup() {
        ReadBookConfig.titleMode = 0
        ReadBookConfig.textSize = 20
        AppConfig.adaptSpecialStyle = true
        setBackground(0, "#ffffff")
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        chapters.forEach { assertTrue(BookHelp.saveContent(source, book, it, body)) }
    }
    @After fun cleanup() {
        scenario?.close()
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        appDb.readerSessionDao.get(id)?.let { appDb.readerSessionDao.close(it.id, it.ownerToken) }
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        BookHelp.clearCache(book)
        ReadBookConfig.titleMode = originalTitle
        ReadBookConfig.textSize = originalFont
        AppConfig.adaptSpecialStyle = originalAdapt
        restoreBackground(originalBackground, originalBackgroundAlpha)
    }
    private fun launch(route: AppRoute = TextReaderRoute(book.bookUrl, requestId = id)) {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
    }
    private fun canvas() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
    private fun ready(requestId: String = id) {
        try {
            compose.waitForIdle()
            compose.waitUntil(15_000) {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection)).fetchSemanticsNodes().size == 1 &&
                    appDb.readerSessionDao.get(requestId)?.chapterIndex?.let { it >= 0 } == true
            }
            compose.waitForIdle()
        } catch (error: Throwable) {
            val session = appDb.readerSessionDao.get(requestId)
            val detail = appDb.readerDetailDao.active(requestId)
            val route = current()
            println("Reader fixture state: route=${route?.javaClass?.simpleName}, chapter=${session?.chapterIndex}, " +
                "revision=${session?.revision}, detail=${detail?.phase}")
            if ((route is TextReaderRoute && route.bookUrl == book.bookUrl) ||
                (route is BookInfoRoute && route.bookUrl == book.bookUrl)) {
                runCatching {
                    File(context.cacheDir, "compose-text-reader-return-failure.png").outputStream().use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            throw error
        }
    }
    private fun action(index: Int): Boolean {
        val action = canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions][index]
        return action.action()
    }
    private fun menu() { canvas().performTouchInput { click(center) } }
    private fun setBackground(kind: Int, value: String, alpha: Int = 100) {
        backgroundConfig.bgType = kind; backgroundConfig.bgStr = value
        backgroundConfig.bgTypeNight = kind; backgroundConfig.bgStrNight = value
        backgroundConfig.bgTypeEInk = kind; backgroundConfig.bgStrEInk = value
        ReadBookConfig.bgAlpha = alpha
    }
    private fun restoreBackground(saved: ReadBookConfig.Config, alpha: Int) {
        backgroundConfig.bgType = saved.bgType; backgroundConfig.bgStr = saved.bgStr
        backgroundConfig.bgTypeNight = saved.bgTypeNight; backgroundConfig.bgStrNight = saved.bgStrNight
        backgroundConfig.bgTypeEInk = saved.bgTypeEInk; backgroundConfig.bgStrEInk = saved.bgStrEInk
        ReadBookConfig.bgAlpha = alpha
    }
    private fun backgroundPixel(top: Boolean = false): Int {
        val bitmap = compose.onNodeWithTag("text_reader_background").captureToImage().asAndroidBitmap()
        return bitmap.getPixel(bitmap.width - 2, if (top) 2 else bitmap.height - 2)
    }
    private fun isGreen(pixel: Int) = android.graphics.Color.red(pixel) < 40 &&
        android.graphics.Color.green(pixel) > 160 && android.graphics.Color.blue(pixel) < 100
    @Test fun theSelectedSolidBackgroundActuallyPaintsBehindTheReader() {
        setBackground(0, "#00cc22")
        launch(); ready()
        compose.waitUntil(10_000) { isGreen(backgroundPixel()) }
        canvas().assertExists()
        File(context.cacheDir, "compose-text-reader-background.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        scenario!!.recreate(); ready()
        compose.waitUntil(10_000) { isGreen(backgroundPixel()) }
        assertEquals(1500, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun backgroundOpacityCompositesWithoutChangingTheSavedCharacterPosition() {
        setBackground(0, "#00cc22")
        launch(); ready()
        compose.waitUntil(10_000) { isGreen(backgroundPixel()) }
        ReadBookConfig.bgAlpha = 0
        scenario!!.recreate(); ready()
        val base = android.graphics.Color.red(backgroundPixel())
        assertTrue(base > 100)
        ReadBookConfig.bgAlpha = 50
        scenario!!.recreate(); ready()
        compose.waitUntil(10_000) { android.graphics.Color.red(backgroundPixel()) in 1 until base }
        assertEquals(1500, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun anOwnedImageBackgroundSurvivesChapterChangesAndAMissingFileDoesNotBreakReading() {
        val file = File(context.cacheDir, "reader-background-$id.png")
        try {
            file.writeBytes(imageBytes()); setBackground(2, file.path)
            launch(); ready()
            compose.waitUntil(10_000) { isGreen(backgroundPixel()) }
            menu()
            compose.onNodeWithText(context.getString(R.string.next_chapter)).performClick()
            ready()
            compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex == 1 }
            assertTrue(isGreen(backgroundPixel(top = true)))
            file.delete()
            scenario!!.recreate(); ready()
            assertFalse(isGreen(backgroundPixel(top = true)))
            assertTrue(canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text.contains("Paragraph"))
        } finally { file.delete() }
    }
    @Test fun titleInsetsMoveActualGlyphPixelsAndDisappearWhenTheTitleIsHidden() {
        val config = ReadBookConfig.config
        val top = config.titleTopSpacing; val bottom = config.titleBottomSpacing
        fun firstInkRow(): Int {
            val pixels = canvas().captureToImage().toPixelMap()
            return (0 until pixels.height).first { y -> (0 until pixels.width).any { x ->
                val color = pixels[x,y]; color.red < .9f && color.green < .9f && color.blue < .9f
            } }
        }
        try {
            config.titleTopSpacing = 0; config.titleBottomSpacing = 0
            appDb.bookDao.upProgress(book.bookUrl, 0)
            launch(); ready()
            val original = firstInkRow()
            config.titleTopSpacing = 80; config.titleBottomSpacing = 60
            scenario!!.recreate(); ready()
            val shifted = firstInkRow()
            assertTrue(shifted > original + 50)
            assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            ReadBookConfig.titleMode = 2
            scenario!!.recreate(); ready()
            assertTrue(firstInkRow() < shifted - 50)
            assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        } finally { config.titleTopSpacing = top; config.titleBottomSpacing = bottom }
    }
    private fun current(): AppRoute? {
        var route: AppRoute? = null
        scenario!!.onActivity { route = it.currentDestination }
        return route
    }

    @Test fun readingGuidesPaintAndSurviveRecreationAndPageTurnsWithoutAnchorDrift() {
        ReadBookConfig.underlineMode
        val profiles = (ReadBookConfig.configList + ReadBookConfig.shareConfig).distinctBy { System.identityHashCode(it) }
        val saved = profiles.map { it.copy() }
        fun redCount(): Int {
            val pixels = canvas().captureToImage().toPixelMap()
            return (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
                val c = pixels[x,y]; c.red > .8f && c.green < .3f && c.blue < .3f
            } }
        }
        try {
            ReadBookConfig.underlineMode = 6; ReadBookConfig.underlineColor = 0xffff0000.toInt()
            ReadBookConfig.underlineWidth = 3f; ReadBookConfig.underlineDistance = 20f
            ReadBookConfig.underlineBodyEnabled = true; ReadBookConfig.underlineTitleEnabled = false
            launch(); ready()
            assertTrue(redCount() > 100)
            assertEquals(1500, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            File(context.cacheDir, "compose-text-reader-underlines.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(action(1))
            compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos > 1500 }
            val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
            scenario!!.recreate(); ready()
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertTrue(redCount() > 100)
            ReadBookConfig.underlineMode = 0
            scenario!!.recreate(); ready()
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertEquals(0, redCount())
        } finally {
            profiles.zip(saved).forEach { (target, old) ->
                target.underlineMode = old.underlineMode; target.underlineWidth = old.underlineWidth
                target.underlineDistance = old.underlineDistance; target.underlineColor = old.underlineColor
                target.underlineColorSet = old.underlineColorSet; target.underlineBodyEnabled = old.underlineBodyEnabled
                target.underlineTitleEnabled = old.underlineTitleEnabled; target.underlineConfigVersion = old.underlineConfigVersion
            }
        }
    }

    @Test fun actualRouteDisplaysTheSavedCharacterAnchorAndKeepsItAfterAcknowledgement() {
        launch(); ready()
        assertTrue(current() is TextReaderRoute)
        assertEquals(1500, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        val text = canvas().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(text.contains("Paragraph")); assertFalse(text.contains("TextReaderRoute"))
        File(context.cacheDir, "compose-text-reader-destination.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun pageTurnsPersistAndActivityRecreationKeepsTheExactCharacterAnchor() {
        launch(); ready()
        assertTrue(action(1))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos > 1500 }
        val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        scenario!!.recreate(); ready()
        assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertTrue(action(0))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos < position }
    }

    @Test fun changingFontAndRecreatingTwiceDoesNotDriftTheRequestedPosition() {
        launch(); ready()
        val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        val initialText = canvas().fetchSemanticsNode().config[SemanticsProperties.Text]
        ReadBookConfig.textSize = 31; scenario!!.recreate(); ready()
        assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        val largeText = canvas().fetchSemanticsNode().config[SemanticsProperties.Text]
        assertNotEquals(initialText, largeText)
        ReadBookConfig.textSize = 17; scenario!!.recreate(); ready()
        assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertNotEquals(largeText, canvas().fetchSemanticsNode().config[SemanticsProperties.Text])
    }

    @Test fun changingSpacingMarginsAndTypefaceReflowsWithoutMovingTheSavedAnchor() {
        val config = ReadBookConfig.config
        val saved = config.copy()
        val systemFont = AppConfig.systemTypefaces
        try {
            launch(); ready()
            val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
            val initial = canvas().fetchSemanticsNode().config[SemanticsProperties.Text]
            val bounds = canvas().fetchSemanticsNode().boundsInRoot
            config.letterSpacing = 0.4f; config.textBold = 1
            config.paddingLeft = 100; config.paddingRight = 140
            config.textFont = "/unavailable-reader-font-${UUID.randomUUID()}.ttf"
            AppConfig.systemTypefaces = 2
            scenario!!.recreate(); ready()
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertNotEquals(initial, canvas().fetchSemanticsNode().config[SemanticsProperties.Text])
            assertTrue(canvas().fetchSemanticsNode().boundsInRoot.width < bounds.width)
            scenario!!.recreate(); ready()
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        } finally {
            config.letterSpacing = saved.letterSpacing; config.textBold = saved.textBold
            config.paddingLeft = saved.paddingLeft; config.paddingRight = saved.paddingRight
            config.textFont = saved.textFont; AppConfig.systemTypefaces = systemFont
        }
    }
    @Test fun changingLineAndParagraphSpacingReflowsWithoutDriftingTheCharacterAnchor() {
        val config = ReadBookConfig.config
        val saved = config.copy()
        try {
            launch(); ready()
            val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
            val initial = canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            config.lineSpacingExtra = 28; config.paragraphSpacing = 20; config.titleLineSpacingExtra = 30
            scenario!!.recreate(); ready()
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertTrue(canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text.length < initial.length)
            scenario!!.recreate(); ready()
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        } finally {
            config.lineSpacingExtra = saved.lineSpacingExtra; config.paragraphSpacing = saved.paragraphSpacing
            config.titleLineSpacingExtra = saved.titleLineSpacingExtra
        }
    }
    @Test fun tappingTheActuallyPaintedLinkAfterAParagraphGapOpensItsTarget() {
        val config = ReadBookConfig.config
        val saved = config.copy()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addDataAuthority("example.invalid", null)
        }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            config.paragraphSpacing = 20; config.lineSpacingExtra = 20
            rich("Before\n<usehtml><p><a href='https://example.invalid/target'>MMMMMMMM</a></p></usehtml>")
            val pixels = canvas().captureToImage().toPixelMap()
            var target: androidx.compose.ui.geometry.Offset? = null
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x,y]
                    if (color.blue - color.red > .08f && color.blue - color.green > .1f) {
                        target = androidx.compose.ui.geometry.Offset(x + .5f, y + .5f); break
                    }
                }
                if (target != null) break
            }
            assertNotNull("Actual link pixels must be visible", target)
            canvas().performTouchInput { click(checkNotNull(target)) }
            compose.waitUntil(5_000) { monitor.hits == 1 }
            assertTrue(current() is TextReaderRoute)
        } finally {
            config.paragraphSpacing = saved.paragraphSpacing; config.lineSpacingExtra = saved.lineSpacingExtra
            instrumentation.removeMonitor(monitor)
        }
    }
    @Test fun catalogSelectionReturnsToTheSameReaderWithTheChosenChapter() {
        launch(); ready(); menu()
        compose.onNodeWithText(context.getString(R.string.chapter_list)).performClick()
        compose.waitUntil(10_000) { current() is BookTocRoute }
        compose.onNodeWithText(chapters[1].title).performClick()
        ready()
        compose.waitUntil(10_000) { current() is TextReaderRoute && appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex == 1 }
        assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun nextChapterIsLoadedAndAcknowledgedInTheExistingSession() {
        launch(); ready(); menu()
        compose.onNodeWithText(context.getString(R.string.next_chapter)).performClick()
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex == 1 }
        assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertEquals(id, appDb.readerSessionDao.get(id)!!.id)
    }

    @Test fun readerDetailBackRestoresTheOriginalEntryAndPosition() {
        launch(); ready()
        repeat(3) {
            menu()
            compose.onNodeWithText(context.getString(R.string.book_info)).performClick()
            compose.waitUntil(15_000) { current() is BookInfoRoute }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(15_000) { current() is TextReaderRoute }
            ready()
            assertEquals(1500, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertTrue(appDb.readerSessionDao.get(id)!!.revision > 0)
        }
    }

    @Test fun pagingBackFromTheFirstPageOfAChapterOpensThePreviousChaptersLastPage() {
        launch(TextReaderRoute(book.bookUrl, chapterIndex = 1, requestId = id)); ready()
        assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertTrue(action(0))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.let { it.durChapterIndex == 0 && it.durChapterPos > 1500 } }
    }

    @Test fun keyboardPageDownTurnsAndPersistsTheActualViewport() {
        launch(); ready()
        canvas().performKeyInput { pressKey(Key.PageDown) }
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos > 1500 }
    }

    @Test fun hidingAndRestoringTheTitlePreservesTheSameBodyCharacter() {
        launch(); ready()
        val titleLength = chapters[0].title.length + 1
        ReadBookConfig.titleMode = 2; scenario!!.recreate(); ready()
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos == 1500 - titleLength }
        ReadBookConfig.titleMode = 0; scenario!!.recreate(); ready()
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos == 1500 }
    }

    @Test fun showingATitleWhileAtBodyOffsetZeroKeepsTheFirstBodyCharacter() {
        ReadBookConfig.titleMode = 2
        appDb.bookDao.update(book.copy(durChapterPos = 0))
        launch(); ready()
        ReadBookConfig.titleMode = 0; scenario!!.recreate(); ready()
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos == chapters[0].title.length + 1 }
    }

    @Test fun backClosesTheOwnedRequestAndKeepsTheLastVisiblePosition() {
        launch(); ready(); assertTrue(action(1))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos > 1500 }
        val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(10_000) { appDb.readerSessionDao.get(id) == null }
        assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun anExpiredDetailRequestCannotOpenAnotherBook() {
        launch(TextReaderRoute(book.bookUrl, requestId = id, returnToDetails = true))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.reader_return_request_expired)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection)).fetchSemanticsNodes().isEmpty())
        assertEquals(1500, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun detailReadAndBackPublishesTheFinalPositionToTheOriginalDetailEntry() {
        val parent = UUID.randomUUID().toString()
        launch(BookInfoRoute(book.bookUrl, requestId = parent))
        compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.reading)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.reading)).performClick()
        compose.waitUntil(15_000) { current() is TextReaderRoute }
        val child = (current() as TextReaderRoute).requestId!!
        ready(child)
        assertEquals(parent, appDb.bookInfoRequestDao.child(child)?.requestId)
        assertTrue(action(1))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos > 1500 }
        val position = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(15_000) { (current() as? BookInfoRoute)?.requestId == parent && appDb.readerSessionDao.get(child) == null }
        assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    private fun rich(content: String) {
        ReadBookConfig.titleMode = 2
        assertTrue(BookHelp.saveContent(source, book, chapters[0], content))
        launch(TextReaderRoute(book.bookUrl, chapterIndex = 0, requestId = id)); ready()
    }
    private fun greenPixels(node: SemanticsNodeInteraction): Int {
        val bitmap = node.captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { ((it ushr 16) and 255) < 40 && ((it ushr 8) and 255) > 160 && (it and 255) < 100 }
    }
    private fun imageBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(320, 160, android.graphics.Bitmap.Config.ARGB_8888)
        return try { bitmap.eraseColor(0xff00cc22.toInt()); ByteArrayOutputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
        } } finally { bitmap.recycle() }
    }

    @Test fun htmlForegroundAndBoldStylesActuallyDrawColoredGlyphPixels() {
        rich("<usehtml><p><font color='#ff0000'><b>Crimson</b></font> ordinary text.</p></usehtml>")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.count { ((it ushr 16) and 255) > 180 && ((it ushr 8) and 255) < 70 && (it and 255) < 70 } > 30)
        assertTrue(canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text.contains("Crimson"))
    }

    @Test fun anExplicitPageBreakSeparatesContentThatWouldOtherwiseFitOnePage() {
        rich("Before\n[newpage]\nAfter")
        val first = canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(first.contains("Before")); assertFalse(first.contains("After"))
        assertTrue(action(1))
        compose.waitUntil(10_000) { canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text.contains("After") }
        assertFalse(canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text.contains("Before"))
        assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos > 0)
    }

    @Test fun aCachedIllustrationIsDrawnAndItsPreviewActuallyZoomsAndResets() {
        val url = source.bookSourceUrl + "/illustration.png"
        BookHelp.writeImage(book, url, imageBytes())
        rich("<img src=\"$url\">\nAfter illustration")
        val label = context.getString(R.string.text_reader_image, 1)
        val loaded = context.getString(R.string.preview_image_by_click)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().any { it.config[SemanticsProperties.StateDescription] == loaded }
        }
        assertTrue(greenPixels(compose.onRoot()) > 1000)
        File(context.cacheDir, "compose-text-reader-image.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription(label).performClick()
        val preview = compose.onNodeWithTag("text_reader_image_preview")
        preview.assertExists()
        val before = greenPixels(preview)
        val image = compose.onNode(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = image.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions[0].action()) }; compose.waitForIdle()
        assertTrue(greenPixels(preview) > before * 1.1)
        repeat(10) { compose.runOnIdle { actions[0].action() } }
        compose.waitForIdle(); image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "800%"))
        compose.runOnIdle { assertTrue(actions[2].action()) }; compose.waitForIdle()
        image.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
        preview.performTouchInput {
            pinch(center - androidx.compose.ui.geometry.Offset(width / 12f, 0f), center + androidx.compose.ui.geometry.Offset(width / 12f, 0f),
                center - androidx.compose.ui.geometry.Offset(width / 4f, 0f), center + androidx.compose.ui.geometry.Offset(width / 4f, 0f))
        }
        compose.waitForIdle()
        val scale = image.fetchSemanticsNode().config[SemanticsProperties.StateDescription].removeSuffix("%").toInt()
        assertTrue(scale >= 150)
        repeat(4) { preview.performTouchInput { swipe(androidx.compose.ui.geometry.Offset(16f, center.y), androidx.compose.ui.geometry.Offset(width - 16f, center.y), 200) } }
        compose.waitForIdle(); assertTrue(greenPixels(preview) > before * 1.1)
        compose.runOnIdle { actions[2].action() }; compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        preview.assertDoesNotExist()
        assertTrue(greenPixels(compose.onRoot()) > 1000)
    }

    @Test fun accessibleHttpLinkActionOpensOnlyItsCapturedTarget() {
        rich("<usehtml><p><a href='https://example.invalid/target'>Link</a> <a href='javascript:alert(1)'>Script</a> <a href='#note'>Note</a></p></usehtml>")
        val actions = canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(4, actions.size)
        assertEquals(context.getString(R.string.text_reader_open_link, "Link"), actions[2].label)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addDataAuthority("example.invalid", null)
            addDataPath("/target", android.os.PatternMatcher.PATTERN_LITERAL)
        }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            compose.runOnIdle { assertTrue(actions[2].action()) }
            compose.waitUntil(5_000) { monitor.hits == 1 }
            assertTrue(current() is TextReaderRoute)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun aCapturedLinkActionIsRejectedAfterTurningThePage() {
        rich("<usehtml><p><a href='https://example.invalid/target'>Link</a></p></usehtml>\n[newpage]\nAfter")
        val captured = canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions].last()
        assertEquals(context.getString(R.string.text_reader_open_link, "Link"), captured.label)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addDataAuthority("example.invalid", null)
        }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            assertTrue(action(1))
            compose.waitUntil(10_000) {
                canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text.contains("After")
            }
            compose.runOnIdle { assertFalse(captured.action()) }
            assertEquals(0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun onlyVisibleImagesAreDownloadedAndReturningToAPageUsesItsCachedBytes() {
        val first = AtomicInteger(); val second = AtomicInteger(); val bytes = imageBytes()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/first.png") first.incrementAndGet() else second.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }
        server.start()
        fun loaded(ordinal: Int): Boolean = compose.onAllNodesWithContentDescription(context.getString(R.string.text_reader_image, ordinal))
            .fetchSemanticsNodes().any { it.config[SemanticsProperties.StateDescription] == context.getString(R.string.preview_image_by_click) }
        try {
            rich("<img src=\"http://127.0.0.1:${server.listeningPort}/first.png\">\n[newpage]\n<img src=\"http://127.0.0.1:${server.listeningPort}/second.png\">")
            compose.waitUntil(15_000) { loaded(1) }
            assertEquals(1, first.get()); assertEquals(0, second.get())
            assertTrue(action(1)); compose.waitUntil(15_000) { loaded(2) }
            assertEquals(1, first.get()); assertEquals(1, second.get())
            assertTrue(action(0)); compose.waitUntil(15_000) { loaded(1) }
            assertEquals(1, first.get()); assertEquals(1, second.get())
        } finally { server.stop() }
    }
    @Test fun retryingADecodeFailureBypassesTheCorruptCacheAndDrawsTheNewImage() {
        val count = AtomicInteger(); val bytes = imageBytes()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                count.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.listeningPort}/repair.png"
            BookHelp.writeImage(book, url, "invalid image bytes".toByteArray())
            rich("<img src=\"$url\">")
            val label = context.getString(R.string.text_reader_image, 1)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().any {
                    it.config[SemanticsProperties.StateDescription] == context.getString(R.string.photo_open_failed)
                }
            }
            assertEquals(0, count.get())
            compose.onNodeWithContentDescription(label).performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().any {
                    it.config[SemanticsProperties.StateDescription] == context.getString(R.string.preview_image_by_click)
                }
            }
            assertEquals(1, count.get()); assertTrue(greenPixels(compose.onRoot()) > 1000)
        } finally { server.stop() }
    }
}
