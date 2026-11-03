package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.audio.AudioCachePolicy
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.help.storage.RestoreRecoveryRequiredException
import io.legado.app.help.storage.backupRestoreMutex
import io.legado.app.help.storage.RestoreWriteGeneration
import io.legado.app.help.storage.RestoreWorkInvalidatedException
import io.legado.app.help.storage.restoreWriteGeneration
import io.legado.app.help.book.isAudio
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splitties.systemservices.notificationManager
import java.util.ArrayDeque

class AudioCacheService : BaseService() {
    override val guardsRestoreRecovery = true

    companion object {
        private const val ACTION_CACHE_RANGE = "audioCacheRange"
        private const val EXTRA_BOOK_URL = "bookUrl"
        private const val EXTRA_START_INDEX = "start"
        private const val EXTRA_END_INDEX = "end"
        private const val EXTRA_RESTORE_SEQUENCE = "restoreSequence"
        private const val EXTRA_RESTORE_PROCESS = "restoreProcess"

        fun start(
            context: Context,
            bookUrl: String,
            startIndex: Int,
            endIndex: Int,
        ): Boolean {
            if (bookUrl.isBlank() || startIndex < 0 || endIndex < startIndex) return false
            if (RestoreRecoveryGate.isQuarantined) return false
            val generation = restoreWriteGeneration.capture()
            context.startForegroundServiceCompat(
                Intent(context, AudioCacheService::class.java).apply {
                    action = ACTION_CACHE_RANGE
                    putExtra(EXTRA_BOOK_URL, bookUrl)
                    putExtra(EXTRA_START_INDEX, startIndex)
                    putExtra(EXTRA_END_INDEX, endIndex)
                    putExtra(EXTRA_RESTORE_SEQUENCE, generation.sequence)
                    putExtra(EXTRA_RESTORE_PROCESS, generation.processId)
                }
            )
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCacheService::class.java))
        }
    }

    private data class CacheTask(
        val bookUrl: String,
        val startIndex: Int,
        val endIndex: Int,
        val generation: RestoreWriteGeneration.Token,
    )

    private val queueLock = Any()
    private val taskQueue = ArrayDeque<CacheTask>()
    private var workerJob: Job? = null
    private var acceptedGeneration = restoreWriteGeneration.capture()
    private var latestStartId = 0
    private var bookName = ""
    private var currentBookUrl = ""
    private var doneCount = 0
    private var totalCount = 0
    private var failCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.audio_cache_notification_title))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.stop),
                servicePendingIntent<AudioCacheService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (result == START_NOT_STICKY) return result

        synchronized(queueLock) {
            latestStartId = startId
        }
        when (intent?.action) {
            ACTION_CACHE_RANGE -> enqueue(intent, startId)
            IntentAction.stop -> stopAndClear(startId)
            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelQueuedWork()
        notificationManager.cancel(NotificationId.AudioCacheService)
        super.onDestroy()
    }

    override fun onRestoreRecoveryRequired() {
        cancelQueuedWork()
        acknowledgeRecoveryForegroundStart(NotificationId.AudioCacheService, R.string.restore_recovery_background_stopped)
        super.onRestoreRecoveryRequired()
    }

    private fun cancelQueuedWork() {
        val job = synchronized(queueLock) {
            taskQueue.clear()
            workerJob.also { workerJob = null }
        }
        job?.cancel()
    }

    override fun startForegroundNotification() {
        if (stopIfRestoreRecoveryRequired()) return
        startForeground(NotificationId.AudioCacheService, buildNotification().build())
    }

    private fun enqueue(intent: Intent, startId: Int) {
        val generation = restoreWriteGeneration.capture()
        if (intent.hasExtra(EXTRA_RESTORE_SEQUENCE) &&
            (intent.getLongExtra(EXTRA_RESTORE_SEQUENCE, -1L) != generation.sequence ||
                intent.getStringExtra(EXTRA_RESTORE_PROCESS) != generation.processId)) {
            stopIfIdle(startId)
            return
        }
        val task = CacheTask(
            bookUrl = intent.getStringExtra(EXTRA_BOOK_URL).orEmpty(),
            startIndex = intent.getIntExtra(EXTRA_START_INDEX, -1),
            endIndex = intent.getIntExtra(EXTRA_END_INDEX, -1),
            generation = generation,
        )
        if (task.bookUrl.isBlank() || task.startIndex < 0 || task.endIndex < task.startIndex) {
            stopIfIdle(startId)
            return
        }
        val retired = synchronized(queueLock) {
            val old = if (!acceptedGeneration.isCurrent) {
                taskQueue.clear()
                workerJob.also { workerJob = null }
            } else null
            acceptedGeneration = generation
            taskQueue.addLast(task)
            startWorkerLocked()
            old
        }
        // Publish the replacement worker before cancelling its predecessor: the old
        // finally block must never stop a service which has just accepted fresh work.
        retired?.cancel(RestoreWorkInvalidatedException())
    }

    private fun startWorkerLocked() {
        if (workerJob?.isActive == true) return
        val job = lifecycleScope.launch(IO, start = CoroutineStart.LAZY) {
            drainQueue(currentCoroutineContext()[Job] ?: return@launch)
        }
        workerJob = job
        job.start()
    }

    private suspend fun drainQueue(currentJob: Job) {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val task = synchronized(queueLock) {
                    if (workerJob !== currentJob || taskQueue.isEmpty()) {
                        if (workerJob === currentJob) workerJob = null
                        null
                    } else {
                        taskQueue.removeFirst()
                    }
                } ?: break
                try {
                    processTask(task)
                } catch (error: CancellationException) {
                    throw error
                } catch (required: RestoreRecoveryRequiredException) {
                    throw CancellationException("Audio caching stopped for data recovery").apply { initCause(required) }
                } catch (error: Exception) {
                    AppLog.put("音频缓存任务失败 ${task.bookUrl}\n${error.localizedMessage}", error)
                }
            }
        } finally {
            val stopId = synchronized(queueLock) {
                if (workerJob === currentJob) {
                    taskQueue.removeAll { RestoreRecoveryGate.isQuarantined || !it.generation.isCurrent }
                    workerJob = null
                    if (taskQueue.isNotEmpty()) startWorkerLocked()
                }
                audioCacheIdleStopId(
                    latestStartId = latestStartId,
                    hasWorker = workerJob != null,
                    hasQueuedTask = taskQueue.isNotEmpty(),
                )
            }
            if (stopId != null) stopSelfResult(stopId)
        }
    }

    private suspend fun processTask(task: CacheTask) {
        backupRestoreMutex.withLock {
            currentCoroutineContext().ensureActive()
            task.generation.requireCurrent()
        }
        val treeUri = AppConfig.audioCacheTreeUri
        if (!AudioCacheManager.isCacheDirAvailable(treeUri)) return
        val book = appDb.bookDao.getBook(task.bookUrl) ?: return
        if (!book.isAudio) return
        val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        currentBookUrl = book.bookUrl
        bookName = book.name
        doneCount = 0
        failCount = 0
        totalCount = 0
        updateNotification()
        if (!ensureChapterList(source, book, task.generation)) return
        val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        val range = AudioCachePolicy.normalizeRange(
            task.startIndex,
            task.endIndex,
            chapterCount
        ) ?: return

        totalCount = range.count()
        val cachedKeys = AudioCacheManager.listCachedChapterKeys(treeUri, book.bookUrl).toMutableSet()
        updateNotification()

        for (index in range) {
            currentCoroutineContext().ensureActive()
            task.generation.requireCurrent()
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
            if (chapter != null && !chapter.isVolume) {
                val key = AudioCacheKey.from(chapter)
                if (key !in cachedKeys) {
                    val result = AudioCacheManager.cacheChapter(treeUri, source, book, chapter, task.generation)
                    val error = result.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error == null) {
                        task.generation.requireCurrent()
                        cachedKeys.add(key)
                        postEvent(
                            EventBus.AUDIO_CACHE_CHANGED,
                            AudioCacheStateChanged(book.bookUrl, key, true, treeUri)
                        )
                    } else {
                        failCount++
                    }
                }
            }
            doneCount++
            updateNotification()
        }
    }

    private suspend fun ensureChapterList(source: BookSource, book: Book, generation: RestoreWriteGeneration.Token): Boolean {
        generation.requireCurrent()
        if (appDb.bookChapterDao.getChapterCount(book.bookUrl) > 0) return true
        return try {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
                backupRestoreMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    generation.requireCurrent()
                    appDb.bookDao.updatePreservingReadConfig(book)
                }
            }
            val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
            backupRestoreMutex.withLock {
                currentCoroutineContext().ensureActive()
                generation.requireCurrent()
                appDb.withTransaction {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (required: RestoreRecoveryRequiredException) {
            throw CancellationException("Audio caching stopped for data recovery").apply { initCause(required) }
        } catch (error: Exception) {
            AppLog.put(
                "音频缓存前加载目录失败 ${book.name}\n${error.localizedMessage}",
                error,
                true
            )
            false
        }
    }

    private fun stopAndClear(startId: Int) {
        cancelQueuedWork()
        stopSelfResult(startId)
    }

    private fun stopIfIdle(startId: Int) {
        val idle = synchronized(queueLock) {
            workerJob?.isActive != true && taskQueue.isEmpty()
        }
        if (idle) stopSelfResult(startId)
    }

    private fun updateNotification() {
        if (isRestoreRecoveryBlocked) return
        notificationManager.notify(NotificationId.AudioCacheService, buildNotification().build())
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val contentIntent = currentBookUrl.takeIf { it.isNotBlank() }?.let { bookUrl ->
            activityPendingIntent<AudioPlayActivity>("audioPlay") {
                putExtra("bookUrl", bookUrl)
            }
        }
        notificationBuilder.setContentIntent(contentIntent)
        if (totalCount <= 0) {
            return notificationBuilder
                .setContentText(getString(R.string.service_starting))
                .setProgress(0, 0, true)
        }
        return notificationBuilder
            .setContentText(
                getString(
                    R.string.audio_cache_notification_text,
                    bookName,
                    doneCount,
                    totalCount,
                    failCount
                )
            )
            .setProgress(totalCount, doneCount, false)
    }
}

internal fun audioCacheIdleStopId(
    latestStartId: Int,
    hasWorker: Boolean,
    hasQueuedTask: Boolean,
): Int? = latestStartId.takeIf { !hasWorker && !hasQueuedTask }
