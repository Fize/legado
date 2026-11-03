package io.legado.app.data.navigation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.data.repository.PlatformReaderReturnRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformMediaPreparationTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlatformReaderReturnRepository
    private val book = Book(bookUrl = "media", name = "Media", type = BookType.audio, durChapterPos = 99)
    private val dao get() = db.bookInfoRequestDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).addLegadoMigrations().build()
        repository = PlatformReaderReturnRepository(db)
    }
    @After fun cleanup() { db.close() }

    private fun request(type: Int, index: Int = 2, position: Int = 8000, volumes: Set<Int> = emptySet()) {
        db.bookDao.insert(book.copy(type = type))
        db.bookChapterDao.insert(*(0..5).map {
            BookChapter(bookUrl = book.bookUrl, url = "https://fixture/$it", index = it,
                title = "Chapter $it", isVolume = it in volumes)
        }.toTypedArray())
        dao.claim(BookInfoRequest("detail", book.bookUrl, book.bookUrl, "owner", updatedAt = 1), "owner")
        checkNotNull(dao.begin("detail", "owner", 0, book.bookUrl, "player", BookInfoChildKind.Reader,
            BookInfoNavigationPosition(index, position, volumeIndex = 99, chapterInVolumeIndex = 99), 2))
    }

    @Test fun audioSeekAndAcknowledgementCommitOnceAcrossOwnerTakeover() {
        request(BookType.audio)
        val first = repository.prepareMedia("player", book.bookUrl, "first", BookType.audio)
        assertTrue(first.positionApplied)
        assertEquals(2, first.book.durChapterIndex)
        assertEquals(8000, first.book.durChapterPos)
        assertNull(dao.child("player")!!.position)
        db.bookDao.update(first.book.copy(durChapterPos = 9500))
        val restored = repository.prepareMedia("player", book.bookUrl, "restored", BookType.audio)
        assertFalse(restored.positionApplied)
        assertEquals(9500, restored.book.durChapterPos)
        assertFalse(repository.finish(first.owner))
        assertTrue(repository.finish(restored.owner))
    }

    @Test fun videoPositionUsesCatalogInsteadOfStaleVolumeHints() {
        request(BookType.video, index = 5, volumes = setOf(0, 3))
        val prepared = repository.prepareMedia("player", book.bookUrl, "video", BookType.video)
        assertEquals(1, prepared.book.durVolumeIndex)
        assertEquals(1, prepared.book.chapterInVolumeIndex)
        assertEquals(5, prepared.book.durChapterIndex)
        assertEquals(8000, prepared.book.durChapterPos)
    }

    @Test fun selectingVolumeOpensItsFirstEpisode() {
        request(BookType.video, index = 3, volumes = setOf(0, 3))
        val prepared = repository.prepareMedia("player", book.bookUrl, "video", BookType.video)
        assertEquals(4, prepared.book.durChapterIndex)
        assertEquals(0, prepared.book.chapterInVolumeIndex)
    }

    @Test fun aVolumeOnlyMovieKeepsTheSelectedLineAsTheActualChapter() {
        request(BookType.video, index = 2, volumes = (0..5).toSet())
        val prepared = repository.prepareMedia("player", book.bookUrl, "video", BookType.video)
        assertEquals(2, prepared.book.durVolumeIndex)
        assertEquals(2, prepared.book.durChapterIndex)
        assertEquals(0, prepared.book.chapterInVolumeIndex)
    }

    @Test fun missingChapterRollsBackBothClaimAndProgress() {
        request(BookType.audio, index = 99)
        assertThrows(IllegalStateException::class.java) {
            repository.prepareMedia("player", book.bookUrl, "audio", BookType.audio)
        }
        assertEquals(BookInfoChildPhase.Requested, dao.child("player")!!.phase)
        assertNotNull(dao.child("player")!!.position)
        assertEquals(99, db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun wrongPlayerCannotClaimOrConsumeAnInstruction() {
        request(BookType.audio)
        assertThrows(IllegalStateException::class.java) {
            repository.prepareMedia("player", book.bookUrl, "video", BookType.video)
        }
        assertEquals(BookInfoChildPhase.Requested, dao.child("player")!!.phase)
        assertNotNull(dao.child("player")!!.position)
    }

    @Test fun videoWithoutVolumesAndStandaloneChapterRoutesUseAbsoluteChapterIndices() {
        request(BookType.video, index = 4)
        val prepared = repository.prepareMedia("player", book.bookUrl, "video", BookType.video)
        assertEquals(4, prepared.book.chapterInVolumeIndex)
        val standalone = repository.seekMedia(book.bookUrl, 1, BookType.video)
        assertEquals(1, standalone.durChapterIndex)
        assertEquals(1, standalone.chapterInVolumeIndex)
        assertEquals(0, standalone.durChapterPos)
    }

    @Test fun sourceChangeReturnsTheExactReplacementAndPreparationResolvesItsCanonicalKey() {
        request(BookType.audio)
        val prepared = repository.prepareMedia("player", book.bookUrl, "audio", BookType.audio)
        val replacement = prepared.book.copy(bookUrl = "replacement")
        repository.replaceSource(prepared.owner, book.bookUrl, replacement.bookUrl) {
            db.bookDao.delete(prepared.book)
            db.bookDao.insert(replacement)
        }
        val restored = repository.prepareMedia("player", book.bookUrl, "next", BookType.audio)
        assertEquals(replacement.bookUrl, restored.book.bookUrl)
        assertTrue(repository.finish(restored.owner))
        assertEquals(replacement.bookUrl, dao.child("player")!!.returnBookUrl)
    }
}
