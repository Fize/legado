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

@RunWith(AndroidJUnit4::class)
class MatureReaderFeatureRetentionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun productionEntryKeepsMatureReaderRenderingInEInkMode() {
        val originalEInk = AppConfig.isEInkMode
        val originalThemeMode = AppConfig.themeMode
        val originalConfig = ReadBookConfig.durConfig.copy()
        val file = File.createTempFile("mature-eink-", ".txt", context.cacheDir)
        file.writeText((0 until 180).joinToString("\n\n") {
            "E-ink paragraph $it remains readable through the production reader entry."
        })
        val book = Book(
            bookUrl = file.absolutePath,
            originName = file.name,
            name = file.name,
            charset = "UTF-8",
            type = BookType.local or BookType.text,
            totalChapterNum = 1,
        ).apply { setPageAnim(PageAnim.noAnim) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = book.bookUrl,
                url = "mature-eink-chapter",
                title = "E-ink rendering",
                start = 0L,
                end = file.length(),
            ),
        )

        try {
            instrumentation.runOnMainSync {
                AppConfig.themeMode = "3"
                AppConfig.isEInkMode = true
                ReadBookConfig.durConfig.setCurTextColor(Color.BLACK)
                ReadBookConfig.durConfig.setCurBg(0, "#F5F5F0")
                ReadBookConfig.durConfig.setCurPageAnim(PageAnim.noAnim)
                ChapterProvider.upStyle()
            }
            val intent = context.bookReaderIntent(book)
            assertEquals(ReadBookActivity::class.java.name, intent.component?.className)

            ActivityScenario.launch<ReadBookActivity>(intent).use { scenario ->
                awaitReader(scenario, book.bookUrl)
                scenario.onActivity { activity ->
                    assertEquals(Color.BLACK, ReadBookConfig.durConfig.curTextColor())
                    assertEquals("#F5F5F0", ReadBookConfig.durConfig.curBgStr())
                    assertEquals(PageAnim.noAnim, ReadBookConfig.durConfig.curPageAnim())

                    val readView = activity.findViewById<ReadView>(R.id.read_view)
                    val page = readView.curPage
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.draw(Canvas(bitmap))
                        var backgroundPixels = 0
                        var darkTextPixels = 0
                        for (y in bitmap.height / 8 until bitmap.height * 7 / 8 step 3) {
                            for (x in bitmap.width / 8 until bitmap.width * 7 / 8 step 3) {
                                val color = bitmap.getPixel(x, y)
                                if (color == Color.rgb(245, 245, 240)) backgroundPixels++
                                if (Color.red(color) < 64 && Color.green(color) < 64 && Color.blue(color) < 64) {
                                    darkTextPixels++
                                }
                            }
                        }
                        assertTrue("E-ink page must render the configured paper background", backgroundPixels > 1_000)
                        assertTrue("E-ink page must draw readable dark text", darkTextPixels > 40)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                AppConfig.themeMode = originalThemeMode
                AppConfig.isEInkMode = originalEInk
                ReadBookConfig.durConfig = originalConfig
                ChapterProvider.upStyle()
            }
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
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
