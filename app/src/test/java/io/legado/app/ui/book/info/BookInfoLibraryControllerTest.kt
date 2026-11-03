package io.legado.app.ui.book.info

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookInfoLibraryError
import io.legado.app.data.repository.BookInfoLibraryException
import io.legado.app.data.repository.BookInfoLibraryOptions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookInfoLibraryControllerTest {
    private class Fixture(val saved: SavedStateHandle = SavedStateHandle()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var options = BookInfoLibraryOptions(true, "target-A", true, false)
        var remoteExists = false
        var checks = 0
        var beforeUpload: suspend (Boolean) -> Unit = {}
        var beforeDelete: suspend () -> Unit = {}
        val uploads = arrayListOf<Boolean>()
        val deletions = arrayListOf<Triple<Boolean, Boolean, String?>>()
        val controller = BookInfoLibraryController(saved, scope,
            { options }, { _, _ -> checks++; remoteExists },
            { _, _, overwrite -> uploads.add(overwrite); beforeUpload(overwrite) },
            { _, original, remote, target -> deletions.add(Triple(original, remote, target)); beforeDelete() })
        fun close() = scope.cancel()
        fun snapshot() = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    }

    @Test fun absentRemoteUploadsConditionallyAndClearsTheFinishedManualAction() {
        val f = Fixture()
        try {
            assertTrue(f.controller.beginUpload("book"))
            assertEquals(listOf(false), f.uploads); assertEquals(1, f.checks)
            assertNull(f.controller.state.value.operation)
            assertTrue(f.saved.keys().isEmpty())
        } finally { f.close() }
    }

    @Test fun restoredUploadReconcilesTheDurableOutcomeWithoutStartingAnotherTransfer() {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        var restored: Fixture? = null
        try {
            f.beforeUpload = { gate.await() }
            f.controller.beginUpload("book", read = true, requestId = "durable-upload")
            restored = Fixture(f.snapshot())
            restored.controller.reconcileUpload("durable-upload", io.legado.app.data.entities.BookInfoUploadStatus.Running, null)
            assertEquals(BookInfoLibraryPhase.Uploading, restored.controller.state.value.phase)
            restored.controller.reconcileUpload("durable-upload", io.legado.app.data.entities.BookInfoUploadStatus.Completed, null)
            val completion = checkNotNull(restored.controller.state.value.completion)
            assertEquals("book", completion.bookUrl); assertTrue(completion.read)
            restored.controller.reconcileUpload("durable-upload", io.legado.app.data.entities.BookInfoUploadStatus.Completed, null)
            assertEquals(completion, restored.controller.state.value.completion)
            assertTrue(restored.uploads.isEmpty()); assertEquals(0, restored.checks)
        } finally { restored?.close(); f.close() }
    }

    @Test fun cancellingUploadFencesTheOldSuccessFromTheNextConfirmation() = runBlocking {
        val f = Fixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.beforeUpload = { gate.await() }
            f.controller.beginUpload("book", read = true, requestId = "old-upload")
            f.controller.uploadCancelled("old-upload")
            f.remoteExists = true
            f.controller.beginUpload("book", requestId = "next-upload")
            gate.complete(Unit)
            assertEquals("next-upload", f.controller.state.value.requestId)
            assertEquals(BookInfoLibraryPhase.ConfirmOverwrite, f.controller.state.value.phase)
            assertNull(f.controller.state.value.completion)
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun aLateFailureCannotReplaceTheNextAttemptForTheSameDurableUploadId() = runBlocking {
        val f = Fixture(); val old = CompletableDeferred<Unit>(); val next = CompletableDeferred<Unit>()
        var attempts = 0
        try {
            f.beforeUpload = {
                if (++attempts == 1) { old.await(); throw BookInfoLibraryException(BookInfoLibraryError.RemoteUpload) }
                else next.await()
            }
            f.controller.beginUpload("book", requestId = "durable-upload")
            f.controller.reconcileUpload("durable-upload", io.legado.app.data.entities.BookInfoUploadStatus.Failed, BookInfoLibraryError.Conflict)
            f.controller.confirmOverwrite()
            old.complete(Unit)
            assertEquals(BookInfoLibraryPhase.Uploading, f.controller.state.value.phase)
            assertNull(f.controller.state.value.error)
            next.complete(Unit)
            assertNull(f.controller.state.value.operation)
            assertEquals(2, attempts)
        } finally { old.complete(Unit); next.complete(Unit); f.close() }
    }

    @Test fun cancellingADurableDeletionFencesItsLateFailureFromTheNextConfirmation() = runBlocking {
        val f = Fixture()
        val barrier = CompletableDeferred<Unit>()
        try {
            f.beforeDelete = { barrier.await(); throw BookInfoLibraryException(BookInfoLibraryError.Interrupted) }
            f.controller.beginDelete("book")
            f.controller.confirmDelete()
            val oldId = checkNotNull(f.controller.state.value.requestId)
            f.controller.deletionCancelled("unrelated")
            assertEquals(BookInfoLibraryPhase.Removing, f.controller.state.value.phase)
            f.controller.deletionCancelled(oldId)
            assertNull(f.controller.state.value.operation)
            f.controller.beginDelete("book")
            val nextId = f.controller.state.value.requestId
            assertNotEquals(oldId, nextId)
            barrier.complete(Unit)
            assertEquals(nextId, f.controller.state.value.requestId)
            assertEquals(BookInfoLibraryPhase.ConfirmDelete, f.controller.state.value.phase)
            assertNull(f.controller.state.value.error)
        } finally { barrier.complete(Unit); f.close() }
    }

    @Test fun existingRemoteRequiresExplicitApprovalAndRestoresWithoutReplayingTheCheck() {
        val f = Fixture()
        f.remoteExists = true
        f.controller.beginUpload("book", read = true)
        assertEquals(BookInfoLibraryPhase.ConfirmOverwrite, f.controller.state.value.phase)
        assertTrue(f.uploads.isEmpty())
        val restored = Fixture(f.snapshot())
        try {
            assertEquals(0, restored.checks)
            restored.controller.confirmOverwrite()
            assertEquals(listOf(true), restored.uploads)
            assertTrue(restored.controller.state.value.completion!!.read)
        } finally { f.close(); restored.close() }
    }

    @Test fun decliningOverwriteKeepsTheReadIntentWithoutUploading() {
        val f = Fixture()
        try {
            f.remoteExists = true
            f.controller.beginUpload("book", read = true)
            f.controller.dismiss()
            assertTrue(f.uploads.isEmpty())
            assertTrue(f.controller.state.value.completion!!.read)
            assertFalse(f.controller.state.value.completion!!.deleted)
        } finally { f.close() }
    }

    @Test fun aConditionalWriteConflictReturnsToApprovalInsteadOfOverwriting() {
        val f = Fixture()
        try {
            f.beforeUpload = { if (!it) throw BookInfoLibraryException(BookInfoLibraryError.Conflict) }
            f.controller.beginUpload("book")
            assertEquals(BookInfoLibraryPhase.ConfirmOverwrite, f.controller.state.value.phase)
            assertEquals(listOf(false), f.uploads)
            f.controller.confirmOverwrite()
            assertEquals(listOf(false, true), f.uploads)
            assertNull(f.controller.state.value.operation)
        } finally { f.close() }
    }

    @Test fun failedUploadRetainsReadIntentAndRetryChecksTheRemoteAgain() {
        val f = Fixture()
        try {
            f.beforeUpload = { error("Network unavailable") }
            f.controller.beginUpload("book", read = true)
            assertEquals(BookInfoLibraryPhase.Error, f.controller.state.value.phase)
            f.remoteExists = true
            f.controller.retry()
            assertEquals(2, f.checks)
            assertEquals(BookInfoLibraryPhase.ConfirmOverwrite, f.controller.state.value.phase)
            assertEquals(listOf(false), f.uploads)
            f.controller.dismiss()
            assertTrue(f.controller.state.value.completion!!.read)
        } finally { f.close() }
    }

    @Test fun interruptedUploadAndRepeatedHandoffNeverAutomaticallyRepeatTheTransfer() {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.beforeUpload = { gate.await() }
        f.controller.beginUpload("book", true, "file-completion")
        assertTrue(f.controller.beginUpload("book", true, "file-completion"))
        assertFalse(f.controller.beginUpload("other", false, "other-completion"))
        assertEquals(listOf(false), f.uploads)
        f.controller.dismiss(); assertTrue(f.controller.state.value.busy)
        val restored = Fixture(f.snapshot())
        f.close()
        try {
            assertEquals(BookInfoLibraryError.Interrupted, restored.controller.state.value.error)
            assertTrue(restored.uploads.isEmpty())
            restored.remoteExists = true
            restored.controller.retry()
            assertEquals(BookInfoLibraryPhase.ConfirmOverwrite, restored.controller.state.value.phase)
            assertTrue(restored.uploads.isEmpty())
        } finally { restored.close() }
    }

    @Test fun deletionChoicesAndCompletionRestoreWithOnlyPrimitiveKeys() {
        val f = Fixture()
        f.controller.beginDelete("book")
        f.controller.setDeleteOriginal(true); f.controller.setDeleteRemote(true)
        val restored = Fixture(f.snapshot())
        try {
            assertTrue(restored.controller.state.value.deleteOriginal)
            assertTrue(restored.controller.state.value.deleteRemote)
            restored.controller.confirmDelete()
            assertEquals(listOf(Triple(true, true, "target-A")), restored.deletions)
            val completion = restored.controller.state.value.completion!!
            assertTrue(completion.deleted)
            val done = Fixture(restored.snapshot())
            try {
                assertEquals(completion, done.controller.state.value.completion)
                done.controller.consumeCompletion("stale")
                assertEquals(completion, done.controller.state.value.completion)
                done.controller.consumeCompletion(completion.id)
                assertTrue(done.saved.keys().isEmpty())
            } finally { done.close() }
        } finally { f.close(); restored.close() }
    }

    @Test fun failedRemoteDeletionKeepsTheChoicesAndRequiresConfirmationBeforeRetry() {
        val f = Fixture()
        try {
            f.beforeDelete = { throw BookInfoLibraryException(BookInfoLibraryError.RemoteDelete) }
            f.controller.beginDelete("book"); f.controller.setDeleteRemote(true); f.controller.confirmDelete()
            assertEquals(BookInfoLibraryError.RemoteDelete, f.controller.state.value.error)
            f.controller.retry()
            assertEquals(BookInfoLibraryPhase.ConfirmDelete, f.controller.state.value.phase)
            assertEquals(1, f.deletions.size)
            f.beforeDelete = {}
            f.controller.setDeleteRemote(false); f.controller.confirmDelete()
            assertEquals(Triple(false, false, "target-A"), f.deletions.last())
            assertTrue(f.controller.state.value.completion!!.deleted)
        } finally { f.close() }
    }

    @Test fun disabledDeleteConfirmationUsesTheSavedLocalPreference() {
        val f = Fixture()
        try {
            f.options = f.options.copy(confirmDelete = false, deleteOriginal = true)
            f.controller.beginDelete("book")
            assertEquals(listOf(Triple(true, false, "target-A")), f.deletions)
            assertTrue(f.controller.state.value.completion!!.deleted)
        } finally { f.close() }
    }

    @Test fun missingConfigurationIsRecoverableAndReadingCanContinue() {
        val f = Fixture()
        try {
            f.options = f.options.copy(target = null)
            f.controller.beginUpload("book", read = true)
            assertEquals(BookInfoLibraryError.NotConfigured, f.controller.state.value.error)
            assertTrue(f.uploads.isEmpty())
            f.controller.dismiss()
            assertTrue(f.controller.state.value.completion!!.read)
        } finally { f.close() }
    }

    @Test fun retryCannotSilentlySwitchToAnotherWebDavAccount() {
        val f = Fixture()
        try {
            f.beforeUpload = { error("Disconnected") }
            f.controller.beginUpload("book")
            f.options = f.options.copy(target = "target-B")
            f.controller.retry()
            assertEquals(BookInfoLibraryError.Changed, f.controller.state.value.error)
            assertEquals(listOf(false), f.uploads)
        } finally { f.close() }
    }
}
