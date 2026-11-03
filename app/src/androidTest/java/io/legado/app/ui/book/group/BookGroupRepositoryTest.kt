package io.legado.app.ui.book.group

import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asFlow
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookChapter
import io.legado.app.constant.BookType
import io.legado.app.data.repository.BookGroupDraft
import io.legado.app.data.repository.BookGroupError
import io.legado.app.data.repository.BookGroupException
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.data.repository.BookInfoRepository
import io.legado.app.help.storage.prepareBackupMediaDirectories
import io.legado.app.help.storage.restoreBackupMediaDirectory
import io.legado.app.help.storage.remapRestoredCoverPath
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookGroupRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private lateinit var database: AppDatabase
    private lateinit var repository: BookGroupRepository
    private val files = arrayListOf<File>()
    private val directories = arrayListOf<File>()
    private lateinit var existingCovers: Set<String>

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
        repository = BookGroupRepository(database, context)
        existingCovers = File(context.externalFiles, "covers").listFiles().orEmpty().map { it.canonicalPath }.toSet()
    }
    @After fun cleanUp() { database.close(); files.forEach { it.delete() }; directories.forEach { it.deleteRecursively() } }

    @Test fun concurrentCreationAllocatesAll63PositiveBitsAndStopsAtTheLimit() = runBlocking {
        val groups = (0..62).map { index -> async(Dispatchers.Default) { repository.save(BookGroupDraft(name = "Group $index")) } }.awaitAll()
        assertEquals((0..62).map { 1L shl it }.toSet(), groups.map { it.groupId }.toSet())
        val failure = runCatching { repository.save(BookGroupDraft(name = "Overflow")) }.exceptionOrNull()
        assertEquals(BookGroupError.Limit, (failure as BookGroupException).reason)
        assertEquals(63, database.bookGroupDao.all.size)
        assertNull(database.bookGroupDao.getByID(Long.MIN_VALUE))
    }

    @Test fun creatingAReusedBitClearsStaleBookMembership() = runBlocking {
        val book = Book(bookUrl = "stale://$id", name = "Stale", group = 3)
        database.bookDao.insert(book)
        val group = repository.save(BookGroupDraft(name = "New group"))
        assertEquals(1L, group.groupId)
        assertEquals(2L, database.bookDao.getBook(book.bookUrl)!!.group)
    }

    @Test fun legacySignBitDeletionClearsMembershipAndProtectsSystemGroups() = runBlocking {
        database.bookGroupDao.insert(BookGroup(Long.MIN_VALUE, "Legacy"), BookGroup(1, "Other"), BookGroup(-1, "All"))
        val book = Book(bookUrl = "legacy://$id", name = "Legacy", group = Long.MIN_VALUE or 1)
        database.bookDao.insert(book)
        assertEquals(listOf("Legacy"), database.bookGroupDao.getGroupNames(Long.MIN_VALUE))
        assertEquals(listOf(book.bookUrl), database.bookDao.getBooksByGroup(Long.MIN_VALUE).map { it.bookUrl })
        assertEquals(listOf(book.bookUrl), database.bookDao.flowByGroup(Long.MIN_VALUE).first().map { it.bookUrl })
        assertTrue(database.bookDao.flowBookshelfBooks().first().single().hasUserGroup)
        assertTrue(database.bookDao.flowNetNoGroup().first().isEmpty())
        assertTrue(database.bookGroupDao.flowSelect().first().any { it.groupId == Long.MIN_VALUE })
        assertTrue(withTimeout(20_000) { database.bookGroupDao.show.asFlow().first() }.any { it.groupId == Long.MIN_VALUE })
        repository.delete(Long.MIN_VALUE)
        assertEquals(1L, database.bookDao.getBook(book.bookUrl)!!.group)
        assertNotNull(database.bookGroupDao.getByID(1))
        assertNull(database.bookGroupDao.getByID(Long.MIN_VALUE))
        assertEquals(BookGroupError.Protected, (runCatching { repository.delete(-1) }.exceptionOrNull() as BookGroupException).reason)
        assertNotNull(database.bookGroupDao.getByID(-1))
    }

    @Test fun aFailedMembershipUpdateRollsBackTheGroupDeletion() = runBlocking {
        database.bookGroupDao.insert(BookGroup(1, "Retained"))
        val book = Book(bookUrl = "rollback://$id", name = "Retained", group = 1)
        database.bookDao.insert(book)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_group_clear BEFORE UPDATE OF `group` ON books BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        assertTrue(runCatching { repository.delete(1) }.isFailure)
        assertNotNull(database.bookGroupDao.getByID(1))
        assertEquals(1L, database.bookDao.getBook(book.bookUrl)!!.group)
    }

    @Test fun editingDoesNotOverwriteVisibilityOrOrderAndSelectionReorderKeepsSystemSlots() = runBlocking {
        database.bookGroupDao.insert(BookGroup(1, "A", order = 1), BookGroup(-1, "All", order = 2), BookGroup(2, "B", order = 3))
        val draft = BookGroupDraft(id = 1, name = "Edited", sort = 2, refresh = false, onlyRead = true,
            generation = checkNotNull(database.bookGroupDao.getByID(1)!!.generation))
        repository.setVisible(1, false)
        repository.move(1, 1, true)
        repository.save(draft)
        assertEquals(listOf(2L, -1L, 1L), database.bookGroupDao.all.map { it.groupId })
        val stored = database.bookGroupDao.getByID(1)!!
        assertFalse(stored.show)
        assertEquals(3, stored.order)
        assertEquals("Edited", stored.groupName)
        assertEquals(2, stored.bookSort)
        assertFalse(stored.enableRefresh)
        assertTrue(stored.onlyUpdateRead)
    }

    @Test fun deletingWhileAssigningCannotReintroduceADeletedGroupBit() = runBlocking {
        val info = BookInfoRepository(database)
        val book = Book(bookUrl = "assignment://$id", name = "Assignment")
        database.bookDao.insert(book)
        repeat(12) {
            database.bookGroupDao.insert(BookGroup(1, "Delete"))
            awaitAll(async(Dispatchers.Default) { repository.delete(1) }, async(Dispatchers.Default) {
                info.assignGroups(book.copy(group = 1), emptyList(), true)
            })
            assertEquals(0L, database.bookDao.getBook(book.bookUrl)!!.group)
            assertNull(database.bookGroupDao.getByID(1))
        }
    }

    @Test fun importedCoverIsCopiedDurablyAndDecodedWithoutAView() = runBlocking {
        val input = File(context.cacheDir, "group-$id.png"); files.add(input)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(id.hashCode() or 0xff000000.toInt())
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", input)
        val imported = File(repository.importCover(uri))
        if (imported.canonicalPath !in existingCovers) files.add(imported)
        assertTrue(imported.isFile)
        assertEquals(File(context.externalFiles, "covers").canonicalFile, imported.parentFile!!.canonicalFile)
        assertArrayEquals(input.readBytes(), imported.readBytes())
        input.delete()
        val group = repository.save(BookGroupDraft(name = "Cover", cover = imported.path))
        val decoded = repository.coverBitmap(database.bookGroupDao.getByID(group.groupId)!!.cover)
        assertNotNull(decoded)
        decoded!!.recycle()
        val isolated = File(context.cacheDir, "group-media-$id").apply { mkdirs() }; directories.add(isolated)
        val sourceRoot = File(isolated, "source").apply { mkdirs() }
        val backupRoot = File(isolated, "backup").apply { mkdirs() }
        val targetRoot = File(isolated, "restored").apply { mkdirs() }
        File(sourceRoot, "covers").mkdirs()
        imported.copyTo(File(sourceRoot, "covers/${imported.name}"))
        prepareBackupMediaDirectories(sourceRoot, backupRoot, emptyList(), includePersistedCovers = false,
            includeOtherCovers = true, includeBackgrounds = false)
        assertTrue(File(backupRoot, "covers/${imported.name}").isFile)
        restoreBackupMediaDirectory(backupRoot, targetRoot, "covers").getOrThrow()
        val remapped = checkNotNull(remapRestoredCoverPath(imported.path, backupRoot, targetRoot))
        assertArrayEquals(imported.readBytes(), File(remapped).readBytes())
        val restoredBitmap = repository.coverBitmap(remapped)
        assertNotNull(restoredBitmap); restoredBitmap!!.recycle()
        val svg = File(context.cacheDir, "group-$id.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="#134567"/></svg>""")
        }
        files.add(svg)
        val svgCopy = File(repository.importCover(FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", svg)))
        if (svgCopy.canonicalPath !in existingCovers) files.add(svgCopy)
        assertArrayEquals(svg.readBytes(), svgCopy.readBytes())
        val vector = repository.coverBitmap(svgCopy.path)
        assertNotNull(vector)
        vector!!.recycle()
    }

    @Test fun newControllerRestoresSelectionAndEditorDraftWithoutWriting() = runBlocking {
        database.bookGroupDao.insert(BookGroup(1, "Original"))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saved = SavedStateHandle()
        try {
            lateinit var first: BookGroupsController
            instrumentation.runOnMainSync { first = BookGroupsController(saved, firstScope, repository) }
            await { !first.state.value.loading }
            instrumentation.runOnMainSync {
                first.openSelection("book://$id", 1)
                first.edit(first.state.value.groups.single())
                first.updateEditor { it.copy(name = "Draft", cover = "https://example.invalid/cover.png", sort = 3, refresh = false, onlyRead = true) }
                first.confirmDelete()
            }
            lateinit var restored: SavedStateHandle
            instrumentation.runOnMainSync { restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }); firstScope.cancel() }
            lateinit var second: BookGroupsController
            instrumentation.runOnMainSync { second = BookGroupsController(restored, secondScope, repository) }
            await { !second.state.value.loading }
            val state = second.state.value
            assertTrue(state.open); assertTrue(state.selection); assertTrue(state.confirmingDelete)
            assertEquals(1L, state.selected)
            assertEquals("Draft", state.editor!!.name)
            assertEquals(database.bookGroupDao.getByID(1)!!.generation, state.editor.generation)
            assertEquals(3, state.editor.sort)
            assertFalse(state.editor.refresh); assertTrue(state.editor.onlyRead)
            assertEquals("Original", database.bookGroupDao.getByID(1)!!.groupName)
            instrumentation.runOnMainSync { second.cancelDelete(); second.dismiss() }
            assertNull(second.state.value.editor)
            assertTrue(second.state.value.open)
            repository.delete(1, database.bookGroupDao.getByID(1)!!.generation)
            val replacement = repository.save(BookGroupDraft(name = "Replacement"))
            await { second.state.value.groups.any { it.generation == replacement.generation } && second.state.value.selected == 0L }
        } finally { firstScope.cancel(); secondScope.cancel() }
    }

    @Test fun aPartiallySelectedLegacyCombinationCanBeCleared() {
        database.bookGroupDao.insert(BookGroup(3, "Legacy combination"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            lateinit var controller: BookGroupsController
            instrumentation.runOnMainSync { controller = BookGroupsController(SavedStateHandle(), scope, repository) }
            await { !controller.state.value.loading }
            instrumentation.runOnMainSync { controller.openSelection("book://$id", 1); controller.toggle(3) }
            assertEquals(0L, controller.state.value.selected)
            instrumentation.runOnMainSync { controller.toggle(3) }
            assertEquals(3L, controller.state.value.selected)
        } finally { scope.cancel() }
    }

    @Test fun addingAPreviewReplacesTheWholeTemporaryChapterList() = runBlocking {
        database.bookGroupDao.insert(BookGroup(1, "Shelf"))
        val book = Book(bookUrl = "temporary://$id", name = "Temporary", type = BookType.text or BookType.notShelf, group = 1)
        database.bookDao.insert(book)
        database.bookChapterDao.insert(*(0..3).map { BookChapter(bookUrl = book.bookUrl, url = "old-$it", title = "Old $it", index = it) }.toTypedArray())
        val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl, url = "new-$it", title = "New $it", index = it) }
        val assigned = BookInfoRepository(database).assignGroups(book, chapters, false)
        assertTrue(assigned.inBookshelf)
        assertEquals(listOf("New 0", "New 1"), database.bookChapterDao.getChapterList(book.bookUrl).map { it.title })
    }

    @Test fun retryingTheSameCreationAfterLosingTheAcknowledgementIsIdempotent() = runBlocking {
        val draft = BookGroupDraft(name = "Recover creation")
        val first = repository.save(draft)
        val second = repository.save(draft)
        assertEquals(first.groupId, second.groupId)
        assertEquals(first.generation, second.generation)
        assertEquals(1, database.bookGroupDao.all.size)
        assertEquals(BookGroupError.Changed, (runCatching { repository.save(draft.copy(generation = "")) }.exceptionOrNull() as BookGroupException).reason)
    }

    @Test fun aStaleEditorCannotChangeOrDeleteAReusedBit() = runBlocking {
        val original = repository.save(BookGroupDraft(name = "Original owner"))
        val draft = BookGroupDraft(id = original.groupId, name = "Stale edit", generation = checkNotNull(original.generation))
        repository.delete(original.groupId, original.generation)
        val replacement = repository.save(BookGroupDraft(name = "New owner"))
        assertEquals(original.groupId, replacement.groupId)
        assertNotEquals(original.generation, replacement.generation)
        assertEquals(BookGroupError.Changed, (runCatching { repository.save(draft) }.exceptionOrNull() as BookGroupException).reason)
        assertEquals(BookGroupError.Changed, (runCatching { repository.delete(original.groupId, original.generation) }.exceptionOrNull() as BookGroupException).reason)
        assertEquals(BookGroupError.Changed, (runCatching { repository.setVisible(original.groupId, false, original.generation) }.exceptionOrNull() as BookGroupException).reason)
        assertEquals(BookGroupError.Changed, (runCatching { repository.move(original.groupId, 1, false, original.generation) }.exceptionOrNull() as BookGroupException).reason)
        assertEquals("New owner", database.bookGroupDao.getByID(replacement.groupId)!!.groupName)
        val book = Book(bookUrl = "owner://$id", name = "Ownership", group = 0)
        database.bookDao.insert(book)
        assertEquals(BookGroupError.Changed, (runCatching {
            BookInfoRepository(database).assignGroups(book.copy(group = original.groupId), emptyList(), true,
                mapOf(original.groupId to checkNotNull(original.generation)))
        }.exceptionOrNull() as BookGroupException).reason)
        assertEquals(0L, database.bookDao.getBook(book.bookUrl)!!.group)
    }

    @Test fun restoringOldOrDuplicatedGenerationsCannotDeleteAnotherGroup() {
        database.bookGroupDao.insert(BookGroup(1, "Legacy", generation = null), BookGroup(2, "Blank", generation = ""))
        val first = database.bookGroupDao.getByID(1)!!
        val second = database.bookGroupDao.getByID(2)!!
        assertFalse(first.generation.isNullOrBlank())
        assertNotEquals(first.generation, second.generation)
        database.bookGroupDao.insert(first.copy(groupId = 4, groupName = "Copied"))
        assertEquals(3, database.bookGroupDao.all.size)
        assertEquals(first.generation, database.bookGroupDao.getByID(1)!!.generation)
        assertNotEquals(first.generation, database.bookGroupDao.getByID(4)!!.generation)
        database.bookGroupDao.insert(BookGroup(1, "Restored old backup", generation = null))
        assertNotEquals(first.generation, database.bookGroupDao.getByID(1)!!.generation)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
        assertTrue("Group state did not load", condition())
    }
}
