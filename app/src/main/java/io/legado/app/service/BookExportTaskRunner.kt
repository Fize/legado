package io.legado.app.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.data.entities.BookTransferTask
import io.legado.app.data.repository.BookTransferConfig
import io.legado.app.data.repository.BookTransferRepository
import io.legado.app.data.repository.exportJournal
import io.legado.app.utils.GSON
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileDocWriteContext
import io.legado.app.utils.FileDocWriteInterceptor
import io.legado.app.help.storage.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/** Executes one claimed task; the service owns scheduling and notification lifetime. */
@Singleton
class BookExportTaskRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val tasks: BookTransferRepository,
    private val resources: CacheResourceLeases,
) {
    suspend fun run(task: BookTransferTask) {
        run(task, restoreWriteGeneration.capture())
    }

    internal suspend fun run(task: BookTransferTask, generation: RestoreWriteGeneration.Token) = withContext(Dispatchers.IO) {
        require(task.kind == "export" && task.phase == "running")
        val boundary = ExportRestoreBoundary(db, generation, currentCoroutineContext())
        try {
            val (config, book, target) = boundary.mutate {
                val config = checkNotNull(GSON.fromJson(task.config,BookTransferConfig::class.java))
                config.options.validate()
                val book = tasks.snapshot(task)
                val target = if(config.options.webDav) BookExportWebDavTarget.capture(context).also {
                    check(it.key == config.webDavKey) { "WebDAV export configuration changed" }
                } else null
                Triple(config, book, target)
            }
            resources.acquireBook(book).use {
                val directory = FileDoc.fromDir(config.options.directory)
                ExportStagingFiles(context, directory, exportJournal(context)).use { staging ->
                    boundary.mutate { tasks.verifyNow(task); staging.recoverAbandoned() }
                    val sidecars = if (config.options.type == "txt" && config.options.pictures) TxtExportSidecars(
                        db, book, directory, exportJournal(context), boundary,
                        { tasks.verifyNow(task) }, { action -> tasks.publishNow(task, action) },
                        staging::create,
                    ) else null
                    val writes = object : FileDocWriteInterceptor {
                        override fun create(parent: FileDoc, name: String, subDirs: Array<out String>, execution: kotlin.coroutines.CoroutineContext): FileDoc? {
                            sidecars?.create(parent, name, subDirs, execution)?.let { return it }
                            if (parent.uri != directory.uri || subDirs.isNotEmpty() || !ExportStagingFiles.isEngineStagingName(name)) return null
                            return boundary.synchronous(execution) { tasks.verifyNow(task); staging.create(parent, name) }
                        }
                        override fun write(destination: FileDoc, source: java.io.File, execution: kotlin.coroutines.CoroutineContext) =
                            sidecars?.write(destination, source, execution) ?: false
                    }
                    val engine = BookExportEngine(context,db,config.options,
                        progress = { progress,total -> boundary.synchronous { tasks.progressNow(task,progress,total) } },
                        ownerCheck = { boundary.checkCurrent(); tasks.verifyNow(task); sidecars?.captureCatalog() },
                        upload = { uri,name -> coroutineScope {
                            // Once accepted, a remote PUT may commit even if cancellation or
                            // restore follows. Never silently reissue it or claim remote rollback.
                            val pending = boundary.mutate {
                                async(start = CoroutineStart.UNDISPATCHED) { checkNotNull(target).upload(uri,name) }
                            }
                            pending.await()
                            boundary.mutate { Unit }
                        } },
                        publication = { action -> boundary.synchronous { tasks.publishNow(task,action) } },
                        localRefresh = { parsed,chapters -> boundary.synchronous { tasks.refreshLocalNow(task,parsed,chapters) } },
                    )
                    try {
                        withContext(FileDocWriteContext(writes)) { engine.export(book,config.section,config.chunk) }
                    } finally { sidecars?.close() }
                }
            }
            boundary.mutate {
                tasks.progressNow(task,book.totalChapterNum,book.totalChapterNum)
                tasks.finish(task)
            }
        } catch(cancelled: CancellationException) {
            interruptIfReady(task)
            throw cancelled
        } catch(error: Exception) {
            try { boundary.mutate { tasks.finish(task,error.localizedMessage ?: "Export failed") } }
            catch (cancelled: CancellationException) { interruptIfReady(task); throw cancelled }
            throw error
        }
    }

    private suspend fun interruptIfReady(task: BookTransferTask) = withContext(NonCancellable) {
        try { backupRestoreMutex.withLock { tasks.interrupt(task) } }
        catch (_: RestoreRecoveryRequiredException) { /* Preserve unresolved restore state. */ }
    }
}
