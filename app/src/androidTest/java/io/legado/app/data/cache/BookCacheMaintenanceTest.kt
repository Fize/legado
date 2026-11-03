package io.legado.app.data.cache

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookCacheMaintenanceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(target.cacheDir, "maintenance-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getExternalFilesDir(type: String?): File = root
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$id-$name", mode)
    }
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val leases = CacheResourceLeases()
    private val repository = BookCacheMaintenanceRepository(context, database, leases, object : CachedChapterReader {
        override fun read(book: Book, chapter: BookChapter): String? =
            File(root, "book_cache/${book.getFolderName()}/${chapter.getFileName()}").takeIf { it.isFile }?.readText()
    })
    @After fun close() { database.close(); root.deleteRecursively(); target.deleteSharedPreferences("$id-${context.packageName}_preferences") }
    private fun book(name: String = "Book", type: Int = BookType.text) = Book(bookUrl = "test://$id/$name", name = name, type = type)
    private fun folder(book: Book) = File(root, "book_cache/${book.getFolderName()}").apply { mkdirs() }
    private fun retention(keep: Int, preload: Int = 0) {
        context.defaultSharedPreferences.edit().putInt(PreferKey.imageRetainNum, keep).putInt(PreferKey.preDownloadNum, preload).commit()
    }

    @Test fun newlyInsertedBooksRetainTheirPublishedBodyAcrossRepeatedCleanup() = runBlocking {
        val book = book(); database.bookDao.insert(book)
        val body = File(folder(book), "chapter").apply { writeText("ALPHA") }
        repeat(3) { repository.clean(); assertEquals("ALPHA", body.readText()) }
        database.bookDao.delete(book)
        repository.clean(); assertFalse(body.exists())
    }
    @Test fun unreferencedInUseFolderSurvivesUntilTheLeaseCloses() = runBlocking {
        val book = book(); val body = File(folder(book), "chapter").apply { writeText("ALPHA") }
        leases.acquireBook(book).use { repository.clean(); assertTrue(body.exists()) }
        repository.clean(); assertFalse(body.exists())
    }
    @Test fun cachedEpubFilesRespectBothReferencesAndActiveParserLeases() = runBlocking {
        val book = book().copy(type = BookType.text or BookType.local, originName = "book.epub")
        val file = File(root, "epub/book.epub").apply { parentFile!!.mkdirs(); writeText("archive") }
        database.bookDao.insert(book); repository.clean(); assertTrue(file.exists())
        database.bookDao.delete(book)
        leases.acquire(epubCacheKey(book.originName)).use { repository.clean(); assertTrue(file.exists()) }
        repository.clean(); assertFalse(file.exists())
    }
    @Test fun comicRetentionKeepsConfiguredChaptersAndSkipsAnActiveReader() = runBlocking {
        retention(1)
        val book = book(type = BookType.image).copy(durChapterIndex = 2)
        database.bookDao.insert(book)
        val images = File(folder(book), "images").apply { mkdirs() }
        (0..3).forEach { index ->
            val chapter = BookChapter(bookUrl = book.bookUrl, index = index, url = "https://images.invalid/$index", title = "Chapter $index")
            database.bookChapterDao.insert(chapter)
            val url = "https://images.invalid/$index.jpg"
            File(folder(book), chapter.getFileName()).writeText("<img src=\"$url\">")
            File(images, "${MD5Utils.md5Encode16(url)}.jpg").writeText("image")
        }
        leases.acquireBook(book).use { repository.clean(); assertEquals(4, images.listFiles()!!.size) }
        repository.clean()
        assertEquals(setOf(1, 2).map { "${MD5Utils.md5Encode16("https://images.invalid/$it.jpg")}.jpg" }.toSet(), images.listFiles()!!.map { it.name }.toSet())
    }
    @Test fun maximumRetentionValuesDoNotOverflowTheChapterRange() = runBlocking {
        retention(Int.MAX_VALUE, Int.MAX_VALUE)
        val book = book(type = BookType.image).copy(durChapterIndex = 2)
        database.bookDao.insert(book)
        val chapter = BookChapter(bookUrl = book.bookUrl, index = 2,
            url = "https://images.invalid/current", title = "Current")
        database.bookChapterDao.insert(chapter)
        val url = "https://images.invalid/current.jpg"
        File(folder(book), chapter.getFileName()).writeText("<img src=\"$url\">")
        val image = File(folder(book), "images/${MD5Utils.md5Encode16(url)}.jpg")
            .apply { parentFile!!.mkdirs(); writeText("current") }
        repository.clean()
        assertEquals("current", image.readText())
    }
    @Test fun disabledComicRetentionDoesNotRemoveImages() = runBlocking {
        retention(0)
        val book = book(type = BookType.image); database.bookDao.insert(book)
        val image = File(folder(book), "images/keep.jpg").apply { parentFile!!.mkdirs(); writeText("image") }
        repository.clean(); assertTrue(image.exists())
    }
    @Test fun renamedBooksKeepTheSameResourceOwnershipKey() = runBlocking {
        val original = book(); val renamed = original.copy(name = "Renamed")
        assertNotEquals(original.getFolderNameNoCache(), renamed.getFolderNameNoCache())
        assertEquals(bookCacheKey(original), bookCacheKey(renamed))
        val body = File(folder(renamed), "chapter").apply { writeText("ALPHA") }
        leases.acquireBook(original).use { repository.clean(); assertTrue(body.exists()) }
        repository.clean(); assertFalse(body.exists())
    }
    @Test fun lateImagePublicationInvalidatesAnAlreadyComputedRetentionPlan() = runBlocking {
        retention(1)
        val book = book(type = BookType.image); database.bookDao.insert(book)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://images.invalid/0", title = "Chapter")
        database.bookChapterDao.insert(chapter)
        val image = File(folder(book), "images/new.jpg").apply { parentFile!!.mkdirs() }
        val publishingReader = object : CachedChapterReader {
            override fun read(book: Book, chapter: BookChapter): String {
                leases.use(bookCacheKey(book), mutating = true) { image.writeText("new") }
                return ""
            }
        }
        BookCacheMaintenanceRepository(context, database, leases, publishingReader).clean()
        assertEquals("new", image.readText())
    }
}
