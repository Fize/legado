package io.legado.app.ui.book.read.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.entities.ReaderDetailPhase
import io.legado.app.data.repository.*
import io.legado.app.model.*
import io.legado.app.model.localBook.EpubResourceAddress
import io.legado.app.ui.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import io.legado.app.utils.fromJsonObject

internal data class TextReaderReady(val key: TextReaderChapterKey, val entry: TextReaderEntry,
    val document: TextReaderDocument, val title: String) {
    val contentKey get() = TextReaderContentKey(key.owner.sessionId, key.owner.token, key.bookUrl, key.chapterIndex, key.generation)
}
internal data class TextReaderFootnote(val anchor: String, val position: Int, val instance: Long,
    val document: TextReaderDocument? = null, val persistedPosition: Int? = null) {
    fun targetIn(main: TextReaderDocument): TextReaderAnchor? {
        val source = (document ?: main).anchors.firstOrNull { it.key == anchor } ?: return null
        return main.anchors.firstOrNull { it.id == source.id && it.resource == source.resource }
    }
}

internal data class TextReaderUiState(
    val ready: TextReaderReady? = null, val loading: Boolean = true, val error: String? = null,
    val anchor: Int = 0, val page: Int = 0, val pageCount: Int = 0, val menu: Boolean = false,
    val persistedAnchor: Int? = null,
    val navigation: AppRoute? = null, val closed: Boolean = false,
    val background: TextReaderBackground? = null,
    val footnote: TextReaderFootnote? = null, val linkReturnAvailable: Boolean = false,
    val linkLoading: Boolean = false, val linkFailure: Long = 0,
    val searchMatch: IntRange? = null,
    val ruleHighlights: List<TextReaderRuleMatch> = emptyList(),val ruleWarning: Long=0,
    val ruleAction: TextReaderHighlightRule?=null,val ruleBusy: Boolean=false,val ruleError: Boolean=false,
    val annotations: List<ReaderAnnotationItem> = emptyList(),
    val annotationDraft: io.legado.app.data.entities.ReaderAnnotationDraft? = null,
    val annotationList: Boolean = false,val annotationBusy: Boolean = false,val annotationError: String? = null,
)

