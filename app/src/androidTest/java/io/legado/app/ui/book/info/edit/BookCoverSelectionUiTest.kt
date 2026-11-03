package io.legado.app.ui.book.info.edit

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.navigation.EditBookInfoRoute
import io.legado.app.utils.defaultSharedPreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BookCoverSelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val prefs = context.defaultSharedPreferences
    private val previous = listOf(PreferKey.autoBackup, PreferKey.cronet).associateWith { prefs.all[it] }
    private val privacy = LocalConfig.all["privacyPolicyOk"]
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var server: NanoHTTPD
    private val authorizedImages = AtomicInteger()
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.autoBackup, false).putBoolean(PreferKey.cronet, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit()
        val bytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() } }.toByteArray()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.headers["x-cover-test"] != id) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Missing cover credentials")
                authorizedImages.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource("$base/source", "Cover source $id", header = "{\"X-Cover-Test\":\"$id\"}")
        appDb.bookSourceDao.insert(source)
        book = Book(bookUrl = "cover-ui://$id", origin = "https://original.invalid", name = "Cover UI $id", author = "Author", tocUrl = "cover-ui://$id/toc", coverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter"))
        (1..2).forEach { index -> appDb.searchBookDao.insert(SearchBook(bookUrl = "$base/book$index", origin = source.bookSourceUrl, originName = source.bookSourceName, name = book.name, author = book.author, coverUrl = "$base/cover$index.png", originOrder = index)) }
    }
    @After fun cleanup() {
        server.stop(); appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); BookHelp.clearCache(book); appDb.bookSourceDao.delete(source)
        prefs.edit().apply { previous.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
        LocalConfig.edit().apply { if (privacy is Boolean) putBoolean("privacyPolicyOk", privacy) else remove("privacyPolicyOk") }.commit()
    }
    private fun waitFor(tag: String) = compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun openEditor() = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, EditBookInfoRoute(book.bookUrl)))
    @Test fun detailCoverEntrySelectsAuthenticatedCrossOriginCandidate() {
        ActivityScenario.launch<BookInfoActivity>(Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl)).use {
            waitFor("book_info_cover"); compose.onNodeWithTag("book_info_cover").performClick()
            waitFor("cover_result_1")
            compose.waitUntil(15000) { authorizedImages.get() > 0 }
            try {
                compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(source.bookSourceName).fetchSemanticsNodes().size == 2 }
            } catch (error: ComposeTimeoutException) {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                    compose.onAllNodes(isRoot())[index].printToLog("CoverSelectionFailure", maxDepth = 8)
                }
                val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    java.io.File(context.cacheDir, "cover-selector-failure.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { image.recycle() }
                throw AssertionError("Expected two decoded source covers; nodes=" +
                    compose.onAllNodesWithContentDescription(source.bookSourceName).fetchSemanticsNodes().size +
                    "; authenticated responses=" + authorizedImages.get(), error)
            }
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            java.io.File(context.cacheDir, "cover-selector.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            compose.onNodeWithTag("cover_result_1").performClick()
            compose.waitUntil(15000) { appDb.bookDao.getBook(book.bookUrl)?.customCoverUrl == "$base/cover1.png" }
            val selected = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(source.bookSourceUrl, selected.getCoverSourceOrigin())
            assertEquals(CoverSourceRef("$base/cover1.png", source.bookSourceUrl), selected.customCoverSource)
        }
    }
    @Test fun editorSelectionRemainsDraftAcrossRotationAndCommitsOnlyOnSave() {
        openEditor().use { scenario ->
            waitFor("book_edit_search_cover"); compose.onNodeWithTag("book_edit_search_cover").performClick()
            waitFor("cover_result_1"); compose.onNodeWithTag("cover_result_1").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("cover_search_dialog").fetchSemanticsNodes().isEmpty() }
            assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
            scenario.recreate()
            waitFor("book_edit_save"); compose.waitForIdle()
            compose.onNodeWithTag("book_edit_save").performClick()
            compose.waitUntil(15000) { appDb.bookDao.getBook(book.bookUrl)?.customCoverUrl == "$base/cover1.png" }
            assertEquals(source.bookSourceUrl, appDb.bookDao.getBook(book.bookUrl)!!.getCoverSourceOrigin())
        }
    }
    @Test fun closingEditorDoesNotCommitItsSelectedCover() {
        openEditor().use {
            waitFor("book_edit_search_cover"); compose.onNodeWithTag("book_edit_search_cover").performClick()
            waitFor("cover_result_2"); compose.onNodeWithTag("cover_result_2").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("cover_search_dialog").fetchSemanticsNodes().isEmpty() }
        }
        assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
        assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customCoverSource)
    }
}
