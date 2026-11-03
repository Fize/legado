package io.legado.app.ui.main

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.main.bookshelf.BookshelfRootScreen
import io.legado.app.ui.main.bookshelf.BookshelfRootViewModel
import io.legado.app.ui.main.my.MyRootScreen
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
class BookshelfWindowBoundsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val oldLayout = AppConfig.bookshelfLayout
    private val oldMargin = AppConfig.bookshelfMargin
    private val id = UUID.randomUUID().toString()
    private val books = (0..11).map { Book(bookUrl = "window-test-$id-$it", name = "Adaptive fixture $it") }
    private var scenario: ActivityScenario<AppActivity>? = null
    private var width by mutableFloatStateOf(600f)
    private var font by mutableFloatStateOf(1f)
    @Before fun setup() {
        AppConfig.bookshelfLayout = 6; AppConfig.bookshelfMargin = 16
        books.forEach { appDb.bookDao.insert(it) }
    }
    @After fun cleanup() {
        scenario?.close()
        books.forEach { appDb.bookDao.delete(it) }
        AppConfig.bookshelfLayout = oldLayout; AppConfig.bookshelfMargin = oldMargin
    }
    private fun capture(name: String) {
        File(context.cacheDir, "compatibility-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun actualShelfFitsItsContentBoundsAndReflowsWhenFontSizeChanges() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
        scenario!!.onActivity { activity ->
            val shelf = ViewModelProvider(activity)[BookshelfRootViewModel::class.java]
            shelf.openGroup(BookGroup(BookGroup.IdAll))
            activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                MaterialTheme { Box(Modifier.width(width.dp).fillMaxSize()) {
                    BookshelfRootScreen({ _, _ -> }, {}, {}, {}, bookshelfViewModel = shelf)
                } }
            }
        } }
        fun expect(columns: Int) {
            try {
                compose.waitUntil(10_000) {
                    val node = compose.onAllNodesWithTag("bookshelf_items").fetchSemanticsNodes().firstOrNull()
                    val visibleCells = node?.children.orEmpty().filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
                    val positions = visibleCells.map { (it.boundsInRoot.center.x / 2).toInt() }.distinct()
                    positions.size == columns
                }
            } catch (error: Throwable) {
                capture("shelf-layout-failure")
                throw AssertionError("Expected $columns actual card columns:\n" + compose.onRoot().printToString(), error)
            }
        }
        expect(5); capture("shelf-600")
        compose.runOnIdle { width = 520f }
        expect(4); capture("shelf-520")
        compose.runOnIdle { width = 360f; font = 2f }
        expect(2); capture("shelf-360-large-font")
        assertEquals(6, AppConfig.bookshelfLayout)
    }

    @Test fun myPageRemainsCenteredAndBoundedOnTheTablet() {
        var density = 1f
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            density = LocalDensity.current.density
            MaterialTheme { MyRootScreen({}, {}) }
        } }
        compose.onNodeWithTag("my_preferences").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("my_preferences").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width <= 720f * density + 1)
        assertEquals(root.center.x, bounds.center.x, 1f)
        capture("my-tablet")
    }
}
