package io.legado.app.ui.book.info

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookInfoFilesControllerTest {
    private val book = "book://online"
    private val text = BookInfoWebFile("https://example.test/book.txt", "book.txt")
    private val zip = BookInfoWebFile("https://example.test/book.zip", "book.zip")
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test fun supportedImportPublishesTypedCompletionAndConsumesOnlyItsOwnAcknowledgement() {
        val saved = SavedStateHandle()
        val scope = scope()
        try {
            val controller = BookInfoFilesController(saved, scope, { key, operation ->
                assertEquals(book, key); assertEquals(BookInfoFileOperation.Import(text), operation)
                BookInfoFileResult.Imported("book://local")
            }, { error("No archive") }, { error("No folder request") })
            controller.open(book, upload = true, read = true)
            controller.select(text)
            assertFalse(controller.state.value.open)
            assertFalse(controller.state.value.busy)
            val completed = controller.state.value.completion!!
            assertEquals("book://local", completed.bookUrl)
            assertTrue(completed.upload); assertTrue(completed.read)
            controller.consumeCompletion("stale")
            assertEquals(completed, controller.state.value.completion)
            controller.consumeCompletion(completed.id)
            assertNull(controller.state.value.completion)
            assertFalse(saved.keys().any { it.startsWith("fileImport.") })
        } finally { scope.cancel() }
    }

    @Test fun busyOperationCannotDuplicateOrDismissAndInterruptedProcessRequiresExplicitRetry() {
        val saved = SavedStateHandle()
        val firstScope = scope()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = BookInfoFilesController(saved, firstScope, { _, _ -> calls++; gate.await(); BookInfoFileResult.Imported("local") }, { emptyList() }, {})
        first.open(book, false, false); first.select(text)
        assertTrue(first.state.value.busy)
        first.select(text); first.dismiss()
        assertEquals(1, calls); assertTrue(first.state.value.open)
        val persisted = copy(saved)
        firstScope.cancel()
        val restoredScope = scope()
        try {
            val restored = BookInfoFilesController(persisted, restoredScope, { _, _ -> calls++; BookInfoFileResult.Imported("local") }, { emptyList() }, {})
            assertEquals(BookInfoFileError.Interrupted, restored.state.value.error)
            assertNull(restored.state.value.selected)
            restored.syncCandidates(book, listOf(text))
            assertEquals(text, restored.state.value.selected)
            assertEquals(1, calls)
            restored.retry()
            assertEquals(2, calls)
            assertEquals("local", restored.state.value.completion!!.bookUrl)
        } finally { restoredScope.cancel() }
    }

    @Test fun archiveSelectionRestoresFromUriAndFailedEntryRetriesWithoutDownloadingAgain() {
        val saved = SavedStateHandle()
        val scope = scope()
        var downloads = 0
        var imports = 0
        val operations: suspend (String, BookInfoFileOperation) -> BookInfoFileResult = { _, operation ->
            when (operation) {
                is BookInfoFileOperation.Download -> { downloads++; BookInfoFileResult.Downloaded("file:///book.zip") }
                is BookInfoFileOperation.ImportEntry -> {
                    assertEquals("nested/book.txt", operation.entry)
                    imports++
                    if (imports == 1) error("Disk unavailable")
                    BookInfoFileResult.Imported("local")
                }
                else -> error("Unexpected operation")
            }
        }
        try {
            val first = BookInfoFilesController(saved, scope, operations, { listOf("book.txt", "nested/book.txt") }, {})
            first.open(book, true, false); first.select(zip)
            assertEquals(2, first.state.value.entries.size)
            assertNull(first.state.value.completion)
            val restored = BookInfoFilesController(copy(saved), scope, operations, { uri ->
                assertEquals("file:///book.zip", uri); listOf("book.txt", "nested/book.txt")
            }, {})
            assertEquals(first.state.value.entries, restored.state.value.entries)
            restored.selectEntry("unlisted.txt")
            assertEquals(0, imports)
            restored.selectEntry("nested/book.txt")
            assertEquals(BookInfoFileError.Storage, restored.state.value.error)
            assertEquals("nested/book.txt", restored.state.value.selectedEntry)
            restored.retry()
            assertEquals(1, downloads); assertEquals(2, imports)
            assertEquals("local", restored.state.value.completion!!.bookUrl)
        } finally { scope.cancel() }
    }

    @Test fun singleEntryArchiveImportsAutomaticallyAndKeepsReadAndUploadIntent() {
        val scope = scope()
        val operations = arrayListOf<BookInfoFileOperation>()
        try {
            val controller = BookInfoFilesController(SavedStateHandle(), scope, { _, operation ->
                operations.add(operation)
                if (operation is BookInfoFileOperation.Download) BookInfoFileResult.Downloaded("archive") else BookInfoFileResult.Imported("local")
            }, { listOf("single.txt") }, {})
            controller.open(book, false, true); controller.select(zip)
            assertEquals(listOf(BookInfoFileOperation.Download(zip), BookInfoFileOperation.ImportEntry("archive", "single.txt")), operations)
            assertTrue(controller.state.value.completion!!.read)
            assertFalse(controller.state.value.completion!!.upload)
        } finally { scope.cancel() }
    }

    @Test fun unsupportedFileWaitsForExplicitOpenAndNeverImportsAsABook() {
        val scope = scope()
        var calls = 0
        try {
            val file = BookInfoWebFile("url", "unknown.bin")
            val controller = BookInfoFilesController(SavedStateHandle(), scope, { _, op ->
                calls++; assertEquals(BookInfoFileOperation.Download(file), op); BookInfoFileResult.Downloaded("file:///download.bin")
            }, { error("No archive") }, {})
            controller.open(book, true, true); controller.select(file)
            assertTrue(controller.state.value.unsupported); assertEquals(0, calls)
            controller.openUnsupported()
            assertEquals(1, calls)
            assertNull(controller.state.value.completion!!.bookUrl)
            assertEquals("file:///download.bin", controller.state.value.completion!!.uri)
        } finally { scope.cancel() }
    }

    @Test fun missingFolderKeepsTheChoiceAndCanRetryAfterThePlatformResult() {
        val scope = scope()
        var calls = 0
        var requests = 0
        try {
            val controller = BookInfoFilesController(SavedStateHandle(), scope, { _, _ ->
                if (calls++ == 0) throw BookInfoFileException(BookInfoFileError.Folder)
                BookInfoFileResult.Imported("local")
            }, { emptyList() }, { requests++ })
            controller.open(book, false, false); controller.select(text)
            assertEquals(1, requests); assertEquals(text, controller.state.value.selected)
            assertFalse(controller.state.value.busy)
            controller.retry()
            assertEquals("local", controller.state.value.completion!!.bookUrl)
        } finally { scope.cancel() }
    }

    @Test fun emptyArchiveRetainsDownloadedUriAndRetryOnlyRefreshesItsEntries() {
        val scope = scope()
        var downloads = 0
        var reads = 0
        try {
            val controller = BookInfoFilesController(SavedStateHandle(), scope, { _, _ ->
                downloads++; BookInfoFileResult.Downloaded("archive")
            }, { if (reads++ == 0) emptyList() else listOf("one.txt", "two.txt") }, {})
            controller.open(book, false, false); controller.select(zip)
            assertEquals(BookInfoFileError.EmptyArchive, controller.state.value.error)
            controller.retry()
            assertEquals(1, downloads); assertEquals(2, reads)
            assertEquals(listOf("one.txt", "two.txt"), controller.state.value.entries)
        } finally { scope.cancel() }
    }

    @Test fun largeDataUrlIsReidentifiedFromCandidatesInsteadOfBeingWrittenIntoSavedState() {
        val saved = SavedStateHandle()
        val firstScope = scope()
        val gate = CompletableDeferred<Unit>()
        val file = BookInfoWebFile("data:text/plain;base64," + "A".repeat(1_000_000), "embedded.txt")
        val first = BookInfoFilesController(saved, firstScope, { _, _ -> gate.await(); BookInfoFileResult.Imported("local") }, { emptyList() }, {})
        first.open(book, false, false); first.select(file)
        val persisted = copy(saved)
        assertTrue(persisted.keys().all { (persisted.get<Any?>(it) as? String)?.length?.let { length -> length < 1000 } != false })
        firstScope.cancel()
        val restoredScope = scope()
        try {
            val restored = BookInfoFilesController(persisted, restoredScope, { _, operation ->
                assertEquals(BookInfoFileOperation.Import(file), operation); BookInfoFileResult.Imported("local")
            }, { emptyList() }, {})
            restored.syncCandidates(book, listOf(file))
            restored.retry()
            assertEquals("local", restored.state.value.completion!!.bookUrl)
        } finally { restoredScope.cancel() }
    }

    @Test fun unacknowledgedCompletionSurvivesANewControllerWithoutRepeatingTheOperation() {
        val scope = scope()
        val saved = SavedStateHandle()
        try {
            val first = BookInfoFilesController(saved, scope, { _, _ -> BookInfoFileResult.Imported("local") }, { emptyList() }, {})
            first.open(book, true, true); first.select(text)
            val restored = BookInfoFilesController(copy(saved), scope, { _, _ -> error("Must not replay import") }, { error("No archive") }, {})
            assertEquals(first.state.value.completion, restored.state.value.completion)
            assertFalse(restored.state.value.open)
        } finally { scope.cancel() }
    }
}
