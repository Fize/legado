package io.legado.app.ui.book.read.compose

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import io.legado.app.help.HighlightStyle
import io.legado.app.model.TextReaderUnderline
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TextReaderAnnotationDrawingTest {
    @get:Rule val compose=createEmptyComposeRule()
    private var scenario: ActivityScenario<AppActivity>?=null
    private var settings by mutableStateOf(HighlightStyle(fill=0xffff0000.toInt()))
    private val current=AtomicReference<TextReaderLayout?>()
    private val fonts=AtomicReference<Map<String,FontFamily>>(emptyMap())
    private fun launch(text: String=(0..30).joinToString("\n") { "alpha beta 0123456789 😀 paragraph $it" },guides: Boolean=false) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,BookshelfRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            val density=LocalDensity.current
            val measurer=rememberTextMeasurer()
            val annotations=remember(settings,text) { listOf(TextReaderAnnotation(0,text.length,settings)) }
            val families=rememberTextReaderAnnotationFonts(annotations)
            val annotated=textReaderApplyAnnotations(AnnotatedString(text,paragraphStyles=listOf(AnnotatedString.Range(
                ParagraphStyle(textDirection=TextDirection.Content),0,text.length))),annotations,families)
            val layout=remember(annotated,annotations,measurer,density) {
                TextReaderLayout.measure(TextReaderRenderKey(TextReaderContentKey("annotation","owner","book",0,1),480,280,settings.hashCode().toLong().and(0x7fffffff)),
                    annotated,TextStyle(fontSize=18.sp,color=Color.Black),measurer,
                    underline=if(guides) TextReaderUnderline(mode=5,width=3f,distance=12f) else TextReaderUnderline(),annotations=annotations)
            }
            SideEffect { current.set(layout); fonts.set(families) }
            Box(Modifier.requiredSize(with(density) { 480.toDp() },with(density) { 280.toDp() }).background(Color.White)) {
                TextReaderCanvas(layout,0,null,Modifier.testTag("annotation_paint_test"),onSelection={ _,_ -> },onPrevious={false},onNext={false},onTap={})
            }
        } }
        compose.waitUntil(15_000) { current.get()!=null }; compose.waitForIdle()
    }
    @After fun cleanup() { scenario?.close() }
    private fun redPixels(): Int {
        val image=compose.onNodeWithTag("annotation_paint_test").captureToImage().toPixelMap()
        return (0 until image.height).sumOf { y -> (0 until image.width).count { x -> val c=image[x,y]; c.red>.8f && c.green<.3f && c.blue<.3f } }
    }
    private fun verify() {
        val layout=checkNotNull(current.get())
        assertEquals(0,layout.pages.first().start); assertEquals(layout.chapterText.length,layout.pages.last().end)
        layout.pages.zipWithNext().forEach { (a,b) -> assertEquals(a.end,b.start) }
        layout.paragraphs.forEach { p -> p.annotations.forEach { run ->
            assertTrue(run.inkTop>=run.top-.1f); assertTrue(run.inkBottom<=run.bottom+.1f)
            assertTrue(run.left>=-.1f && run.right<=layout.key.viewportWidth+.1f)
        } }
    }
    @Test fun allSixFillShapesActuallyPaintAndRetainPagination() {
        launch(); val counts=arrayListOf<Int>()
        HighlightStyle.FillShape.entries.forEach { shape ->
            compose.runOnIdle { settings=HighlightStyle(fill=0xffff0000.toInt(),fillShape=shape) }; compose.waitForIdle(); verify()
            val count=redPixels(); assertTrue("$shape paints $count",count>40); counts+=count
        }
        assertTrue("Half fill occupies less than the full line",counts[3]<counts[0])
        assertTrue("Baseline is a narrow band",counts[4]<counts[0])
    }
    @Test fun fiveUnderlineKindsPaintTheirOwnStrokePatterns() {
        settings=HighlightStyle(underline=HighlightStyle.Underline(color=0xffff0000.toInt(),width=2f,distance=8f)); launch()
        val counts=arrayListOf<Int>()
        HighlightStyle.Kind.entries.forEach { kind ->
            compose.runOnIdle { settings=settings.copy(underline=settings.underline!!.copy(kind=kind)) }; compose.waitForIdle(); verify()
            val count=redPixels(); assertTrue("$kind paints $count",count>30); counts+=count
        }
        assertTrue(counts[2]<counts[0]); assertTrue(counts[3]<counts[0]); assertTrue(counts[4]>counts[0])
    }
    @Test fun wideDistantWavesAndGuidesFitInsideTheSameNativeLines() {
        settings=HighlightStyle(underline=HighlightStyle.Underline(HighlightStyle.Kind.WAVY,0xffff0000.toInt(),10f,30f))
        launch(guides=true); verify()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        current.get()!!.paragraphs.forEach { p -> p.annotations.forEach { run ->
            assertTrue(run.baseline+30*density+15*density<=run.bottom+.1f)
        } }
        assertTrue(redPixels()>40)
    }
    @Test fun boxesStrikesAndEmphasisMarksPaintIndependently() {
        launch()
        listOf(HighlightStyle(box=HighlightStyle.Deco(0xffff0000.toInt())),HighlightStyle(strike=HighlightStyle.Deco(0xffff0000.toInt())),HighlightStyle(emphasis=HighlightStyle.Deco(0xffff0000.toInt()))).forEach { style ->
            compose.runOnIdle { settings=style }; compose.waitForIdle(); verify(); assertTrue(redPixels()>40)
        }
    }
    @Test fun shadowPixelsArePaintedWithoutASecondCopyOfTheText() {
        settings=HighlightStyle(shadow=HighlightStyle.Shadow(1f,6f,6f,0xffff0000.toInt()))
        launch(); verify(); assertTrue(redPixels()>20)
    }
    @Test fun changingFontMetricsReflowsWithoutInsertingCharacters() {
        launch(); val text=current.get()!!.chapterText; val pages=current.get()!!.pages.size
        compose.runOnIdle { settings=HighlightStyle(fontSize=42f,letterSpacing=.3f,bold=true) }; compose.waitForIdle(); verify()
        assertEquals(text,current.get()!!.chapterText); assertTrue(current.get()!!.pages.size>pages)
    }
    @Test fun bidiAnnotationsUseActualGlyphIntervalsAndKeepHitCoordinates() {
        launch("مرحبا بالعالم alpha 😀 שלום")
        verify(); assertTrue(redPixels()>40)
        val layout=current.get()!!; val at=layout.chapterText.indexOf("alpha"); val rect=layout.boundingBox(at)
        val hit=layout.offsetAt(layout.pageAtOffset(at),Offset(rect.left+rect.width*.25f,rect.top+rect.height/2))
        val native=layout.paragraphs.first().layout.getOffsetForPosition(Offset(rect.left+rect.width*.25f,rect.top+rect.height/2))
        assertEquals(native,hit)
        val point=Offset(rect.left+rect.width*.25f,rect.top+rect.height/2)
        assertEquals(at,layout.characterAt(layout.pageAtOffset(at),point))
        val word=layout.wordAt(layout.pageAtOffset(at),point)
        assertEquals("alpha",layout.chapterText.substring(word.min,word.max))
    }
    @Test fun fileAndContentUriFontsReachTheNativeParagraphSpans() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"annotation-font-${UUID.randomUUID()}.ttf")
        try {
            context.assets.open("font/number.ttf").use { input -> file.outputStream().use { input.copyTo(it) } }
            launch()
            val uri=FileProvider.getUriForFile(context,context.packageName+".fileProvider",file).toString()
            for(path in listOf(file.path,uri)) {
                compose.runOnIdle { settings=HighlightStyle(fontPath=path) }
                compose.waitUntil(10_000) { fonts.get().containsKey(path) }; compose.waitForIdle(); verify()
                assertTrue(current.get()!!.paragraphs.first().layout.layoutInput.text.spanStyles.any { it.item.fontFamily==fonts.get()[path] })
            }
        } finally { file.delete() }
    }
}
