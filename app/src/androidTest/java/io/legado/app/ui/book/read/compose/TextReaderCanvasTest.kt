package io.legado.app.ui.book.read.compose

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TextReaderCanvasTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<AppActivity>? = null
    private val content = TextReaderContentKey("text-reader-fixture", "owner", "fixture://chapter", 0, 1)
    private var width by mutableIntStateOf(480)
    private var height by mutableIntStateOf(240)
    private var generation by mutableLongStateOf(0)
    private var fontSize by mutableIntStateOf(18)
    private var fontScale by mutableFloatStateOf(1f)
    private var paragraphSpacing by mutableFloatStateOf(0f)
    private var nativeLineHeight by mutableFloatStateOf(0f)
    private var referenceParagraph by mutableStateOf<Int?>(null)
    private var anchor by mutableIntStateOf(0)
    private var selection by mutableStateOf<TextRange?>(null)
    private val current = AtomicReference<TextReaderLayout?>()
    private val reported = AtomicReference<Pair<TextReaderRenderKey, TextRange>?>()
    private val finished = AtomicReference<TextReaderRenderKey?>()
    private var firstMeasurement: androidx.compose.ui.text.TextLayoutResult? = null
    private val body = (1..60).joinToString("\n") { "alpha beta gamma delta paragraph $it" }
    @After fun close() { scenario?.close() }

    private fun launch(text: String = body, direction: LayoutDirection = LayoutDirection.Ltr) {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, BookshelfRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            val realDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(realDensity.density, fontScale),
                LocalLayoutDirection provides direction) {
                MaterialTheme {
                    val density = LocalDensity.current
                    val measurer = rememberTextMeasurer()
                    val key = TextReaderRenderKey(content, width, height, generation)
                    val measured = remember(measurer, text, width, fontSize, density, nativeLineHeight) {
                        measurer.measure(text, TextStyle(fontSize = fontSize.sp, color = Color.Black,
                            lineHeight = if (nativeLineHeight > 0) nativeLineHeight.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Minimum)),
                            constraints = Constraints(minWidth = width, maxWidth = width))
                    }
                    val layout = remember(key, measured, paragraphSpacing) {
                        TextReaderLayout.create(key, measured, paragraphGap = with(density) { fontSize.sp.toPx() } * paragraphSpacing)
                    }
                    SideEffect { current.set(layout); if (firstMeasurement == null) firstMeasurement = measured }
                    Box(Modifier.width(with(density) { width.toDp() }).height(with(density) { height.toDp() })
                        .background(Color.White).testTag("reader_host")) {
                        val reference = referenceParagraph
                        if (reference != null) Canvas(Modifier.fillMaxSize()) {
                            val paragraph = layout.paragraphs[reference]
                            val page = layout.pageAtOffset(anchor)
                            clipRect(top = paragraph.top - page.top, bottom = paragraph.top + paragraph.height - page.top) {
                                translate(top = paragraph.top - page.top) { drawText(paragraph.layout) }
                            }
                        } else TextReaderCanvas(layout, anchor, selection, selectionColor = Color(0x660000FF),
                            onSelection = { render, range -> reported.set(render to range); selection = range },
                            onPrevious = { val page = layout.pageAtOffset(anchor)
                                if (page.index > 0) { anchor = layout.pages[page.index - 1].start; true } else false },
                            onNext = { val page = layout.pageAtOffset(anchor)
                                if (page.index < layout.pages.lastIndex) { anchor = layout.pages[page.index + 1].start; true } else false },
                            onTap = {}, onSelectionFinished = { finished.set(it) })
                    }
                }
            }
        } }
        compose.waitUntil(10_000) { current.get() != null }
        compose.waitForIdle()
    }
    private fun canvas() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
    @Test fun aSpacedParagraphMatchesItsIsolatedNativePaintingWithoutNeighbourFragments() {
        paragraphSpacing = .2f; nativeLineHeight = 1.2f
        launch((0..4).joinToString("\n") { "Paragraph alpha beta gamma." })
        val paragraph = layout().paragraphs[1]
        val production = compose.onNodeWithTag("reader_host").captureToImage()
        compose.runOnIdle { referenceParagraph = 1 }
        compose.waitForIdle()
        val reference = compose.onNodeWithTag("reader_host").captureToImage().toPixelMap()
        val actual = production.toPixelMap()
        var ink = 0
        for (y in kotlin.math.floor(paragraph.top.toDouble()).toInt() until
            kotlin.math.ceil((paragraph.top + paragraph.height).toDouble()).toInt().coerceAtMost(actual.height)) {
            for (x in 0 until actual.width) {
                val found = actual[x,y]; val expected = reference[x,y]
                if (found.red < .5f) ink++
                assertTrue("Neighbour glyph leaked at $x/$y", kotlin.math.abs(found.red - expected.red) < .03f &&
                    kotlin.math.abs(found.green - expected.green) < .03f && kotlin.math.abs(found.blue - expected.blue) < .03f)
            }
        }
        assertTrue(ink > 100)
        File(context.cacheDir, "compose-text-reader-paragraph-gaps.png").outputStream().use {
            production.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun longPressAfterAParagraphGapSelectsTheActuallyDrawnWord() {
        paragraphSpacing = 1f
        launch("First paragraph\nSecond paragraph\nThird paragraph")
        val result = layout(); val page = result.pages.first()
        val offset = result.chapterText.indexOf("Second") + 1
        val bounds = result.boundingBox(offset)
        canvas().performTouchInput { longClick(Offset(bounds.center.x, bounds.center.y - page.top)) }
        compose.waitForIdle()
        val selected = reported.get()!!.second
        assertEquals("Second", result.chapterText.substring(selected.min, selected.max))
    }
    @Test fun paragraphGapPixelsRemainEmptyWhileTranslatedSelectionIsVisible() {
        paragraphSpacing = 1f
        launch("First\nSecond")
        val result = layout()
        val original = result.sourceBoundingBox(6)
        val target = result.boundingBox(6)
        assertTrue(target.top > original.top)
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(6, 12, false)) }
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("reader_host").captureToImage().toPixelMap()
        val previousBottom = result.paragraphs.first().height
        val gapY = ((previousBottom + target.top) / 2).toInt()
        assertTrue((0 until pixels.width).all { x -> pixels[x, gapY].red > .98f && pixels[x, gapY].green > .98f })
        var blue = 0
        for (y in target.top.toInt().coerceAtLeast(0) until target.bottom.toInt().coerceAtMost(pixels.height))
            for (x in 0 until pixels.width) if (pixels[x,y].blue - pixels[x,y].red > .15f) blue++
        assertTrue(blue > 10)
    }
    private fun layout() = current.get()!!
    private fun action(index: Int): Boolean {
        val action = canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions][index]
        assertTrue(action.label.isNotBlank())
        var result = false
        compose.runOnIdle { result = action.action() }
        compose.waitForIdle()
        return result
    }

    @Test fun actualMeasuredLinesCoverMixedUnicodeWithoutSplittingSurrogates() {
        val text = (1..40).joinToString("\n") { "\u4F60\u597D \uD83D\uDC69\u200D\uD83D\uDCBB \u0645\u0631\u062D\u0628\u0627 world $it" }
        launch(text)
        val pages = layout().pages
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(pages.size > 1)
        pages.forEach { if (it.start < text.length) assertFalse(Character.isLowSurrogate(text[it.start])) }
    }
    @Test fun actualRtlTextRetainsLogicalOffsetsAcrossPages() {
        val text = (1..40).joinToString("\n") { "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645 $it" }
        launch(text, LayoutDirection.Rtl)
        val result = layout()
        assertEquals(text, result.pages.joinToString("") { result.visibleText(it) })
        assertEquals(LayoutDirection.Rtl, result.text.layoutInput.layoutDirection)
        assertTrue(action(1))
        canvas().assertTextEquals(result.visibleText(result.pageAtOffset(anchor)))
    }
    @Test fun accessibilityActionsTurnPagesAndExposeOnlyVisibleText() {
        launch()
        assertFalse(action(0))
        assertTrue(action(1))
        assertEquals(layout().pages[1].start, anchor)
        canvas().assertTextEquals(layout().visibleText(layout().pages[1]))
        assertTrue(action(0)); assertEquals(0, anchor)
    }
    @Test fun pageLocalAccessibilitySelectionReturnsChapterOffsets() {
        launch(); action(1)
        val page = layout().pageAtOffset(anchor)
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(1, 6, false)) }
        compose.waitForIdle()
        assertEquals(layout().key to TextRange(page.start + 1, page.start + 6), reported.get())
        assertEquals(layout().key, finished.get())
        assertEquals(TextRange(1, 6), canvas().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
    @Test fun reversedAccessibilitySelectionPreservesAnchorAndFocusDirection() {
        launch()
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(6, 1, false)) }
        compose.waitForIdle()
        assertEquals(TextRange(6, 1), reported.get()!!.second)
        assertEquals(TextRange(6, 1), canvas().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
    @Test fun accessibilityCannotSelectHalfOfASurrogatePair() {
        launch("abc\uD83D\uDE00def")
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(4, 5, false)) }
        compose.waitForIdle()
        assertEquals(TextRange(3, 5), reported.get()!!.second)
        assertEquals(TextRange(3, 5), canvas().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
    @Test fun outOfBoundsAccessibilitySelectionCannotChangeTheChapterSelection() {
        launch()
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertFalse(it(-1, body.length, false)) }
        assertNull(reported.get())
    }
    @Test fun longPressUsesTheCurrentPagesTranslatedTextCoordinates() {
        launch(); action(1)
        val result = layout(); val page = result.pageAtOffset(anchor)
        val offset = page.start + result.visibleText(page).indexOfFirst { it.isLetter() }
        val bounds = result.boundingBox(offset)
        canvas().performTouchInput { longClick(Offset(bounds.center.x, bounds.center.y - page.top)) }
        compose.waitForIdle()
        val selected = reported.get()!!
        assertEquals(result.key, selected.first)
        assertEquals(result.key, finished.get())
        assertTrue(selected.second.min <= offset && selected.second.max > offset)
        assertTrue(selected.second.min >= page.start && selected.second.max <= page.end)
    }
    @Test fun largerFontsAndNewWidthsPreserveCharacterAnchorsAndRejectOldReports() {
        launch()
        val original = layout(); val originalPage = original.pages[2]
        val position = originalPage.start + 1
        compose.runOnIdle { anchor = position; width = 320; fontScale = 1.8f; generation++ }
        compose.waitUntil(10_000) { layout().key.generation == 1L }
        compose.waitForIdle()
        val next = layout(); val page = next.pageAtOffset(position)
        assertEquals(position, anchor)
        assertTrue(position in page.start until page.end)
        assertTrue(next.pages.size > original.pages.size)
        assertFalse(acceptsTextReaderViewport(next.key, original.key, originalPage, position))
        canvas().assertTextEquals(next.visibleText(page))
    }
    @Test fun anOversizedLineRemainsReachableByVerticalScrolling() {
        fontSize = 160; height = 70
        launch("Large")
        assertTrue(layout().pages.first().requiresVerticalScroll(height))
        val scroll = compose.onNodeWithTag("reader_host").onChildren().filter(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
        assertTrue(scroll.isNotEmpty())
        assertTrue(scroll.first().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
    }
    @Test fun selectionIsDrawnOnRealCanvasPixelsWithoutRecreatingTheMeasuredText() {
        launch()
        val measured = layout().text
        val retainedParagraphs = layout().paragraphs.map { it.layout }
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(0, 5, false)) }
        compose.waitForIdle()
        assertSame(measured, layout().text)
        retainedParagraphs.forEachIndexed { index, paragraph -> assertSame(paragraph, layout().paragraphs[index].layout) }
        val image = compose.onNodeWithTag("reader_host").captureToImage()
        val pixels = image.toPixelMap()
        var blue = 0; var ink = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = pixels[x, y]
            if (color.blue - color.red > .15f) blue++
            if (color.red < .3f && color.green < .3f && color.blue < .3f) ink++
        }
        assertTrue("Selected text must have painted highlight geometry", blue > 10)
        assertTrue("The text must be rendered, not just supplied to semantics", ink > 10)
        File(context.cacheDir, "compose-text-reader-canvas.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun unusedPageSpaceDoesNotExposeAFragmentOfTheNextLine() {
        height = 240
        launch()
        val page = layout().pageAtOffset(anchor)
        val image = compose.onNodeWithTag("reader_host").captureToImage()
        val pixels = image.toPixelMap()
        val start = kotlin.math.ceil(page.height.toDouble()).toInt() + 1
        assertTrue("Fixture must leave some unused viewport space", start < image.height)
        for (y in start until image.height) for (x in 0 until image.width) {
            val pixel = pixels[x, y]
            assertTrue("Next-page glyph leaked into unused space at $x/$y", pixel.red > .98f && pixel.green > .98f && pixel.blue > .98f)
        }
    }

    @Test fun emptyTextHasOnePageWithWorkingAccessibleActions() {
        launch("")
        assertEquals(1, layout().pages.size)
        assertFalse(action(0)); assertFalse(action(1))
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(0, 0, false)) }
        assertEquals(TextRange.Zero, reported.get()!!.second)
    }
}
