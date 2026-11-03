package io.legado.app.ui.book.read.compose

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.TextReaderRoute
import io.legado.app.ui.navigation.AboutRoute
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextReaderFootnoteTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://footnote-$id.invalid", "Footnote fixture")
    private val book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl,
        name = "Footnote fixture $id", author = id, totalChapterNum = 1,
        readConfig = Book.ReadConfig(useReplaceRule = false))
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = book.bookUrl + "/chapter", title = "Fixture", index = 0)
    private val originalTitle = ReadBookConfig.titleMode
    private val originalFont = ReadBookConfig.textSize
    private val originalAdapt = AppConfig.adaptSpecialStyle
    private var scenario: ActivityScenario<AppActivity>? = null
    private lateinit var model: TextReaderViewModel
    private val selector = SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection)
    private fun main() = compose.onNode(selector and !hasAnyAncestor(hasTestTag("text_reader_footnote")))
    private fun note() = compose.onNode(selector and hasAnyAncestor(hasTestTag("text_reader_footnote")))
    private fun position() = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
    private fun text(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun link(label: String, node: SemanticsNodeInteraction = main()): CustomAccessibilityAction =
        node.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label.endsWith(label) }
    private fun activate(action: CustomAccessibilityAction) {
        compose.runOnIdle { assertTrue(action.action()) }; compose.waitForIdle()
    }
    private fun openNote() {
        activate(link("Reference")); noteReady()
    }
    private fun noteReady() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("text_reader_footnote_canvas").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }
    private fun ready() {
        compose.waitUntil(15_000) { compose.onAllNodes(selector).fetchSemanticsNodes().size == 1 &&
            appDb.readerSessionDao.get(id)?.chapterIndex == 0 }
        compose.waitForIdle()
    }
    @Before fun setup() {
        ReadBookConfig.titleMode = 2; ReadBookConfig.textSize = 20; AppConfig.adaptSpecialStyle = true
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
    }
    @After fun cleanup() {
        scenario?.close()
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        appDb.readerSessionDao.get(id)?.let { appDb.readerSessionDao.close(it.id, it.ownerToken) }
        appDb.bookDao.delete(book); appDb.bookSourceDao.delete(source); BookHelp.clearCache(book)
        ReadBookConfig.titleMode = originalTitle; ReadBookConfig.textSize = originalFont; AppConfig.adaptSpecialStyle = originalAdapt
    }
    private fun launch(long: Boolean = false, image: Boolean = false, inspectModel: Boolean = false) {
        val paragraphs = (0..149).joinToString("") { "<p>Body paragraph $it alpha beta gamma delta.</p>" }
        val imageUrl = source.bookSourceUrl + "/note.png"
        if (image) {
            val bitmap = android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xffff00ff.toInt())
                val bytes = java.io.ByteArrayOutputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
                }
                BookHelp.writeImage(book, imageUrl, bytes)
            } finally { bitmap.recycle() }
        }
        val notes = if (image) "<p>Illustrated footnote <img src='$imageUrl'></p>"
            else if (long) (0..119).joinToString("") { "<p>Footnote paragraph $it contains independent pagination.</p>" }
            else "<p><b>Footnote bold</b> and <i>italic</i> text. <a epub:type='noteref' href='#second'>Nested</a> " +
                "<a epub:type='noteref' href='#note'>Self</a></p>"
        val html = "<usehtml><p id='start'><a epub:type='noteref' href='#note'>Reference</a> " +
            "<a href='#target'>Jump</a> <a href='#missing'>Missing</a></p>" + paragraphs +
            "<p id='target'>Far target destination.</p><aside id='note' epub:type='footnote'>$notes</aside>" +
            "<aside id='second' role='doc-footnote'><p>Second footnote <a href='#start'>Backref</a></p></aside></usehtml>"
        assertTrue(BookHelp.saveContent(source, book, chapter, html))
        val route = TextReaderRoute(book.bookUrl, requestId = id)
        if (inspectModel) {
            scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
            scenario!!.onActivity { activity ->
                model = ViewModelProvider(activity)[TextReaderViewModel::class.java]
                activity.setContent { MaterialTheme { TextReaderDestination(route, {}, {}, true, emptySet(), model) } }
            }
        } else scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        ready(); assertTrue(text(main()).contains("Reference"))
    }
    @Test fun anInternalJumpAndItsReturnSurviveActivityRecreation() {
        launch(); val original = position()
        activate(link("Jump"))
        compose.waitUntil(10_000) { position() > original }
        val jumped = position()
        assertTrue(text(main()).contains("Far target"))
        scenario!!.recreate(); ready(); assertEquals(jumped, position())
        compose.onNodeWithTag("text_reader_link_return").performClick()
        compose.waitUntil(10_000) { position() == original }
        assertTrue(text(main()).contains("Reference"))
        compose.onNodeWithTag("text_reader_link_return").assertDoesNotExist()
    }
    @Test fun modalFootnotesFreezeTheBookAndRejectTheCapturedBackgroundActions() {
        launch(); val original = position(); val stale = link("Jump")
        openNote(); assertTrue(text(note()).contains("Footnote bold"))
        compose.runOnIdle { assertFalse(stale.action()) }
        assertEquals(original, position())
        File(context.cacheDir, "compose-text-reader-footnote.png").outputStream().use {
            compose.onNodeWithTag("text_reader_footnote").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready()
        assertEquals(original, position()); assertTrue(text(main()).contains("Reference"))
        activate(link("Jump")); compose.waitUntil(10_000) { position() > original }
    }
    @Test fun viewingANoteInTheChapterKeepsAnExactReturnPosition() {
        launch(); val original = position(); openNote()
        compose.onNodeWithTag("text_reader_footnote_show").performClick(); ready()
        compose.waitUntil(10_000) { position() > original }
        assertTrue(text(main()).contains("Footnote bold"))
        compose.onNodeWithTag("text_reader_link_return").performClick()
        compose.waitUntil(10_000) { position() == original }
    }
    @Test fun longNotesPaginateAndRestoreWithoutWritingBookProgress() {
        launch(long = true); val original = position(); openNote()
        val first = text(note())
        compose.onNodeWithTag("text_reader_footnote_next").performClick(); compose.waitForIdle()
        val second = text(note()); assertNotEquals(first, second); assertEquals(original, position())
        scenario!!.recreate(); noteReady()
        assertEquals(second, text(note())); assertEquals(original, position())
        compose.onNodeWithTag("text_reader_footnote_previous").performClick(); compose.waitForIdle()
        assertEquals(first, text(note())); assertEquals(original, position())
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ready(); assertEquals(original, position())
    }
    @Test fun nestedNoteLinksRejectOldCallbacksAndCanReturnToTheReference() {
        launch(); val original = position(); openNote()
        val captured = link("Nested", note()); activate(captured); noteReady()
        assertTrue(text(note()).contains("Second footnote"))
        compose.runOnIdle { assertFalse(captured.action()) }
        activate(link("Backref", note())); ready()
        assertEquals(original, position()); assertTrue(text(main()).contains("Reference"))
    }
    @Test fun missingAnchorsShowAnErrorWithoutMovingTheReader() {
        launch(); val original = position(); activate(link("Missing"))
        compose.onNodeWithText(context.getString(R.string.text_reader_link_target_missing)).assertIsDisplayed()
        assertEquals(original, position()); assertTrue(text(main()).contains("Reference"))
        compose.onNodeWithTag("text_reader_footnote").assertDoesNotExist()
    }
    @Test fun closingAndReopeningTheSameNoteRejectsItsEarlierPageActions() {
        launch(long = true); val original = position(); openNote()
        val staleNext = note().fetchSemanticsNode().config[SemanticsActions.CustomActions][1]
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready(); openNote()
        val first = text(note())
        compose.runOnIdle { assertFalse(staleNext.action()) }
        assertEquals(first, text(note())); assertEquals(original, position())
        compose.onNodeWithTag("text_reader_footnote_next").performClick(); compose.waitForIdle()
        assertNotEquals(first, text(note()))
    }
    @Test fun tappingThePaintedReferenceActuallyOpensItsFootnote() {
        launch(); val original = position(); val pixels = main().captureToImage().toPixelMap()
        var target: androidx.compose.ui.geometry.Offset? = null
        // The leftmost link is Reference; another link's ascender may paint a higher pixel.
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val color = pixels[x,y]
                if (color.blue - color.red > .08f && color.blue - color.green > .1f) {
                    target = androidx.compose.ui.geometry.Offset(x + .5f, y + .5f); break
                }
            }
            if (target != null) break
        }
        assertNotNull("The reference must have painted link glyphs", target)
        main().performTouchInput { click(checkNotNull(target)) }; noteReady()
        assertTrue(text(note()).contains("Footnote bold")); assertEquals(original, position())
    }
    @Test fun aFootnoteImageUsesItsOwnedCacheAndOpensAZoomablePreview() {
        launch(image = true); val original = position(); openNote()
        val label = context.getString(R.string.text_reader_image, 1)
        val image = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("text_reader_footnote")))
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(label) and hasAnyAncestor(hasTestTag("text_reader_footnote")))
            .fetchSemanticsNodes().any { it.config[SemanticsProperties.StateDescription] == context.getString(R.string.preview_image_by_click) } }
        val pixels = compose.onNodeWithTag("text_reader_footnote").captureToImage().toPixelMap()
        assertTrue((0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
            val c = pixels[x,y]; c.red > .8f && c.green < .2f && c.blue > .8f
        } } > 20)
        image.performClick()
        val preview = compose.onNodeWithTag("text_reader_image_preview"); preview.assertIsDisplayed()
        val zoom = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("text_reader_image_preview")))
        val actions = zoom.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        activate(actions[0]); zoom.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150%"))
        compose.onNode(hasText(context.getString(R.string.close)) and hasAnyAncestor(hasTestTag("text_reader_image_preview"))).performClick()
        preview.assertDoesNotExist(); assertEquals(original, position())
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready(); assertEquals(original, position())
    }
    @Test fun aReferenceToTheCurrentNoteDoesNotDisableItsFollowingLinks() {
        launch(); val original = position(); openNote()
        activate(link("Self", note())); noteReady()
        activate(link("Nested", note())); noteReady()
        assertTrue(text(note()).contains("Second footnote")); assertEquals(original, position())
    }
    @Test fun oldCloseAndViewInTextCallbacksCannotOperateOnAReopenedNote() {
        launch(inspectModel = true); val original = position(); openNote()
        val snapshot = model.state.value.ready!!; val departed = model.state.value.footnote!!
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready(); openNote()
        val current = model.state.value.footnote!!
        assertNotEquals(departed.instance, current.instance)
        compose.runOnIdle { model.dismissFootnote(snapshot.key, departed.anchor, departed.instance) }; compose.waitForIdle()
        compose.onNodeWithTag("text_reader_footnote").assertExists()
        compose.runOnIdle { assertFalse(model.showFootnoteInText(snapshot.key, departed.anchor, departed.instance)) }; compose.waitForIdle()
        compose.onNodeWithTag("text_reader_footnote").assertExists(); assertEquals(original, position())
        assertEquals(current, model.state.value.footnote)
    }
}