/** One ViewModel per navigation entry. Layout objects remain exclusively in Composition. */
@HiltViewModel
class TextReaderViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val sessions: ReaderSessionRepository,
    private val entries: TextReaderEntryRepository,
    private val loader: TextReaderContentRepository,
    private val images: TextReaderImageRepository,
    private val epubs: TextReaderEpubRepository,
    private val searches: BookContentSearchRepository,
    private val annotations: ReaderAnnotationRepository,
    private val highlightRules: TextReaderHighlightRepository,
) : ViewModel() {
    private val content = TextReaderContentSession(viewModelScope, loader)
    private val mutableState = MutableStateFlow(TextReaderUiState())
    internal val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private var request: ReaderOpenRequest? = null
    private var owner: ReaderProgressOwner? = null
    private var claimed = false
    private var active = false
    private var epoch = 0L
    private var entry: TextReaderEntry? = null
    private var loadKey: TextReaderChapterKey? = null
    private var loadTitleLength: Int? = null
    private var loadIndentLength: Int? = null
    private var appearanceEpoch = 0L
    private var lifecycleEpoch = 0L
    private var render: TextReaderRenderKey? = null
    private var noteRender: TextReaderRenderKey? = null
    private var noteRenderGeneration = 0L
    private var noteInstance = 0L
    private var linkEpoch = 0L
    private var linkJob: Job? = null
    private var linkCommitting = false
    private var progress: Job? = null
    private var detailEntries = emptySet<String>()
    private var ruleJob: Job?=null
    private var annotationJob: Job? = null
    private var annotationBook: String? = null
    private var visiblePage: TextReaderPageSlice? = null

    init {
        viewModelScope.launch {
            content.state.collectLatest { value ->
                when (value) {
                    is TextReaderContentState.Ready -> {
                        val metadata = entry ?: return@collectLatest
                        if (value.key != loadKey) return@collectLatest
                        try {
                            val document = entries.document(metadata, value.chapter)
                            val selected=if(savedState.get<Boolean>("textReader.search.highlightEnabled")==true)
                                savedState.get<String>("textReader.search.highlight")?.let { searches.readerSelection(it) } else null
                            val searchMatch=selected?.takeIf { (session,hit) -> session.bookUrl==metadata.bookUrl && session.origin==metadata.origin &&
                                hit.chapterIndex==metadata.chapterIndex && hit.chapterUrl==metadata.chapterUrl &&
                                session.selectedDocumentKey==textReaderLinkKey(document.text) }?.let { (session,_) ->
                                val start=document.positions?.toDisplay(checkNotNull(session.selectedPosition)) ?: checkNotNull(session.selectedPosition)
                                val end=document.positions?.toDisplayEnd(checkNotNull(session.selectedEnd)) ?: checkNotNull(session.selectedEnd)
                                start until end
                            }
                            if (value.key == loadKey) {
                                val note = restoreLinks(metadata, document)
                                val ready = TextReaderReady(value.key, metadata, document, value.chapter.title)
                                val oldTitle = loadTitleLength ?: previousTitleLength(metadata, value.requestedPosition, value.key.owner.revision)
                                val oldIndent = loadIndentLength ?: previousIndentLength(metadata, value.requestedPosition, value.key.owner.revision)
                                mutableState.update { it.copy(
                                ready = ready,
                                anchor = document.restorePosition(value.requestedPosition, oldTitle, oldIndent),
                                persistedAnchor = (document.storage ?: document).position(value.requestedPosition, oldTitle, oldIndent),
                                loading = false, error = null, footnote = note, searchMatch=searchMatch, linkReturnAvailable = linkHistory().isNotEmpty()) }
                                restoreAnnotations(ready)
                                observeHighlightRules(ready)
                                savedState.get<String>("textReader.links.resource")?.let { resource ->
                                    loadResource(ready, resourceKey = resource)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { if (value.key == loadKey) mutableState.update {
                            it.copy(loading = false, error = error.localizedMessage ?: "Unable to display chapter")
                        } }
                    }
                    is TextReaderContentState.Failed -> if (value.key == loadKey) mutableState.update {
                        it.copy(loading = false, error = value.message)
                    }
                    else -> Unit
                }
            }
        }
    }

    internal fun initialize(route: TextReaderRoute) {
        if (request != null) return
        val id = route.requestId ?: savedState.get<String>("textReaderEntryId")
            ?: UUID.randomUUID().toString().also { savedState["textReaderEntryId"] = it }
        request = ReaderOpenRequest(id, route.bookUrl, chapterIndex = route.chapterIndex,
            returnToDetails = route.returnToDetails)
        viewModelScope.launch {
            sessions.observeDetail(id).collect { detail ->
                if (active && detail?.phase == ReaderDetailPhase.Returned) resume()
            }
        }
        viewModelScope.launch {
            sessions.observeCatalog(id).collect { catalog ->
                if (active && catalog?.chapterIndex != null && !catalog.consumed) resume()
            }
        }
    }

    internal fun setDetailEntries(ids: Set<String>) { detailEntries = ids }

    internal fun resume() {
        active = true
        val ticket = ++epoch
        render = null
        mutableState.update { it.copy(loading = true, error = null, navigation = null) }
        viewModelScope.launch {
            operations.withLock {
                try {
                    val launch = request ?: return@withLock
                    if (!claimed) {
                        val token = UUID.randomUUID().toString()
                        owner = ReaderProgressOwner(launch.id, token, 0)
                        val result = sessions.claim(launch, token)
                        owner = result.progressOwner(); claimed = true
                    }
                    var currentOwner = checkNotNull(owner)
                    val detail = sessions.detail(currentOwner.sessionId)
                    if (detail?.phase == ReaderDetailPhase.Requested && detail.id !in detailEntries &&
                        savedState.get<String>("textReader.deliveredDetail") == detail.id) {
                        // The user left an acknowledged child before its producer finished claiming it.
                        // Cancellation and producer claim race transactionally; recover the displayed winner.
                        if (!sessions.cancelDetail(currentOwner, detail.id)) sessions.recoverDetail(currentOwner, detail.id)
                    }
                    if (detail?.phase == ReaderDetailPhase.Displayed && detail.id !in detailEntries) {
                        sessions.recoverDetail(currentOwner, detail.id)
                    }
                    val pending = sessions.detail(currentOwner.sessionId)
                    if (pending?.phase == ReaderDetailPhase.Requested || pending?.phase == ReaderDetailPhase.Displayed) {
                        if (ticket == epoch && pending.phase == ReaderDetailPhase.Requested) mutableState.update {
                            it.copy(navigation = BookInfoRoute(pending.bookUrl, pending.id, returnToReader = true))
                        }
                        return@withLock
                    }
                    sessions.applyDetail(currentOwner)
                    val applied=sessions.applyCatalog(currentOwner)
                    val saved = applied ?: checkNotNull(sessions.current(currentOwner))
                    val searchId=savedState.get<String>("textReader.search.pending")
                    val searchSelection=searchId?.let { searches.readerSelection(it) }
                    if(applied!=null && searchSelection!=null && searchSelection.first.catalogId==savedState.get<String>("textReader.search.request")) {
                        savedState.get<String>("textReader.search.return")?.let(TextReaderLinkPosition::decode)?.let {
                            saveHistory(linkHistory()+it)
                        }
                        savedState["textReader.search.highlight"]=searchId
                        savedState["textReader.search.highlightEnabled"]=true
                    }
                    savedState.remove<String>("textReader.search.pending")
                    savedState.remove<String>("textReader.search.request")
                    savedState.remove<String>("textReader.search.return")
                    currentOwner = saved.progressOwner(); owner = currentOwner
                    if (saved.deleted) { mutableState.update { it.copy(closed = true) }; return@withLock }
                    check(sessions.activate(currentOwner)) { "Reader no longer owns this book" }
                    val metadata = entries.entry(saved)
                    if (ticket != epoch || !active) return@withLock
                    load(metadata, currentOwner,searchSelection?.first?.selectedTitleLength,searchSelection?.first?.selectedIndentLength)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (ticket == epoch) mutableState.update {
                    it.copy(loading = false, error = error.localizedMessage ?: "Unable to open book")
                } }
            }
        }
    }

    private fun load(metadata: TextReaderEntry, currentOwner: ReaderProgressOwner, previousTitleLength: Int? = null,
        previousIndentLength: Int? = null, prepared: TextReaderChapterContent? = null) {
        cancelLinkLoad()
        ruleJob?.cancel(); ruleJob=null
        entry = metadata
        loadTitleLength = previousTitleLength
        loadIndentLength = previousIndentLength
        render = null
        visiblePage = null
        noteRender = null
        mutableState.update { it.copy(ready = null, loading = true, error = null, pageCount = 0,
            background = metadata.background, footnote = null, linkReturnAvailable = false,ruleHighlights=emptyList(),ruleAction=null,ruleError=false) }
        loadKey = if (prepared == null) content.load(currentOwner, metadata.bookUrl, metadata.origin, metadata.chapterIndex, metadata.position, metadata.processing)
            else content.loadPrepared(currentOwner,metadata.bookUrl,metadata.origin,metadata.chapterIndex,metadata.position,metadata.processing,prepared) { loadKey=it }
    }

    internal fun refreshAppearance() {
        val shown = state.value.ready ?: return
        if (!active || state.value.loading || state.value.navigation != null || state.value.annotationDraft!=null || state.value.annotationBusy || state.value.ruleAction!=null || state.value.ruleBusy) return
        val ticket = epoch
        val appearanceTicket = ++appearanceEpoch
        viewModelScope.launch {
            operations.withLock {
                try {
                    if (!active || ticket != epoch || appearanceTicket != appearanceEpoch || loadKey != shown.key) return@withLock
                    progress?.join()
                    val session = sessions.current(shown.key.owner) ?: return@withLock
                    val metadata = entries.entry(session, shown.entry.chapterIndex, state.value.anchor)
                    if (!active || ticket != epoch || appearanceTicket != appearanceEpoch || loadKey != shown.key || state.value.loading) return@withLock
                    val current = metadata.copy(position = state.value.anchor)
                    val before = shown.entry
                    val documentChanged = before.origin != current.origin || before.chapterUrl != current.chapterUrl ||
                        before.titleMode != current.titleMode || before.splitTitle != current.splitTitle ||
                        before.adaptSpecialStyle != current.adaptSpecialStyle || before.isVolume != current.isVolume ||
                        before.titleImage != current.titleImage || before.imageStyle != current.imageStyle || before.localImages != current.localImages ||
                        before.processing != current.processing
                    if (documentChanged) load(current.copy(position = state.value.persistedAnchor ?: shown.document.storagePosition(state.value.anchor)), shown.key.owner,
                        shown.document.storageTitleLength, shown.document.storageIndentLength)
                    else {
                        entry = current
                        mutableState.update { it.copy(ready = it.ready?.copy(entry = current), background = current.background) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (active && ticket == epoch && appearanceTicket == appearanceEpoch && loadKey == shown.key)
                        mutableState.update { it.copy(error = error.localizedMessage ?: "Unable to refresh reading appearance") }
                }
            }
        }
    }

    /** A departing composition may finish after its replacement has already resumed. */
    internal fun enterLifecycle(): Long {
        val ticket = ++lifecycleEpoch
        resume()
        return ticket
    }
    internal fun leaveLifecycle(ticket: Long) { if (ticket == lifecycleEpoch) pause() }
    internal fun pause() { ruleJob?.cancel(); ruleJob=null; lifecycleEpoch++; active = false; epoch++; render = null; noteRender = null; cancelLinkLoad() }
    internal fun retry() { resume() }
    internal fun menu(visible: Boolean) { mutableState.update { if (visible && (it.footnote != null || it.linkLoading)) it else it.copy(menu = visible) } }
    internal fun navigationHandled(route: AppRoute) {
        if (state.value.navigation == route && route is BookInfoRoute && route.returnToReader)
            savedState["textReader.deliveredDetail"] = route.requestId
        mutableState.update { if (it.navigation == route) it.copy(navigation = null) else it }
    }

    internal suspend fun image(key: TextReaderChapterKey, image: TextReaderImage, force: Boolean = false): ByteArray {
        check(key == loadKey) { "Reader content changed" }
        val bytes = images.load(key, image, force)
        check(key == loadKey) { "Reader content changed" }
        return bytes
    }

    internal fun installRender(key: TextReaderRenderKey) {
        if (state.value.ready?.contentKey == key.content && !state.value.loading &&
            (render == null || key.generation >= checkNotNull(render).generation)) render = key
    }

    internal fun acceptsInteraction(key: TextReaderRenderKey, anchor: Int): Boolean =
        active && !state.value.loading && state.value.navigation == null &&
            !state.value.linkLoading && state.value.footnote == null && state.value.annotationDraft == null &&
            !state.value.annotationList && !state.value.annotationBusy && state.value.ruleAction==null && !state.value.ruleBusy && state.value.anchor == anchor && render == key

    private fun chapterKey(metadata: TextReaderEntry) = textReaderLinkKey("${metadata.chapterIndex}:${metadata.chapterUrl}")
    private fun linkHistory(): List<TextReaderLinkPosition> {
        val revision = owner?.revision ?: -1
        val pending = savedState.get<Long>("textReader.links.pendingRevision")
        val key = if (pending != null && pending <= revision) "textReader.links.pendingHistory" else "textReader.links.historyV2"
        return savedState.get<ArrayList<String>>(key).orEmpty().takeLast(32)
            .mapNotNull(TextReaderLinkPosition::decode)
    }
    private fun saveHistory(history: List<TextReaderLinkPosition>) {
        savedState["textReader.links.historyV2"] = ArrayList(history.takeLast(32).map(TextReaderLinkPosition::encode))
        savedState.remove<Long>("textReader.links.pendingRevision")
        savedState.remove<ArrayList<String>>("textReader.links.pendingHistory")
    }
    private fun stageHistory(history: List<TextReaderLinkPosition>, revision: Long) {
        savedState["textReader.links.pendingHistory"] = ArrayList(history.takeLast(32).map(TextReaderLinkPosition::encode))
        savedState["textReader.links.pendingRevision"] = revision
    }
    private fun linkPosition(ready: TextReaderReady) = TextReaderLinkPosition(
        ready.entry.chapterIndex,chapterKey(ready.entry),state.value.persistedAnchor ?: ready.document.storagePosition(state.value.anchor),
        ready.document.storageTitleLength,ready.document.storageIndentLength)
    private fun clearSavedNote() {
        savedState.remove<String>("textReader.links.note")
        savedState.remove<Int>("textReader.links.notePosition")
        savedState.remove<String>("textReader.links.resource")
        savedState.remove<Int>("textReader.links.noteVersion")
        savedState.remove<Int>("textReader.links.noteTitle")
        savedState.remove<Int>("textReader.links.noteIndent")
        noteRender = null
    }
    private fun restoreLinks(metadata: TextReaderEntry, document: TextReaderDocument): TextReaderFootnote? {
        val context = textReaderLinkKey(listOf(metadata.bookUrl, metadata.origin, metadata.chapterUrl, metadata.chapterIndex.toString())
            .joinToString("") { "${it.length}:$it" })
        if (savedState.get<String>("textReader.links.context") != context) {
            savedState.remove<ArrayList<Int>>("textReader.links.history")
            savedState.remove<Int>("textReader.links.titleLength")
            savedState.remove<Int>("textReader.links.indentLength")
            clearSavedNote()
        }
        savedState["textReader.links.context"] = context
        val oldTitle = savedState.get<Int>("textReader.links.titleLength")?.coerceAtLeast(0) ?: document.storageTitleLength
        val oldIndent = savedState.get<Int>("textReader.links.indentLength")?.coerceAtLeast(0) ?: document.storageIndentLength
        val historyContext = textReaderLinkKey("${metadata.bookUrl.length}:${metadata.bookUrl}${metadata.origin}")
        if (savedState.get<String>("textReader.links.historyContext") != historyContext) {
            saveHistory(emptyList())
        } else saveHistory(linkHistory())
        savedState["textReader.links.historyContext"] = historyContext
        val legacy = savedState.remove<ArrayList<Int>>("textReader.links.history").orEmpty().filter { it >= 0 }.takeLast(32)
        if (legacy.isNotEmpty()) saveHistory(linkHistory() + legacy.map {
            TextReaderLinkPosition(metadata.chapterIndex,chapterKey(metadata),
                (document.storage ?: document).position(it,oldTitle,oldIndent),document.storageTitleLength,
                document.storageIndentLength)
        })
        savedState["textReader.links.titleLength"] = document.storageTitleLength
        savedState["textReader.links.indentLength"] = document.storageIndentLength
        if (savedState.get<String>("textReader.links.resource") != null) return null
        val note = document.anchors.firstOrNull { it.key == savedState.get<String>("textReader.links.note") && it.end > it.start &&
            document.text.substring(it.start, it.end).isNotBlank() }
        if (note == null) { clearSavedNote(); return null }
        val restored = restoredNotePosition(document,note)
        saveNotePosition(document,note,restored.first,restored.second)
        return TextReaderFootnote(note.key,restored.first,++noteInstance,persistedPosition=restored.second)
    }
    private fun restoredNotePosition(document: TextReaderDocument, anchor: TextReaderAnchor): Pair<Int,Int?> {
        val saved = savedState.get<Int>("textReader.links.notePosition") ?: 0
        if (savedState.get<Int>("textReader.links.noteVersion") == 1) {
            val source = document.storage ?: document
            val at = source.position(saved,savedState["textReader.links.noteTitle"],savedState["textReader.links.noteIndent"])
            val display = document.restorePosition(at)
            return (display - anchor.start).coerceIn(0,(anchor.end-anchor.start-1).coerceAtLeast(0)) to at
        }
        val offset = saved.coerceIn(0,(anchor.end-anchor.start-1).coerceAtLeast(0))
        return (document.position(anchor.start+offset)-anchor.start).coerceAtLeast(0) to null
    }
    private fun saveNotePosition(document: TextReaderDocument, anchor: TextReaderAnchor, offset: Int, pinned: Int? = null) {
        savedState["textReader.links.noteVersion"] = 1
        // A page may begin inside synthetic indentation. Persist the next content
        // character so a lossy whitespace projection cannot restore the previous page.
        var content = (anchor.start + offset).coerceIn(anchor.start, anchor.end)
        while (content < anchor.end && document.text[content].isWhitespace()) content++
        if (content == anchor.end) content = anchor.start + offset
        savedState["textReader.links.notePosition"] = pinned ?: document.storagePosition(content)
        savedState["textReader.links.noteTitle"] = document.storageTitleLength
        savedState["textReader.links.noteIndent"] = document.storageIndentLength
    }
    private fun applyLink(ready: TextReaderReady, action: TextReaderLinkAction): TextReaderLinkAction {
        when (action) {
            is TextReaderLinkAction.Jump -> {
                val position = ready.document.position(action.position)
                if (position != state.value.anchor)
                    saveHistory(linkHistory() + linkPosition(ready))
                clearSavedNote()
                mutableState.update { it.copy(anchor = position, persistedAnchor = null, footnote = null, menu = false,
                    linkReturnAvailable = linkHistory().isNotEmpty()) }
            }
            is TextReaderLinkAction.Note -> {
                openNote(ready, ready.document, action.anchor)
            }
            is TextReaderLinkAction.Resource -> loadResource(ready, action)
            else -> Unit
        }
        return action
    }

    private fun openNote(ready: TextReaderReady, document: TextReaderDocument, anchor: TextReaderAnchor,
        requestedPosition: Int = 0, persistedPosition: Int? = null): TextReaderLinkAction {
        val offset = (document.position(anchor.start + requestedPosition.coerceIn(0, (anchor.end - anchor.start - 1).coerceAtLeast(0))) - anchor.start)
            .coerceAtLeast(0)
        savedState["textReader.links.note"] = anchor.key
        saveNotePosition(document,anchor,offset,persistedPosition)
        val resource = if (document !== ready.document) document.resources.singleOrNull()?.let { EpubResourceAddress.fromPath(it)?.key } else null
        if (resource == null) savedState.remove<String>("textReader.links.resource") else savedState["textReader.links.resource"] = resource
        noteRender = null
        val note = TextReaderFootnote(anchor.key, offset, ++noteInstance, document.takeUnless { it === ready.document },persistedPosition)
        mutableState.update { it.copy(footnote = note, menu = false, linkLoading = false) }
        return TextReaderLinkAction.Note(anchor)
    }

    private fun cancelLinkLoad() {
        linkEpoch++; linkJob?.cancel(); linkJob = null
        mutableState.update { it.copy(linkLoading = false) }
    }
    internal fun cancelPendingLink() {
        if (linkCommitting) return
        cancelLinkLoad()
        if (state.value.footnote == null) clearSavedNote()
    }
    private fun loadResource(ready: TextReaderReady, action: TextReaderLinkAction.Resource? = null, resourceKey: String? = null) {
        cancelLinkLoad()
        val ticket = linkEpoch
        val capturedAnchor = state.value.anchor
        val capturedNote = state.value.footnote?.instance
        val savedAnchor = savedState.get<String>("textReader.links.note")
        mutableState.update { it.copy(linkLoading = true, menu = false) }
        linkJob = viewModelScope.launch {
            try {
                val resource = if (action != null) epubs.resource(ready.key, action.address)
                    else epubs.resourceByKey(ready.key, checkNotNull(resourceKey))
                val document = resource?.let { loader.resourceDocument(ready.key, it) }
                if (ticket != linkEpoch || !active || state.value.loading || loadKey != ready.key ||
                    state.value.anchor != capturedAnchor || state.value.footnote?.instance != capturedNote) return@launch
                val result = if (document == null) TextReaderLinkAction.Missing else if (action != null)
                    document.linkAction(TextReaderLink(0, 0, action.address.href, action.noteReference), ready.entry.chapterUrl)
                else document.anchors.firstOrNull { it.key == savedAnchor && it.end > it.start }
                    ?.let(TextReaderLinkAction::Note) ?: TextReaderLinkAction.Missing
                if (result is TextReaderLinkAction.Note && document != null) {
                    val restored = if (action == null) restoredNotePosition(document,result.anchor) else 0 to null
                    openNote(ready, document, result.anchor,restored.first,restored.second)
                }
                else if (result is TextReaderLinkAction.Jump && action != null) {
                    loadChapterLink(ready,address = action.address)
                } else {
                    if (resourceKey != null) clearSavedNote()
                    mutableState.update { it.copy(linkLoading = false, linkFailure = it.linkFailure + 1) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == linkEpoch && active && loadKey == ready.key) {
                mutableState.update { it.copy(linkLoading = false, linkFailure = it.linkFailure + 1) }
            } }
        }
    }
    private fun loadChapterLink(ready: TextReaderReady, address: EpubResourceAddress? = null,
        returnTo: TextReaderLinkPosition? = null, annotation: ReaderAnnotationItem? = null) {
        cancelLinkLoad()
        val ticket = linkEpoch
        val capturedAnchor = state.value.anchor
        val capturedNote = state.value.footnote?.instance
        mutableState.update { it.copy(linkLoading = true,menu = false) }
        linkJob = viewModelScope.launch {
            try {
                val annotationChapter=annotation?.let { annotations.target(ready.key.owner,ready.entry,it) }
                val destination = if(annotationChapter!=null) EpubChapterTarget(annotationChapter.index,annotationChapter.url)
                    else if (returnTo != null) EpubChapterTarget(returnTo.chapter, "")
                    else epubs.targetChapter(ready.key,checkNotNull(address))
                checkNotNull(destination) { "Missing reading chapter" }
                val current = checkNotNull(sessions.current(ready.key.owner))
                val metadata = entries.entry(current,destination.index,0)
                check(metadata.origin == ready.entry.origin && (returnTo?.chapterKey?.let { it == chapterKey(metadata) }
                    ?: (metadata.chapterUrl == destination.url))) { "Reader chapter changed" }
                val prepared = loader.load(ready.key.copy(chapterIndex=metadata.chapterIndex,processing=metadata.processing))
                val document = entries.document(metadata,prepared)
                val position = if(annotation!=null) document.storagePosition(textReaderAnnotationRange(document,annotation.position,annotation.end,annotation.titleLength,annotation.text).first)
                else if (returnTo != null) (document.storage ?: document).position(
                    returnTo.position,returnTo.titleLength,returnTo.indentLength)
                else {
                    val action = document.linkAction(TextReaderLink(0,0,checkNotNull(address).href),metadata.chapterUrl)
                    check(action is TextReaderLinkAction.Jump) { "Missing visible chapter anchor" }
                    document.storagePosition(action.position)
                }
                operations.withLock {
                    if (ticket != linkEpoch || !active || loadKey != ready.key || state.value.loading ||
                        state.value.anchor != capturedAnchor || state.value.footnote?.instance != capturedNote) return@withLock
                    progress?.join()
                    if(annotation!=null) annotations.target(ready.key.owner,ready.entry,annotation)
                    val before = linkHistory()
                    // Save the small return key before the transaction. An uncommitted revision
                    // is ignored on restoration; a committed move survives lifecycle cancellation.
                    val after = if (returnTo != null) before.dropLast(1)
                        else (before + linkPosition(ready)).takeLast(32)
                    withContext(NonCancellable) {
                        linkCommitting = true
                        try {
                            stageHistory(after,ready.key.owner.revision + 1)
                            val moved = try {
                                sessions.navigateChapter(ready.key.owner,metadata.bookUrl,metadata.origin,
                                    metadata.chapterIndex,metadata.chapterUrl,position)
                            } catch (error: Exception) {
                                saveHistory(before)
                                throw error
                            }
                            if (moved == null) { saveHistory(before); error("Reader no longer owns this book") }
                            owner = moved.progressOwner()
                            if(annotation!=null) {
                                savedState["textReader.search.highlightEnabled"]=false
                                mutableState.update { it.copy(searchMatch=null) }
                            }
                            saveHistory(after)
                            clearSavedNote()
                            if (active && ticket == linkEpoch && loadKey == ready.key) {
                                load(metadata.copy(position=position),moved.progressOwner(),prepared=prepared)
                            } else mutableState.update { it.copy(linkLoading=false) }
                        } finally { linkCommitting = false }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == linkEpoch && active && loadKey == ready.key) {
                    // A removed return destination must not trap the system Back action in retries.
                    if (returnTo != null) saveHistory(linkHistory().dropLast(1))
                    mutableState.update { it.copy(linkLoading=false,linkFailure=it.linkFailure+1,
                        linkReturnAvailable=linkHistory().isNotEmpty()) }
                }
            }
        }
    }
    internal fun followLink(key: TextReaderRenderKey, anchor: Int, link: TextReaderLink): TextReaderLinkAction? {
        val ready = state.value.ready ?: return null
        if (!acceptsInteraction(key, anchor) || link !in ready.document.links) return null
        return applyLink(ready, ready.document.linkAction(link, ready.entry.chapterUrl.ifBlank { ready.entry.bookUrl }))
    }
    internal fun dismissFootnote(key: TextReaderChapterKey? = null, id: String? = null, instance: Long? = null) {
        if (key != null && state.value.ready?.key != key || id != null && state.value.footnote?.anchor != id ||
            instance != null && state.value.footnote?.instance != instance) return
        cancelLinkLoad(); clearSavedNote(); mutableState.update { it.copy(footnote = null) }
    }
    internal fun showFootnoteInText(key: TextReaderChapterKey, id: String, instance: Long): Boolean {
        val ready = state.value.ready ?: return false
        if (!active || state.value.loading || state.value.linkLoading || state.value.navigation != null || ready.key != key ||
            state.value.footnote?.let { it.anchor == id && it.instance == instance } != true) return false
        val target = state.value.footnote?.targetIn(ready.document) ?: return false
        applyLink(ready, TextReaderLinkAction.Jump(target.start)); return true
    }
    internal fun returnFromLink(): Boolean {
        val ready = state.value.ready ?: return false
        val history = linkHistory()
        if (!active || state.value.loading || state.value.linkLoading || state.value.navigation != null || state.value.footnote != null || history.isEmpty()) return false
        val target = history.last()
        savedState["textReader.search.highlightEnabled"]=false
        mutableState.update { it.copy(searchMatch=null) }
        if (target.chapter == ready.entry.chapterIndex && target.chapterKey == chapterKey(ready.entry)) {
            val at = (ready.document.storage ?: ready.document).position(target.position,target.titleLength,target.indentLength)
            saveHistory(history.dropLast(1))
            mutableState.update { it.copy(anchor = ready.document.restorePosition(at), persistedAnchor = at,
                menu = false, linkReturnAvailable = history.size > 1) }
        } else loadChapterLink(ready,returnTo = target)
        return true
    }
    internal fun nextFootnoteRenderGeneration(): Long = ++noteRenderGeneration
    internal fun installFootnoteRender(key: TextReaderRenderKey, id: String) {
        if (!state.value.loading && state.value.ready?.contentKey == key.content && state.value.footnote?.anchor == id &&
            (noteRender == null || key.generation >= checkNotNull(noteRender).generation)) noteRender = key
    }
    internal fun acceptsFootnote(key: TextReaderRenderKey, id: String, position: Int): Boolean =
        active && !state.value.loading && !state.value.linkLoading && state.value.navigation == null && state.value.ready?.contentKey == key.content &&
            state.value.footnote?.let { it.anchor == id && it.position == position } == true && noteRender == key
    internal fun turnFootnote(key: TextReaderRenderKey, id: String, position: Int, target: Int): Boolean {
        val ready = state.value.ready ?: return false
        val document = state.value.footnote?.document ?: ready.document
        val note = document.anchors.firstOrNull { it.key == id } ?: return false
        if (!acceptsFootnote(key, id, position) || target !in 0..(note.end - note.start)) return false
        val next = (document.position(note.start + target.coerceAtMost(note.end - note.start - 1)) - note.start).coerceAtLeast(0)
        saveNotePosition(document,note,next)
        mutableState.update { it.copy(footnote = it.footnote?.copy(position = next,persistedPosition=null)) }; return true
    }
    internal fun followFootnoteLink(key: TextReaderRenderKey, id: String, position: Int, link: TextReaderLink): TextReaderLinkAction? {
        val ready = state.value.ready ?: return null
        val document = state.value.footnote?.document ?: ready.document
        val note = document.anchors.firstOrNull { it.key == id } ?: return null
        if (!acceptsFootnote(key, id, position)) return null
        val original = document.links.firstOrNull { it.target == link.target && it.noteReference == link.noteReference &&
            maxOf(it.start, note.start) - note.start == link.start && minOf(it.end, note.end) - note.start == link.end } ?: return null
        val mainAction = ready.document.linkAction(original, ready.entry.chapterUrl.ifBlank { ready.entry.bookUrl })
        if (mainAction is TextReaderLinkAction.Resource && document !== ready.document) {
            val action = document.linkAction(original, ready.entry.chapterUrl)
            if (action is TextReaderLinkAction.Note) return openNote(ready, document, action.anchor)
        }
        return applyLink(ready, mainAction)
    }

    private fun hasPreviousPosition(metadata: TextReaderEntry, position: Int, revision: Long): Boolean =
        savedState.get<String>("textReader.position.book") == metadata.bookUrl &&
            savedState.get<String>("textReader.position.origin") == metadata.origin &&
            savedState.get<Int>("textReader.position.chapter") == metadata.chapterIndex &&
            savedState.get<Int>("textReader.position.offset") == position &&
            savedState.get<Long>("textReader.position.revision") == revision

    private fun previousTitleLength(metadata: TextReaderEntry, position: Int, revision: Long): Int? =
        if (hasPreviousPosition(metadata, position, revision)) savedState["textReader.position.titleLength"] else null
    private fun previousIndentLength(metadata: TextReaderEntry, position: Int, revision: Long): Int? =
        if (hasPreviousPosition(metadata, position, revision)) savedState["textReader.position.indentLength"] else null

    internal fun visible(key: TextReaderRenderKey, page: TextReaderPageSlice, offset: Int, count: Int) {
        val current = state.value
        val ready = current.ready ?: return
        if (!active || current.loading || current.linkLoading || current.navigation != null || current.footnote != null || current.annotationDraft!=null || current.annotationList || current.annotationBusy || current.ruleAction!=null || current.ruleBusy || current.anchor != offset ||
            !acceptsTextReaderViewport(render, key, page, offset) || page.index !in 0 until count) return
        visiblePage = page
        mutableState.update { it.copy(page = page.index, pageCount = count) }
        val position = current.persistedAnchor ?: ready.document.storagePosition(offset)
        progress = viewModelScope.launch {
            try {
                if (!entries.save(ready.key.owner, ready.entry, position, ready.title)) error("Reader no longer owns this book")
                savedState["textReader.position.book"] = ready.entry.bookUrl
                savedState["textReader.position.origin"] = ready.entry.origin
                savedState["textReader.position.chapter"] = ready.entry.chapterIndex
                savedState["textReader.position.offset"] = position
                savedState["textReader.position.titleLength"] = ready.document.storageTitleLength
                savedState["textReader.position.indentLength"] = ready.document.storageIndentLength
                savedState["textReader.position.revision"] = ready.key.owner.revision
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (active && loadKey == ready.key) {
                render = null
                mutableState.update { it.copy(error = error.localizedMessage ?: "Unable to save reading position") }
            } }
        }
    }

    internal fun turn(key: TextReaderRenderKey, position: Int): Boolean {
        val ready = state.value.ready ?: return false
        if (!active || state.value.loading || state.value.linkLoading || state.value.footnote != null || state.value.annotationDraft!=null || state.value.annotationList || state.value.annotationBusy || state.value.ruleAction!=null || state.value.ruleBusy || render != key || position !in 0..ready.document.text.length) return false
        savedState["textReader.search.highlightEnabled"]=false
        mutableState.update { it.copy(anchor = ready.document.position(position), persistedAnchor = null,searchMatch=null) }
        return true
    }

    internal fun chapter(direction: Int, atEnd: Boolean = false): Boolean {
        val ready = state.value.ready ?: return false
        val index = ready.entry.chapterIndex + direction
        if (!active || state.value.loading || state.value.linkLoading || state.value.footnote != null || state.value.annotationDraft!=null || state.value.annotationList || state.value.annotationBusy || state.value.ruleAction!=null || state.value.ruleBusy || direction !in listOf(-1, 1) || index !in 0 until ready.entry.chapterCount) return false
        savedState["textReader.search.highlightEnabled"]=false
        render = null
        mutableState.update { it.copy(loading = true,searchMatch=null) }
        viewModelScope.launch {
            operations.withLock {
                try {
                    progress?.join()
                    val current = checkNotNull(sessions.current(ready.key.owner))
                    load(entries.entry(current, index, if (atEnd) Int.MAX_VALUE else 0), current.progressOwner())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableState.update { it.copy(loading = false, error = error.localizedMessage) } }
            }
        }
        return true
    }

    private fun observeHighlightRules(ready: TextReaderReady) {
        ruleJob?.cancel()
        if(!active) return
        ruleJob=viewModelScope.launch {
            try {
                highlightRules.observe(ready.entry.bookUrl,ready.entry.origin,ready.document).collectLatest { result ->
                    if(active && loadKey==ready.key) mutableState.update { current ->
                        val actionId=savedState.get<Long>("textReader.rule.id")
                        val actionFingerprint=savedState.get<String>("textReader.rule.fingerprint")
                        val actionContext=textReaderLinkKey("${ready.entry.bookUrl}:${ready.entry.origin}:${chapterKey(ready.entry)}")
                        val action=if(savedState.get<String>("textReader.rule.context")==actionContext)
                            result.matches.firstOrNull { it.rule.id==actionId && it.rule.fingerprint==actionFingerprint }?.rule else null
                        if(action==null && actionId!=null) {
                            savedState.remove<Long>("textReader.rule.id"); savedState.remove<String>("textReader.rule.fingerprint"); savedState.remove<String>("textReader.rule.context")
                        }
                        current.copy(ruleHighlights=result.matches,ruleAction=action,
                            ruleWarning=current.ruleWarning+if(result.completed) 0 else 1)
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(active && loadKey==ready.key) mutableState.update { it.copy(ruleHighlights=emptyList(),ruleWarning=it.ruleWarning+1) } }
        }
    }
    internal fun showHighlightRule(key: TextReaderRenderKey,anchor: Int,match: TextReaderRuleMatch): Boolean {
        if(!acceptsInteraction(key,anchor) || match !in state.value.ruleHighlights) return false
        savedState["textReader.rule.id"]=match.rule.id
        savedState["textReader.rule.fingerprint"]=match.rule.fingerprint
        val ready=checkNotNull(state.value.ready)
        savedState["textReader.rule.context"]=textReaderLinkKey("${ready.entry.bookUrl}:${ready.entry.origin}:${chapterKey(ready.entry)}")
        mutableState.update { it.copy(ruleAction=match.rule,menu=false,ruleError=false) }; return true
    }
    internal fun dismissHighlightRule() {
        if(state.value.ruleBusy) return
        savedState.remove<Long>("textReader.rule.id"); savedState.remove<String>("textReader.rule.fingerprint"); savedState.remove<String>("textReader.rule.context")
        mutableState.update { it.copy(ruleAction=null,ruleError=false) }
    }
    internal fun disableHighlightRule(expected: TextReaderHighlightRule) {
        if(!active || state.value.ruleBusy || state.value.ruleAction!=expected) return
        mutableState.update { it.copy(ruleBusy=true,ruleError=false) }
        viewModelScope.launch { operations.withLock {
            try {
                highlightRules.disable(expected)
                savedState.remove<Long>("textReader.rule.id"); savedState.remove<String>("textReader.rule.fingerprint"); savedState.remove<String>("textReader.rule.context")
                mutableState.update { it.copy(ruleHighlights=it.ruleHighlights.filterNot { hit -> hit.rule==expected },ruleAction=null) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutableState.update { it.copy(ruleError=true) } }
            finally { mutableState.update { it.copy(ruleBusy=false) } }
        } }
    }
    internal fun manageHighlightRules() = navigateHighlightRules(HighlightRuleRoute)
    internal fun editHighlightRule(expected: TextReaderHighlightRule) {
        if(state.value.ruleAction!=expected || state.value.ruleHighlights.none { it.rule==expected }) return
        navigateHighlightRules(HighlightRuleEditRoute(expected.id))
    }
    private fun navigateHighlightRules(destination: AppRoute) {
        if(!active || state.value.loading || state.value.navigation!=null || state.value.linkLoading || state.value.annotationBusy || state.value.annotationDraft!=null || state.value.annotationList || state.value.footnote!=null || state.value.ruleBusy) return
        val key=state.value.ready?.key ?: return
        val ticket=epoch
        dismissHighlightRule()
        viewModelScope.launch { operations.withLock {
            try { progress?.join(); if(active && ticket==epoch && loadKey==key && state.value.navigation==null) mutableState.update { it.copy(menu=false,navigation=destination) } }
            catch(cancelled: CancellationException) { throw cancelled }
        } }
    }

    private fun restoreAnnotations(ready: TextReaderReady) {
        val key="${ready.entry.bookUrl}:${ready.entry.origin}"
        if(annotationBook!=key) {
            annotationJob?.cancel(); annotationBook=key
            annotationJob=viewModelScope.launch {
                try {
                    annotations.observe(ready.entry.bookUrl).collectLatest { rows ->
                        if(annotationBook==key) mutableState.update { it.copy(annotations=rows) }
                    }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) { if(annotationBook==key) mutableState.update { it.copy(annotations=emptyList(),annotationError=error.message) } }
            }
        }
        viewModelScope.launch { operations.withLock {
            try {
                val draft=annotations.restore(ready.key.owner,ready.entry.bookUrl,ready.entry.origin)
                if(loadKey==ready.key && !state.value.annotationBusy) mutableState.update {
                    it.copy(annotationDraft=draft,annotationList=savedState["textReader.annotations.list"] ?: false)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { if(loadKey==ready.key) mutableState.update { it.copy(annotationError=error.message) } }
        } }
    }
    internal fun annotationList(visible: Boolean) {
        if(state.value.annotationBusy) return
        savedState["textReader.annotations.list"]=visible
        mutableState.update { it.copy(annotationList=visible,menu=false,annotationError=null) }
    }
    internal fun bookmarkPage() {
        val ready=state.value.ready ?: return
        val page=visiblePage ?: return
        val key=render ?: return
        createAnnotation(key,state.value.anchor,state.value.anchor.coerceIn(page.start,page.end),page.end,false)
    }
    internal fun createAnnotation(key: TextReaderRenderKey,anchor: Int,start: Int,end: Int,highlight: Boolean) {
        val ready=state.value.ready ?: return
        if(!acceptsInteraction(key,anchor)) return
        annotationOperation {
            val draft=annotations.create(ready.key.owner,ready.entry,ready.document,ready.title,start,end,highlight)
            mutableState.update { it.copy(annotationDraft=draft,menu=false) }
        }
    }
    internal fun editAnnotation(row: ReaderAnnotationItem) {
        val ready=state.value.ready ?: return
        if(row !in state.value.annotations) return
        annotationOperation {
            val draft=annotations.edit(ready.key.owner,ready.entry,row)
            mutableState.update { it.copy(annotationDraft=draft) }
        }
    }
    internal fun annotationText(id: String,text: String?=null,note: String?=null) {
        val ready=state.value.ready ?: return
        val draft=state.value.annotationDraft?.takeIf { it.id==id } ?: return
        val body=text ?: draft.bookText
        val comment=note ?: draft.note
        if(state.value.annotationBusy) return
        if(body.length>ReaderAnnotationRepository.MAX_TEXT || comment.length>ReaderAnnotationRepository.MAX_TEXT) {
            mutableState.update { it.copy(annotationError="Annotation text too long") }; return
        }
        mutableState.update { it.copy(annotationDraft=draft.copy(bookText=body,note=comment),annotationError=null) }
        viewModelScope.launch { operations.withLock {
            try { annotations.update(ready.key.owner,draft.id,body,comment) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutableState.update { it.copy(annotationError=error.message) } }
        } }
    }
    internal fun annotationStyle(id: String,change: (io.legado.app.help.HighlightStyle) -> io.legado.app.help.HighlightStyle) {
        val ready=state.value.ready ?: return
        val draft=state.value.annotationDraft?.takeIf { it.id==id && it.highlight } ?: return
        if(state.value.annotationBusy) return
        val before=io.legado.app.utils.GSON.fromJsonObject<io.legado.app.help.HighlightStyle>(draft.style).getOrNull() ?: io.legado.app.help.HighlightStyle()
        val style=change(before).normalized()
        if(style.resolvedFontPath.length>2048) return
        mutableState.update { it.copy(annotationDraft=draft.copy(style=mergeReaderHighlightStyle(draft.style,style)),annotationError=null) }
        viewModelScope.launch { operations.withLock {
            try { annotations.updateStyle(ready.key.owner,id,style) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutableState.update { it.copy(annotationError=error.message) } }
        } }
    }
    internal fun finishAnnotation(save: Boolean,id: String?=null) {
        val ready=state.value.ready ?: return
        val draft=state.value.annotationDraft?.takeIf { id==null || it.id==id } ?: return
        annotationOperation {
            if(save) annotations.save(ready.key.owner,draft.id) else annotations.discard(ready.key.owner,draft.id)
            mutableState.update { it.copy(annotationDraft=null) }
        }
    }
    internal fun deleteAnnotation(row: ReaderAnnotationItem,id: String?=null) {
        val ready=state.value.ready ?: return
        if(row !in state.value.annotations || id!=null && state.value.annotationDraft?.id!=id) return
        annotationOperation {
            annotations.delete(ready.key.owner,ready.entry,row)
            state.value.annotationDraft?.takeIf { it.editingTime==row.time && it.highlight==row.highlight }?.let {
                annotations.discard(ready.key.owner,it.id)
            }
            mutableState.update { it.copy(annotationDraft=null) }
        }
    }
    internal fun jumpAnnotation(row: ReaderAnnotationItem) {
        val ready=state.value.ready ?: return
        if(!active || row !in state.value.annotations || state.value.annotationBusy || state.value.annotationDraft!=null) return
        annotationList(false)
        loadChapterLink(ready,annotation=row)
    }
    private fun annotationOperation(block: suspend () -> Unit) {
        if(!active || state.value.loading || state.value.annotationBusy || state.value.ruleAction!=null || state.value.ruleBusy || state.value.linkLoading || state.value.navigation!=null) return
        mutableState.update { it.copy(annotationBusy=true,annotationError=null) }
        viewModelScope.launch { operations.withLock {
            try { progress?.join(); block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutableState.update { it.copy(annotationError=error.message ?: "Unable to save annotation") } }
            finally { mutableState.update { it.copy(annotationBusy=false) } }
        } }
    }

    internal fun catalog() = navigate(false)
    internal fun details() = navigate(true)
    internal fun search(query: String="") {
        if(query.length<=2048) navigate(false,true,query)
    }
    private fun navigate(detail: Boolean,search: Boolean=false,query: String="") {
        val ready = state.value.ready ?: return
        if (!active || state.value.loading || state.value.linkLoading || state.value.footnote != null) return
        render = null
        mutableState.update { it.copy(loading = true, menu = false) }
        viewModelScope.launch {
            operations.withLock {
                try {
                    progress?.join()
                    if (detail) {
                        val child = checkNotNull(sessions.beginDetail(ready.key.owner, ready.entry.chapterIndex,
                            state.value.persistedAnchor ?: ready.document.storagePosition(state.value.anchor)))
                        owner = ready.key.owner.copy(revision = child.revision)
                        mutableState.update { it.copy(navigation = BookInfoRoute(child.bookUrl, child.id, returnToReader = true)) }
                    } else {
                        val child = checkNotNull(sessions.beginCatalog(ready.key.owner, ready.entry.chapterIndex,
                            state.value.persistedAnchor ?: ready.document.storagePosition(state.value.anchor)))
                        owner = sessions.current(ready.key.owner)?.progressOwner() ?: owner
                        val destination=if(search) {
                            val id=UUID.randomUUID().toString()
                            savedState["textReader.search.pending"]=id
                            savedState["textReader.search.request"]=child.id
                            savedState["textReader.search.return"]=linkPosition(ready).encode()
                            SearchContentRoute(child.bookUrl,query=query,sessionId=id,readerCatalogRequestId=child.id)
                        } else BookTocRoute(child.bookUrl, readerCatalogRequestId = child.id)
                        mutableState.update { it.copy(navigation = destination) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableState.update { it.copy(loading = false, error = error.localizedMessage) } }
            }
        }
    }

    internal fun close() {
        pause()
        viewModelScope.launch {
            operations.withLock {
                try {
                    progress?.join()
                    owner?.let { sessions.closeAndAwait(it) }
                    content.close()
                    mutableState.update { it.copy(closed = true) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableState.update { it.copy(loading = false, error = error.localizedMessage) } }
            }
        }
    }

    override fun onCleared() {
        content.close()
        owner?.let(sessions::close)
    }
}
