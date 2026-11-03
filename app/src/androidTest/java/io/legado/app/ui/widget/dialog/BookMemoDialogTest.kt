package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.os.SystemClock
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMemo
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.ui.AppActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BookMemoDialogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private val book = Book(
        bookUrl = "memo-test:${UUID.randomUUID()}",
        name = "Memo test ${UUID.randomUUID()}",
        author = "Memo author",
    )
    private var archive: File? = null

    @get:Rule
    val composeRule = createAndroidComposeRule<AppActivity>()

    @Before
    fun setUp() {
        appDb.bookDao.insert(book)
    }

    @After
    fun cleanUp() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<BookMemoDialog>()
                .forEach { it.dismissAllowingStateLoss() }
        }
        appDb.bookDao.delete(book)
        archive?.parentFile?.deleteRecursively()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test
    fun composeDialogKeepsMarkdownDraftAcrossRecreationAndSavesIt() {
        composeRule.activityRule.scenario.onActivity { activity ->
            BookMemoDialog().apply {
                arguments = Bundle().apply { putString("bookUrl", book.bookUrl) }
            }.show(activity.supportFragmentManager, "memo-test")
        }
        val edit = context.getString(R.string.edit)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(edit).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(edit).assertIsDisplayed().performClick()

        val markdown = "# 备忘标题\n\n**重要内容**\n\n- 第一条\n- 第二条"
        onView(isAssignableFrom(EditText::class.java))
            .inRoot(isDialog())
            .perform(replaceText(markdown), closeSoftKeyboard())
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            runCatching {
                onView(isAssignableFrom(EditText::class.java))
                    .inRoot(isDialog())
                    .check(matches(withText(markdown)))
            }.isSuccess
        }
        composeRule.onNodeWithText(context.getString(R.string.book_memo_save))
            .assertIsDisplayed().performClick()
        composeRule.waitUntil(10_000) {
            appDb.bookMemoDao.get(book.bookUrl)?.content == markdown
        }
        assertEquals(markdown, appDb.bookMemoDao.get(book.bookUrl)?.content)
    }

    @Test
    fun actualLanArchiveRestoresMemoWithoutResurrectingClearedContent() = runBlocking {
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = it != BackupConfig.bookshelfContentKey }
        BackupConfig.ignoreConfig["localBook"] = false
        appDb.bookMemoDao.save(book.bookUrl, "# Saved in the archive", 100)
        val backup = Backup.backupForLanTransferLocked(context).also { archive = it }
        ZipFile(backup).use { zip ->
            val entry = checkNotNull(zip.getEntry("bookMemo.json"))
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val memos = GSON.fromJsonArray<BookMemo>(json).getOrThrow()
            assertEquals("# Saved in the archive", memos.single { it.bookUrl == book.bookUrl }.content)
        }
        appDb.bookDao.delete(book)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertEquals("# Saved in the archive", appDb.bookMemoDao.get(book.bookUrl)!!.content)
        val changed = book.copy(bookUrl = book.bookUrl + "-changed")
        appDb.bookDao.replace(book, changed)
        appDb.bookMemoDao.save(changed.bookUrl, "New memo after changing source", 101)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertNull(appDb.bookMemoDao.get(changed.bookUrl))
        assertEquals("New memo after changing source", appDb.bookMemoDao.get(book.bookUrl)!!.content)
        appDb.bookMemoDao.save(book.bookUrl, "", 102)
        Restore.restoreOrThrow(context, backup.toUri(), lanTransfer = true)
        assertEquals("", appDb.bookMemoDao.get(book.bookUrl)!!.content)
    }
}
