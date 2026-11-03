package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoPreviewCommitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val previews = BookInfoPreviewStore(context)
    private val key = UUID.randomUUID().toString()
    private val requestId = UUID.randomUUID().toString()
    private lateinit var database: AppDatabase
    private lateinit var repository: BookInfoNavigationRepository
    private lateinit var lease: BookInfoPreviewLease
    private lateinit var parent: BookInfoRequest
    private val original = Book(bookUrl = "preview-original://$requestId", name = "Same name $requestId", author = "Author $requestId", type = BookType.text or BookType.notShelf)
    private val candidate = original.copy(bookUrl = "preview-changed://$requestId", origin = "changed-source://$requestId")
    private val preview = BookInfoPreview(candidate, listOf(BookChapter(bookUrl = candidate.bookUrl,
        url = "chapter", title = "Changed chapter")), candidate.origin, false, infoHtml = "cached info", tocHtml = "cached toc")
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
        repository = BookInfoNavigationRepository(database)
        val initial = previews.open(key)
        check(previews.save(initial, 1, BookInfoPreview(original, emptyList(), null, false)))
        lease = previews.open(key)
        parent = checkNotNull(repository.claimPreview(requestId, original.bookUrl, lease, previews, "owner"))
    }
    @After fun cleanup() {
        previews.discard(previews.open(key))
        database.close()
    }
    private fun assertLibraryUnchanged(expected: Book) {
        val stored = checkNotNull(database.bookDao.getBook(expected.bookUrl))
        assertEquals(expected.name, stored.name); assertEquals(expected.author, stored.author)
        assertEquals(expected.origin, stored.origin); assertEquals(expected.type, stored.type)
        assertEquals(expected.durChapterIndex, stored.durChapterIndex); assertEquals(expected.durChapterPos, stored.durChapterPos)
    }
    @Test fun anUnshelvedReplacementPreservesAnotherLibraryAndReaderIdentity() = runBlocking {
        val library = original.copy(bookUrl = "library://$requestId", type = BookType.text)
        database.bookDao.insert(library)
        database.readerSessionDao.claim(ReaderSessionState("reader", library.bookUrl, library.bookUrl, true, false, updatedAt = 1), "reader-owner")
        val updated = repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        assertEquals(candidate.bookUrl, updated.bookUrl)
        assertEquals(candidate.bookUrl, previews.peek(key)!!.book.bookUrl)
        assertFalse(database.bookDao.has(candidate.bookUrl))
        assertLibraryUnchanged(library)
        assertTrue(database.readerSessionDao.ownsBook("reader", "reader-owner", 0, library.bookUrl))
    }
    @Test fun anUncommittedStageKeepsTheOldPreviewAfterANewOwnerOpensIt() = runBlocking {
        assertTrue(previews.stage(lease, 1, requestId, 1, preview))
        val newer = previews.open(key)
        val claimed = checkNotNull(repository.claimPreview(requestId, original.bookUrl, newer, previews, "new"))
        val recovered = previews.resolve(newer, requestId, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision)
        assertEquals(original.bookUrl, recovered!!.book.bookUrl)
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
        assertEquals(original.bookUrl, claimed.bookUrl)
    }
    @Test fun aCommittedCanonicalKeyRecoversTheStagedPayloadAfterReopening() = runBlocking {
        assertTrue(previews.stage(lease, 1, requestId, 1, preview))
        database.bookInfoRequestDao.replacePreview(requestId, "owner", 0, original.bookUrl, candidate.bookUrl, 2) { }
        val newer = previews.open(key)
        val claimed = checkNotNull(repository.claimPreview(requestId, original.bookUrl, newer, previews, "new"))
        val recovered = checkNotNull(previews.resolve(newer, requestId, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision))
        assertEquals(candidate.bookUrl, recovered.book.bookUrl)
        assertEquals("cached info", recovered.infoHtml)
        assertEquals("Changed chapter", recovered.chapters.single().title)
        assertEquals(candidate.bookUrl, previews.peek(key)!!.book.bookUrl)
    }
    @Test fun aFailedDatabaseReplacementDoesNotPromoteTheStage() {
        assertTrue(previews.stage(lease, 1, requestId, 1, preview))
        assertThrows(IllegalStateException::class.java) {
            database.bookInfoRequestDao.replacePreview(requestId, "owner", 0, original.bookUrl, candidate.bookUrl, 2) {
                throw IllegalStateException("fixture failure")
            }
        }
        assertEquals(original.bookUrl, previews.resolve(lease, requestId, 0, original.bookUrl)!!.book.bookUrl)
        assertEquals(0L, database.bookInfoRequestDao.get(requestId)!!.revision)
    }
    @Test fun anExpiredLeaseCannotStageOrMoveTheRequest() = runBlocking {
        previews.open(key)
        try {
            repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
            fail("Expired preview was accepted")
        } catch (_: IllegalStateException) { }
        assertEquals(original.bookUrl, database.bookInfoRequestDao.get(requestId)!!.bookUrl)
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
    }
    @Test fun aRevokedDatabaseOwnerCannotStageItsSnapshot() = runBlocking {
        database.bookInfoRequestDao.claim(parent, "new-owner")
        try {
            repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
            fail("Revoked owner was accepted")
        } catch (_: IllegalStateException) { }
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
        assertEquals("new-owner", database.bookInfoRequestDao.get(requestId)!!.ownerToken)
    }
    @Test fun preparingAChildPreservesCurrentMembershipProgressAndTheExistingReader() = runBlocking {
        val library = original.copy(type = BookType.text, durChapterIndex = 4, durChapterPos = 7)
        database.bookDao.insert(library)
        database.readerSessionDao.claim(ReaderSessionState("reader", library.bookUrl, library.bookUrl, true, false, updatedAt = 1), "reader-owner")
        val child = repository.prepareAndBegin(parent.navigationOwner(), BookInfoChildKind.Catalog, original, emptyList())
        assertNotNull(child)
        val saved = checkNotNull(database.bookDao.getBook(original.bookUrl))
        assertEquals(0, saved.type and BookType.notShelf)
        assertEquals(4, saved.durChapterIndex); assertEquals(7, saved.durChapterPos)
        assertTrue(database.readerSessionDao.ownsBook("reader", "reader-owner", 0, original.bookUrl))
    }
    @Test fun preparingACollidingPreviewCannotReplaceAnUnrelatedLibraryBook() = runBlocking {
        val library = original.copy(bookUrl = "library://$requestId", type = BookType.text)
        database.bookDao.insert(library)
        try {
            repository.prepareAndBegin(parent.navigationOwner(), BookInfoChildKind.Reader, original, emptyList())
            fail("Colliding library identity was replaced")
        } catch (_: IllegalStateException) { }
        assertLibraryUnchanged(library)
        assertFalse(database.bookDao.has(original.bookUrl))
        assertEquals(0L, database.bookInfoRequestDao.get(requestId)!!.revision)
        assertNull(database.bookInfoRequestDao.activeChild(requestId))
    }

    @Test fun anAbandonedEditorCannotPersistItsLateDraft() = runBlocking {
        database.bookDao.insert(original)
        val dao = database.bookInfoRequestDao
        val child = checkNotNull(dao.begin(requestId, "owner", 0, original.bookUrl, "editor", BookInfoChildKind.Edit, null, 2))
        val editor = checkNotNull(dao.claimChild(child.id, original.bookUrl, child.kind, "editor-owner", 3))
        assertTrue(dao.abandonDisplayed(requestId, "owner", 1, original.bookUrl, child.id, 4))
        try {
            BookInfoEditRepository(database).saveDraft(original.bookUrl, BookInfoEditDraft(bookName = "Late name"),
                setOf(BookInfoEditField.Name), editor)
            fail("Abandoned editor persisted its draft")
        } catch (_: IllegalStateException) { }
        assertEquals(original.name, database.bookDao.getBook(original.bookUrl)!!.name)
        assertEquals(BookInfoChildPhase.Cancelled, dao.child(child.id)!!.phase)
    }

    @Test fun anOwnedEditorSavesItsDraftAndReturnInOneTransaction() = runBlocking {
        database.bookDao.insert(original)
        val dao = database.bookInfoRequestDao
        val child = checkNotNull(dao.begin(requestId, "owner", 0, original.bookUrl, "editor", BookInfoChildKind.Edit, null, 2))
        val editor = checkNotNull(dao.claimChild(child.id, original.bookUrl, child.kind, "editor-owner", 3))
        BookInfoEditRepository(database).saveDraft(original.bookUrl, BookInfoEditDraft(bookName = "Saved name"),
            setOf(BookInfoEditField.Name), editor)
        assertEquals("Saved name", database.bookDao.getBook(original.bookUrl)!!.name)
        assertEquals(BookInfoChildPhase.Returned, dao.child(child.id)!!.phase)
        assertFalse(dao.abandonDisplayed(requestId, "owner", 1, original.bookUrl, child.id, 5))
    }

    @Test fun aSourceChangedPreviewCanReplaceItsOwnUnusedTemporaryRowWhenReading() = runBlocking {
        database.bookDao.insert(original)
        val updated = repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        val child = repository.prepareAndBegin(updated.navigationOwner(), BookInfoChildKind.Reader, candidate, preview.chapters)
        assertEquals(candidate.bookUrl, child!!.bookUrl)
        assertFalse(database.bookDao.has(original.bookUrl))
        assertEquals(candidate.name, database.bookDao.getBook(candidate.bookUrl)!!.name)
        assertTrue(database.bookDao.getBook(candidate.bookUrl)!!.type and BookType.notShelf != 0)
    }

    @Test fun aSourceChangedPreviewCannotRetireAnotherReadersTemporaryRow() = runBlocking {
        database.bookDao.insert(original)
        database.readerSessionDao.claim(ReaderSessionState("reader", original.bookUrl, original.bookUrl, false, false, updatedAt = 1), "reader-owner")
        val updated = repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        try {
            repository.prepareAndBegin(updated.navigationOwner(), BookInfoChildKind.Reader, candidate, preview.chapters)
            fail("Another reader lost its temporary book")
        } catch (_: IllegalStateException) { }
        assertTrue(database.readerSessionDao.ownsBook("reader", "reader-owner", 0, original.bookUrl))
        assertNotNull(database.bookDao.getBook(original.bookUrl))
        assertFalse(database.bookDao.has(candidate.bookUrl))
    }
    private suspend fun assertWritesRejected(owner: BookInfoRequestOwner, key: String = owner.bookUrl) {
        val books = BookInfoRepository(database)
        val snapshot = original.copy(bookUrl = key)
        val writes: List<suspend () -> Unit> = listOf(
            { books.pin(key, owner) },
            { books.applyEdit(key, BookInfoEdit.Cover("late-cover"), owner) },
            { books.saveSnapshot(snapshot.copy(), false, owner = owner) },
            { books.addToBookshelf(snapshot.copy(), emptyList(), owner) },
            { books.assignGroups(snapshot.copy(group = 1), emptyList(), true, entryOwner = owner) },
        )
        writes.forEachIndexed { index, write ->
            try { write(); fail("Expired detail write $index was accepted") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun aRevokedDetailOwnerCannotPinEditSaveAddOrAssignGroups() = runBlocking {
        database.bookDao.insert(original)
        repository.claimPreview(requestId, original.bookUrl, lease, previews, "replacement")
        assertWritesRejected(parent.navigationOwner())
        assertLibraryUnchanged(original)
        assertNull(database.bookDao.getBook(original.bookUrl)!!.customCoverUrl)
    }

    @Test fun aParentCannotWriteWhileItsChildOwnsTheBook() = runBlocking {
        database.bookDao.insert(original)
        val child = checkNotNull(repository.begin(parent.navigationOwner(), BookInfoChildKind.Edit))
        val current = checkNotNull(repository.get(requestId))
        assertWritesRejected(current.navigationOwner())
        assertLibraryUnchanged(original)
        assertEquals(child.id, database.bookInfoRequestDao.activeChild(requestId)!!.id)
    }

    @Test fun anOldRevisionCannotWriteAfterTheCanonicalKeyChanges() = runBlocking {
        database.bookDao.insert(original)
        repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        assertWritesRejected(parent.navigationOwner())
        assertLibraryUnchanged(original)
        assertEquals(candidate.bookUrl, repository.get(requestId)!!.bookUrl)
    }

    @Test fun aCurrentOwnerCannotWriteToAnotherBookKey() = runBlocking {
        database.bookDao.insert(original)
        val other = original.copy(bookUrl = "other://$requestId", name = "Other $requestId")
        database.bookDao.insert(other)
        assertWritesRejected(parent.navigationOwner(), other.bookUrl)
        assertLibraryUnchanged(other)
        assertNull(database.bookDao.getBook(other.bookUrl)!!.customCoverUrl)
    }

    @Test fun ownedMetadataWritesPreserveCurrentProgressAndOtherMetadata() = runBlocking {
        val stored = original.copy(type = BookType.text, durChapterIndex = 5, durChapterPos = 8,
            durVolumeIndex = 2, chapterInVolumeIndex = 3, durChapterTime = 99, customCoverUrl = "custom")
        database.bookDao.insert(stored)
        val books = BookInfoRepository(database)
        books.applyEdit(stored.bookUrl, BookInfoEdit.CanUpdate(false), parent.navigationOwner())
        val saved = books.pin(stored.bookUrl, parent.navigationOwner())
        assertEquals(5, saved.durChapterIndex); assertEquals(8, saved.durChapterPos)
        assertEquals(2, saved.durVolumeIndex); assertEquals(3, saved.chapterInVolumeIndex)
        assertEquals("custom", saved.customCoverUrl); assertFalse(saved.canUpdate)
        assertTrue(saved.order < stored.order)
    }

    @Test fun addingAnOwnedPreviewCannotReplaceAnotherLibraryIdentity() = runBlocking {
        val library = original.copy(bookUrl = "library://$requestId", type = BookType.text)
        database.bookDao.insert(library)
        try {
            BookInfoRepository(database).addToBookshelf(original.copy(), emptyList(), parent.navigationOwner())
            fail("Adding the preview replaced another library identity")
        } catch (_: IllegalStateException) { }
        assertLibraryUnchanged(library)
        assertFalse(database.bookDao.has(original.bookUrl))
    }

    @Test fun ownedGroupAssignmentKeepsMembershipThatChangedAfterTheScreenSnapshot() = runBlocking {
        val stored = original.copy(type = BookType.text, durChapterIndex = 6, durChapterPos = 9)
        database.bookDao.insert(stored)
        val group = BookGroup(groupId = 1, groupName = "Owned group")
        database.bookGroupDao.insert(group)
        val assigned = BookInfoRepository(database).assignGroups(original.copy(group = 1), emptyList(),
            false, mapOf(1L to checkNotNull(group.generation)), parent.navigationOwner())
        assertTrue(assigned.inBookshelf)
        val saved = checkNotNull(database.bookDao.getBook(original.bookUrl))
        assertEquals(1L, saved.group)
        assertEquals(0, saved.type and BookType.notShelf)
        assertEquals(6, saved.durChapterIndex); assertEquals(9, saved.durChapterPos)
    }

    @Test fun addingTheOwnedTemporaryBookPreservesAllReadingCoordinates() = runBlocking {
        val stored = original.copy(durChapterIndex = 7, durChapterPos = 11, durVolumeIndex = 3,
            chapterInVolumeIndex = 4, durChapterTime = 123)
        database.bookDao.insert(stored)
        BookInfoRepository(database).addToBookshelf(original.copy(), emptyList(), parent.navigationOwner())
        val saved = checkNotNull(database.bookDao.getBook(original.bookUrl))
        assertEquals(0, saved.type and BookType.notShelf)
        assertEquals(7, saved.durChapterIndex); assertEquals(11, saved.durChapterPos)
        assertEquals(3, saved.durVolumeIndex); assertEquals(4, saved.chapterInVolumeIndex)
        assertEquals(123L, saved.durChapterTime)
    }

    @Test fun refreshingAnUnshelvedPreviewDoesNotAdoptAnUnrelatedLibraryNameMatch() = runBlocking {
        val library = original.copy(bookUrl = "library://$requestId", type = BookType.text)
        database.bookDao.insert(library)
        val saved = repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        assertEquals(candidate.bookUrl, saved.book.bookUrl)
        assertFalse(saved.inBookshelf)
        assertFalse(database.bookDao.has(candidate.bookUrl))
        assertLibraryUnchanged(library)
        assertEquals(candidate.bookUrl, repository.get(requestId)!!.bookUrl)
        assertEquals("cached info", previews.peek(key)!!.infoHtml)
    }

    @Test fun refreshingALibraryBookKeepsCurrentUserMetadataAndReadingProgress() = runBlocking {
        val stored = original.copy(type = BookType.text or BookType.updateError, customCoverUrl = "current cover", customIntro = "current intro",
            canUpdate = false, group = 2, durChapterIndex = 6, durChapterPos = 9, durVolumeIndex = 3,
            chapterInVolumeIndex = 2, durChapterTime = 100)
        database.bookDao.insert(stored)
        val saved = repository.refreshPreview(parent.navigationOwner(), lease, previews, 1,
            preview.copy(inBookshelf = true), original)
        val persisted = checkNotNull(database.bookDao.getBook(candidate.bookUrl))
        assertTrue(saved.inBookshelf)
        assertEquals(0, persisted.type and BookType.updateError)
        assertFalse(database.bookDao.has(original.bookUrl))
        assertEquals("current cover", persisted.customCoverUrl); assertEquals("current intro", persisted.customIntro)
        assertFalse(persisted.canUpdate); assertEquals(2L, persisted.group)
        assertEquals(6, persisted.durChapterIndex); assertEquals(9, persisted.durChapterPos)
        assertEquals(3, persisted.durVolumeIndex); assertEquals(2, persisted.chapterInVolumeIndex)
        assertEquals(100L, persisted.durChapterTime)
        assertEquals("Changed chapter", database.bookChapterDao.getChapterList(candidate.bookUrl).single().title)
        assertEquals("cached info", saved.book.infoHtml)
        assertEquals("cached toc", saved.book.tocHtml)
        assertEquals(6, previews.peek(key)!!.book.durChapterIndex)
    }

    @Test fun aRemovedLibraryBookCannotBeResurrectedByARefreshResponse() = runBlocking {
        try {
            repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview.copy(inBookshelf = true), original)
            fail("Refresh resurrected a removed library book")
        } catch (_: IllegalStateException) { }
        assertFalse(database.bookDao.has(candidate.bookUrl))
        assertEquals(original.bookUrl, repository.get(requestId)!!.bookUrl)
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
    }

    @Test fun metadataOnlyRefreshPreservesTheExistingChapterList() = runBlocking {
        database.bookDao.insert(original.copy(type = BookType.text))
        val chapter = BookChapter(bookUrl = original.bookUrl, url = "original chapter", title = "Preserved chapter")
        database.bookChapterDao.insert(chapter)
        val saved = repository.refreshPreview(parent.navigationOwner(), lease, previews, 1,
            BookInfoPreview(original.copy(name = "Refreshed name"), emptyList(), null, true), original, false)
        assertEquals("Preserved chapter", database.bookChapterDao.getChapterList(original.bookUrl).single().title)
        assertEquals("Preserved chapter", saved.chapters.single().title)
        assertEquals("Preserved chapter", previews.peek(key)!!.chapters.single().title)
        assertEquals("Refreshed name", database.bookDao.getBook(original.bookUrl)!!.name)
    }

    @Test fun metadataOnlyRedirectMovesItsChapterKeysWithTheCanonicalBookKey() = runBlocking {
        database.bookDao.insert(original.copy(type = BookType.text))
        database.bookChapterDao.insert(BookChapter(bookUrl = original.bookUrl, url = "preserved", title = "Preserved"))
        val saved = repository.refreshPreview(parent.navigationOwner(), lease, previews, 1,
            preview.copy(inBookshelf = true, chapters = emptyList()), original, false)
        assertEquals(candidate.bookUrl, saved.chapters.single().bookUrl)
        assertEquals("Preserved", database.bookChapterDao.getChapterList(candidate.bookUrl).single().title)
        assertEquals(candidate.bookUrl, repository.get(requestId)!!.bookUrl)
    }

    @Test fun aFailedPreviewStageRollsBackLibraryAndChapterRefreshTogether() = runBlocking {
        val stored = original.copy(type = BookType.text)
        database.bookDao.insert(stored)
        database.bookChapterDao.insert(BookChapter(bookUrl = original.bookUrl, url = "original", title = "Original"))
        check(previews.save(lease, 2, BookInfoPreview(original, emptyList(), null, true)))
        try {
            repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview.copy(inBookshelf = true), original)
            fail("Refresh committed despite a rejected preview stage")
        } catch (_: IllegalStateException) { }
        assertLibraryUnchanged(stored)
        assertFalse(database.bookDao.has(candidate.bookUrl))
        assertEquals("Original", database.bookChapterDao.getChapterList(original.bookUrl).single().title)
        assertEquals(0L, repository.get(requestId)!!.revision)
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
    }

    @Test fun aRefreshCannotReplaceAnotherCanonicalBookUrl() = runBlocking {
        val other = candidate.copy(name = "Other identity $requestId", type = BookType.text)
        database.bookDao.insert(other)
        try {
            repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview, original)
            fail("Refresh replaced another canonical identity")
        } catch (_: IllegalStateException) { }
        assertLibraryUnchanged(other)
        assertEquals(0L, repository.get(requestId)!!.revision)
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
    }

    @Test fun aRefreshCannotWriteAfterItsOwnerHasBeenRevoked() = runBlocking {
        database.bookDao.insert(original)
        repository.claimPreview(requestId, original.bookUrl, lease, previews, "new owner")
        try {
            repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview, original)
            fail("Revoked refresh committed")
        } catch (_: IllegalStateException) { }
        assertLibraryUnchanged(original)
        assertEquals(original.bookUrl, previews.peek(key)!!.book.bookUrl)
    }

    @Test fun refreshingAPreviewWithATemporaryRowStillKeepsItsLibraryIdentityIndependent() = runBlocking {
        database.bookDao.insert(original)
        val library = original.copy(bookUrl = "library://$requestId", name = "Other $requestId", type = BookType.text)
        database.bookDao.insert(library)
        val changed = preview.copy(book = candidate.copy(name = library.name))
        val saved = repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, changed, original)
        assertFalse(saved.inBookshelf)
        assertEquals(library.name, saved.book.name)
        assertLibraryUnchanged(original)
        assertLibraryUnchanged(library)
        assertFalse(database.bookDao.has(candidate.bookUrl))
    }

    @Test fun anUnshelvedRefreshKeepsTheOriginalReadersCachedImagesInPlace() = runBlocking {
        database.bookDao.insert(original)
        val imageUrl = "https://image.invalid/$requestId.png"
        val bytes = byteArrayOf(1, 2, 3, 4)
        io.legado.app.help.book.BookHelp.writeImage(original, imageUrl, bytes)
        try {
            repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview, original)
            assertArrayEquals(bytes, io.legado.app.help.book.BookHelp.getImage(original, imageUrl).readBytes())
        } finally {
            io.legado.app.help.book.BookHelp.clearCache(original)
            io.legado.app.help.book.BookHelp.clearCache(candidate)
        }
    }

    @Test fun unrelatedNavigationCannotPromoteAFailedSameUrlPreviewCommit() = runBlocking {
        database.bookDao.insert(original)
        val changed = BookInfoPreview(original.copy(name = "Uncommitted name"), emptyList(), null, false)
        check(previews.stage(lease, 1, requestId, 1, changed))
        assertThrows(IllegalStateException::class.java) {
            database.bookInfoRequestDao.replacePreview(requestId, "owner", 0, original.bookUrl, original.bookUrl, 2) {
                throw IllegalStateException("Commit failed")
            }
        }
        checkNotNull(repository.begin(parent.navigationOwner(), BookInfoChildKind.Catalog))
        val newer = previews.open(key)
        val claimed = checkNotNull(repository.claimPreview(requestId, original.bookUrl, newer, previews, "replacement"))
        assertEquals(1L, claimed.revision)
        assertNull(claimed.previewCommitRevision)
        val recovered = checkNotNull(previews.resolve(newer, requestId, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision))
        assertEquals(original.name, recovered.book.name)
    }

    @Test fun aCommittedSameUrlPreviewSurvivesLaterNavigationRevisions() = runBlocking {
        database.bookDao.insert(original)
        val changed = BookInfoPreview(original.copy(name = "Committed name"), emptyList(), null, false)
        check(previews.stage(lease, 1, requestId, 1, changed))
        val committed = database.bookInfoRequestDao.replacePreview(requestId, "owner", 0, original.bookUrl, original.bookUrl, 2) { }
        checkNotNull(repository.begin(committed.navigationOwner(), BookInfoChildKind.Catalog))
        val newer = previews.open(key)
        val claimed = checkNotNull(repository.claimPreview(requestId, original.bookUrl, newer, previews, "replacement"))
        assertEquals(2L, claimed.revision)
        assertEquals(1L, claimed.previewCommitRevision)
        val recovered = checkNotNull(previews.resolve(newer, requestId, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision))
        assertEquals("Committed name", recovered.book.name)
    }

    private fun removeLegacyJournalReceiptFlag() {
        val file = java.io.File(context.noBackupFilesDir, "book-info-previews/$key.json")
        val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
        json.getAsJsonObject("pending").remove("requiresCommitReceipt")
        file.writeText(json.toString())
    }

    @Test fun aLegacySameUrlJournalWithoutAReceiptDoesNotUseUnrelatedNavigationAsProof() = runBlocking {
        database.bookDao.insert(original)
        check(previews.stage(lease, 1, requestId, 1,
            BookInfoPreview(original.copy(name = "Unproven legacy name"), emptyList(), null, false)))
        removeLegacyJournalReceiptFlag()
        checkNotNull(repository.begin(parent.navigationOwner(), BookInfoChildKind.Catalog))
        val newer = previews.open(key)
        val claimed = checkNotNull(repository.claimPreview(requestId, original.bookUrl, newer, previews, "replacement"))
        assertNull(claimed.previewCommitRevision)
        val recovered = checkNotNull(previews.resolve(newer, requestId, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision))
        assertEquals(original.name, recovered.book.name)
    }

    @Test fun aLegacySourceJournalCanStillRecoverItsMatchingChangedCanonicalKey() = runBlocking {
        check(previews.stage(lease, 1, requestId, 1, preview))
        removeLegacyJournalReceiptFlag()
        database.bookInfoRequestDao.replacePreview(requestId, "owner", 0, original.bookUrl, candidate.bookUrl, 2) { }
        database.openHelper.writableDatabase.execSQL("UPDATE book_info_requests SET previewCommitRevision = NULL WHERE id = ?", arrayOf(requestId))
        val newer = previews.open(key)
        val claimed = checkNotNull(repository.claimPreview(requestId, original.bookUrl, newer, previews, "replacement"))
        assertNull(claimed.previewCommitRevision)
        val recovered = checkNotNull(previews.resolve(newer, requestId, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision))
        assertEquals(candidate.bookUrl, recovered.book.bookUrl)
        assertEquals("cached info", recovered.infoHtml)
    }

    @Test fun aQueuedOldPreviewCannotOverwriteTheCommittedGeneration() = runBlocking {
        val committed = repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        assertFalse(previews.save(lease, 100, BookInfoPreview(original, emptyList(), null, false)))
        assertEquals(candidate.bookUrl, previews.peek(key)!!.book.bookUrl)
        val snapshot = checkNotNull(previews.resolveSnapshot(lease, requestId, committed.revision,
            committed.bookUrl, committed.previewCommitRevision))
        assertEquals(1L, snapshot.commitRevision)
        val edited = preview.copy(book = candidate.copy(name = "Observed edit"))
        assertTrue(previews.save(lease, 101, edited, snapshot.commitRevision))
        assertEquals("Observed edit", previews.peek(key)!!.book.name)
    }

    @Test fun reopeningAPreviewRetainsItsCommittedGenerationForTheNewWriter() = runBlocking {
        repository.replacePreview(parent.navigationOwner(), lease, previews, 1, preview, original)
        val newer = previews.open(key)
        assertEquals(1L, newer.commitRevision)
        assertTrue(previews.save(newer, 1, preview.copy(book = candidate.copy(name = "New owner edit"))))
        assertEquals("New owner edit", previews.peek(key)!!.book.name)
        assertFalse(previews.save(lease, 200, preview, 1))
    }

    @Test fun aRefreshFromAnUnobservedGenerationRollsBackItsDatabaseChanges() = runBlocking {
        database.bookDao.insert(original.copy(type = BookType.text))
        repository.refreshPreview(parent.navigationOwner(), lease, previews, 1, preview.copy(inBookshelf = true), original)
        val current = checkNotNull(repository.get(requestId))
        val second = candidate.copy(bookUrl = "second-refresh://$requestId", name = "Unobserved response")
        try {
            repository.refreshPreview(current.navigationOwner(), lease, previews, 2,
                BookInfoPreview(second, emptyList(), candidate.origin, true), candidate)
            fail("A stale UI generation committed a new response")
        } catch (_: IllegalStateException) { }
        assertFalse(database.bookDao.has(second.bookUrl))
        assertEquals(candidate.bookUrl, repository.get(requestId)!!.bookUrl)
        assertEquals(candidate.name, database.bookDao.getBook(candidate.bookUrl)!!.name)
        assertEquals(candidate.bookUrl, previews.peek(key)!!.book.bookUrl)
    }

    @Test fun anotherChildCannotPersistASnapshotWhileTheFirstChildIsPending() = runBlocking {
        database.bookDao.insert(original)
        val first = checkNotNull(repository.prepareAndBegin(parent.navigationOwner(), BookInfoChildKind.Edit, original, emptyList()))
        val current = checkNotNull(repository.get(requestId))
        try {
            repository.prepareAndBegin(current.navigationOwner(), BookInfoChildKind.Reader,
                original.copy(name = "Late duplicate child"), emptyList())
            fail("A duplicate child replaced the prepared snapshot")
        } catch (_: IllegalStateException) { }
        assertEquals(first.id, database.bookInfoRequestDao.activeChild(requestId)!!.id)
        assertEquals(original.name, database.bookDao.getBook(original.bookUrl)!!.name)
        assertEquals(current.revision, repository.get(requestId)!!.revision)
    }

    @Test fun preparingAChildCannotResurrectARemovedLibraryBook() = runBlocking {
        val library = original.copy(type = BookType.text)
        database.bookDao.insert(library)
        database.bookDao.delete(library)
        try {
            repository.prepareAndBegin(parent.navigationOwner(), BookInfoChildKind.Reader, library, emptyList())
            fail("Preparing a child resurrected a removed library record")
        } catch (_: IllegalStateException) { }
        assertFalse(database.bookDao.has(library.bookUrl))
        assertNull(database.bookInfoRequestDao.activeChild(requestId))
        assertEquals(parent.revision, repository.get(requestId)!!.revision)
    }

}
