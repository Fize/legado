package io.legado.app.ui.book.manga

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.repository.MangaReaderSessionListener
import io.legado.app.data.repository.MangaReaderSessionRepository
import io.legado.app.data.repository.MangaReaderPreferences
import io.legado.app.data.repository.MangaReaderPreferencesStore
import io.legado.app.data.repository.MangaContentRepository
import io.legado.app.data.repository.ReaderOpenRequest
import io.legado.app.data.repository.ReaderSessionRepository
import io.legado.app.data.repository.progressOwner
import io.legado.app.data.repository.MangaReaderDataRepository
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.postEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import io.legado.app.data.entities.ReaderDetailPhase
import io.legado.app.help.book.isImage
import kotlinx.coroutines.isActive
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@HiltViewModel
class ReadMangaViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sessionRepository: MangaReaderSessionRepository,
    private val preferencesRepository: MangaReaderPreferencesStore,
    private val contentRepository: MangaContentRepository,
    private val dataRepository: MangaReaderDataRepository,
    private val cacheLeases: CacheResourceLeases,
    private val readerSessions: ReaderSessionRepository = ReaderSessionRepository(),
    private val downloadCommands: io.legado.app.service.BookDownloadCommands? = null,
) : ViewModel(), MangaReaderSessionListener {

    val showReadTitleBarAddition: Boolean
        get() = dataRepository.titleBarAdditionVisible

    val globalEInkModeEnabled: Boolean
        get() = dataRepository.globalEInkModeEnabled

    val enhancedProgressSyncEnabled: Boolean
        get() = dataRepository.enhancedProgressSyncEnabled

    val openReaderLinksInBrowser: Boolean
        get() = dataRepository.openReaderLinksInBrowser

    val addToShelfConfirmationEnabled: Boolean
        get() = dataRepository.addToShelfConfirmationEnabled

    fun setOpenReaderLinksInBrowser(enabled: Boolean) {
        dataRepository.openReaderLinksInBrowser = enabled
    }

    fun savedImageDirectory(): Uri? = dataRepository.savedImageDirectory()

    fun rememberImageDirectory(uri: Uri) = dataRepository.rememberImageDirectory(uri)

    internal fun snapshot() = sessionRepository.snapshot()

    internal fun downloadRange(start: Int,end: Int,failureMessage: String) {
        val book=sessionRepository.snapshot().book ?: return
        val ref=io.legado.app.data.repository.CacheBookRef(book.bookUrl,io.legado.app.data.repository.transferBookKey(book))
        viewModelScope.launch {
            try {
                checkNotNull(downloadCommands).start(ref,start,end)
                showDialog(null)
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(error: Exception) { io.legado.app.constant.AppLog.put("Manga download request failed",error); showNotice(failureMessage) }
        }
    }

    internal fun awaitPendingWrites() = sessionRepository.awaitBackgroundTasks()

    internal suspend fun refreshBookFromDatabase() =
        sessionRepository.refreshBookFromDatabase()

    internal fun clearLoadedContent() = sessionRepository.clearLoadedContent()

    internal fun refreshContent() = sessionRepository.refreshContent()

    internal fun moveToPreviousChapter(force: Boolean = false) =
        sessionRepository.moveToPreviousChapter(force)

    internal fun moveToNextChapter(force: Boolean = false) =
        sessionRepository.moveToNextChapter(force)

    internal fun addCurrentBookToBookshelf() = sessionRepository.addCurrentBookToBookshelf()

    internal fun setTitleHidden(hidden: Boolean) = sessionRepository.setTitleHidden(hidden)

    internal fun reloadContent() = sessionRepository.reloadContent()

    internal fun updateVisiblePage(chapterIndex: Int, pageIndex: Int) =
        sessionRepository.updateVisiblePage(chapterIndex, pageIndex)

    internal fun beginReading() = sessionRepository.beginReading()

    internal fun syncProgress(onOlderCloudProgress: (BookProgress) -> Unit = {}) =
        sessionRepository.syncProgress(onOlderCloudProgress)

    internal fun upReadTime() = sessionRepository.upReadTime()

    internal fun saveRead() = sessionRepository.saveRead()

    internal fun uploadProgress() = sessionRepository.uploadProgress()

    internal fun cancelPreDownload() = sessionRepository.cancelPreDownload()

    internal fun setCurrentPage(position: Int) = sessionRepository.setCurrentPage(position)

    internal fun setProgress(progress: BookProgress) = sessionRepository.setProgress(progress)

    fun footerConfig(json: String = readerUiState.value.footerConfigJson): MangaFooterConfig =
        GSON.fromJsonObject<MangaFooterConfig>(json).getOrNull() ?: MangaFooterConfig()

    fun colorFilterConfig(
        json: String = readerUiState.value.colorFilterJson,
    ): MangaColorFilterConfig =
        GSON.fromJsonObject<MangaColorFilterConfig>(json).getOrNull()
            ?: MangaColorFilterConfig()

    internal fun previewFooterConfig(config: MangaFooterConfig) {
        updateReaderState { it.copy(footerConfigJson = GSON.toJson(config)) }
    }

    fun saveFooterConfig(config: MangaFooterConfig) {
        val json = GSON.toJson(config)
        updateReaderState { it.copy(footerConfigJson = json) }
        updatePreferences { it.copy(footerConfigJson = json) }
    }

    fun saveColorFilterConfig(config: MangaColorFilterConfig) {
        val json = config.toJson()
        updateReaderState { it.copy(colorFilterJson = json,
            colorFilter = intArrayOf(config.r, config.g, config.b, config.a), windowBrightness = config.l) }
        updatePreferences { it.copy(colorFilterJson = json) }
    }

    private val _readerUiState = MutableStateFlow(
        MangaReaderUiState(
            anchorItemIndex = savedStateHandle[STATE_ANCHOR_ITEM] ?: 0,
            horizontal = savedStateHandle[STATE_HORIZONTAL] ?: false,
            doublePage = savedStateHandle[STATE_DOUBLE_PAGE] ?: false,
            rightToLeft = savedStateHandle[STATE_RIGHT_TO_LEFT] ?: false,
        )
    )
    internal val readerUiState: StateFlow<MangaReaderUiState> = _readerUiState.asStateFlow()
    private val contentUpdateId = java.util.concurrent.atomic.AtomicInteger()
    private var preferencesJob: Job? = null
    private var readerActive = false
    private var automaticReadingJob: Job? = null
    private var windowWidthDp = 0
    private var windowHeightDp = 0

    internal fun updateWindowSize(widthDp: Int, heightDp: Int) {
        windowWidthDp = widthDp
        windowHeightDp = heightDp
        updateReaderState(::configuredState)
    }

    private fun configuredState(state: MangaReaderUiState): MangaReaderUiState {
        val doublePage = io.legado.app.utils.shouldUseMangaDoublePage(
            windowWidthDp, windowHeightDp, state.doublePageEnabled)
        val horizontal = state.horizontalScrollEnabled || doublePage
        val rightToLeft = horizontal && state.rightToLeftEnabled
        val snap = !state.horizontalPageSnapDisabled
        val layoutChanged = state.horizontal != horizontal || state.doublePage != doublePage ||
            state.rightToLeft != rightToLeft || state.pageSnapEnabled != snap
        return state.copy(
            horizontal = horizontal, horizontalMode = horizontal, doublePage = doublePage,
            rightToLeft = rightToLeft, pageSnapEnabled = snap,
            layoutVersion = if (layoutChanged) state.layoutVersion + 1 else state.layoutVersion,
            scaleEnabled = !state.scaleDisabled, clickScrollEnabled = !state.clickScrollDisabled,
            pageAnimationEnabled = !state.pageAnimationDisabled,
            gray = state.grayEnabled, eInk = state.eInkEnabled,
        )
    }

    init {
        sessionRepository.attach(this, viewModelScope)
    }

    internal fun observePreferences() {
        if (preferencesJob != null) return
        preferencesJob = viewModelScope.launch {
            preferencesRepository.preferences.collect(::applyPreferences)
        }
    }

    internal fun applyPreferences(preferences: MangaReaderPreferences) {
        val titleChanged = readerUiState.value.titleHidden != preferences.titleHidden
        sessionRepository.setTitleHidden(preferences.titleHidden)
        updateReaderState { state ->
            configuredState(state.copy(
                preDownloadCount = preferences.preDownloadCount,
                scaleDisabled = preferences.scaleDisabled,
                longClickSaveEnabled = preferences.longClickSaveEnabled,
                clickScrollDisabled = preferences.clickScrollDisabled,
                autoPageSpeed = preferences.autoPageSpeed,
                horizontalScrollEnabled = preferences.horizontalScrollEnabled,
                doublePageEnabled = preferences.doublePageEnabled,
                rightToLeftEnabled = preferences.rightToLeftEnabled,
                horizontalPageSnapDisabled = preferences.horizontalPageSnapDisabled,
                pageAnimationDisabled = preferences.pageAnimationDisabled,
                titleHidden = preferences.titleHidden,
                eInkEnabled = preferences.eInkEnabled,
                grayEnabled = preferences.grayEnabled,
                eInkThreshold = preferences.eInkThreshold,
                footerConfigJson = preferences.footerConfigJson,
                colorFilterJson = preferences.colorFilterJson,
                colorFilter = if (state.colorFilterJson == preferences.colorFilterJson) state.colorFilter else
                    colorFilterConfig(preferences.colorFilterJson).let { intArrayOf(it.r, it.g, it.b, it.a) },
                windowBrightness = if (state.colorFilterJson == preferences.colorFilterJson) state.windowBrightness else
                    preferences.colorFilterJson.takeIf { it.isNotBlank() }?.let { colorFilterConfig(it).l },
            ))
        }
        if (titleChanged && sessionRepository.snapshot().book != null) sessionRepository.reloadContent()
    }

    internal fun showDialog(dialog: MangaReaderDialog?) {
        updateReaderState { state -> state.copy(dialog = dialog,
            pendingCloudProgress = state.pendingCloudProgress.takeUnless { dialog == null && state.dialog == MangaReaderDialog.CloudProgress }) }
    }

    internal fun handleMenuAction(action: MangaReaderMenuAction) {
        val state = readerUiState.value
        when (action) {
            is MangaReaderMenuAction.Toggle -> toggleSetting(action.setting)
            MangaReaderMenuAction.AutoPage -> setAutomaticReading(if (state.autoPageEnabled) MangaAutomaticReading.Off else MangaAutomaticReading.Page)
            MangaReaderMenuAction.AutoScroll -> setAutomaticReading(if (state.autoScrollEnabled) MangaAutomaticReading.Off else MangaAutomaticReading.Continuous)
            MangaReaderMenuAction.Download -> snapshot().book?.let {
                showDialog(MangaReaderDialog.DownloadRange(snapshot().chapterIndex + 1, snapshot().chapterCount))
            }
            MangaReaderMenuAction.Preload -> showDialog(MangaReaderDialog.NumberPicker(MangaNumberSetting.PreDownload, state.preDownloadCount, 0))
            MangaReaderMenuAction.AutoSpeed -> showDialog(MangaReaderDialog.NumberPicker(MangaNumberSetting.AutoPageSpeed, state.autoPageSpeed, 1))
            MangaReaderMenuAction.Footer -> showDialog(MangaReaderDialog.Footer)
            MangaReaderMenuAction.ColorFilter -> { setMenuVisible(false); showDialog(MangaReaderDialog.ColorFilter) }
            MangaReaderMenuAction.Epaper -> showDialog(MangaReaderDialog.Epaper)
        }
    }

    /** Returns false only for decisions that require navigation or a platform gateway. */
    internal fun handleDialogAction(action: MangaReaderDialogAction): Boolean {
        val dialog = readerUiState.value.dialog
        when (action) {
            MangaReaderDialogAction.Dismiss -> showDialog(null)
            is MangaReaderDialogAction.Footer -> if (dialog == MangaReaderDialog.Footer) {
                previewFooterConfig(action.config)
                if (action.save) { saveFooterConfig(action.config); showDialog(null) }
            }
            is MangaReaderDialogAction.ColorFilter -> if (dialog == MangaReaderDialog.ColorFilter) {
                val config = action.config
                updateReaderState { it.copy(colorFilter = intArrayOf(config.r, config.g, config.b, config.a), windowBrightness = config.l) }
                if (action.save) { saveColorFilterConfig(config); showDialog(null) }
            }
            is MangaReaderDialogAction.Epaper -> if (dialog == MangaReaderDialog.Epaper) {
                val threshold = action.threshold.coerceIn(0, 255)
                updateReaderState { it.copy(eInkThreshold = threshold) }
                if (action.save) { updatePreferences { it.copy(eInkThreshold = threshold) }; showDialog(null) }
            }
            is MangaReaderDialogAction.CloudProgress -> if (dialog == MangaReaderDialog.CloudProgress) {
                if (action.accept) readerUiState.value.pendingCloudProgress?.let(::setProgress)
                showDialog(null)
            }
            is MangaReaderDialogAction.BrowserMode -> if (dialog == MangaReaderDialog.BrowserOpenMode) {
                setOpenReaderLinksInBrowser(action.external); showDialog(null)
            }
            is MangaReaderDialogAction.Number -> if (dialog is MangaReaderDialog.NumberPicker &&
                action.kind == dialog.kind && action.value in dialog.minimumValue..9999) {
                updatePreferences { preferences -> when (action.kind) {
                    MangaNumberSetting.PreDownload -> preferences.copy(preDownloadCount = action.value)
                    MangaNumberSetting.AutoPageSpeed -> preferences.copy(autoPageSpeed = action.value)
                } }
                showDialog(null)
            }
            is MangaReaderDialogAction.Bookshelf -> return dialog != MangaReaderDialog.AddToBookshelf
            is MangaReaderDialogAction.Download -> return dialog !is MangaReaderDialog.DownloadRange ||
                action.start < 0 || action.end < action.start || action.end >= dialog.endChapter
        }
        return true
    }

    internal fun toggleSetting(setting: MangaReaderSetting) {
        updatePreferences { it.toggle(setting) }
    }

    internal fun updatePreferences(
        transform: (MangaReaderPreferences) -> MangaReaderPreferences,
    ) {
        viewModelScope.launch { preferencesRepository.update(transform) }
    }

    internal fun updateReaderState(transform: (MangaReaderUiState) -> MangaReaderUiState) {
        val before = _readerUiState.value
        _readerUiState.update { current -> transform(current).also(::saveRestorableState) }
        if (automaticMangaRecipe(before) != automaticMangaRecipe(_readerUiState.value)) restartAutomaticReading()
    }

    internal fun setReaderActive(active: Boolean) {
        if (readerActive == active) return
        if (active) sessionRepository.beginReading() else sessionRepository.pauseReading()
        readerActive = active
        restartAutomaticReading()
    }

    private var destinationResumed = false
    private var resumeJob: Job? = null
    private var shelfDecisionJob: Job? = null
    private var detailsJob: Job? = null
    private var detailEntries: Set<String> = emptySet()
    private val cancelledDetails = mutableSetOf<String>()
    private var appliedReaderRevision = -1L
    private var applyingReaderDetail = false
    private var resumeGeneration = 0L

    internal fun setDetailEntries(entries: Set<String>) { detailEntries = entries }
    internal fun detailChanged(request: io.legado.app.data.entities.ReaderDetailRequest?) {
        val queued = request?.takeIf { it.phase == ReaderDetailPhase.Requested && it.id !in cancelledDetails }
        updateReaderState { it.copy(detailNavigation = queued,
            navigationPending = if (queued != null) true else if (it.detailNavigation != null) false else it.navigationPending) }
        if (destinationResumed && request?.phase == ReaderDetailPhase.Returned) resumeReader()
    }
    internal fun detailNavigationHandled(id: String) {
        updateReaderState { if (it.detailNavigation?.id == id) it.copy(detailNavigation = null) else it }
    }
    internal fun observeDetails(id: String) = readerSessions.observeDetail(id)
    internal fun requestDetails() {
        if (detailsJob?.isActive == true || !initialized || !readerUiState.value.interactive ||
            readerUiState.value.navigationPending || readerUiState.value.loadingVisible) return
        updateReaderState { it.copy(navigationPending = true, menuVisible = false) }
        setReaderActive(false)
        detailsJob = viewModelScope.launch {
            try {
                changeSourceCoroutine?.cancelAndJoin()
                saveRead()
                val owner = sessionRepository.progressOwner() ?: return@launch
                val snapshot = snapshot()
                val request = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    readerSessions.beginDetail(owner, snapshot.chapterIndex, snapshot.chapterPosition)?.also {
                        sessionRepository.bindProgressOwner(owner.copy(revision = it.revision))
                        requestOwner = owner.copy(revision = it.revision)
                    }
                }
                if (request != null) detailChanged(request)
                else updateReaderState { it.copy(navigationPending = false) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { showNotice(error.localizedMessage ?: "打开详情失败"); updateReaderState { it.copy(navigationPending = false) } }
        }
    }
    internal fun cancelDetailsNavigation() {
        val request = readerUiState.value.detailNavigation ?: return
        cancelledDetails += request.id
        updateReaderState { it.copy(detailNavigation = null) }
        viewModelScope.launch {
            val owner = sessionRepository.progressOwner() ?: requestOwner ?: return@launch
            readerSessions.cancelDetail(owner, request.id)
            if (destinationResumed) resumeReader()
        }
    }

    internal fun resumeReader() {
        cancelCatalogNavigation()
        destinationResumed = true
        updateReaderState { it.copy(interactive = false) }
        setReaderActive(false)
        resumeJob?.cancel()
        val generation = ++resumeGeneration
        applyingReaderDetail = true
        resumeJob = viewModelScope.launch {
            try {
                initializationJob?.join()
                var owner = sessionRepository.progressOwner() ?: requestOwner ?: return@launch
                val detail = readerSessions.detail(owner.sessionId)
                if (detail?.phase == ReaderDetailPhase.Displayed && detail.id !in detailEntries) readerSessions.recoverDetail(owner, detail.id)
                val pending = readerSessions.detail(owner.sessionId)
                if (pending?.phase in setOf(ReaderDetailPhase.Requested, ReaderDetailPhase.Displayed)) {
                    detailChanged(pending); return@launch
                }
                readerSessions.applyDetail(owner)
                if (!readerSessions.activate(owner)) {
                    if (readerSessions.current(owner)?.deleted == true) updateReaderState { it.copy(exit = MangaReaderExit.Deleted) }
                    return@launch
                }
                // Consume every navigation result before loading any generation of the book.
                // The catalog observer requests this same pipeline instead of mutating the engine.
                val saved = readerSessions.applyCatalog(owner) ?: readerSessions.current(owner) ?: return@launch
                if (saved.deleted) { updateReaderState { it.copy(exit = MangaReaderExit.Deleted) }; return@launch }
                if (saved.revision != appliedReaderRevision) {
                    showLoading()
                    updateReaderState { it.copy(items = emptyList(), bookUrl = null, detailNavigation = null) }
                    sessionRepository.beginRequest()
                    owner = saved.progressOwner()
                    sessionRepository.bindProgressOwner(owner); requestOwner = owner
                    val book = checkNotNull(kotlinx.coroutines.withContext(Dispatchers.IO) { sessionRepository.findBook(saved.bookUrl) })
                        .copy(durChapterIndex = saved.chapterIndex.coerceAtLeast(0), durChapterPos = saved.pageIndex)
                    sessionRepository.configureLaunch(!book.isNotShelf, true)
                    kotlinx.coroutines.withContext(Dispatchers.IO) { initManga(book) }
                    appliedReaderRevision = saved.revision
                }
                if (!readerSessions.activate(owner)) return@launch
                appliedReaderRevision = sessionRepository.progressOwner()?.revision ?: appliedReaderRevision
                beginReading(); setReaderActive(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("恢复漫画详情返回失败", error); loadFail(error.localizedMessage ?: "恢复阅读失败", retry = true) }
            finally { if (generation == resumeGeneration) {
                applyingReaderDetail = false
                if (destinationResumed) updateReaderState { it.copy(interactive = true) }
            } }
        }
    }

    internal fun pauseReader() {
        cancelCatalogNavigation()
        destinationResumed = false
        updateReaderState { it.copy(interactive = false, command = null) }
        resumeJob?.cancel()
        setReaderActive(false)
        upReadTime()
        saveRead()
        cancelPreDownload()
        if (!io.legado.app.BuildConfig.DEBUG && snapshot().inBookshelf) {
            if (enhancedProgressSyncEnabled) syncProgress() else uploadProgress()
        }
    }

    internal fun networkAvailable() {
        if (readerActive && initialized && !readerUiState.value.loadingVisible &&
            enhancedProgressSyncEnabled && snapshot().inBookshelf) syncProgress(::sureNewProgress)
    }

    internal fun requestExit() {
        cancelCatalogNavigation()
        if (snapshot().book == null || snapshot().inBookshelf) {
            updateReaderState { it.copy(exit = MangaReaderExit.Closed) }
        } else if (addToShelfConfirmationEnabled) showDialog(MangaReaderDialog.AddToBookshelf)
        else decideBookshelf(false)
    }

    internal suspend fun finishExit(): Boolean {
        initializationJob?.cancel()
        changeSourceCoroutine?.cancel()
        pauseReader()
        val owner = sessionRepository.progressOwner() ?: requestOwner
        return try {
            if (owner != null) readerSessions.closeAndAwait(owner)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            AppLog.put("保存阅读返回失败", error)
            updateReaderState { it.copy(exit = null, noticeMessage = error.localizedMessage) }
            resumeReader()
            false
        }
    }

    internal fun decideBookshelf(keep: Boolean) {
        if (shelfDecisionJob?.isActive == true) return
        shelfDecisionJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (keep) sessionRepository.addCurrentBookToBookshelf() else sessionRepository.removeCurrentBookFromBookshelf()
                }
                updateReaderState { it.copy(dialog = null, exit = if (keep) MangaReaderExit.AddedToBookshelf else MangaReaderExit.Closed) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { showNotice(error.localizedMessage ?: "操作失败") }
        }
    }

    internal fun setAutomaticReading(mode: MangaAutomaticReading) {
        updateReaderState { it.copy(autoPageEnabled = mode == MangaAutomaticReading.Page,
            autoScrollEnabled = mode == MangaAutomaticReading.Continuous) }
    }

    private fun restartAutomaticReading() {
        automaticReadingJob?.cancel()
        automaticReadingJob = null
        val state = readerUiState.value
        if (!readerActive || automaticMangaCommand(state) == null) return
        automaticReadingJob = viewModelScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val snapshot = readerUiState.value
                kotlinx.coroutines.delay(if (snapshot.autoPageEnabled) snapshot.autoPageSpeed.coerceAtLeast(1) * 1000L else 16L)
                automaticMangaCommand(readerUiState.value)?.let(::dispatchReaderCommand)
            }
        }
    }

    internal fun setMenuVisible(visible: Boolean) {
        updateReaderState { it.copy(menuVisible = visible) }
    }

    internal fun turnPage(direction: Int) {
        val state = readerUiState.value
        if (state.interactive && !state.loadingVisible) dispatchReaderCommand(MangaReaderCommand.TurnPage(direction,
            animated = state.pageAnimationEnabled))
    }

    internal fun seekPage(index: Int) {
        val state = readerUiState.value
        if (!state.interactive) return
        val chapter = sessionRepository.snapshot().chapterIndex
        val itemIndex = state.items.indexOfFirst { it.chapterIndex == chapter && it.index == index }
        if (itemIndex < 0) return
        val aligned = alignSpreadStartPosition(itemIndex, state.items, state.doublePage)
        dispatchReaderCommand(MangaReaderCommand.ScrollToItem(aligned))
        updateReaderState { it.copy(anchorItemIndex = aligned, seekPage = index) }
        sessionRepository.setCurrentPage(index)
    }

    private var lastVisibleImage: Triple<Int, Int, MangaPage>? = null

    internal fun acceptVisibleItem(item: BaseMangaPage, contentVersion: Int, layoutVersion: Int): Boolean {
        val state = readerUiState.value
        if (!state.interactive || applyingReaderDetail) return false
        val index = currentMangaItemIndex(state, item, contentVersion, layoutVersion)
        if (index < 0 || sessionRepository.snapshot().book?.bookUrl != state.bookUrl) return false
        // Chapter cards must advance the viewport anchor, but their -1/end indices are not image progress.
        updateReaderState {
            if (item is MangaPage) it.copy(anchorItemIndex = index, seekPage = item.index,
                seekPageCount = item.imageCount, chapterName = item.mChapterName,
                chapterIndex = item.chapterIndex, chapterCount = item.chapterSize)
            else it.copy(anchorItemIndex = index)
        }
        when (item) {
            is MangaPage -> {
                val identity = Triple(contentVersion, layoutVersion, item)
                if (sessionRepository.snapshot().let {
                    it.chapterIndex != item.chapterIndex || it.chapterPosition != item.index
                }) sessionRepository.updateVisiblePage(item.chapterIndex, item.index)
                else if (lastVisibleImage != identity) {
                    // Decoding may have clamped an invalid launch position before the viewport
                    // reports it. Persist that canonical position even though the session agrees.
                    sessionRepository.saveRead()
                }
                lastVisibleImage = identity
            }
            is io.legado.app.ui.book.manga.entities.ReaderLoading -> if (item.isVolume &&
                sessionRepository.snapshot().chapterIndex != item.chapterIndex) {
                // Advance through empty volume chapters so the session loads the following neighbour.
                sessionRepository.updateVisiblePage(item.chapterIndex, 0)
            }
        }
        return true
    }

    internal fun dispatchReaderCommand(command: MangaReaderCommand) {
        updateReaderState { state ->
            state.copy(
                command = resolveMangaScrollRequest(state, command),
                commandVersion = state.commandVersion + 1,
            )
        }
    }

    internal fun consumeReaderCommand(version: Long) {
        updateReaderState { if (it.commandVersion == version) it.copy(command = null) else it }
    }

    internal fun clearNoticeMessage() {
        _readerUiState.update { it.copy(noticeMessage = null) }
    }

    internal fun showNotice(message: String) {
        _readerUiState.update { it.copy(noticeMessage = message) }
    }

    override fun onContentChanged() {
        viewModelScope.launch { publishContent() }
    }

    private fun publishContent() {
        val snapshot = sessionRepository.snapshot()
        val data = snapshot.content
        if (!data.curFinish) return
        val items = data.items
        val updateId = contentUpdateId.incrementAndGet()
        updateReaderState { state ->
            val alignedPosition = alignSpreadStartPosition(
                position = data.pos,
                items = items,
                doublePage = state.doublePage,
            )
            val currentChapter = snapshot.currentChapter
            state.copy(
                items = items,
                command = state.command.takeIf { state.bookUrl == snapshot.book?.bookUrl },
                anchorItemIndex = alignedPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                contentVersion = updateId,
                bookTitle = snapshot.book?.name.orEmpty(),
                chapterName = currentChapter?.chapter?.title.orEmpty(),
                chapterIndex = snapshot.chapterIndex,
                chapterCount = snapshot.chapterCount,
                chapterUrl = currentChapter?.chapter?.url.orEmpty(),
                sourceName = snapshot.source?.bookSourceName.orEmpty(),
                sourceOrigin = snapshot.book?.origin,
                bookUrl = snapshot.book?.bookUrl,
                seekPage = snapshot.chapterPosition,
                seekPageCount = currentChapter?.imageCount ?: 0,
                loadingVisible = if (data.curFinish) false else state.loadingVisible,
                loadingErrorMessage = if (data.curFinish) null else state.loadingErrorMessage,
                hasNextChapter = snapshot.hasNextChapter,
                nextChapterLoading = data.curFinish && snapshot.hasNextChapter && !data.nextFinish,
            )
        }
    }

    override fun onLoadFailed(message: String, retry: Boolean) {
        _readerUiState.update { state ->
            if (state.loadingVisible) {
                state.copy(
                    loadingErrorMessage = message,
                    loadingErrorMessageRes = null,
                    canRetryLoading = retry,
                )
            } else {
                state.copy(nextChapterLoading = false, noticeMessage = message)
            }
        }
    }

    private fun loadFail(@androidx.annotation.StringRes messageRes: Int, retry: Boolean = true) {
        _readerUiState.update {
            it.copy(
                loadingErrorMessage = null,
                loadingErrorMessageRes = messageRes,
                canRetryLoading = retry,
            )
        }
    }

    override fun onCloudProgressFound(progress: BookProgress) {
        updateReaderState {
            it.copy(
                pendingCloudProgress = progress,
                dialog = MangaReaderDialog.CloudProgress,
            )
        }
    }

    override fun onLoadingStarted() {
        contentUpdateId.incrementAndGet()
        updateReaderState {
            it.copy(
                loadingVisible = true,
                loadingErrorMessage = null,
                loadingErrorMessageRes = null,
            )
        }
    }

    override fun onNextChapterStarted() {
        _readerUiState.update { it.copy(nextChapterLoading = true) }
    }

    fun loadFail(message: String, retry: Boolean = true) = onLoadFailed(message, retry)

    fun sureNewProgress(progress: BookProgress) = onCloudProgressFound(progress)

    fun showLoading() = onLoadingStarted()

    fun startLoad() = onNextChapterStarted()

    private fun saveRestorableState(state: MangaReaderUiState) {
        savedStateHandle[STATE_ANCHOR_ITEM] = state.anchorItemIndex
        savedStateHandle[STATE_HORIZONTAL] = state.horizontal
        savedStateHandle[STATE_DOUBLE_PAGE] = state.doublePage
        savedStateHandle[STATE_RIGHT_TO_LEFT] = state.rightToLeft
    }

    private var changeSourceCoroutine: Job? = null

    private fun <T> execute(
        scope: CoroutineScope = viewModelScope,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
        block: suspend CoroutineScope.() -> T,
    ): Coroutine<T> = Coroutine.async(
        scope,
        context,
        start,
        executeContext,
        semaphore,
        block,
    )

    private var initializationJob: Job? = null
    private var activeRequest: ReaderOpenRequest? = null
    private var requestOwner: io.legado.app.data.repository.ReaderProgressOwner? = null
    private var initialized = false

    fun initialize(request: ReaderOpenRequest) {
        if (activeRequest == request && (initialized || initializationJob?.isActive == true)) return
        initializationJob?.cancel()
        changeSourceCoroutine?.cancel()
        activeRequest = request
        lastVisibleImage = null
        initialized = false
        val previousOwner = sessionRepository.progressOwner() ?: requestOwner
        setReaderActive(false)
        sessionRepository.beginRequest()
        if (previousOwner != null && previousOwner.sessionId != request.id) readerSessions.close(previousOwner)
        showLoading()
        updateReaderState { it.copy(items = emptyList(), bookUrl = null, bookTitle = "", chapterName = "",
            chapterUrl = "", sourceName = "", sourceOrigin = null, chapterIndex = 0, chapterCount = 0,
            seekPage = 0, seekPageCount = 0, command = null, dialog = null, menuVisible = false,
            autoPageEnabled = false, autoScrollEnabled = false, pendingCloudProgress = null,
            hasNextChapter = false, nextChapterLoading = false, noticeMessage = null, exit = null) }
        initializationJob = viewModelScope.launch {
            val token = java.util.UUID.randomUUID().toString()
            requestOwner = io.legado.app.data.repository.ReaderProgressOwner(request.id, token, 0)
            try {
                var saved = readerSessions.claim(request, token)
                requestOwner = saved.progressOwner()
                while (true) {
                    val detail = readerSessions.detail(request.id) ?: break
                    if (detail.phase == ReaderDetailPhase.Displayed && detail.id !in detailEntries) {
                        readerSessions.recoverDetail(saved.progressOwner(), detail.id)
                    } else if (detail.phase == ReaderDetailPhase.Returned) {
                        readerSessions.applyDetail(saved.progressOwner())
                    } else {
                        detailChanged(detail)
                        readerSessions.observeDetail(request.id).first { it == null || it.phase == ReaderDetailPhase.Returned }
                    }
                    saved = checkNotNull(readerSessions.current(saved.progressOwner()))
                }
                if (saved.deleted) { updateReaderState { it.copy(exit = MangaReaderExit.Deleted) }; return@launch }
                saved = readerSessions.applyCatalog(saved.progressOwner()) ?: saved
                val preferences = preferencesRepository.current()
                applyPreferences(preferences)
                val found = kotlinx.coroutines.withContext(Dispatchers.IO) { sessionRepository.findBook(saved.bookUrl) }
                currentCoroutineContext().ensureActive()
                if (found == null) {
                    loadFail(R.string.no_book, retry = false)
                    return@launch
                }
                val book = if (saved.chapterIndex >= 0) found.copy(
                    durChapterIndex = saved.chapterIndex, durChapterPos = saved.pageIndex) else found
                sessionRepository.bindProgressOwner(saved.progressOwner())
                sessionRepository.configureLaunch(!book.isNotShelf, saved.chapterChanged)
                kotlinx.coroutines.withContext(Dispatchers.IO) { initManga(book) }
                currentCoroutineContext().ensureActive()
                initialized = true
                appliedReaderRevision = saved.revision
                sessionRepository.saveRead()
                if (destinationResumed) resumeReader()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                readerSessions.close(io.legado.app.data.repository.ReaderProgressOwner(request.id, token, 0))
                throw cancelled
            }
            catch (error: Exception) {
                AppLog.put("初始化漫画失败\n${error.localizedMessage}", error)
                if (error is io.legado.app.data.repository.ReaderReturnRequestExpiredException) {
                    loadFail(R.string.reader_return_request_expired, retry = false)
                } else loadFail("初始化漫画失败\n${error.localizedMessage}", retry = true)
            }
        }
    }

    internal fun retryLoading() {
        if (initializationJob?.isActive == true) return
        initialized = false
        activeRequest?.let(::initialize)
    }

    private var catalogNavigationEpoch = 0L

    internal fun cancelCatalogNavigation() {
        catalogNavigationEpoch++
        updateReaderState { it.copy(navigationPending = false) }
    }

    internal suspend fun beginCatalog(): io.legado.app.data.entities.ReaderCatalogRequest? {
        if (readerUiState.value.navigationPending || !readerUiState.value.interactive) return null
        val owner = sessionRepository.progressOwner() ?: return null
        val snapshot = snapshot()
        val epoch = ++catalogNavigationEpoch
        updateReaderState { it.copy(navigationPending = true) }
        try {
            // A committed revision must be rebound even if the UI cancels while SQLite is finishing.
            // This bounded transaction performs no network work.
            val request = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                readerSessions.beginCatalog(owner, snapshot.chapterIndex, snapshot.chapterPosition)?.also {
                    if (sessionRepository.progressOwner()?.token == owner.token) {
                        sessionRepository.bindProgressOwner(owner.copy(revision = it.revision))
                    }
                }
            }
            if (request == null) updateReaderState { it.copy(navigationPending = false) }
            return request?.takeIf { epoch == catalogNavigationEpoch && readerUiState.value.interactive }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            updateReaderState { it.copy(navigationPending = false) }
            showNotice(error.localizedMessage ?: "目录加载失败")
            return null
        }
    }

    internal fun observeCatalog(requestId: String) = readerSessions.observeCatalog(requestId)

    private suspend fun initManga(book: Book) {
        addCloseable("reader-cache", cacheLeases.acquireBook(book))
        val isSameBook = sessionRepository.initializeBook(book)
        if (!book.isLocal && book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }

        if (book.isLocal && !contentRepository.hasReadableLocalFile(book)) {
            return
        }

        if ((sessionRepository.snapshot().chapterCount == 0 || book.isLocalModified()) &&
            !loadChapterListAwait(book)
        ) {
            return
        }

        sessionRepository.loadContent(isSameBook)

        val chapterChanged = sessionRepository.consumeChapterChanged()
        if (!chapterChanged && sessionRepository.snapshot().inBookshelf) {
            if (dataRepository.enhancedProgressSyncEnabled) {
                sessionRepository.syncProgress(::sureNewProgress)
            } else {
                syncBookProgress(book)
            }
        }

        if (!book.isLocal && sessionRepository.snapshot().source == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    private suspend fun loadChapterListAwait(book: Book): Boolean {
        val bookSource = sessionRepository.snapshot().source ?: return true
        val oldBook = book.copy()
        contentRepository.loadChapterList(bookSource, book).onSuccess { cList ->
            sessionRepository.replaceChapterList(oldBook, book, cList)
            return true
        }.onFailure {
            currentCoroutineContext().ensureActive()
            //加载章节出错
            loadFail(R.string.error_load_toc)
            return false
        }
        return true
    }

    /**
     * 加载详情页
     */
    private suspend fun loadBookInfo(book: Book): Boolean {
        val source = sessionRepository.snapshot().source ?: return true
        try {
            contentRepository.loadBookInfo(source, book)
            return true
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            loadFail("详情页出错: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * 自动换源
     */
    private fun autoChangeSource(name: String, author: String) {
        if (!dataRepository.automaticSourceChangeEnabled) return
        val requestId = activeRequest?.id
        execute {
            val sources = sessionRepository.enabledSources()
            flow {
                for (source in sources) {
                    source.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                // 自动换源

            }.mapParallelSafe(dataRepository.sourceSearchConcurrency) { source ->
                contentRepository.findReadableSource(source, name, author)
            }.take(1).onEach { (book, toc) ->
                if (activeRequest?.id == requestId) changeTo(book, toc)
            }.onEmpty {
                throw NoStackTraceException("没有合适书源")
            }.onCompletion {
                // 换源完成
            }.catch {
                AppLog.put("自动换源失败\n${it.localizedMessage}", it)
                showNotice("自动换源失败\n${it.localizedMessage}")
            }.collect()
        }
    }

    /**
     * 同步进度
     */
    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!dataRepository.progressSyncEnabled) return
        val requestId = activeRequest?.id
        execute {
            sessionRepository.cloudProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            if (activeRequest?.id != requestId || snapshot().book?.bookUrl != book.bookUrl) return@onSuccess
            progress ?: return@onSuccess
            if (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos == book.durChapterPos) {
                return@onSuccess
            }
            if (progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                alertSync?.invoke(progress)
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                sessionRepository.setProgress(progress)
                AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                showNotice("已同步最新漫画阅读进度")
            }
        }
    }

    /**
     * 换源
     */
    fun changeTo(book: Book, toc: List<BookChapter>, onSuccess: () -> Unit = {}) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = viewModelScope.launch {
            try {
                sessionRepository.upReadTime()
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    addCloseable("reader-cache", cacheLeases.acquireBook(book))
                    book.removeType(BookType.updateError)
                    sessionRepository.applySourceChange(book, toc)
                }
                onSuccess()
                postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("换源失败\n$error", error, true) }
        }
    }

    fun openChapter(index: Int, durChapterPos: Int = 0) {
        if (index < 0 || index >= snapshot().chapterCount || durChapterPos < 0) return
        viewModelScope.launch {
            sessionRepository.progressOwner()?.let { owner ->
                val bookUrl = snapshot().book?.bookUrl ?: return@launch
                val saved = readerSessions.navigate(owner, bookUrl, index, durChapterPos) ?: return@launch
                sessionRepository.bindProgressOwner(saved.progressOwner())
            }
            sessionRepository.openChapter(index, durChapterPos)
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            sessionRepository.removeCurrentBookFromBookshelf()
        }.onSuccess {
            success?.invoke()
        }
    }

    override fun onCleared() {
        automaticReadingJob?.cancel()
        sessionRepository.saveRead()
        val owner = sessionRepository.progressOwner() ?: requestOwner
        sessionRepository.detach(this)
        if (owner != null) readerSessions.close(owner)
        changeSourceCoroutine?.cancel()
        super.onCleared()
    }

    fun refreshContentDur(book: Book) {
        execute {
            val snapshot = sessionRepository.snapshot()
            sessionRepository.currentChapter(book.bookUrl)
                ?.let { chapter ->
                    contentRepository.deleteCachedChapter(book, chapter)
                    openChapter(snapshot.chapterIndex, snapshot.chapterPosition)
            }
        }
    }

    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        execute {
            sessionRepository.saveImage(src, uri)
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            showNotice("保存图片出错\n${it.localizedMessage}")
        }
    }

    private companion object {
        const val STATE_ANCHOR_ITEM = "manga.anchorItem"
        const val STATE_HORIZONTAL = "manga.horizontal"
        const val STATE_DOUBLE_PAGE = "manga.doublePage"
        const val STATE_RIGHT_TO_LEFT = "manga.rightToLeft"
    }
}

internal fun alignSpreadStartPosition(
    position: Int,
    items: List<BaseMangaPage>,
    doublePage: Boolean,
): Int {
    if (!doublePage || position <= 0) return position
    val item = items.getOrNull(position) as? MangaPage ?: return position
    if (item.index % 2 == 1) {
        val previous = items.getOrNull(position - 1) as? MangaPage
        if (previous != null && previous.chapterIndex == item.chapterIndex) return position - 1
    }
    return position
}
