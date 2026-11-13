package io.legado.app.ui.book.manga

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MangaTileViewportTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun visibleTilesRenderAndRefreshWhenZoomChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("manga-viewport-", ".png", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("manga-repro-long.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        val source = MangaTileSource(file)
        var zoom by mutableFloatStateOf(1f)
        var error = false
        val scenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute))
        try {
            scenario.onActivity { activity -> activity.setContent {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("viewport")) {
                    MangaTiledImage(source, true, zoom, 0f, 0f, false, false, 150,
                        ColorFilter.colorMatrix(ColorMatrix()), onError = { error = true })
                }
            } }
            fun waitForPixels() {
                compose.waitUntil(15_000) {
                    val image = compose.onNodeWithTag("viewport").captureToImage().asAndroidBitmap()
                    android.graphics.Color.red(image.getPixel(image.width/2,image.height/2)) in 233..237
                }
                assertFalse(error)
            }
            waitForPixels()
            compose.runOnIdle { zoom = 5f }
            waitForPixels()
            compose.onNodeWithTag("viewport").captureToImage().asAndroidBitmap().let { image ->
                File(context.cacheDir,"manga-tiled-viewport.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally { scenario.close(); source.close(); file.delete() }
    }
    @Test fun verticalViewportRefreshesAfterScrollingAndFilters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("manga-scroll-", ".png", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("manga-repro-long.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        val source = MangaTileSource(file)
        val list = androidx.compose.foundation.lazy.LazyListState()
        var ink by mutableStateOf(false)
        var error = false
        val scenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute))
        try {
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.fillMaxSize().background(Color.Black).testTag("scroll")) {
                    item {
                        Box(Modifier.fillMaxWidth().aspectRatio(source.width.toFloat()/source.height)) {
                            MangaTiledImage(source, false, 1f, 0f, 0f, false, ink, 150,
                                ColorFilter.colorMatrix(ColorMatrix()), onError = { error = true })
                        }
                    }
                }
            } }
            fun waitForPixels(expected: Int) {
                compose.waitUntil(15_000) {
                    val image = compose.onNodeWithTag("scroll").captureToImage().asAndroidBitmap()
                    android.graphics.Color.red(image.getPixel(image.width/2,image.height/2)) == expected
                }
                assertFalse(error)
            }
            waitForPixels(235)
            kotlinx.coroutines.runBlocking { list.scrollToItem(0,8000) }
            waitForPixels(235)
            assertTrue(list.firstVisibleItemScrollOffset > 0)
            compose.runOnIdle { ink = true }
            waitForPixels(255)
        } finally { scenario.close(); source.close(); file.delete() }
    }

    @Test fun failedSourceCanRetryAndDisposeBeforeNextLoad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("manga-retry-", ".png", context.cacheDir)
        file.writeText("invalid image")
        var retry by mutableIntStateOf(0)
        var mounted by mutableStateOf(true)
        var state: MangaSourceState? = null
        val scenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute))
        try {
            scenario.onActivity { activity -> activity.setContent {
                if (mounted) state = rememberMangaSource(file.path,null,null,0,retry).value
            } }
            compose.waitUntil(15_000) { state?.error == true }
            InstrumentationRegistry.getInstrumentation().context.assets.open("manga-repro-normal.png").use { input -> file.outputStream().use { input.copyTo(it) } }
            compose.runOnIdle { retry++ }
            compose.waitUntil(15_000) { state?.source != null }
            val old = checkNotNull(state?.source)
            compose.runOnIdle { mounted = false }
            compose.waitUntil(15_000) {
                runCatching { old.decode(android.graphics.Rect(0,0,1,1),1).recycle() }.isFailure
            }
            compose.runOnIdle { mounted = true }
            compose.waitUntil(15_000) { state?.source != null && state?.source !== old }
            assertEquals(1200,state?.source?.width)
        } finally { scenario.close(); file.delete() }
    }

}
