package io.legado.app.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.getFolderName
import io.legado.app.data.entities.isEpub
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isImage
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookCacheMaintenanceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val leases: CacheResourceLeases,
    private val reader: CachedChapterReader,
) {
    private val maintenance = Mutex()
    private fun settings(): Retention {
        val values = context.defaultSharedPreferences.all
        return Retention((values[PreferKey.imageRetainNum] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            (values[PreferKey.preDownloadNum] as? Number)?.toInt()?.coerceAtLeast(0) ?: 2)
    }
    private data class Retention(val images: Int, val preload: Int)

    suspend fun clean(): Int = withContext(IO) { maintenance.withLock {
        val caller = currentCoroutineContext()
        val checkCancelled = { caller.ensureActive() }
        checkCancelled()
        database.bookContentSearchDao.pruneResults(System.currentTimeMillis()-java.util.concurrent.TimeUnit.DAYS.toMillis(7))
        val root = context.externalFiles
        val pruner = ReferenceAwareCachePruner(leases)
        var removed = pruner.prune(File(root, "book_cache"),
            key = { CacheResourceKey("book", it.name.takeLast(16)) },
            isReferenced = { candidate -> database.bookDao.getCacheCleanupBooks().any { it.getFolderName() == candidate.name } },
            checkCancelled = checkCancelled)
        removed += pruner.prune(File(root, "epub"), key = { epubCacheKey(it.name) },
            isReferenced = { candidate -> database.bookDao.getCacheCleanupBooks().any { it.isEpub && it.originName == candidate.name } },
            checkCancelled = checkCancelled)
        val retention = settings()
        if (retention.images > 0) database.bookDao.getCacheCleanupImageBooks().forEach { book ->
            checkCancelled()
            leases.plan(bookCacheKey(book)).use { plan ->
                val chapters = database.bookChapterDao.getChapterList(book.bookUrl,
                    (book.durChapterIndex.toLong() - retention.images).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    (book.durChapterIndex.toLong() + retention.preload).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                val keep = hashSetOf<String>()
                chapters.forEach { chapter ->
                    checkCancelled()
                    reader.read(book, chapter)?.let { content ->
                        val matcher = AppPattern.imgPattern.matcher(content)
                        while (matcher.find()) {
                            val src = matcher.group(1) ?: continue
                            val absolute = NetworkUtils.getAbsoluteURL(chapter.url, src)
                            keep += "${MD5Utils.md5Encode16(absolute)}.${BookHelp.getImageSuffix(absolute)}"
                        }
                    }
                }
                val bookFolder = File(root, "book_cache/${book.getFolderName()}")
                val imageFolder = File(bookFolder, "images")
                if (Files.isSymbolicLink(bookFolder.toPath()) || Files.isSymbolicLink(imageFolder.toPath())) return@use
                val images = imageFolder.listFiles().orEmpty()
                val latest = database.bookDao.getBook(book.bookUrl)
                val unchanged = latest != null && latest.isImage && latest.durChapterIndex == book.durChapterIndex &&
                    latest.getFolderName() == book.getFolderName() && settings() == retention
                plan.removeIfUnchanged {
                    if (unchanged) {
                        images.forEach { image ->
                            checkCancelled()
                            if (image.name !in keep && image.isFile && image.delete()) removed++
                        }
                    }
                }
            }
        }
        removed
    } }
}
