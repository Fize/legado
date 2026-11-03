package io.legado.app.ui.book.read.compose

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.*
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TextReaderRichLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<AppActivity>? = null
    private val measured = AtomicReference<TextReaderLayout?>()
    @After fun cleanup() { scenario?.close() }
    private fun layout(document: TextReaderDocument, direction: LayoutDirection = LayoutDirection.Ltr,
        typography: TextReaderTypography = TextReaderTypography()): TextReaderLayout {
        scenario?.close(); measured.set(null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, BookshelfRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val text = textReaderAnnotatedString(document, 20, 4, density, Color.Blue, typography)
            val placeholders = document.images.map { AnnotatedString.Range(Placeholder(
                with(density) { 300.toSp() }, with(density) { 100.toSp() }, PlaceholderVerticalAlign.Center), it.offset, it.offset + 1) }
            val result = measurer.measure(text, textReaderBodyStyle(20, android.graphics.Color.BLACK, typography,
                FontFamily.SansSerif), placeholders = placeholders,
                constraints = Constraints(minWidth = 480, maxWidth = 480))
            val layout = TextReaderLayout.create(TextReaderRenderKey(TextReaderContentKey("entry", "owner", "book", 0, 1), 480, 240, 1),
                result, document.breaks, document.titleLength, with(density) { 20.sp.toPx() } * typography.paragraphSpacing,
                with(density) { typography.titleTopSpacing.dp.toPx() }, with(density) { typography.titleBottomSpacing.dp.toPx() })
            SideEffect { measured.set(layout) }
            }
        } }
        compose.waitUntil(10_000) { measured.get() != null }
        return measured.get()!!
    }
    @Test fun titleInsetsBelongToTheTitlePageInsteadOfBeingDiscardedAsPageOffsets() {
        val result = layout(TextReaderDocument("Title\nBody", 6),
            typography = TextReaderTypography(titleTopSpacing = 20, titleBottomSpacing = 30))
        val density = result.text.layoutInput.density
        val top = with(density) { 20.dp.toPx() }; val bottom = with(density) { 30.dp.toPx() }
        assertEquals(0f, result.pages.first().top, 0f)
        assertEquals(top, result.paragraphs.first().top, .01f)
        assertEquals(top + result.paragraphs.first().height + bottom, result.paragraphs[1].top, .01f)
        assertEquals("Title\nBody", result.chapterText)
        val bounds = result.boundingBox(1)
        assertTrue(result.wordAt(result.pages.first(), bounds.center).max <= 5)
    }
    @Test fun hiddenTitlesDoNotLeaveTheirConfiguredInsetsBehind() {
        val result = layout(TextReaderDocument("Body", 0),
            typography = TextReaderTypography(titleTopSpacing = 100, titleBottomSpacing = 100))
        assertEquals(0f, result.paragraphs.first().top, 0f)
        assertEquals(result.paragraphs.first().height, result.pages.first().height, .01f)
    }
    @Test fun multiLineTitlesReceiveTopAndBottomInsetsOnlyOnce() {
        val result = layout(TextReaderDocument("One\nTwo\nBody", 8),
            typography = TextReaderTypography(titleTopSpacing = 10, titleBottomSpacing = 20))
        val density = result.text.layoutInput.density
        assertEquals(with(density) { 10.dp.toPx() }, result.paragraphs[0].top, .01f)
        assertEquals(result.paragraphs[0].top + result.paragraphs[0].height, result.paragraphs[1].top, .01f)
        assertEquals(result.paragraphs[1].top + result.paragraphs[1].height + with(density) { 20.dp.toPx() },
            result.paragraphs[2].top, .01f)
    }
    @Test fun anOversizedTitleInsetStaysScrollableWithoutCreatingAnEmptyPage() {
        val result = layout(TextReaderDocument("Title\nBody", 6), typography = TextReaderTypography(titleTopSpacing = 300))
        assertTrue(result.pages.first().requiresVerticalScroll(240))
        assertEquals("Title\n", result.visibleText(result.pages.first()))
        assertTrue(result.pages.all { it.start < it.end })
    }
    @Test fun paragraphGapsMoveGlyphsAndHitTestingTogether() {
        val result = layout(TextReaderDocument("First\nSecond\nThird", 0),
            typography = TextReaderTypography(paragraphSpacing = 1f))
        val page = result.pages.first()
        val original = result.sourceBoundingBox(7)
        val positioned = result.boundingBox(7)
        assertTrue(positioned.top > original.top + 10f)
        assertEquals(result.paragraphs[1].start + result.paragraphs[1].layout.getOffsetForPosition(
            original.center.copy(y = original.center.y - result.paragraphs[1].sourceTop)),
            result.offsetAt(page, positioned.center.copy(y = positioned.center.y - page.top)))
        assertEquals(3, result.drawSlices(page).size)
    }
    @Test fun paragraphGapsMoveImagePlaceholdersByExactlyTheTextTranslation() {
        val result = layout(TextReaderDocument("First\n\uFFFC\nAfter", 0,
            images = listOf(TextReaderImage(6, "image"))), typography = TextReaderTypography(paragraphSpacing = .5f))
        val original = result.placeholderRect(0, 6, source = true)!!
        val positioned = result.placeholderRect(0, 6)!!
        val delta = result.boundingBox(6).top - result.sourceBoundingBox(6).top
        assertTrue(delta > 0f)
        assertEquals(original.top + delta, positioned.top, .01f)
        assertEquals(original.left, positioned.left, 0f)
    }
    @Test fun largerLineHeightReallyMovesTheNextBaseline() {
        val document = TextReaderDocument("First\nSecond", 0)
        val normal = layout(document, typography = TextReaderTypography(lineHeight = 1f)).text
        val expanded = layout(document, typography = TextReaderTypography(lineHeight = 2f)).text
        assertTrue(expanded.getLineBaseline(1) - expanded.getLineBaseline(0) >
            (normal.getLineBaseline(1) - normal.getLineBaseline(0)) * 1.5f)
    }
    @Test fun titleLineHeightUsesItsOwnSizeAndDoesNotChangeBodyLineHeight() {
        val result = layout(TextReaderDocument("Title\nTitle2\nBody\nBody2", 13),
            typography = TextReaderTypography(lineHeight = 2f, titleLineHeight = 1f))
        val titleDistance = result.paragraphs[1].top - result.paragraphs[0].top
        val bodyDistance = result.paragraphs[3].top - result.paragraphs[2].top
        assertTrue(titleDistance > 10f)
        assertTrue("Title=$titleDistance body=$bodyDistance", titleDistance < bodyDistance)
    }
    @Test fun aSmallRequestedLineHeightCannotClipAnImageParagraph() {
        val result = layout(TextReaderDocument("Before\n\uFFFC\nAfter", 0,
            images = listOf(TextReaderImage(7, "image"))), typography = TextReaderTypography(lineHeight = .5f))
        val image = result.placeholderRect(0, 7)!!
        val paragraph = result.paragraphs.first { 7 in it.start until it.end }
        assertTrue(image.top >= paragraph.top - 1f)
        assertTrue(image.bottom <= paragraph.top + paragraph.height + 1f)
    }
    @Test fun aLargeParagraphGapDoesNotBecomeAnEmptyPage() {
        val document = TextReaderDocument((1..12).joinToString("\n") { "Paragraph $it" }, 0)
        val result = layout(document, typography = TextReaderTypography(paragraphSpacing = 4f))
        assertEquals(document.text, result.pages.joinToString("") { result.visibleText(it) })
        assertTrue(result.pages.all { it.start < it.end })
        assertTrue(result.pages.size > 2)
    }
    @Test fun configuredLetterSpacingActuallyChangesGlyphGeometry() {
        val document = TextReaderDocument("MMMM", 0)
        val normal = layout(document).text.getBoundingBox(3).left
        val spaced = layout(document, typography = TextReaderTypography(letterSpacing = 0.3f)).text
        assertTrue(spaced.getBoundingBox(3).left > normal + 10f)
        assertEquals(0.3f, spaced.layoutInput.style.letterSpacing.value, 0f)
    }
    @Test fun configuredTitleAlignmentAndWeightAreAppliedOnlyToTheTitle() {
        val result = layout(TextReaderDocument("Title\nBody", 6), typography = TextReaderTypography(
            titleAlignment = 1, titleWeight = 300, bodyWeight = 700)).text
        assertTrue(result.getBoundingBox(0).left > 100f)
        assertTrue(result.getBoundingBox(6).left < 5f)
        assertEquals(FontWeight.Bold, result.layoutInput.style.fontWeight)
        assertTrue(result.layoutInput.text.spanStyles.any { it.start == 0 && it.end == 6 && it.item.fontWeight == FontWeight.Light })
    }
    @Test fun nestedRelativeSizesMultiplyInsteadOfOverwritingEachOther() {
        val result = layout(TextReaderDocument("M M M", 0, listOf(TextReaderSpan(0, 5, relativeSize = 1.25f),
            TextReaderSpan(2, 3, relativeSize = 1.25f)))).text
        assertTrue("Nested size was flattened", result.getBoundingBox(2).width > result.getBoundingBox(0).width * 1.2f)
    }
    @Test fun anExplicitBreakInsideOneLogicalStringCreatesTwoRealMeasuredParagraphs() {
        val result = layout(TextReaderDocument("BeforeAfter", 0, breaks = setOf(6)))
        assertEquals(listOf("Before", "After"), result.pages.map(result::visibleText))
        assertEquals("BeforeAfter", result.chapterText)
    }
    @Test fun aBlockImageHasItsOwnCenteredLineWithoutAddingOffsetCharacters() {
        val result = layout(TextReaderDocument("A\uFFFCB", 0, images = listOf(TextReaderImage(1, "image"))))
        assertEquals("A\uFFFCB", result.chapterText)
        assertNotEquals(result.text.getLineForOffset(0), result.text.getLineForOffset(1))
        assertNotEquals(result.text.getLineForOffset(1), result.text.getLineForOffset(2))
        assertEquals(90f, result.text.placeholderRects.single()!!.left, 2f)
    }
    @Test fun paragraphAlignmentAndPageBoundariesCanCoexist() {
        val result = layout(TextReaderDocument("a\nb\nc", 0,
            spans = listOf(TextReaderSpan(0, 5, alignment = 1)), breaks = setOf(2)))
        assertEquals(listOf(0, 2), result.pages.map { it.start })
        assertTrue(result.text.getBoundingBox(0).left > 100f)
    }
    @Test fun aShortParagraphInAnRtlLayoutStartsAtTheRightSideOfTheActualViewport() {
        val result = layout(TextReaderDocument("مرحبا", 0), LayoutDirection.Rtl)
        assertEquals(LayoutDirection.Rtl, result.text.layoutInput.layoutDirection)
        assertEquals(480, result.text.size.width)
        assertTrue(result.text.getBoundingBox(0).right > 400f)
    }
}
