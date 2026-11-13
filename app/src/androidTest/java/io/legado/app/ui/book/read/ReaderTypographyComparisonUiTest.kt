package io.legado.app.ui.book.read

import android.graphics.Bitmap
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

@RunWith(AndroidJUnit4::class)
class ReaderTypographyComparisonUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun capturePresetRendering() {
        val oldConfigs=ReadBookConfig.configList.toList()
        val paragraph="此开卷第一回也。作者自云：因曾历过一番梦幻之后，故将真事隐去，而借“通灵”之说，撰此《石头记》一书也。故曰“甄士隐”云云。但书中所记何事何人？自又云：“今风尘碌碌，一事无成，忽念及当日所有之女子，一一细考较去，觉其行止见识，皆出于我之上。何我堂堂须眉，诚不若彼裙钗哉？实愧则有余，悔又无益之大无可如何之日也！当此，则自欲将已往所赖天恩祖德，锦衣纨袴之时，饫甘餍肥之日，背父兄教育之恩，负师友规训之德，以至今日一技无成、半生潦倒之罪，编述一集，以告天下人：我之罪固不免，然闺阁中本自历历有人，万不可因我之不肖，自护己短，一并使其泯灭也。虽今日之茅椽蓬牖，瓦灶绳床，其晨夕风露，阶柳庭花，亦未有妨我之襟怀笔墨者。"
        val oldIndex=ReadBookConfig.styleSelect
        val oldShared=ReadBookConfig.shareLayout
        val oldMode=AppConfig.themeMode
        val oldEink=AppConfig.isEInkMode
        val evidence=File(context.cacheDir,"typography-comparison").apply { mkdirs() }
        try {
            for (label in listOf("kindle-before", "weread-before", "kindle-after", "weread-after", "kindle-night")) {
                val name=if(label.startsWith("kindle")) "Kindle" else "微信读书"
                val file=File(context.cacheDir,"$label.txt").apply { writeText((0 until 15).joinToString("\n\n") { paragraph }) }
                val book=Book(bookUrl=file.path,originName=file.name,name="红楼梦",charset="UTF-8",type=BookType.local or BookType.text,totalChapterNum=1).apply { setPageAnim(PageAnim.noAnim) }
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url=label,title="第一回",start=0L,end=file.length()))
                instrumentation.runOnMainSync {
                    AppConfig.themeMode=if(label.endsWith("night")) "2" else "1"; AppConfig.isEInkMode=false
                    ReadBookConfig.shareLayout=false
                    ReadBookConfig.styleSelect=ReadBookConfig.configList.indexOfFirst { it.name==name }
                    val index=ReadBookConfig.styleSelect
                    val original=oldConfigs[index]
                    ReadBookConfig.configList[index]=if(label.endsWith("before")) original.copy(textSize=if(name=="Kindle") 20 else 18,lineSpacingExtra=17,paddingLeft=if(name=="Kindle") 26 else 24,paddingRight=if(name=="Kindle") 26 else 24) else original
                    ChapterProvider.upStyle()
                }
                ActivityScenario.launch<ReadBookActivity>(context.bookReaderIntent(book)).use { scenario ->
                    awaitReader(scenario,book.bookUrl)
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(750)
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        File(evidence,"$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                    }
                    scenario.onActivity { activity ->
                        val page=activity.findViewById<ReadView>(R.id.read_view).curPage.textPage
                        if (!label.endsWith("before")) {
                            assertEquals(19,ReadBookConfig.textSize)
                            assertEquals(0f,ChapterProvider.contentPaint.letterSpacing,0f)
                            assertEquals(28,ReadBookConfig.paddingLeft)
                            val body=page.lines.filter { !it.isTitle }
                            val pitch=body[1].lineBase-body[0].lineBase
                            assertTrue("Expected line pitch within 5 px of WeRead's measured 115 px; got $pitch",pitch in 110f..120f)
                        }
                        File(evidence,"metrics.txt").appendText("$label selected=${ReadBookConfig.styleSelect} size=${ReadBookConfig.textSize} letter=${ReadBookConfig.letterSpacing} line=${ReadBookConfig.lineSpacingExtra} density=${context.resources.displayMetrics.density} fontScale=${context.resources.configuration.fontScale} names=${ReadBookConfig.configList.map { it.name }} bases=${page.lines.filter { !it.isTitle }.take(10).map { it.lineBase }}\n")
                    }

                }
                appDb.bookDao.delete(book); file.delete(); TextFile.clear()
            }
        } finally {
            instrumentation.runOnMainSync { ReadBookConfig.configList.clear(); ReadBookConfig.configList.addAll(oldConfigs); ReadBookConfig.styleSelect=oldIndex; ReadBookConfig.shareLayout=oldShared; AppConfig.themeMode=oldMode; AppConfig.isEInkMode=oldEink; ChapterProvider.upStyle() }
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
