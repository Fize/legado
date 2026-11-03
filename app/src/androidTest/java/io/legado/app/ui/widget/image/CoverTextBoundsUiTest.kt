package io.legado.app.ui.widget.image

import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.BookCoverAppearance
import io.legado.app.model.CoverFontSizes
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.CoverTextLayout
import io.legado.app.ui.compose.component.rememberCoverTextLayout
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Checks actual platform text metrics, including stroke, rather than a synthetic font model. */
@RunWith(AndroidJUnit4::class)
class CoverTextBoundsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase = InstrumentationRegistry.getArguments().getString("coverBoundsPhase", "current")!!
        .also { require(it.matches(Regex("[a-z-]+"))) }
    private fun style(horizontal: Boolean = false, adaptive: Boolean = true, custom: Boolean = false) = BookCoverAppearance(
        ColorDrawable(android.graphics.Color.WHITE), true, true, true, horizontal, adaptive, true,
        if (custom) CoverFontSizes(200, 200, 200, 200) else null, null, "", android.graphics.Color.WHITE, android.graphics.Color.BLUE)

    private fun render(scenario: ActivityScenario<AppActivity>, title: String, author: String?, width: Int,
                       scale: Float, appearance: BookCoverAppearance): CoverTextLayout {
        val result = AtomicReference<CoverTextLayout>()
        scenario.onActivity { activity -> activity.setContent {
            val density = Density(2f, scale)
            CompositionLocalProvider(LocalDensity provides density) {
                val layout = rememberCoverTextLayout(title, author, width, width * 4 / 3, appearance)
                result.set(layout)
                Canvas(Modifier.requiredSize(with(density) { width.toDp() }, with(density) { (width * 4 / 3).toDp() })
                    .background(Color.White).testTag("cover-bounds")) { layout.draw(this) }
            }
        } }
        compose.waitForIdle()
        return checkNotNull(result.get())
    }

    private fun snapshot(name: String) {
        val bitmap = compose.onNodeWithTag("cover-bounds").captureToImage().asAndroidBitmap()
        File(context.cacheDir, "cover-bounds-$phase-$name.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun assertInside(layout: CoverTextLayout, width: Int, right: Float = width.toFloat()) {
        val height = width * 4 / 3f
        assertTrue("No visible cover text", layout.glyphs.isNotEmpty())
        layout.glyphs.forEach { glyph ->
            val halfStroke = glyph.stroke / 2
            val left = glyph.position.x - halfStroke
            val top = glyph.position.y - halfStroke
            val end = glyph.position.x + glyph.layout.size.width + halfStroke
            val bottom = glyph.position.y + glyph.layout.size.height + halfStroke
            assertTrue("Glyph ${glyph.layout.layoutInput.text} exceeds $right x $height: ($left,$top)-($end,$bottom)",
                left >= 0 && top >= 0 && end <= right + .01f && bottom <= height + .01f)
        }
    }

    @Test fun verticalLongTitlesStayInsideTheCoverAndIndicateTruncation() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            for (width in listOf(96, 192, 320)) for (scale in listOf(1f, 1.3f, 2f)) for (adaptive in listOf(false, true)) {
                val layout = render(scenario, "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(10), null, width, scale,
                    style(adaptive = adaptive, custom = !adaptive))
                if (width == 192 && scale == 2f && adaptive) snapshot("long-title")
                assertInside(layout, width)
                assertEquals("…", layout.glyphs.last().layout.layoutInput.text.text)
                assertTrue("Invisible overflow characters were still measured", layout.glyphs.size < 100)
            }
        }
    }

    @Test fun verticalTitleAndAuthorUseSeparateColumnsAndShortUnicodeTitlesArePreserved() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            val short = render(scenario, "天地😀", null, 192, 2f, style())
            assertEquals(listOf("天", "地", "😀"), short.glyphs.map { it.layout.layoutInput.text.text })
            assertInside(short, 192)
            for (custom in listOf(false, true)) {
                val combined = render(scenario, "天地玄黄".repeat(40), "作者".repeat(40), 192, 2f, style(custom = custom))
                snapshot(if (custom) "custom-title-author" else "title-author")
                assertInside(combined, 192)
                val titles = combined.glyphs.filter { it.layout.layoutInput.text.text in listOf("天", "地", "玄", "黄") }
                val authors = combined.glyphs.filter { it.layout.layoutInput.text.text in listOf("作", "者") }
                assertTrue(titles.isNotEmpty()); assertTrue(authors.isNotEmpty())
                assertTrue("Title columns overlap the author", titles.maxOf { it.position.x + it.layout.size.width + it.stroke / 2 } <
                    authors.minOf { it.position.x - it.stroke / 2 })
                assertEquals("…", combined.glyphs.last().layout.layoutInput.text.text)
            }
        }
    }

    @Test fun horizontalCustomFontTitlesLeaveRoomForTheAuthor() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            val layout = render(scenario, "A very long title 天地玄黄 ".repeat(30), "作者".repeat(20), 192, 2f,
                style(horizontal = true, custom = true))
            snapshot("horizontal")
            assertInside(layout, 192)
            assertEquals(2, layout.glyphs.size)
            val title = layout.glyphs[0]; val author = layout.glyphs[1]
            assertTrue("Horizontal title overlaps the author", title.position.y + title.layout.size.height + title.stroke / 2 < author.position.y - author.stroke / 2)
            assertTrue("Long horizontal title should show ellipsis", (0 until title.layout.lineCount).any(title.layout::isLineEllipsized))
        }
    }
}
