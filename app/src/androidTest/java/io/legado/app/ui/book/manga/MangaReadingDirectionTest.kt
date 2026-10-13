package io.legado.app.ui.book.manga

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadManga
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MangaReadingDirectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val source = BookSource(
        bookSourceUrl = "https://compose-manga-${UUID.randomUUID()}.invalid",
        bookSourceName = "Compose manga fixture",
        bookSourceType = BookSourceType.image,
    )
    private val book = Book(
        bookUrl = "${source.bookSourceUrl}/book",
        tocUrl = "${source.bookSourceUrl}/toc",
        origin = source.bookSourceUrl,
        name = "Compose manga ${UUID.randomUUID()}",
        type = BookType.image,
        totalChapterNum = 3,
        durChapterIndex = 1,
        durChapterPos = 1,
        canUpdate = false,
    )
    private var scenario: ActivityScenario<ReadMangaActivity>? = null

    @Before
    fun setUp() {
        assertTrue(
            preferences.edit()
                .putBoolean(PreferKey.enableMangaHorizontalScroll, true)
                .putBoolean(PreferKey.enableMangaDoublePage, false)
                .putBoolean(PreferKey.mangaRightToLeft, false)
                .putBoolean(PreferKey.hideMangaTitle, true)
                .putBoolean(PreferKey.disableMangaScale, false)
                .putBoolean(PreferKey.disableMangaPageAnim, false)
                .putBoolean(PreferKey.enableReadRecord, false)
                .putBoolean(PreferKey.syncBookProgress, false)
                .putBoolean(PreferKey.syncBookProgressPlus, false)
                .putInt(PreferKey.mangaPreDownloadNum, 0)
                .commit()
        )
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        repeat(3) { chapterIndex ->
            val chapter = BookChapter(
                bookUrl = book.bookUrl,
                url = "${source.bookSourceUrl}/chapter/$chapterIndex",
                title = "Chapter ${chapterIndex + 1}",
                index = chapterIndex,
            )
            appDb.bookChapterDao.insert(chapter)
            val content = (0 until 4).joinToString("\n") { pageIndex ->
                val imageUrl = "${source.bookSourceUrl}/image/$chapterIndex/$pageIndex.png"
                val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xff202020.toInt() + chapterIndex * 0x1010 + pageIndex)
                    val bytes = ByteArrayOutputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                    BookHelp.writeImage(book, imageUrl, bytes)
                } finally {
                    bitmap.recycle()
                }
                "<img src=\"$imageUrl\">"
            }
            assertTrue(BookHelp.saveContent(source, book, chapter, content))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        ReadManga.executor.submit {}.get(30, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            if (ReadManga.book?.bookUrl == book.bookUrl) {
                ReadManga.clearMangaChapter()
                ReadManga.book = null
                ReadManga.bookSource = null
            }
        }
        BookHelp.clearCache(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        assertTrue(preferences.edit().clear().apply {
            savedPreferences.forEach { (key, value) -> putValue(key, value) }
        }.commit())
    }

    @Test
    fun horizontalPagerUsesLogicalPageOrderForLtrAndRtlKeys() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 1)

        scenario!!.onActivity { it.toggleRightToLeftOption() }
        assertTrue(AppConfig.mangaRightToLeft)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage(1, 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 1)
    }

    @Test
    fun switchingBetweenPagerAndWebtoonKeepsTheVisiblePage() {
        launchReader()
        scenario!!.onActivity { it.toggleHorizontalScrollOption() }
        awaitPage(1, 1)
        scenario!!.onActivity { activity ->
            assertFalse(AppConfig.enableMangaHorizontalScroll)
            assertEquals(1, activity.visibleReaderPage()?.index)
        }
        scenario!!.onActivity { it.toggleHorizontalScrollOption() }
        awaitPage(1, 1)
    }

    @Test
    fun rotationRestoresStableChapterAndPageIdentity() {
        launchReader()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage(1, 2)
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitPage(1, 2)
        scenario!!.onActivity { activity ->
            assertTrue(activity.readerItems().isNotEmpty())
            assertEquals(2, activity.visibleReaderPage()?.index)
        }
    }

    private fun launchReader() {
        scenario = ActivityScenario.launch(
            Intent(context, ReadMangaActivity::class.java).putExtra("bookUrl", book.bookUrl)
        )
        awaitPage(1, 1)
    }

    private fun key(code: Int) {
        scenario!!.onActivity { activity ->
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun awaitPage(chapter: Int, page: Int) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario?.onActivity { activity ->
                val visible = activity.visibleReaderPage()
                matched = !activity.isReaderLoading() && visible?.chapterIndex == chapter && visible.index == page
            }
            if (matched) return
            SystemClock.sleep(50L)
        }
        error("Timed out waiting for manga page $chapter/$page; actual=${ReadManga.durChapterIndex}/${ReadManga.durChapterPos}")
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) = apply {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
        }
    }
}
