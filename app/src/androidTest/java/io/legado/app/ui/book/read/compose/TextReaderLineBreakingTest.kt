package io.legado.app.ui.book.read.compose

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.TextReaderDocument
import io.legado.app.model.TextReaderTypography
import io.legado.app.model.TextReaderUnderline
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TextReaderLineBreakingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<AppActivity>? = null
    private var language by mutableStateOf("zh-CN")
    private var width by mutableIntStateOf(300)
    private var height by mutableIntStateOf(240)
    private var fontScale by mutableFloatStateOf(1f)
    private var anchor by mutableIntStateOf(0)
    private var selection by mutableStateOf<TextRange?>(null)
    private var guide by mutableStateOf(TextReaderUnderline())
    private val current = AtomicReference<TextReaderLayout?>()
    private val selected = AtomicReference<TextRange?>()
    private val generations = AtomicLong()
    private val english = "Precise typography makes international communication understandable. " +
        "Readers appreciate carefully balanced paragraphs with automatic hyphenation and continuous character positions."
    private fun launch(text: String, titleLength: Int = 0) {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            val configuration = remember(language) { Configuration(context.resources.configuration).apply {
                setLocales(android.os.LocaleList.forLanguageTags(language))
            } }
            val realDensity = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(realDensity.density, fontScale)) {
                val density = LocalDensity.current
                val locales = rememberTextReaderLocales()
                val measurer = rememberTextMeasurer()
                val typography = TextReaderTypography(lineHeight = 1.4f, underline = guide)
                val annotated = textReaderAnnotatedString(TextReaderDocument(text, titleLength), 20, 4,
                    density, Color.Blue, typography)
                val style = textReaderBodyStyle(20, android.graphics.Color.BLACK, typography, FontFamily.SansSerif, locales)
                val layout = remember(annotated, style, measurer, width, height, density, guide) {
                    TextReaderLayout.measure(TextReaderRenderKey(TextReaderContentKey("line-breaking", "owner", "book", 0, 1),
                        width, height, generations.incrementAndGet()), annotated, style, measurer,
                        titleLength = titleLength, underline = guide)
                }
                SideEffect { current.set(layout) }
                Box(Modifier.requiredSize(with(density) { width.toDp() }, with(density) { height.toDp() }).background(Color.White)) {
                    TextReaderCanvas(layout, anchor, selection, Modifier.testTag("line_breaking"),
                        onSelection = { _, range -> selection = range; selected.set(range) },
                        onPrevious = { false }, onNext = { false }, onTap = {})
                }
            }
        } }
        compose.waitUntil(10_000) { current.get() != null }
        compose.waitForIdle()
    }
    @After fun cleanup() { scenario?.close() }
    private fun canvas() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
    private fun nativeLines(layout: TextLayoutResult): List<String> = (0 until layout.lineCount).map {
        layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it, true)).trim()
    }.filter { it.isNotEmpty() }
    private fun verifyCoverage(layout: TextReaderLayout) {
        assertTrue(layout.pages.all { it.start < it.end })
        assertEquals(layout.chapterText, layout.pages.joinToString("") { layout.visibleText(it) })
        layout.pages.zipWithNext().forEach { (a,b) -> assertEquals(a.end, b.start) }
    }
    private fun midWordBreaks(layout: TextLayoutResult): List<Int> = (0 until layout.lineCount - 1).map {
        layout.getLineEnd(it, true)
    }.filter { it > 0 && it < english.length && english[it - 1].isLetter() && english[it].isLetter() }
    /** Find a dictionary break while every word fits individually, excluding emergency wrapping. */
    private fun installHyphenatedLayout(): Int {
        var found: Pair<Int,Int>? = null
        compose.runOnIdle {
            val input = current.get()!!.paragraphs.single().layout.layoutInput
            val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0)
            val widest = english.split(' ').maxOf { measurer.measure(it, input.style).size.width }
            for (candidate in widest + 12..widest + 220 step 4) {
                val key = current.get()!!.key.copy(viewportWidth = candidate)
                val auto = TextReaderLayout.measure(key, input.text, input.style, measurer).paragraphs.single().layout
                val none = TextReaderLayout.measure(key, input.text, input.style.copy(hyphens = Hyphens.None), measurer)
                    .paragraphs.single().layout
                val breaks = midWordBreaks(auto)
                if (breaks.isNotEmpty() && midWordBreaks(none).isEmpty()) {
                    found = candidate to breaks.first(); break
                }
            }
            assertNotNull("The en-US system dictionary must produce a real syllable break", found)
            width = found!!.first
        }
        compose.waitForIdle()
        return found!!.second
    }
    @Test fun chineseStrictRulesKeepClosingMarksOffLineStartsAndOpeningMarksOffLineEnds() {
        val text = "这是一段中文阅读测试。我们说：“标点不能落在错误的位置！”然后继续阅读（括号中的文字），也包括《书名》、【引文】和普通句子。".repeat(4)
        launch(text)
        for (size in listOf(180, 240, 320, 480)) {
            compose.runOnIdle { width = size }; compose.waitForIdle()
            val layout = current.get()!!
            val lines = nativeLines(layout.paragraphs.single().layout)
            assertTrue(lines.size > 2)
            lines.forEach { line ->
                assertFalse("line starts with closing mark: $line", "、。，．？！：；）〕］｝〉》」』】〗〙〛’”".contains(line.first()))
                assertFalse("line ends with opening mark: $line", "（〔［｛〈《「『【〖〘〚‘“".contains(line.last()))
            }
            verifyCoverage(layout)
        }
    }
    @Test fun japaneseStrictRulesKeepSmallKanaAndLongSoundMarksOffLineStarts() {
        language = "ja-JP"
        launch("「きょうは、きれいなコーヒーを飲みましょう。」小さな文字ゃゅょや長音ーを含む文章で、読みやすい改行を確かめます。".repeat(4))
        for (size in listOf(200, 260, 400)) {
            compose.runOnIdle { width = size }; compose.waitForIdle()
            val layout = current.get()!!
            nativeLines(layout.paragraphs.single().layout).forEach { line ->
                assertFalse("prohibited Japanese line start: $line", "、。，．？！：；）〉》」』】ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮー々".contains(line.first()))
            }
            verifyCoverage(layout)
        }
    }
    @Test fun koreanJamoCombiningMarksAndEmojiStayOnGraphemeBoundaries() {
        language = "ko-KR"; width = 180
        val text = "한글 문장과 한글 조합, e\u0301 및 👩‍👩‍👧‍👦 이모지를 함께 읽습니다. ".repeat(6)
        launch(text)
        val iterator = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.forLanguageTag("ko-KR"))
        iterator.setText(text)
        val boundaries = mutableSetOf<Int>()
        var index = iterator.first()
        while (index != android.icu.text.BreakIterator.DONE) { boundaries += index; index = iterator.next() }
        val layout = current.get()!!
        val native = layout.paragraphs.single().layout
        for (line in 0 until native.lineCount) {
            assertTrue(native.getLineStart(line) in boundaries)
            assertTrue(native.getLineEnd(line, false) in boundaries)
        }
        verifyCoverage(layout)
    }
    @Test fun automaticHyphenationChangesNativeBreaksWithoutInsertingSourceCharacters() {
        language = "en-US"; height = 900
        launch(english)
        val offset = installHyphenatedLayout()
        val layout = current.get()!!
        assertTrue(offset in midWordBreaks(layout.paragraphs.single().layout))
        assertEquals(english, layout.chapterText)
        assertFalse(layout.chapterText.contains('-'))
        assertEquals(english, canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        File(context.cacheDir, "compose-text-reader-hyphenation.png").outputStream().use {
            compose.onNodeWithTag("line_breaking").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        verifyCoverage(layout)
    }
    @Test fun hyphenatedWordsKeepTheirSourceSelectionAndGlyphHitCoordinates() {
        language = "en-US"; height = 900
        launch(english)
        val split = installHyphenatedLayout()
        val start = english.substring(0, split).indexOfLast { !it.isLetter() } + 1
        val end = (split until english.length).firstOrNull { !english[it].isLetter() } ?: english.length
        val layout = current.get()!!
        val page = layout.pageAtOffset(split)
        val point = layout.boundingBox(split - 1).center.copy(y = layout.boundingBox(split - 1).center.y - page.top)
        assertTrue(kotlin.math.abs(layout.offsetAt(page, point) - (split - 1)) <= 1)
        assertEquals(TextRange(start, end), layout.wordAt(page, point))
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(start - page.start, end - page.start, false)) }
        compose.waitForIdle()
        assertEquals(TextRange(start, end), selected.get())
        assertEquals(english.substring(start, end), layout.chapterText.substring(selected.get()!!.start, selected.get()!!.end))
    }
    @Test fun readingGuideRemeasurementPreservesDictionaryBreaksAndDrawsWithinLineBoxes() {
        language = "en-US"; height = 900
        launch(english)
        val split = installHyphenatedLayout()
        val original = current.get()!!.paragraphs.single().layout
        val originalBreaks = (0 until original.lineCount).map { original.getLineEnd(it, false) }
        compose.runOnIdle { guide = TextReaderUnderline(mode = 5, color = 0xff00cc22.toInt(), width = 4f, distance = 20f) }
        compose.waitForIdle()
        val layout = current.get()!!
        val paragraph = layout.paragraphs.single()
        assertEquals(originalBreaks, (0 until paragraph.layout.lineCount).map { paragraph.layout.getLineEnd(it, false) })
        assertTrue(split in midWordBreaks(paragraph.layout))
        paragraph.underlines.forEach { run ->
            assertTrue(run.top >= paragraph.layout.getLineTop(run.line))
            assertTrue(run.bottom <= paragraph.layout.getLineBottom(run.line))
            assertTrue("Guide must start within the physical text line", run.left >= paragraph.layout.getLineLeft(run.line) - .01f)
            assertTrue("Guide must end within the physical text line", run.right <= paragraph.layout.getLineRight(run.line) + .01f)
        }
        val pixels = compose.onNodeWithTag("line_breaking").captureToImage().toPixelMap()
        val green = (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
            val c = pixels[x,y]; c.green > .7f && c.red < .3f && c.blue < .3f
        } }
        assertTrue(green > 100)
        File(context.cacheDir, "compose-text-reader-hyphenation-guides.png").outputStream().use {
            compose.onNodeWithTag("line_breaking").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        verifyCoverage(layout)
    }
    @Test fun configurationLocaleChangesRemeasureWithoutChangingTheCharacterAnchor() {
        language = "en-US"
        val text = "日本語の文章と中文标点、English words を一緒に読みます。".repeat(8)
        launch(text)
        val previous = current.get()!!
        compose.runOnIdle { anchor = 40; language = "ja-JP" }; compose.waitForIdle()
        val next = current.get()!!
        assertTrue(next.key.generation > previous.key.generation)
        assertEquals("ja-JP", next.paragraphs.first().layout.layoutInput.style.localeList!![0].toLanguageTag())
        assertEquals(previous.key.content, next.key.content)
        assertEquals(40, anchor)
        val page = next.pageAtOffset(anchor)
        assertEquals(next.visibleText(page), canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
    }
    @Test fun resizingAndLargeFontScaleKeepTheSameSourceCharacterOnTheVisiblePage() {
        language = "ja-JP"
        val text = "幅と文字の大きさを変更しても、同じ本文の位置を保ちます。".repeat(20)
        launch(text)
        compose.runOnIdle { anchor = 100; width = 180; fontScale = 1.8f }; compose.waitForIdle()
        val layout = current.get()!!
        val page = layout.pageAtOffset(anchor)
        assertTrue(100 in page.start until page.end)
        assertEquals(text, layout.chapterText)
        assertEquals(layout.visibleText(page), canvas().fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        verifyCoverage(layout)
    }
    @Test fun titleParagraphsKeepTheirOwnHeadingPolicyAfterParagraphSplitting() {
        language = "en-US"
        val title = "A heading about extraordinary communication\n"
        launch(title + english, title.length)
        val paragraphs = current.get()!!.paragraphs
        val titleStyle = paragraphs.first().layout.layoutInput.text.paragraphStyles.single().item
        assertEquals(LineBreak.Heading, titleStyle.lineBreak)
        assertEquals(Hyphens.None, titleStyle.hyphens)
        assertEquals(LineBreak.Strictness.Strict, paragraphs.last().layout.layoutInput.style.lineBreak.strictness)
        assertEquals(Hyphens.Auto, paragraphs.last().layout.layoutInput.style.hyphens)
    }
    @Test fun emergencyWrappingOfAnUnknownLongWordStillCoversEverySourceCharacter() {
        language = "en-US"; width = 70
        val text = "ExtraordinarilyLongUnrecognizedWordWithNoDictionaryEntry😀AndMoreCharacters"
        launch(text)
        val layout = current.get()!!
        assertTrue(layout.pages.size > 1)
        verifyCoverage(layout)
        layout.pages.forEach { page ->
            assertFalse(page.start in 1 until text.length && Character.isLowSurrogate(text[page.start]))
        }
    }
}
