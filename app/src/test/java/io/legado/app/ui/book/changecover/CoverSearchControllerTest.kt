package io.legado.app.ui.book.changecover

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoverSearchControllerTest {
    private class Data : CoverSearchData {
        var cached = emptyList<BookCoverCandidate>()
        var rule: BookCoverCandidate? = null
        var keys = listOf("A", "B", "C")
        var keysBlock: (suspend () -> List<String>)? = null
        var ruleCalls = 0
        val calls = arrayListOf<String>()
        var ruleBlock: (suspend (String) -> BookCoverCandidate?)? = null
        var sourceBlock: (suspend (String) -> BookCoverCandidate?)? = null
        override suspend fun cached(name: String, author: String) = cached
        override suspend fun sourceKeys() = keysBlock?.invoke() ?: keys
        override suspend fun rule(name: String, author: String): BookCoverCandidate? { ruleCalls++; return ruleBlock?.invoke(name) ?: rule }
        override suspend fun search(key: String, name: String, author: String): BookCoverCandidate? { calls.add(key); return sourceBlock?.invoke(key) ?: result(key) }
        override fun parallelism() = 2
    }
    private companion object {
        fun result(id: String, order: Int = 0) = BookCoverCandidate(id, "https://images.test/$id.png", "source-$id", id, order)
        fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    }

    @Test fun cachedResultsShowImmediatelyAndDoNotLaunchNetworkSearchWhenThereAreSeveral() {
        val data = Data().apply { cached = listOf(result("one"), result("two")) }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("book", " Title ", "作者：Author")
            assertEquals("Title", controller.state.value.name); assertEquals("Author", controller.state.value.author)
            assertEquals(3, controller.state.value.covers.size)
            assertEquals(CoverSearchPhase.Ready, controller.state.value.phase)
            assertEquals(0, data.ruleCalls); assertTrue(data.calls.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun ruleResultPausesBeforeSourceSearchAndResumeSearchesTheEnabledSources() {
        val data = Data().apply { rule = result("rule", -1).copy(kind = CoverCandidateKind.Rule) }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("book", "Title", "Author")
            assertEquals(CoverSearchPhase.RuleReady, controller.state.value.phase)
            assertTrue(data.calls.isEmpty())
            controller.toggleSearch()
            assertEquals(CoverSearchPhase.Complete, controller.state.value.phase)
            assertEquals(listOf("A", "B", "C"), data.calls)
            assertEquals("default", controller.state.value.covers.first().id)
            assertEquals(5, controller.state.value.covers.size)
        } finally { scope.cancel() }
    }

    @Test fun boundedSearchCanStopAndResumeItsPendingSources() {
        val gate = CompletableDeferred<Unit>()
        var active = 0; var maximum = 0; var hold = true
        val data = Data().apply { sourceBlock = { key ->
            active++; maximum = maxOf(maximum, active)
            try { if (hold) gate.await(); result(key) } finally { active-- }
        } }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("book", "Title", "Author")
            assertEquals(2, maximum); assertEquals(2, data.calls.size)
            controller.toggleSearch()
            assertEquals(CoverSearchPhase.Paused, controller.state.value.phase)
            assertEquals(0, active)
            hold = false
            controller.toggleSearch()
            assertEquals(CoverSearchPhase.Complete, controller.state.value.phase)
            assertEquals(2, data.calls.count { it == "A" }); assertEquals(2, data.calls.count { it == "B" }); assertEquals(1, data.calls.count { it == "C" })
            assertEquals(4, controller.state.value.covers.size)
        } finally { scope.cancel() }
    }

    @Test fun aLateRuleFromAnOldTargetCannotReplaceTheNewTarget() {
        val gate = CompletableDeferred<Unit>()
        val data = Data().apply { ruleBlock = { name ->
            if (name == "Old") withContext(NonCancellable) { gate.await(); result("old") }
            else result("new").copy(kind = CoverCandidateKind.Rule)
        } }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("old-book", "Old", "Author")
            controller.open("new-book", "New", "Author")
            gate.complete(Unit)
            assertEquals("new-book", controller.state.value.target)
            assertFalse(controller.state.value.covers.any { it.id == "old" })
            assertTrue(controller.state.value.covers.any { it.id == "new" })
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun oneFailedSourceDoesNotCancelTheOtherResults() {
        val data = Data().apply { sourceBlock = { key -> if (key == "B") error("Unavailable") else result(key) } }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("book", "Title", "Author")
            assertEquals(CoverSearchPhase.Complete, controller.state.value.phase)
            assertEquals(1, controller.state.value.failures)
            assertEquals(setOf("default", "A", "C"), controller.state.value.covers.map { it.id }.toSet())
        } finally { scope.cancel() }
    }

    @Test fun selectionFailureKeepsTheDialogAndCanRetryWithoutChangingTheTarget() {
        val data = Data().apply { cached = listOf(result("one"), result("two")) }
        var attempts = 0
        var selected: BookCoverSelection? = null
        val scope = scope()
        try {
            val saved = SavedStateHandle()
            val controller = CoverSearchController(saved, scope, data, { target, value ->
                assertEquals("book", target)
                if (attempts++ == 0) error("Write failed") else selected = value
            })
            controller.open("book", "Title", "Author"); controller.select("one")
            assertTrue(controller.state.value.open); assertNotNull(controller.state.value.error)
            controller.select("one")
            assertFalse(controller.state.value.open)
            assertEquals(BookCoverSelection("https://images.test/one.png", "source-one"), selected)
            assertFalse(saved.keys().any { it.startsWith("coverSearch.") })
        } finally { scope.cancel() }
    }

    @Test fun busySelectionCannotBeDismissedOrSubmittedTwice() {
        val data = Data().apply { cached = listOf(result("one"), result("two")) }
        val gate = CompletableDeferred<Unit>(); var writes = 0
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> writes++; gate.await() })
            controller.open("book", "Title", "Author"); controller.select("one")
            controller.dismiss(); controller.select("two")
            assertTrue(controller.state.value.saving); assertTrue(controller.state.value.open); assertEquals(1, writes)
            gate.complete(Unit)
            assertFalse(controller.state.value.open)
        } finally { scope.cancel() }
    }

    @Test fun newControllerRestoresKeysAndRebuildsCachedResultsWithoutReplayingTheSearch() {
        val data = Data().apply { cached = listOf(result("one"), result("two")) }
        val saved = SavedStateHandle(); val firstScope = scope()
        val first = CoverSearchController(saved, firstScope, data, { _, _ -> })
        first.open("book", "Title", "Author")
        val restored = copy(saved); firstScope.cancel()
        val scope = scope()
        try {
            val controller = CoverSearchController(restored, scope, data, { _, _ -> })
            assertTrue(controller.state.value.open); assertEquals("book", controller.state.value.target)
            assertEquals(CoverSearchPhase.Paused, controller.state.value.phase)
            assertEquals(3, controller.state.value.covers.size); assertTrue(data.calls.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun refreshRechecksTheRuleAndPreservesStableCandidateIdentities() {
        val data = Data().apply { cached = listOf(result("A"), result("B")) }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("book", "Title", "Author"); controller.refresh()
            assertEquals(1, data.ruleCalls)
            assertEquals(setOf("default", "A", "B", "C"), controller.state.value.covers.map { it.id }.toSet())
        } finally { scope.cancel() }
    }

    @Test fun lateSourceKeysCannotReplaceThePendingListForAnotherBook() {
        val gate = CompletableDeferred<Unit>()
        var keyCalls = 0
        val data = Data().apply {
            keysBlock = { if (++keyCalls == 1) withContext(NonCancellable) { gate.await(); listOf("old") } else listOf("new") }
            ruleBlock = { name -> if (name == "New") result("rule").copy(kind = CoverCandidateKind.Rule) else null }
        }
        val scope = scope()
        try {
            val controller = CoverSearchController(SavedStateHandle(), scope, data, { _, _ -> })
            controller.open("old", "Old", "Author")
            controller.open("new", "New", "Author")
            gate.complete(Unit)
            controller.toggleSearch()
            assertEquals(listOf("new"), data.calls)
            assertEquals("new", controller.state.value.target)
        } finally { scope.cancel() }
    }

    @Test fun largeImageUrlsRemainOutsideTheInstanceStatePayload() {
        val huge = result("one").copy(url = "data:image/png;base64," + "A".repeat(1_000_000))
        val data = Data().apply { cached = listOf(huge, result("two")) }
        val saved = SavedStateHandle(); val scope = scope()
        try {
            val controller = CoverSearchController(saved, scope, data, { _, _ -> })
            controller.open("book", "Title", "Author")
            assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { n -> n < 1000 } != false })
        } finally { scope.cancel() }
    }
}
