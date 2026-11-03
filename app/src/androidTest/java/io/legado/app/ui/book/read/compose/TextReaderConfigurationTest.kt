package io.legado.app.ui.book.read.compose

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.ui.navigation.TextReaderRoute
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TextReaderConfigurationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://reader-configuration-$id.invalid", "Configuration fixture")
    private val book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl,
        name = id, author = id, totalChapterNum = 1, durChapterPos = 200, readConfig = Book.ReadConfig(useReplaceRule = false))
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = book.bookUrl + "/chapter", title = "Configuration chapter")
    private val selected = ReadBookConfig.getConfig(ReadBookConfig.readStyleSelect)
    private val layout = if (ReadBookConfig.shareLayout) ReadBookConfig.shareConfig else selected
    private val savedSelected = selected.copy()
    private val savedLayout = layout.copy()
    private val mode = AppConfig.themeMode
    private val ink = AppConfig.isEInkMode
    private var night by mutableStateOf(false)
    private var scenario: ActivityScenario<AppActivity>? = null
    private lateinit var model: TextReaderViewModel
    private val navigations = java.util.concurrent.CopyOnWriteArrayList<io.legado.app.ui.navigation.AppRoute>()
    @Before fun setup() {
        AppConfig.themeMode = "1"; AppConfig.isEInkMode = false
        selected.bgType = 0; selected.bgStr = "#00cc22"
        selected.bgTypeNight = 0; selected.bgStrNight = "#2244cc"
        layout.bgAlpha = 100; layout.titleMode = 0
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
        assertTrue(BookHelp.saveContent(source, book, chapter, (0..99).joinToString("\n") { "Paragraph $it alpha beta gamma." }))
    }
    @After fun cleanup() {
        scenario?.close()
        io.legado.app.help.globalExecutor.submit {}.get(5, TimeUnit.SECONDS)
        appDb.readerSessionDao.get(id)?.let { appDb.readerSessionDao.close(it.id, it.ownerToken) }
        appDb.bookDao.delete(book); appDb.bookSourceDao.delete(source); BookHelp.clearCache(book)
        selected.bgType = savedSelected.bgType; selected.bgStr = savedSelected.bgStr
        selected.bgTypeNight = savedSelected.bgTypeNight; selected.bgStrNight = savedSelected.bgStrNight
        layout.bgAlpha = savedLayout.bgAlpha; layout.titleMode = savedLayout.titleMode
        layout.paragraphIndent = savedLayout.paragraphIndent
        AppConfig.themeMode = mode; AppConfig.isEInkMode = ink
    }
    private fun launch() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
        scenario!!.onActivity { activity -> installReader(activity) }
        compose.waitUntil(15_000) { model.state.value.ready != null && model.state.value.pageCount > 0 }
    }
    private fun installReader(activity: AppActivity) {
            model = ViewModelProvider(activity)[TextReaderViewModel::class.java]
            activity.setContent {
                val currentNight = night
                val configuration = remember(currentNight) { Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (currentNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                } }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    MaterialTheme { TextReaderDestination(TextReaderRoute(book.bookUrl, requestId = id), {}, { navigations += it }, true, emptySet(), model) }
                }
            }
    }
    private fun pixel(): Int {
        val bitmap = compose.onNodeWithTag("text_reader_background").captureToImage().asAndroidBitmap()
        return bitmap.getPixel(bitmap.width - 2, bitmap.height - 2)
    }
    private fun switchToNight() = compose.runOnIdle { AppConfig.themeMode = "2"; night = true }
    @Test fun returningBeforeTheDetailProducerClaimsDoesNotReopenTheAcknowledgedChild() {
        launch()
        val before = model.state.value.ready!!
        compose.runOnIdle { model.details() }
        compose.waitUntil(10_000) { navigations.size == 1 && model.state.value.navigation == null }
        val child = navigations.single() as io.legado.app.ui.navigation.BookInfoRoute
        assertEquals(ReaderDetailPhase.Requested, appDb.readerDetailDao.get(child.requestId)!!.phase)
        compose.runOnIdle { model.pause(); model.enterLifecycle() }
        compose.waitUntil(15_000) { model.state.value.ready?.key?.generation?.let { it > before.key.generation } == true &&
            model.state.value.pageCount > 0 && !model.state.value.loading }
        assertEquals(1, navigations.size)
        assertEquals(200, model.state.value.anchor)
        assertEquals(ReaderDetailPhase.Cancelled, appDb.readerDetailDao.get(child.requestId)!!.phase)
        assertNull(appDb.readerDetailDao.claim(child.requestId, book.bookUrl, "late-producer-$id", System.currentTimeMillis()))
    }
    @Test fun aLateLifecycleExitCannotPauseTheReplacementComposition() {
        launch()
        val before = model.state.value.ready!!
        var latest = 0L
        compose.runOnIdle {
            val departed = model.enterLifecycle()
            latest = model.enterLifecycle()
            model.leaveLifecycle(departed)
        }
        compose.waitUntil(15_000) { model.state.value.ready?.key?.generation?.let { it > before.key.generation } == true &&
            model.state.value.pageCount > 0 && !model.state.value.loading }
        assertEquals(before.key.owner, model.state.value.ready!!.key.owner)
        assertEquals(200, model.state.value.anchor)
        compose.runOnIdle { model.leaveLifecycle(latest) }
    }
    @Test fun savedFormattingCheckpointRemapsTheBodyWhenRecreationLoadsANewIndent() {
        layout.paragraphIndent = "\u3000\u3000"
        launch()
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos == model.state.value.anchor }
        val before = model.state.value.ready!!
        val oldOffset = model.state.value.anchor
        val suffix = before.document.text.substring(oldOffset).take(12)
        layout.paragraphIndent = "\u3000\u3000\u3000\u3000"
        scenario!!.recreate()
        scenario!!.onActivity { installReader(it) }
        compose.waitUntil(15_000) { model.state.value.ready?.entry?.processing?.paragraphIndent?.length == 4 &&
            model.state.value.pageCount > 0 }
        assertTrue(model.state.value.anchor > oldOffset)
        assertEquals(suffix, model.state.value.ready!!.document.text.substring(model.state.value.anchor).take(12))
    }
    @Test fun changingIndentInTheSameEntryKeepsTheSameBodyCharacterAcrossParagraphs() {
        layout.paragraphIndent = "\u3000\u3000"
        launch()
        val previous = model.state.value.ready!!
        val oldOffset = model.state.value.anchor
        val suffix = previous.document.text.substring(oldOffset).take(12)
        compose.runOnIdle { layout.paragraphIndent = ""; model.refreshAppearance() }
        compose.waitUntil(15_000) { model.state.value.ready?.entry?.processing?.paragraphIndent == "" && model.state.value.pageCount > 0 }
        val next = model.state.value.ready!!
        assertEquals(previous.key.owner, next.key.owner)
        assertNotEquals(previous.key.generation, next.key.generation)
        val offset = model.state.value.anchor
        assertTrue(offset < oldOffset)
        assertEquals(suffix, next.document.text.substring(offset).take(12))
        assertFalse(compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetSelection))
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.contains('\u3000'))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos == offset }
        compose.runOnIdle { layout.paragraphIndent = "\u3000\u3000"; model.refreshAppearance() }
        compose.waitUntil(15_000) { model.state.value.ready?.entry?.processing?.paragraphIndent == "\u3000\u3000" && model.state.value.pageCount > 0 }
        assertEquals(oldOffset, model.state.value.anchor)
        assertEquals(suffix, model.state.value.ready!!.document.text.substring(model.state.value.anchor).take(12))
        assertTrue(compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetSelection))
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.contains("\u3000\u3000"))
    }
    @Test fun colorsAndBackgroundRefreshInTheSameResumedEntryWithoutRecreation() {
        launch()
        compose.waitUntil(10_000) { pixel() == 0xff00cc22.toInt() }
        val owner = model.state.value.ready!!.key.owner
        switchToNight()
        compose.waitUntil(10_000) { pixel() == 0xff2244cc.toInt() }
        assertEquals(owner, model.state.value.ready!!.key.owner)
        assertEquals(200, model.state.value.anchor)
        assertEquals(selected.curTextColor(), model.state.value.ready!!.entry.textColor)
        compose.runOnIdle { layout.bgAlpha = 0; model.refreshAppearance() }
        compose.waitUntil(10_000) { model.state.value.background?.alpha == 0 && pixel() == model.state.value.background!!.fallbackColor }
        val fallback = model.state.value.background!!.fallbackColor
        val alternative = if (fallback == 0xff000000.toInt()) 0xffffffff.toInt() else 0xff000000.toInt()
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(selected.curTextColor(), fallback) >=
            androidx.core.graphics.ColorUtils.calculateContrast(selected.curTextColor(), alternative))
        assertEquals(200, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun aTitleVisibilityChangeDuringRefreshKeepsTheSameBodyCharacter() {
        launch()
        val previous = model.state.value.ready!!
        compose.runOnIdle { layout.titleMode = 2 }
        switchToNight()
        compose.waitUntil(15_000) { model.state.value.ready?.document?.titleLength == 0 && model.state.value.pageCount > 0 }
        val expected = 200 - previous.document.titleLength
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos == expected }
        assertEquals(expected, model.state.value.anchor)
        assertNotEquals(previous.key, model.state.value.ready!!.key)
    }
}
