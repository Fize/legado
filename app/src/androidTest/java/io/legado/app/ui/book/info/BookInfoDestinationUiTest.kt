package io.legado.app.ui.book.info

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.repository.BookInfoPreviewStore
import io.legado.app.data.repository.navigationOwner
import io.legado.app.help.book.BookHelp
import io.legado.app.help.globalExecutor
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookInfoDestinationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://detail-$id.invalid", "Detail source $id", bookSourceType = BookSourceType.image)
    private val book = Book(bookUrl = "${source.bookSourceUrl}/book", name = "Detail destination $id", author = "Author $id",
        origin = source.bookSourceUrl, originName = source.bookSourceName, type = BookType.image,
        tocUrl = "${source.bookSourceUrl}/toc", totalChapterNum = 3, durChapterIndex = 0, durChapterPos = 0)
    private val route = BookInfoRoute(book.bookUrl)
    private var scenario: ActivityScenario<AppActivity>? = null
    private val extraBooks = mutableListOf<Book>()
    @Before fun setup() {
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        repeat(3) { index ->
            val chapter = BookChapter(bookUrl = book.bookUrl, url = "${source.bookSourceUrl}/chapter/$index", title = "Detail chapter ${index + 1}", index = index)
            appDb.bookChapterDao.insert(chapter)
            val url = "${source.bookSourceUrl}/image/$index.png"
            val image = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(0xff303030.toInt() + index)
                val data = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
                BookHelp.writeImage(book, url, data)
                assertTrue(BookHelp.saveContent(source, book, chapter, "<img src=\"$url\">"))
            } finally { image.recycle() }
        }
    }
    @After fun cleanup() {
        val deletion = appDb.bookInfoDeleteDao.latestForRequest(route.requestId)
        if (deletion != null && deletion.status != BookInfoDeleteStatus.Completed) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("book-info-delete-${deletion.id}")
            appDb.runInTransaction {
                appDb.bookInfoDeleteDao.update(deletion.copy(status = BookInfoDeleteStatus.Cancelled, executionToken = ""))
                appDb.bookInfoDeleteDao.release(deletion.id)
            }
        }
        scenario?.close()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        appDb.readerSessionDao.get("other-$id")?.let { appDb.readerSessionDao.close(it.id, it.ownerToken) }
        BookHelp.clearCache(book)
        (extraBooks + book).forEach { appDb.bookDao.delete(it) }
        appDb.bookSourceDao.delete(source)
        deletion?.let { appDb.bookInfoDeleteDao.deleteTerminal(it.id) }
    }
    private fun text(resource: Int): String {
        lateinit var result: String
        scenario!!.onActivity { result = it.getString(resource) }
        return result
    }
    private fun model(activity: AppActivity) = ViewModelProvider(navigationViewModelStores(activity, BookshelfRoute)
        .getOrCreateOwner(NavEntry(route) {}.contentKey))[BookInfoViewModel::class.java]
    private fun entry(activity: AppActivity) = ViewModelProvider(navigationViewModelStores(activity, BookshelfRoute)
        .getOrCreateOwner(NavEntry(route) {}.contentKey))[BookInfoEntryViewModel::class.java]
    private fun launch() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
            ready = model(it).uiState.value.book?.bookUrl == book.bookUrl && model(it).uiState.value.recoveryReady
        }; ready }
        compose.waitForIdle()
    }

    @Test fun aNewDetailEntryCanCancelThePreviousEntriesFailedDeletionAfterRecreation() = kotlinx.coroutines.runBlocking {
        val previous = appDb.bookInfoRequestDao.claim(BookInfoRequest("previous-$id", book.bookUrl, book.bookUrl, "previous", updatedAt = 1), "previous")
        val deletionId = "previous-delete-$id"
        val deletions = io.legado.app.data.repository.BookInfoDeleteRepository(appDb,
            object : io.legado.app.data.repository.BookInfoDeleteEffects(appDb) {
                override suspend fun removeRemote(command: BookInfoDeleteCommand, book: Book) {
                    throw io.legado.app.data.repository.BookInfoLibraryException(io.legado.app.data.repository.BookInfoLibraryError.RemoteDelete)
                }
            })
        try {
            deletions.accept(previous.navigationOwner(), deletionId, book, false, false, null)
            assertEquals(BookInfoDeleteStatus.Failed, deletions.execute(deletionId)!!.status)
            appDb.bookInfoRequestDao.close(previous.id, previous.ownerToken)
            launch()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_delete_cancel").fetchSemanticsNodes().size == 1 }
            scenario!!.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_delete_cancel").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("book_delete_cancel").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { appDb.bookInfoDeleteDao.get(deletionId)?.status == BookInfoDeleteStatus.Cancelled }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_delete_pending").fetchSemanticsNodes().isEmpty() }
            assertTrue(appDb.bookDao.has(book.bookUrl))
            assertNull(appDb.bookInfoDeleteDao.lease(book.bookUrl))
            assertEquals("", appDb.bookInfoDeleteDao.get(deletionId)!!.snapshot)
            assertFalse(appDb.bookInfoRequestDao.get(route.requestId)!!.deleted)
        } finally {
            appDb.bookInfoRequestDao.close(previous.id, previous.ownerToken)
            appDb.bookInfoDeleteDao.get(deletionId)?.let {
                appDb.bookInfoDeleteDao.update(it.copy(status = BookInfoDeleteStatus.Cancelled, snapshot = "", target = null))
                appDb.bookInfoDeleteDao.release(deletionId)
                appDb.bookInfoDeleteDao.deleteTerminal(deletionId)
            }
        }
    }
    private fun atDetailAndConsumed(): Boolean {
        var ready = false
        scenario!!.onActivity { ready = it.currentDestination == route && entry(it).state.value.child == null &&
            model(it).uiState.value.recoveryReady }
        return ready
    }

    @Test fun theRealDestinationBootstrapsItsScreenAndRetainsItsOwnerAcrossRecreation() {
        launch()
        compose.onNodeWithText(book.name).assertIsDisplayed()
        lateinit var first: BookInfoEntryViewModel
        scenario!!.onActivity { first = entry(it) }
        scenario!!.recreate()
        compose.waitUntil(10_000) { atDetailAndConsumed() }
        scenario!!.onActivity { assertSame(first, entry(it)) }
        compose.onNodeWithText(book.name).assertIsDisplayed()
        assertEquals(route.previewKey, appDb.bookInfoRequestDao.get(route.requestId)!!.previewKey)
    }

    @Test fun editorSaveReturnsToTheSameDetailAndRefreshesItsDurablePreviewBeforeConsumption() {
        launch()
        compose.onNodeWithContentDescription(text(R.string.edit)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_edit_name").fetchSemanticsNodes().isNotEmpty() }
        val changed = "Edited detail $id"
        compose.onNodeWithTag("book_edit_name").performTextReplacement(changed)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("book_edit_save").performClick()
        compose.waitUntil(10_000) { atDetailAndConsumed() && appDb.bookDao.getBook(book.bookUrl)?.name == changed }
        compose.onNodeWithText(changed).assertIsDisplayed()
        assertEquals(changed, BookInfoPreviewStore(context).peek(route.previewKey)!!.book.name)
        scenario!!.recreate(); compose.waitUntil(10_000) { atDetailAndConsumed() }
        compose.onNodeWithText(changed).assertIsDisplayed()
    }

    @Test fun catalogSelectionOpensTheOwnedMangaReaderAndBackRefreshesTheSameDetail() {
        launch()
        compose.onNodeWithText(text(R.string.view_toc)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Detail chapter 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Detail chapter 3").performClick()
        compose.waitUntil(10_000) { var reading = false; scenario!!.onActivity {
            reading = it.currentDestination is MangaReaderRoute &&
                it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                appDb.bookDao.getBook(book.bookUrl)?.durChapterIndex == 2
        }; reading }
        val child = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
        assertEquals(BookInfoChildKind.Reader, child.kind)
        assertEquals(BookInfoChildPhase.Displayed, child.phase)
        Espresso.pressBack()
        compose.waitUntil(10_000) { atDetailAndConsumed() }
        scenario!!.onActivity { assertEquals(2, model(it).uiState.value.book!!.durChapterIndex) }
        assertEquals(BookInfoChildPhase.Consumed, appDb.bookInfoRequestDao.child(child.id)!!.phase)
    }

    @Test fun cancellingCatalogKeepsAPreviewThatAnotherReaderOwns() {
        appDb.bookDao.update(book.copy(type = BookType.image or BookType.notShelf))
        appDb.readerSessionDao.claim(ReaderSessionState("other-$id", book.bookUrl, book.bookUrl, false, false, updatedAt = 1), "other")
        launch()
        compose.onNodeWithText(text(R.string.view_toc)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Detail chapter 3").fetchSemanticsNodes().isNotEmpty() }
        Espresso.pressBack()
        compose.waitUntil(10_000) { atDetailAndConsumed() }
        assertNotNull(appDb.bookDao.getBook(book.bookUrl))
        assertTrue(appDb.readerSessionDao.ownsBook("other-$id", "other", 0, book.bookUrl))
    }

    @Test fun anUnshelvedSourceChangeMovesTheEntryWithoutInsertingTheNewPreviewIntoTheLibrary() {
        appDb.bookDao.update(book.copy(type = BookType.image or BookType.notShelf))
        launch()
        val changed = book.copy(bookUrl = "${source.bookSourceUrl}/changed", tocUrl = "${source.bookSourceUrl}/changed-toc")
        val chapters = listOf(BookChapter(bookUrl = changed.bookUrl, url = "changed-chapter", title = "Changed chapter"))
        scenario!!.onActivity { model(it).requestSourceChange(source, changed, chapters) }
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
            ready = model(it).uiState.value.book?.bookUrl == changed.bookUrl && model(it).uiState.value.recoveryReady &&
                entry(it).state.value.request?.bookUrl == changed.bookUrl
        }; ready }
        assertFalse(appDb.bookDao.has(changed.bookUrl))
        assertNotNull(appDb.bookDao.getBook(book.bookUrl))
        assertEquals(changed.bookUrl, BookInfoPreviewStore(context).peek(route.previewKey)!!.book.bookUrl)
    }

    @Test fun leavingTheEntryClosesItsScriptBrowserWhileTheActivityRemainsResumed() {
        launch()
        scenario!!.onActivity { model(it).onButtonClick("Owned browser",
            "java.showBrowser('https://entry-script.invalid','<html><body>Owned detail browser</body></html>',null,'{\"dialogHeight\":480}');") }
        compose.waitUntil(10_000) { var showing = false; scenario!!.onActivity { activity ->
            showing = activity.supportFragmentManager.fragments
                .filterIsInstance<io.legado.app.ui.widget.dialog.BottomWebViewDialog>()
                .any { it.ownerEntryKey == route.requestId && it.dialog?.isShowing == true }
        }; showing }
        scenario!!.onActivity { it.navigateTo(AppLogRoute) }
        compose.waitUntil(10_000) { var closed = false; scenario!!.onActivity { activity ->
            closed = activity.currentDestination == AppLogRoute && activity.supportFragmentManager.fragments
                .filterIsInstance<io.legado.app.ui.widget.dialog.BottomWebViewDialog>()
                .none { it.ownerEntryKey == route.requestId && it.dialog?.isShowing == true }
        }; closed }
    }

    @Test fun aCancelledEntryScopeCannotQueueABrowserOverTheNextRoute() {
        launch()
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        lateinit var extensions: io.legado.app.ui.login.SourceLoginJsExtensions
        scenario!!.onActivity { activity ->
            extensions = io.legado.app.ui.login.SourceLoginJsExtensions(activity, source).apply {
                uiScope = owner; uiOwnerKey = route.requestId
            }
            owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            activity.navigateTo(AppLogRoute)
        }
        extensions.showBrowser("https://cancelled-script.invalid", "<html>Must not open</html>", null, null)
        compose.waitForIdle()
        scenario!!.onActivity { activity ->
            assertEquals(AppLogRoute, activity.currentDestination)
            assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<io.legado.app.ui.widget.dialog.BottomWebViewDialog>().isEmpty())
        }
    }

    @Test fun aDisplayedEditorMissingFromTheRestoredStackIsCancelledAndConsumed() {
        val dao = appDb.bookInfoRequestDao
        dao.claim(BookInfoRequest(route.requestId, book.bookUrl, book.bookUrl, "old-parent", updatedAt = 1,
            previewKey = route.previewKey), "old-parent")
        val child = checkNotNull(dao.begin(route.requestId, "old-parent", 0, book.bookUrl,
            "orphan-$id", BookInfoChildKind.Edit, null, 2))
        dao.claimChild(child.id, book.bookUrl, child.kind, "old-editor", 3)
        launch()
        compose.waitUntil(10_000) { dao.child(child.id)?.phase == BookInfoChildPhase.Consumed && atDetailAndConsumed() }
        compose.onNodeWithText(book.name).assertIsDisplayed()
        assertEquals(2L, dao.get(route.requestId)!!.revision)
    }

    @Test fun closingTheSourceDialogDisposesItsViewModelAndReopeningUsesTheEditedIdentity() {
        launch()
        fun sourceModel(activity: AppActivity): io.legado.app.ui.book.changesource.ChangeBookSourceViewModel {
            val parent = navigationViewModelStores(activity, BookshelfRoute).getOrCreateOwner(NavEntry(route) {}.contentKey)
            return ViewModelProvider(androidx.lifecycle.viewmodel.ViewModelStoreProvider(parent, "detail-source")
                .getOrCreateOwner("dialog"))[io.legado.app.ui.book.changesource.ChangeBookSourceViewModel::class.java]
        }
        compose.onNodeWithText(text(R.string.change_origin)).performScrollTo().performClick()
        lateinit var first: io.legado.app.ui.book.changesource.ChangeBookSourceViewModel
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity { first = sourceModel(it); ready = first.name == book.name }; ready }
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(10_000) { first.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled == true }
        compose.onNodeWithContentDescription(text(R.string.edit)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_edit_name").fetchSemanticsNodes().isNotEmpty() }
        val changed = "Source identity $id"
        compose.onNodeWithTag("book_edit_name").performTextReplacement(changed)
        Espresso.closeSoftKeyboard(); compose.onNodeWithTag("book_edit_save").performClick()
        compose.waitUntil(10_000) { atDetailAndConsumed() && appDb.bookDao.getBook(book.bookUrl)?.name == changed }
        compose.onNodeWithText(text(R.string.change_origin)).performScrollTo().performClick()
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
            val fresh = sourceModel(it); ready = fresh !== first && fresh.name == changed
        }; ready }
    }

    @Test fun anOrphanedPreviewEditorRefreshesOldCachedMetadataBeforeItsResultIsConsumed() {
        val previewBook = book.copy(type = BookType.image or BookType.notShelf)
        appDb.bookDao.update(previewBook)
        val previews = BookInfoPreviewStore(context)
        val first = previews.open(route.previewKey)
        assertTrue(previews.save(first, 1, io.legado.app.data.repository.BookInfoPreview(previewBook,
            appDb.bookChapterDao.getChapterList(book.bookUrl), source.bookSourceUrl, false)))
        val changed = "Restored editor $id"
        appDb.bookDao.update(previewBook.copy(name = changed))
        val dao = appDb.bookInfoRequestDao
        dao.claim(BookInfoRequest(route.requestId, book.bookUrl, book.bookUrl, "old-parent", updatedAt = 1,
            previewKey = route.previewKey), "old-parent")
        val child = checkNotNull(dao.begin(route.requestId, "old-parent", 0, book.bookUrl,
            "orphan-preview-$id", BookInfoChildKind.Edit, null, 2))
        dao.claimChild(child.id, book.bookUrl, child.kind, "old-editor", 3)
        launch()
        compose.waitUntil(10_000) { dao.child(child.id)?.phase == BookInfoChildPhase.Consumed && atDetailAndConsumed() }
        compose.onNodeWithText(changed).assertIsDisplayed()
        assertEquals(changed, previews.peek(route.previewKey)!!.book.name)
    }
    @Test fun confirmedDeletionRunsAsDurableWorkAndClosesTheActualDetailEntry() {
        val confirm = io.legado.app.help.config.LocalConfig.bookInfoDeleteAlert
        io.legado.app.help.config.LocalConfig.bookInfoDeleteAlert = true
        try {
            launch()
            compose.onNodeWithText(text(R.string.remove_from_bookshelf)).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
                ready = model(it).uiState.value.libraryAction.phase == BookInfoLibraryPhase.ConfirmDelete
            }; ready }
            lateinit var commandId: String
            scenario!!.onActivity { commandId = checkNotNull(model(it).uiState.value.libraryAction.requestId) }
            compose.onNodeWithTag("library_delete_confirm").performClick()
            compose.waitUntil(20_000) { appDb.bookInfoDeleteDao.get(commandId)?.status == BookInfoDeleteStatus.Completed }
            assertFalse(appDb.bookDao.has(book.bookUrl))
            assertNull(appDb.bookInfoDeleteDao.lease(book.bookUrl))
            compose.waitUntil(10_000) {
                if (scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED) true
                else { var left = false; scenario!!.onActivity { left = it.currentDestination != route }; left }
            }
            val command = checkNotNull(appDb.bookInfoDeleteDao.get(commandId))
            assertEquals(route.requestId, command.requestId)
            assertTrue(command.callbackClaimed)
        } finally { io.legado.app.help.config.LocalConfig.bookInfoDeleteAlert = confirm }
    }

}
