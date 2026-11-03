package io.legado.app.model

import io.legado.app.data.repository.ReaderProgressOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** A chapter request is bound to both the persisted reader revision and this load generation. */
data class TextReaderChapterKey(
    val owner: ReaderProgressOwner,
    val bookUrl: String,
    val origin: String,
    val chapterIndex: Int,
    val generation: Long,
    val processing: TextReaderProcessing = TextReaderProcessing(),
) {
    init {
        require(owner.sessionId.isNotBlank() && owner.token.isNotBlank() && owner.revision >= 0)
        require(bookUrl.isNotBlank() && origin.isNotBlank() && chapterIndex >= 0 && generation >= 0)
    }
}

/** Paragraphs retain rich content markup until the renderer has built its offset map. */
class TextReaderChapterContent(val title: String, paragraphs: List<String>, val sameTitleRemoved: Boolean,
    val richBody: TextReaderDocument? = null) {
    val paragraphs: List<String> = java.util.Collections.unmodifiableList(ArrayList(paragraphs))
}

fun interface TextReaderChapterLoader {
    suspend fun load(key: TextReaderChapterKey): TextReaderChapterContent
}

sealed interface TextReaderContentState {
    data object Idle : TextReaderContentState
    data class Loading(val key: TextReaderChapterKey, val requestedPosition: Int) : TextReaderContentState
    data class Ready(val key: TextReaderChapterKey, val chapter: TextReaderChapterContent,
        val requestedPosition: Int) : TextReaderContentState
    data class Failed(val key: TextReaderChapterKey, val requestedPosition: Int,
        val message: String) : TextReaderContentState
    data object Closed : TextReaderContentState
}

/** Per navigation entry. No layout, Context, database entity or global reader is retained. */
class TextReaderContentSession(private val scope: CoroutineScope, private val loader: TextReaderChapterLoader) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<TextReaderContentState>(TextReaderContentState.Idle)
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var activeKey: TextReaderChapterKey? = null
    private var job: Job? = null
    init { scope.coroutineContext[Job]?.invokeOnCompletion { close() } }

    fun load(owner: ReaderProgressOwner, bookUrl: String, origin: String, chapterIndex: Int,
        requestedPosition: Int, processing: TextReaderProcessing = TextReaderProcessing()): TextReaderChapterKey {
        return checkNotNull(request(owner, bookUrl, origin, chapterIndex, requestedPosition, processing = processing))
    }

    /** Publish an immutable chapter already checked while preparing an internal navigation. */
    fun loadPrepared(owner: ReaderProgressOwner, bookUrl: String, origin: String, chapterIndex: Int,
        requestedPosition: Int, processing: TextReaderProcessing, chapter: TextReaderChapterContent,
        onKey: (TextReaderChapterKey) -> Unit): TextReaderChapterKey =
        checkNotNull(request(owner,bookUrl,origin,chapterIndex,requestedPosition,processing=processing,prepared=chapter,onKey=onKey))

    private fun request(owner: ReaderProgressOwner, bookUrl: String, origin: String, chapterIndex: Int,
        requestedPosition: Int, expectedFailure: TextReaderContentState.Failed? = null,
        processing: TextReaderProcessing = TextReaderProcessing(), prepared: TextReaderChapterContent? = null,
        onKey: (TextReaderChapterKey) -> Unit = {}): TextReaderChapterKey? {
        require(requestedPosition >= 0)
        val oldJob: Job?
        val nextJob: Job
        val key: TextReaderChapterKey
        synchronized(lock) {
            if (expectedFailure != null && mutableState.value !== expectedFailure) return null
            check(mutableState.value != TextReaderContentState.Closed) { "Reader content session is closed" }
            scope.coroutineContext.ensureActive()
            key = TextReaderChapterKey(owner, bookUrl, origin, chapterIndex, Math.incrementExact(generation), processing)
            generation = key.generation
            oldJob = job
            activeKey = key
            mutableState.value = TextReaderContentState.Loading(key, requestedPosition)
            nextJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    coroutineContext.ensureActive()
                    val chapter = prepared ?: loader.load(key)
                    coroutineContext.ensureActive()
                    synchronized(lock) {
                        if (activeKey == key) mutableState.value =
                            TextReaderContentState.Ready(key, chapter, requestedPosition)
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (activeKey == key) {
                            activeKey = null
                            mutableState.value = TextReaderContentState.Idle
                        }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    synchronized(lock) {
                        if (activeKey == key) mutableState.value = TextReaderContentState.Failed(
                            key, requestedPosition, error.localizedMessage ?: error.javaClass.simpleName)
                    }
                }
            }
            job = nextJob
        }
        oldJob?.cancel()
        onKey(key)
        nextJob.start()
        return key
    }

    /** A retry gets a fresh generation, so a previous attempt cannot acknowledge its viewport. */
    fun retry(): Boolean {
        val failure = synchronized(lock) { mutableState.value as? TextReaderContentState.Failed } ?: return false
        val key = failure.key
        return request(key.owner, key.bookUrl, key.origin, key.chapterIndex, failure.requestedPosition, failure, key.processing) != null
    }

    fun close() {
        val previous = synchronized(lock) {
            activeKey = null
            mutableState.value = TextReaderContentState.Closed
            job.also { job = null }
        }
        previous?.cancel()
    }
}
