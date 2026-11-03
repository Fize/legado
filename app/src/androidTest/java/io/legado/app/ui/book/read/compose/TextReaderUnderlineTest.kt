package io.legado.app.ui.book.read.compose

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.TextReaderUnderline
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TextReaderUnderlineTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<AppActivity>? = null
    private var settings by mutableStateOf(TextReaderUnderline(mode = 1, color = 0xffff0000.toInt(), width = 2f))
    private var anchor by mutableIntStateOf(0)
    private val current = AtomicReference<TextReaderLayout?>()
    private fun launch(text: String = "Title\n" + (1..30).joinToString("\n") { "alpha beta gamma paragraph $it" },
        titleLength: Int = 6, font: Int = 18, image: Boolean = false, rtl: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, BookshelfRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val measurer = rememberTextMeasurer()
                val style = TextStyle(fontSize = font.sp, color = Color.Black)
                val placeholders = if (image) listOf(AnnotatedString.Range(Placeholder(
                    with(density) { 200.toSp() }, with(density) { 100.toSp() }, PlaceholderVerticalAlign.Center), 0, 1)) else emptyList()
                val annotated = AnnotatedString(text, paragraphStyles = listOf(AnnotatedString.Range(
                    ParagraphStyle(textAlign = if (rtl) TextAlign.Right else TextAlign.Left), 0, text.length)))
                val layout = remember(settings, measurer, density) {
                    TextReaderLayout.measure(TextReaderRenderKey(TextReaderContentKey("underline", "owner", "book", 0, 1),
                        480, 240, settings.hashCode().toLong().and(0x7fffffff)), annotated, style, measurer, placeholders,
                        titleLength = titleLength, underline = settings, bodyColor = 0xff0000ff.toInt(), titleColor = 0xff00aa00.toInt())
                }
                SideEffect { current.set(layout) }
                Box(Modifier.requiredSize(with(density) { 480.toDp() }, with(density) { 240.toDp() }).background(Color.White)) {
                    TextReaderCanvas(layout, anchor, null, Modifier.testTag("underlines"),
                        onSelection = { _, _ -> }, onPrevious = { false }, onNext = { false }, onTap = {})
                }
            }
        } }
        compose.waitUntil(10_000) { current.get() != null }
        compose.waitForIdle()
    }
    @After fun cleanup() { scenario?.close() }
    private fun redPixels(): Int {
        val pixels = compose.onNodeWithTag("underlines").captureToImage().toPixelMap()
        return (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
            val c = pixels[x, y]; c.red > .8f && c.green < .3f && c.blue < .3f
        } }
    }
    private fun verifyBounds(layout: TextReaderLayout) {
        layout.paragraphs.forEach { p -> p.underlines.forEach { run ->
            assertTrue("top mode=${settings.mode}", run.top >= p.layout.getLineTop(run.line))
            assertTrue("bottom mode=${settings.mode}", run.bottom <= p.layout.getLineBottom(run.line))
        } }
        assertTrue(layout.pages.all { it.start < it.end })
        assertEquals(0, layout.pages.first().start)
        assertEquals(layout.chapterText.length, layout.pages.last().end)
        layout.pages.zipWithNext().forEach { (a,b) -> assertEquals(a.end, b.start) }
    }
    @Test fun allSixStylesPaintPixelsInsideMeasuredLines() {
        launch()
        val counts = mutableListOf<Int>()
        for (mode in 1..6) {
            compose.runOnIdle { settings = settings.copy(mode = mode) }
            compose.waitForIdle()
            verifyBounds(current.get()!!)
            val count = redPixels(); assertTrue("mode=$mode count=$count", count > 100)
            counts += count
        }
        assertTrue("Dashed guides have gaps", counts[1] < counts[0])
        assertTrue("Double guides have both strokes", counts[3] > counts[0])
        assertTrue("Double dashed guides have gaps", counts[5] < counts[3])
    }
    @Test fun wideDistantWavesReserveSpaceWithoutLosingCharacters() {
        settings = settings.copy(mode = 5, width = 10f, distance = 30f)
        launch()
        verifyBounds(current.get()!!)
        assertTrue(redPixels() > 100)
        val layout = current.get()!!
        val page = layout.pages[1]
        val offset = page.start + 2
        val bounds = layout.boundingBox(offset)
        val hit = layout.offsetAt(page, bounds.center.copy(y = bounds.center.y - page.top))
        assertTrue(kotlin.math.abs(hit - offset) <= 1)
    }
    @Test fun largeWavesAboveTinyFontsAlsoFitTheirNativeLineBoxes() {
        settings = settings.copy(mode = 5, width = 10f, distance = 0f)
        launch("a b c\nd e f", titleLength = 0, font = 1)
        verifyBounds(current.get()!!)
    }
    @Test fun titleAndBodyCanBeEnabledIndependently() {
        settings = settings.copy(body = false)
        launch()
        var layout = current.get()!!
        assertTrue(layout.paragraphs.first().underlines.isNotEmpty())
        assertTrue(layout.paragraphs.drop(1).all { it.underlines.isEmpty() })
        compose.runOnIdle { settings = settings.copy(body = true, title = false) }
        compose.waitForIdle(); layout = current.get()!!
        assertTrue(layout.paragraphs.first().underlines.isEmpty())
        assertTrue(layout.paragraphs.drop(1).all { it.underlines.isNotEmpty() })
    }
    @Test fun unsetColorUsesIndependentTitleAndBodyColorsButTransparentIsNotUnset() {
        settings = settings.copy(color = null)
        launch()
        val paragraphs = current.get()!!.paragraphs
        assertEquals(0xff00aa00.toInt(), paragraphs.first().underlines.first().color)
        assertEquals(0xff0000ff.toInt(), paragraphs[1].underlines.first().color)
        compose.runOnIdle { settings = settings.copy(color = 0) }
        compose.waitForIdle()
        assertTrue(current.get()!!.paragraphs.all { p -> p.underlines.all { it.color == 0 } })
        assertEquals(0, redPixels())
    }
    @Test fun emptyAndImageLinesDoNotAcquireReadingGuides() {
        launch("\uFFFC\n\nBody", titleLength = 0, image = true)
        val paragraphs = current.get()!!.paragraphs
        assertTrue(paragraphs[0].underlines.isEmpty()); assertTrue(paragraphs[1].underlines.isEmpty())
        assertEquals(1, paragraphs[2].underlines.size)
        assertNotNull(current.get()!!.placeholderRect(0, 0))
    }
    @Test fun rtlLinesUseTheirMeasuredPhysicalBounds() {
        launch("مرحبا بالعالم\nمرحبا", titleLength = 0, rtl = true)
        val p = current.get()!!.paragraphs.first()
        val run = p.underlines.first()
        assertEquals(p.layout.getLineLeft(0), run.left, 0f)
        assertEquals(p.layout.getLineRight(0), run.right, 0f)
        assertTrue(run.left > 0)
        assertTrue(redPixels() > 100)
    }
    @Test fun leadingIndentAndTrailingWhitespaceDoNotGetUnderlined() {
        launch("\u3000\u3000alpha beta   ", titleLength = 0)
        val paragraph = current.get()!!.paragraphs.single()
        val run = paragraph.underlines.single()
        assertEquals(paragraph.layout.getBoundingBox(2).left, run.left, .01f)
        assertEquals(paragraph.layout.getBoundingBox(11).right, run.right, .01f)
        val pixels = compose.onNodeWithTag("underlines").captureToImage().toPixelMap()
        for (y in 0 until pixels.height) for (x in 0 until run.left.toInt()) {
            val c = pixels[x,y]
            assertFalse("No red guide under the indent", c.red > .8f && c.green < .3f && c.blue < .3f)
        }
    }
    @Test fun longParagraphsOnlyRetainVisibleGuidesForPainting() {
        launch((1..100).joinToString(" ") { "alpha beta gamma" }, titleLength = 0)
        val layout = current.get()!!
        assertTrue(layout.pages.size > 2)
        val page = layout.pages[1]
        val slice = layout.drawSlices(page).single()
        assertTrue(slice.underlines.isNotEmpty())
        assertTrue(slice.underlines.size < layout.paragraphs.single().underlines.size)
        assertTrue(slice.underlines.all { it.top + slice.origin >= slice.top && it.bottom + slice.origin <= slice.bottom })
    }
    @Test fun disablingOrZeroWidthRemovesGuidesAndRestoresOriginalGeometry() {
        launch()
        compose.runOnIdle { settings = settings.copy(width = 0f) }
        compose.waitForIdle()
        val zero = current.get()!!
        assertTrue(zero.paragraphs.all { it.underlines.isEmpty() }); assertEquals(0, redPixels())
        compose.runOnIdle { settings = settings.copy(width = 2f, mode = 0) }
        compose.waitForIdle()
        val off = current.get()!!
        assertEquals(zero.pages, off.pages)
        assertEquals(zero.paragraphs.map { it.top }, off.paragraphs.map { it.top })
    }
    @Test fun changingGuideSpaceKeepsTheSameCharacterAnchorOnTheVisiblePage() {
        launch()
        val offset = 250
        compose.runOnIdle { anchor = offset; settings = settings.copy(mode = 6, width = 10f, distance = 30f) }
        compose.waitForIdle()
        val layout = current.get()!!
        val page = layout.pageAtOffset(anchor)
        assertTrue(offset in page.start until page.end)
        assertEquals(layout.visibleText(page), compose.onNode(SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.SetSelection)).fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.Text].single().text)
        verifyBounds(layout)
    }
}
