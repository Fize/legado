package io.legado.app.ui.book.manga

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.MangaReaderSessionRepository
import io.legado.app.model.MangaReaderSession
import io.legado.app.data.repository.MangaReaderPreferences
import io.legado.app.data.repository.MangaReaderPreferencesStore
import io.legado.app.data.repository.MangaContentRepository
import io.legado.app.data.repository.MangaReaderDataRepository
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class MangaReaderStateTest {

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        ReadMangaViewModel(
            savedState,
            MangaReaderSessionRepository(MangaReaderSession(MangaReaderDataRepository())),
            FakePreferencesStore(),
            MangaContentRepository(),
            MangaReaderDataRepository(),
            io.legado.app.data.cache.CacheResourceLeases(),
        )

    private class FakePreferencesStore : MangaReaderPreferencesStore {
        private val state = MutableStateFlow(MangaReaderPreferences())
        override val preferences = state
        override suspend fun current() = state.value
        override suspend fun update(
            transform: (MangaReaderPreferences) -> MangaReaderPreferences,
        ) {
            state.value = transform(state.value)
        }
    }

    private fun page(chapter: Int, index: Int) = MangaPage(
        chapterIndex = chapter,
        chapterSize = 2,
        mImageUrl = "$chapter/$index",
        index = index,
        imageCount = 4,
        mChapterName = "chapter $chapter",
    )

    @Test fun liveFilterPreviewSurvivesWindowLayoutChangesWithoutOverwritingStoredPreferences() {
        val model = viewModel()
        model.applyPreferences(MangaReaderPreferences())
        model.showDialog(MangaReaderDialog.ColorFilter)
        model.handleDialogAction(MangaReaderDialogAction.ColorFilter(
            io.legado.app.ui.book.manga.config.MangaColorFilterConfig(r = 24, l = 77), false))
        model.updateWindowSize(1280, 800)
        assertEquals(77, model.readerUiState.value.windowBrightness)
        assertEquals(24, model.readerUiState.value.colorFilter[0])
        assertEquals("", model.readerUiState.value.colorFilterJson)
    }

    @Test fun singlePagePreferencesClearTheDefaultDoublePageLayoutBeforeContentCanAlign() {
        val model = viewModel()
        model.updateWindowSize(1280, 800)
        assertTrue(model.readerUiState.value.doublePage)
        model.applyPreferences(MangaReaderPreferences(horizontalScrollEnabled = true, doublePageEnabled = false))
        val state = model.readerUiState.value
        assertTrue(state.horizontal)
        assertFalse(state.doublePage)
        assertEquals(1, alignSpreadStartPosition(1, (0..3).map { page(1, it) }, state.doublePage))
    }

    @Test fun windowChangesRecomputeTheEffectiveLayoutWithoutAnActivityRoundTrip() {
        val model = viewModel()
        model.applyPreferences(MangaReaderPreferences(horizontalScrollEnabled = false, doublePageEnabled = true))
        model.updateWindowSize(1280, 800)
        assertTrue(model.readerUiState.value.doublePage)
        assertTrue(model.readerUiState.value.horizontal)
        val version = model.readerUiState.value.layoutVersion
        model.updateWindowSize(800, 1280)
        assertFalse(model.readerUiState.value.doublePage)
        assertFalse(model.readerUiState.value.horizontal)
        assertEquals(version + 1, model.readerUiState.value.layoutVersion)
        model.updateWindowSize(800, 1280)
        assertEquals(version + 1, model.readerUiState.value.layoutVersion)
    }

    @Test fun aPageReportFromAnOlderContentGenerationIsRejected() {
        val page = page(1, 1)
        val state = MangaReaderUiState(items = listOf(page), loadingVisible = false, contentVersion = 3, layoutVersion = 2)
        assertFalse(isCurrentMangaPageReport(state, page, 2, 2))
        assertFalse(isCurrentMangaPageReport(state, page, 3, 1))
    }
    @Test fun anOldImageAtTheSameNumericPageCannotReportProgress() {
        val page = page(1, 1)
        val state = MangaReaderUiState(items = listOf(page), loadingVisible = false, contentVersion = 3, layoutVersion = 2)
        assertFalse(isCurrentMangaPageReport(state, page.copy(mImageUrl = "old-book.png"), 3, 2))
    }
    @Test fun theCurrentSettledPageCanReportProgressAfterLoading() {
        val page = page(1, 1)
        val state = MangaReaderUiState(items = listOf(page), loadingVisible = false, contentVersion = 3, layoutVersion = 2)
        assertTrue(isCurrentMangaPageReport(state, page, 3, 2))
        assertFalse(isCurrentMangaPageReport(state.copy(loadingVisible = true), page, 3, 2))
    }

    @Test fun chapterCardsAdvanceTheAnchorWithoutOverwritingImageProgress() {
        val model = viewModel()
        val title = ReaderLoading(1, -1, "Chapter 2")
        val end = ReaderLoading(1, 4, "End")
        val items = listOf(title) + (0..3).map { page(1, it) } + end + ReaderLoading(2, -1, "Chapter 3")
        model.updateReaderState { it.copy(items = items, loadingVisible = false, horizontal = true,
            doublePage = false, anchorItemIndex = 1, seekPage = 0, contentVersion = 3, layoutVersion = 2) }
        assertTrue(model.acceptVisibleItem(title, 3, 2))
        assertEquals(0, model.readerUiState.value.anchorItemIndex)
        assertEquals(0, model.readerUiState.value.seekPage)
        assertEquals(MangaScrollRequest.ToPage(1, 0, true),
            resolveMangaScrollRequest(model.readerUiState.value, MangaReaderCommand.TurnPage(1)))
        assertTrue(model.acceptVisibleItem(end, 3, 2))
        assertEquals(5, model.readerUiState.value.anchorItemIndex)
        assertEquals(0, model.readerUiState.value.seekPage)
        assertEquals(MangaScrollRequest.ToPage(2, -1, true),
            resolveMangaScrollRequest(model.readerUiState.value, MangaReaderCommand.TurnPage(1)))
    }

    @Test fun staleOrDifferentChapterCardsCannotChangeTheViewportAnchor() {
        val card = ReaderLoading(1, -1, "Current")
        val state = MangaReaderUiState(items = listOf(card), loadingVisible = false, contentVersion = 3, layoutVersion = 2)
        assertEquals(0, currentMangaItemIndex(state, card, 3, 2))
        assertEquals(-1, currentMangaItemIndex(state, card, 2, 2))
        assertEquals(-1, currentMangaItemIndex(state, card, 3, 1))
        assertEquals(-1, currentMangaItemIndex(state, card.copy(mMessage = "Previous"), 3, 2))
        assertEquals(-1, currentMangaItemIndex(state, card.copy(isVolume = true), 3, 2))
        assertEquals(-1, currentMangaItemIndex(state, page(1, -1), 3, 2))
    }

    @Test fun dialogDismissalClearsOnlyThePendingCloudDecision() {
        val model = viewModel()
        model.showDialog(MangaReaderDialog.CloudProgress)
        model.handleDialogAction(MangaReaderDialogAction.CloudProgress(false))
        assertNull(model.readerUiState.value.dialog)
        assertNull(model.readerUiState.value.pendingCloudProgress)
    }

    @Test fun lateDialogPreviewsCannotChangeAnotherOpenDialog() {
        val model = viewModel()
        model.showDialog(MangaReaderDialog.Footer)
        model.handleDialogAction(MangaReaderDialogAction.Epaper(7, false))
        assertEquals(150, model.readerUiState.value.eInkThreshold)
        assertEquals(MangaReaderDialog.Footer, model.readerUiState.value.dialog)
        model.showDialog(MangaReaderDialog.Epaper)
        model.handleDialogAction(MangaReaderDialogAction.Epaper(7, false))
        assertEquals(7, model.readerUiState.value.eInkThreshold)
    }

    @Test fun invalidDownloadDecisionsNeverReachThePlatformGateway() {
        val model = viewModel()
        assertTrue(model.handleDialogAction(MangaReaderDialogAction.Download(0, 2)))
        model.showDialog(MangaReaderDialog.DownloadRange(1, 3))
        assertTrue(model.handleDialogAction(MangaReaderDialogAction.Download(-1, 2)))
        assertTrue(model.handleDialogAction(MangaReaderDialogAction.Download(2, 1)))
        assertTrue(model.handleDialogAction(MangaReaderDialogAction.Download(0, 3)))
        assertFalse(model.handleDialogAction(MangaReaderDialogAction.Download(0, 2)))
    }

    @Test fun scrollTargetsSurvivePrependingPreviousChapterPages() {
        val items = (0..3).map { page(1, it) }
        val state = MangaReaderUiState(items = items, horizontal = true, anchorItemIndex = 1)
        val target = resolveMangaScrollRequest(state, MangaReaderCommand.TurnPage(1)) as MangaScrollRequest.ToPage
        assertEquals(1, target.chapterIndex); assertEquals(2, target.pageIndex)
        val expanded = (0..3).map { page(0, it) } + items
        assertEquals(6, mangaScrollTarget(expanded, target))
    }
    @Test fun doublePageTurnsResolveToTheNextSpreadIdentity() {
        val state = MangaReaderUiState(items = (0..3).map { page(1, it) }, horizontal = true, doublePage = true, anchorItemIndex = 0)
        assertEquals(MangaScrollRequest.ToPage(1, 2, true), resolveMangaScrollRequest(state, MangaReaderCommand.TurnPage(1)))
    }
    @Test fun verticalTurnsResolveOnePageEvenWhenDoublePagePreferenceIsSet() {
        val state = MangaReaderUiState(items = (0..3).map { page(1, it) }, horizontal = false, doublePage = true, anchorItemIndex = 0)
        assertEquals(MangaScrollRequest.ToPage(1, 1, true), resolveMangaScrollRequest(state, MangaReaderCommand.TurnPage(1)))
    }
    @Test fun oldCommandAcknowledgementsCannotConsumeANewerRequest() {
        val model = viewModel()
        model.updateReaderState { it.copy(items = (0..3).map { index -> page(1, index) }) }
        model.dispatchReaderCommand(MangaReaderCommand.TurnPage(1))
        val first = model.readerUiState.value.commandVersion
        model.dispatchReaderCommand(MangaReaderCommand.TurnPage(1))
        model.consumeReaderCommand(first)
        assertTrue(model.readerUiState.value.command != null)
        model.consumeReaderCommand(model.readerUiState.value.commandVersion)
        assertNull(model.readerUiState.value.command)
    }
    @Test fun anEmptyReaderDoesNotInventAScrollTarget() {
        assertNull(resolveMangaScrollRequest(MangaReaderUiState(), MangaReaderCommand.TurnPage(1)))
    }
    @Test fun lateContentFromAnotherBookCannotEnterTheActiveSession() = kotlinx.coroutines.runBlocking {
        val session = MangaReaderSession(MangaReaderDataRepository())
        session.book = io.legado.app.data.entities.Book(bookUrl = "current")
        session.contentLoadFinish(io.legado.app.data.entities.BookChapter(bookUrl = "old", url = "old-chapter", title = "Old"), "<img src='old.png'>")
        assertNull(session.curMangaChapter)
        assertNull(session.prevMangaChapter)
        assertNull(session.nextMangaChapter)
        assertEquals("current", session.book?.bookUrl)
    }

    @Test
    fun `double page spreads never pair pages across chapter boundaries`() {
        val loading = ReaderLoading(chapterIndex = 1, index = -1, mMessage = "next")
        val items = listOf(page(0, 0), page(0, 1), page(0, 2), loading, page(1, 0), page(1, 1))

        val spreads = buildMangaSpreads(items, doublePage = true)

        assertEquals(listOf("0:0+1", "0:2", "1:-1", "1:0+1"), spreads.map { it.key })
        assertNull(spreads[1].second)
        assertNull(spreads[2].second)
    }

    @Test
    fun `single page mode keeps one stable spread per content item`() {
        val items = listOf(page(0, 0), page(0, 1), page(0, 2))
        val spreads = buildMangaSpreads(items, doublePage = false)

        assertEquals(3, spreads.size)
        assertEquals(listOf(0, 1, 2), spreads.map { it.firstItemIndex })
        assertEquals(1, findMangaSpreadIndex(spreads, 1))
    }

    @Test
    fun `odd restored page resolves to its containing double spread`() {
        val spreads = buildMangaSpreads((0..3).map { page(0, it) }, doublePage = true)

        assertEquals(0, findMangaSpreadIndex(spreads, 1))
        assertEquals(1, findMangaSpreadIndex(spreads, 3))
    }

    @Test
    fun `view model restores only stable reader position and layout keys`() {
        val savedState = SavedStateHandle()
        val first = viewModel(savedState)
        first.updateReaderState { state ->
            state.copy(
                anchorItemIndex = 17,
                horizontal = true,
                doublePage = true,
                rightToLeft = true,
                noticeMessage = "transient",
            )
        }

        val restored = viewModel(savedState).readerUiState.value

        assertEquals(17, restored.anchorItemIndex)
        assertEquals(true, restored.horizontal)
        assertEquals(true, restored.doublePage)
        assertEquals(true, restored.rightToLeft)
        assertNull(restored.noticeMessage)
    }

    @Test
    fun `reader load failure is retained in state while loading`() {
        val viewModel = viewModel()

        viewModel.showLoading()
        viewModel.loadFail("failed", retry = true)

        val state = viewModel.readerUiState.value
        assertTrue(state.loadingVisible)
        assertEquals("failed", state.loadingErrorMessage)
        assertTrue(state.canRetryLoading)
        assertNull(state.noticeMessage)
    }

    @Test
    fun `reader background failure is exposed as consumable notice`() {
        val viewModel = viewModel()
        viewModel.updateReaderState { it.copy(loadingVisible = false) }

        viewModel.loadFail("failed", retry = false)
        assertFalse(viewModel.readerUiState.value.loadingVisible)
        assertEquals("failed", viewModel.readerUiState.value.noticeMessage)

        viewModel.clearNoticeMessage()
        assertNull(viewModel.readerUiState.value.noticeMessage)
    }

    @Test
    fun `manga image request prefers an existing cache without global reader state`() {
        val cache = File.createTempFile("manga-page", ".jpg")
        val page = page(chapter = 0, index = 0).copy(cachePath = cache.absolutePath)

        assertEquals(cache.absolutePath, mangaImagePath(page))

        cache.delete()
        assertEquals(page.mImageUrl, mangaImagePath(page))
    }
}
