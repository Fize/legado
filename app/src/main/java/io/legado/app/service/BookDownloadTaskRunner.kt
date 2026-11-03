package io.legado.app.service

import io.legado.app.data.AppDatabase
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookTransferTask
import io.legado.app.data.repository.*
import io.legado.app.help.book.*
import io.legado.app.help.storage.*
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.onEachParallel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookDownloadTaskRunner @Inject constructor(private val db: AppDatabase,private val tasks: BookTransferRepository,
    private val resources: CacheResourceLeases) {
    private fun chapterKey(chapter: BookChapter)=GSON.toJson(chapter.copy(wordCount=null))
    suspend fun run(task: BookTransferTask) {
        val generation = restoreWriteGeneration.capture()
        run(task, generation)
    }
    internal suspend fun run(task: BookTransferTask, generation: RestoreWriteGeneration.Token)=withContext(Dispatchers.IO) {
        require(task.kind=="download" && task.phase=="running")
        try {
            mutation(generation) { Unit }
            val config=checkNotNull(GSON.fromJson(task.config,BookTransferConfig::class.java))
            require(config.threads in 1..128 && config.start>=0)
            var book=tasks.snapshot(task); require(!book.isLocal && !book.isAudio)
            val source=checkNotNull(db.bookSourceDao.getBookSource(book.origin)).infoSnapshot()
            val sourceKey=transferSourceKey(GSON.toJson(source))
            val execution=currentCoroutineContext()
            fun verifySource() {
                execution.ensureActive(); generation.requireCurrent(); tasks.verifyNow(task)
                check(transferSourceKey(GSON.toJson(db.bookSourceDao.getBookSource(book.origin)))==sourceKey) { "Book source changed" }
            }
            resources.acquireBook(book).use {
                if(db.bookChapterDao.getChapterCount(book.bookUrl)==0) {
                    if(book.tocUrl.isBlank()) WebBook.getBookInfoAwait(source,book,canReName=false)
                    val chapters=WebBook.getChapterListAwait(source,book).getOrThrow()
                    mutation(generation) { verifySource(); tasks.refreshRemoteNow(task,book,chapters) }
                    val parsed=book
                    book=tasks.snapshot(task).also {
                        it.infoHtml=parsed.infoHtml; it.tocHtml=parsed.tocHtml
                        it.variableMap.clear(); it.variableMap.putAll(parsed.variableMap)
                    }
                }
                val chapters=db.runInTransaction<List<BookChapter>> {
                    verifySource()
                    val end=if(config.end<0) Int.MAX_VALUE else config.end
                    db.bookChapterDao.getChapterList(book.bookUrl,config.start,end).map { it.infoSnapshot() }
                }
                require(chapters.isNotEmpty()) { "No chapters to download" }
                mutation(generation) { tasks.progressNow(task,0,chapters.size) }
                val nextUrls=chapters.associate { it.index to it.url }
                val complete=AtomicInteger()
                chapters.asFlow().onEachParallel(config.threads) { initial ->
                    verifySource()
                    if(!initial.isVolume) {
                        val currentBook=book.infoSnapshot()
                        val chapter=initial.infoSnapshot().also { it.deferUpdates=true }
                        val key=chapterKey(initial)
                        val token=BookHelp.contentSaveToken(currentBook,chapter)
                        val content=if(BookHelp.hasImageContent(currentBook,chapter)) BookHelp.getContent(currentBook,chapter)
                            else null
                        if(content==null) {
                            val next=nextUrls[chapter.index+1]
                            val body=WebBook.getContentAwait(source.infoSnapshot(),currentBook,chapter,next,needSave=false)
                            mutation(generation) {
                                tasks.publishNow(task) {
                                    verifySource()
                                    check(chapterKey(checkNotNull(db.bookChapterDao.getChapter(task.bookUrl,initial.index)))==key) { "Chapter changed" }
                                    check(BookHelp.isContentSaveCurrent(token)) { "Chapter cache changed" }
                                    check(BookHelp.saveContent(source,currentBook,chapter,body,token)) { "Cannot save chapter" }
                                    db.bookChapterDao.updateResourceMetadata(task.bookUrl,chapter.index,chapter.title,chapter.imgUrl,GSON.toJson(chapter.variableMap))
                                }
                            }
                            val publishedKey=chapterKey(chapter.copy(variable=GSON.toJson(chapter.variableMap)))
                            BookHelp.saveImagesWithPublication(source,currentBook,chapter,body,1,publish={ action ->
                                mutation(generation) { tasks.publishNow(task) {
                                    verifySource()
                                    check(chapterKey(checkNotNull(db.bookChapterDao.getChapter(task.bookUrl,initial.index)))==publishedKey) { "Chapter changed" }
                                    action()
                                } }
                            })
                            verifySource()
                            check(BookHelp.hasImageContent(currentBook,chapter)) { "Chapter images are incomplete" }
                        }
                    }
                    mutation(generation) { verifySource(); tasks.progressNow(task,complete.incrementAndGet(),chapters.size) }
                }.collect()
                mutation(generation) { tasks.finish(task) }
            }
        } catch(cancelled: CancellationException) {
            interruptIfReady(task); throw cancelled
        } catch(required: RestoreRecoveryRequiredException) {
            throw CancellationException("Book download stopped for data recovery").apply { initCause(required) }
        } catch(error: Exception) {
            try { mutation(generation) { tasks.finish(task,error.localizedMessage ?: "Download failed") } }
            catch (cancelled: CancellationException) { interruptIfReady(task); throw cancelled }
            throw error
        }
    }
    private suspend inline fun <T> mutation(generation: RestoreWriteGeneration.Token, action: () -> T): T {
        try {
            return backupRestoreMutex.withLock {
                currentCoroutineContext().ensureActive()
                generation.requireCurrent()
                action()
            }
        } catch (required: RestoreRecoveryRequiredException) {
            throw CancellationException("Book download stopped for data recovery").apply { initCause(required) }
        }
    }
    private suspend fun interruptIfReady(task: BookTransferTask) = withContext(NonCancellable) {
        try { backupRestoreMutex.withLock { tasks.interrupt(task) } }
        catch (_: RestoreRecoveryRequiredException) { /* Preserve quarantined business state. */ }
    }
}
