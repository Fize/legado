package io.legado.app.model

import io.legado.app.data.repository.ReaderProgressOwner
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TextReaderContentSessionTest {
    @Test fun retriesKeepTheProcessingSnapshotAndNewLoadsOwnTheirOwnValues() {
        val requests = mutableListOf<TextReaderChapterKey>()
        val session = TextReaderContentSession(scope) { key ->
            requests += key
            if (requests.size == 1) error("Retry fixture") else chapter()
        }
        val options = TextReaderProcessing(" ", true, 2, true)
        session.load(owner, "book", "source", 0, 40, options)
        assertTrue(session.retry())
        assertEquals(options, requests[1].processing)
        assertNotEquals(requests[0].generation, requests[1].generation)
        session.load(owner, "book", "source", 0, 40, options.copy(paragraphIndent = ""))
        assertEquals(" ", requests[1].processing.paragraphIndent)
        assertEquals("", requests[2].processing.paragraphIndent)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val owner = ReaderProgressOwner("entry", "owner", 4)
    private fun chapter(title: String = "Chapter") = TextReaderChapterContent(title, listOf("Body"), false)
    private fun TextReaderContentSession.open(chapter: Int = 0, position: Int = 0,
        owner: ReaderProgressOwner = this@TextReaderContentSessionTest.owner, url: String = "book") =
        load(owner, url, "source", chapter, position)
    @After fun cleanup() { scope.cancel() }

    @Test fun loadingKeepsTheExactPersistedCharacterPositionUntilLayoutAcknowledgesIt() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) { gate.await() }
        assertEquals(TextReaderContentState.Idle, session.state.value)
        val key = session.open(position = 999)
        assertEquals(TextReaderContentState.Loading(key, 999), session.state.value)
        gate.complete(chapter())
        val ready = session.state.value as TextReaderContentState.Ready
        assertEquals(999, ready.requestedPosition)
        assertEquals(key, ready.key)
    }

    @Test fun aLatePreviousChapterCannotReplaceTheCurrentChapter() {
        val gates = List(2) { CompletableDeferred<TextReaderChapterContent>() }
        val session = TextReaderContentSession(scope) { key -> withContext(NonCancellable) { gates[key.chapterIndex].await() } }
        val old = session.open(); val current = session.open(chapter = 1, position = 23)
        gates[1].complete(chapter("Current")); gates[0].complete(chapter("Old"))
        val ready = session.state.value as TextReaderContentState.Ready
        assertEquals(current, ready.key); assertEquals("Current", ready.chapter.title)
        assertEquals(23, ready.requestedPosition); assertNotEquals(old.generation, current.generation)
    }

    @Test fun refreshingTheSameChapterInvalidatesThePreviousLoad() {
        val first = CompletableDeferred<TextReaderChapterContent>(); var calls = 0
        val session = TextReaderContentSession(scope) {
            if (calls++ == 0) withContext(NonCancellable) { first.await() } else chapter("Refreshed")
        }
        val old = session.open(position = 12); val fresh = session.open(position = 34)
        first.complete(chapter("Cached"))
        assertNotEquals(old, fresh)
        assertEquals(fresh, (session.state.value as TextReaderContentState.Ready).key)
        assertEquals(34, (session.state.value as TextReaderContentState.Ready).requestedPosition)
    }

    @Test fun anotherBookOrOwnerRevisionCannotReceiveAnOldResult() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) {
            if (it.bookUrl == "book") withContext(NonCancellable) { gate.await() } else chapter("Other")
        }
        session.open()
        val current = session.open(owner = owner.copy(revision = 5), url = "other")
        gate.complete(chapter("Old"))
        assertEquals(current, (session.state.value as TextReaderContentState.Ready).key)
    }

    @Test fun errorsBelongOnlyToTheCurrentAttempt() {
        val gate = CompletableDeferred<Unit>()
        val session = TextReaderContentSession(scope) {
            if (it.chapterIndex == 0) { withContext(NonCancellable) { gate.await() }; error("Old failure") }
            chapter("Current")
        }
        session.open(); val current = session.open(chapter = 1)
        gate.complete(Unit)
        assertEquals(current, (session.state.value as TextReaderContentState.Ready).key)
    }

    @Test fun aNewRevisionOfTheSameBookRejectsThePreviousRevision() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) {
            if (it.owner.revision == 4L) withContext(NonCancellable) { gate.await() } else chapter("Revision five")
        }
        session.open()
        val current = session.open(owner = owner.copy(revision = 5))
        gate.complete(chapter("Revision four"))
        assertEquals(current, (session.state.value as TextReaderContentState.Ready).key)
    }

    @Test fun sourceChangeOfTheSameBookRejectsThePreviousSource() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) {
            if (it.origin == "source") withContext(NonCancellable) { gate.await() } else chapter("New source")
        }
        session.open()
        val current = session.load(owner, "book", "new-source", 0, 32)
        gate.complete(chapter("Old source"))
        val ready = session.state.value as TextReaderContentState.Ready
        assertEquals(current, ready.key); assertEquals(32, ready.requestedPosition)
    }

    @Test fun retryKeepsTheIdentityAndAnchorButGetsANewGeneration() {
        var calls = 0
        val session = TextReaderContentSession(scope) { if (calls++ == 0) error("Network failed") else chapter() }
        val original = session.open(chapter = 3, position = 74)
        val failure = session.state.value as TextReaderContentState.Failed
        assertEquals("Network failed", failure.message)
        assertTrue(session.retry())
        val ready = session.state.value as TextReaderContentState.Ready
        assertEquals(original.copy(generation = original.generation + 1), ready.key)
        assertEquals(74, ready.requestedPosition); assertEquals(2, calls)
    }

    @Test fun retryWhileLoadingOrReadyCannotDuplicateWork() {
        var calls = 0; val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) { calls++; gate.await() }
        assertFalse(session.retry()); session.open(); assertFalse(session.retry())
        gate.complete(chapter()); assertFalse(session.retry()); assertEquals(1, calls)
    }

    @Test fun closeRejectsLateSuccessAndCannotBeReopened() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) { withContext(NonCancellable) { gate.await() } }
        session.open(); session.close(); session.close(); gate.complete(chapter())
        assertEquals(TextReaderContentState.Closed, session.state.value)
        assertFalse(session.retry())
        assertThrows(IllegalStateException::class.java) { session.open() }
    }

    @Test fun closeRejectsLateFailure() {
        val gate = CompletableDeferred<Unit>()
        val session = TextReaderContentSession(scope) { withContext(NonCancellable) { gate.await() }; error("Late") }
        session.open(); session.close(); gate.complete(Unit)
        assertEquals(TextReaderContentState.Closed, session.state.value)
    }

    @Test fun cancellationIsNotReportedAsARecoverableLoadingError() {
        val session = TextReaderContentSession(scope) { throw CancellationException("Cancelled") }
        session.open()
        assertEquals(TextReaderContentState.Idle, session.state.value)
        assertFalse(session.retry())
    }

    @Test fun scopeDisposalClosesTheSession() {
        val session = TextReaderContentSession(scope) { awaitCancellation() }
        session.open(); scope.cancel()
        assertEquals(TextReaderContentState.Closed, session.state.value)
    }

    @Test fun invalidInputsDoNotCancelTheCurrentLoad() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        val session = TextReaderContentSession(scope) { gate.await() }
        val key = session.open()
        assertThrows(IllegalArgumentException::class.java) { session.open(chapter = -1) }
        assertThrows(IllegalArgumentException::class.java) { session.open(position = -1) }
        assertThrows(IllegalArgumentException::class.java) { session.open(owner = owner.copy(revision = -1)) }
        gate.complete(chapter())
        assertEquals(key, (session.state.value as TextReaderContentState.Ready).key)
    }

    @Test fun paragraphsCannotBeMutatedByTheProducerOrConsumer() {
        val input = mutableListOf("Original")
        val result = TextReaderChapterContent("Title", input, true)
        input[0] = "Changed"
        assertEquals(listOf("Original"), result.paragraphs)
        assertThrows(UnsupportedOperationException::class.java) { (result.paragraphs as MutableList<String>).clear() }
    }

    @Test fun preparedNavigationReplacesTheOldLoadWithoutFetchingAgain() {
        val gate = CompletableDeferred<TextReaderChapterContent>()
        var requests = 0
        val session = TextReaderContentSession(scope) { requests++; withContext(NonCancellable) { gate.await() } }
        val old = session.open()
        val nextOwner = owner.copy(revision=owner.revision+1)
        val prepared = chapter("Prepared")
        var registered: TextReaderChapterKey? = null
        val next = session.loadPrepared(nextOwner,"book","source",2,81,TextReaderProcessing(),prepared) {
            assertTrue(session.state.value is TextReaderContentState.Loading)
            registered=it
        }
        assertEquals(next,registered)
        assertEquals(1,requests)
        assertTrue(next.generation > old.generation)
        assertSame(prepared,(session.state.value as TextReaderContentState.Ready).chapter)
        gate.complete(chapter("Late old chapter"))
        assertEquals(next,(session.state.value as TextReaderContentState.Ready).key)
        assertEquals(81,(session.state.value as TextReaderContentState.Ready).requestedPosition)
    }

    @Test fun richMarkupIsKeptForTheRendererInsteadOfBeingUsedAsACharacterOffset() {
        val paragraphs = listOf("<img src='chapter.png'>", "<usehtml><b>Body</b></usehtml>", "[newpage]")
        val result = TextReaderChapterContent("Title", paragraphs, false)
        assertEquals(paragraphs, result.paragraphs)
    }
}
