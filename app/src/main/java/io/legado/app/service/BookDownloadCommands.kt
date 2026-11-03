package io.legado.app.service

import io.legado.app.data.repository.BookTransferConfig
import io.legado.app.data.repository.BookTransferRepository
import io.legado.app.data.repository.CacheBookRef
import io.legado.app.help.config.AppConfig
import javax.inject.Inject
import javax.inject.Singleton

/** A stable book reference is captured by the invoking reader before any suspension. */
@Singleton
class BookDownloadCommands @Inject constructor(private val tasks: BookTransferRepository,private val scheduler: BookTransferScheduler) {
    suspend fun start(ref: CacheBookRef,start: Int,end: Int) {
        val ids=tasks.enqueue(listOf(ref),"download",BookTransferConfig(start=start,end=end,threads=AppConfig.threadCount.coerceIn(1,128)))
        scheduler.schedule(ids)
    }
}
