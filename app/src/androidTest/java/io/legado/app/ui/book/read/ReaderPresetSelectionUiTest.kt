package io.legado.app.ui.book.read

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.bookReaderIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.utils.spToPx
import org.junit.Assert.assertFalse


@RunWith(AndroidJUnit4::class)
class ReaderPresetSelectionUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.v2.createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun themeSelectionAppliesTypographyAndSurvivesRecreation() {
        val file = File(context.cacheDir, "preset-diagnostic.txt")
        file.writeText((0 until 80).joinToString("\n\n") {
            "春天的清晨，阳光穿过窗户照在书桌上。我们翻开一本书，慢慢读过每一行文字。阅读应当自然舒适，标点与汉字之间保持协调，行与行之间留出适当的空白。"
        })
        val book = Book(bookUrl=file.path, originName=file.name, name="排版对比", charset="UTF-8",
            type=BookType.local or BookType.text, totalChapterNum=1).apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url="diagnostic",title="排版对比",start=0L,end=file.length()))
        val oldIndex=ReadBookConfig.styleSelect
        val oldShared=ReadBookConfig.shareLayout
        val oldMode=AppConfig.themeMode
        val oldEink=AppConfig.isEInkMode
        val oldConfigs=ReadBookConfig.configList.toList()
        val oldShareConfig=ReadBookConfig.shareConfig
        val kindleIndex=oldConfigs.indexOfFirst { it.name=="Kindle" }
        val wereadIndex=oldConfigs.indexOfFirst { it.name=="微信读书" }
        val evidence=File(context.cacheDir,"preset-selection").apply { mkdirs() }
        try {
            instrumentation.runOnMainSync {
                // Distinct values prove all three properties switch, even for user-edited presets.
                ReadBookConfig.configList[wereadIndex]=oldConfigs[wereadIndex].copy(textSize=18,letterSpacing=.03f,lineSpacingExtra=14)
                ReadBookConfig.shareConfig=oldShareConfig.copy(textSize=31,letterSpacing=.2f,lineSpacingExtra=25)
                ReadBookConfig.styleSelect=kindleIndex
                ReadBookConfig.shareLayout=true
                AppConfig.themeMode="1"; AppConfig.isEInkMode=false
                ChapterProvider.upStyle()
            }
            ActivityScenario.launch<ReadBookActivity>(context.bookReaderIntent(book)).use { scenario ->
                awaitReader(scenario,book.bookUrl)
                fun openDialog() { scenario.onActivity { ReadStyleDialog().showNow(it.supportFragmentManager,"selection-test") } }
                fun verify(index: Int) {
                    val preset=ReadBookConfig.configList[index]
                    compose.waitUntil(15000) {
                        !ReadBookConfig.shareLayout && ReadBookConfig.styleSelect==index &&
                            kotlin.math.abs(ChapterProvider.contentPaint.textSize-preset.textSize.toFloat().spToPx()) < .01f &&
                            kotlin.math.abs(ChapterProvider.contentPaint.letterSpacing-preset.letterSpacing) < .001f &&
                            kotlin.math.abs(ChapterProvider.lineSpacingExtra-preset.lineSpacingExtra/10f) < .001f
                    }
                    assertEquals(preset.textSize,ReadBookConfig.textSize)
                    assertEquals(preset.letterSpacing,ReadBookConfig.letterSpacing,0f)
                    assertEquals(preset.lineSpacingExtra,ReadBookConfig.lineSpacingExtra)
                    assertFalse(ReadBookConfig.shareLayout)
                }
                openDialog()
                compose.onNodeWithContentDescription("Kindle 1").assertIsDisplayed().performClick()
                verify(kindleIndex) // Reselecting the current preset exits shared layout too.
                val kindleBounds=compose.onNodeWithContentDescription("Kindle 1").fetchSemanticsNode().boundsInRoot
                val wereadBounds=compose.onNodeWithContentDescription("微信读书 2").fetchSemanticsNode().boundsInRoot
                assertTrue(kindleBounds.left < wereadBounds.left)
                compose.onNodeWithContentDescription("微信读书 2").performClick()
                verify(wereadIndex)
                compose.onNodeWithContentDescription("Kindle 1").performClick()
                verify(kindleIndex)
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(evidence,"kindle-first.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
                scenario.onActivity { (it.supportFragmentManager.findFragmentByTag("selection-test") as ReadStyleDialog).dismissNow() }
                ReadBookConfig.saveNow()
                instrumentation.runOnMainSync { ReadBookConfig.initConfigs(); ReadBookConfig.initShareConfig() }
                scenario.recreate()
                awaitReader(scenario,book.bookUrl)
                verify(kindleIndex)
                openDialog()
                compose.onNodeWithContentDescription("Kindle 1").assertIsDisplayed()
                compose.onNodeWithContentDescription("微信读书 2").performClick()
                verify(wereadIndex)
                scenario.onActivity { (it.supportFragmentManager.findFragmentByTag("selection-test") as ReadStyleDialog).dismissNow() }
            }
            // No preset settings or saved indices are changed merely to reorder the UI.
            assertEquals(oldConfigs.map { it.name },ReadBookConfig.configList.map { it.name })
            assertEquals(oldConfigs[kindleIndex],ReadBookConfig.configList[kindleIndex])
            File(evidence,"result.txt").writeText("PASS: Kindle first; same-theme reselect exits shared layout; Kindle/WeRead/Kindle switches actual paint size, spacing and line height; persisted config reload and activity recreation retain selection; subsequent switch works.")
        } finally {
            instrumentation.runOnMainSync {
                ReadBookConfig.configList.clear(); ReadBookConfig.configList.addAll(oldConfigs)
                ReadBookConfig.shareConfig=oldShareConfig
                ReadBookConfig.styleSelect=oldIndex; ReadBookConfig.shareLayout=oldShared
                AppConfig.themeMode=oldMode; AppConfig.isEInkMode=oldEink; ChapterProvider.upStyle()
            }
            ReadBookConfig.saveNow()
            appDb.bookDao.delete(book); file.delete(); TextFile.clear()
        }
    }

    private fun awaitReader(scenario: ActivityScenario<ReadBookActivity>, bookUrl: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val page = activity.findViewById<ReadView>(R.id.read_view).curPage
                ready = ReadBook.book?.bookUrl == bookUrl &&
                    ReadBook.curTextChapter?.isCompleted == true &&
                    !page.textPage.isMsgPage &&
                    page.textPage.lines.any { line -> line.columns.isNotEmpty() }
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        assertTrue("Mature reader did not render the E-ink fixture", false)
    }
}
