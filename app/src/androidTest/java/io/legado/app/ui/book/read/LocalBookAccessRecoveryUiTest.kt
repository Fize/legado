package io.legado.app.ui.book.read

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.test.runCleanupSteps
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** A failed restored URI gets a visible recovery choice without deleting the library row. */
@RunWith(AndroidJUnit4::class)
class LocalBookAccessRecoveryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()

    @Test
    fun missingRestoredUriOffersReauthorizationAndCancelKeepsReadingState() {
        val root = File(context.cacheDir, "missing-local-$id").apply { check(mkdirs()) }
        val file = File(root, "Restored-$id.txt")
        val book = Book(
            bookUrl = Uri.fromFile(file).toString(),
            origin = BookType.localTag,
            originName = file.name,
            name = "Restored $id",
            author = "Fixture",
            type = BookType.local,
            durChapterIndex = 7,
            durChapterPos = 1536,
        )
        var scenario: ActivityScenario<ReadBookActivity>? = null
        try {
            appDb.bookDao.insert(book)
            scenario = ActivityScenario.launch(
                Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
            )
            awaitDialog()

            onView(withText(R.string.local_book_access_required_title))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.local_book_access_required_message))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.local_book_access_select_folder))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))

            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())

            val preserved = checkNotNull(appDb.bookDao.getBook(book.bookUrl))
            assertEquals(7, preserved.durChapterIndex)
            assertEquals(1536, preserved.durChapterPos)
        } finally {
            runCleanupSteps(
                { scenario?.close() },
                { BookHelp.clearCache(book) },
                { appDb.bookChapterDao.delByBook(book.bookUrl) },
                { appDb.bookDao.delete(book) },
                { check(root.deleteRecursively() || !root.exists()) },
            )
        }
    }

    private fun awaitDialog() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            try {
                onView(withText(R.string.local_book_access_required_title))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
                return
            } catch (failure: Throwable) {
                last = failure
                SystemClock.sleep(50)
            }
        }
        throw AssertionError("Local-book recovery dialog did not appear", last)
    }
}
