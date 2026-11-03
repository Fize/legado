package io.legado.app.ui.video

import android.app.Application
import android.content.Intent
import io.legado.app.constant.BookType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.read.PlatformMediaReturnSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.legado.app.model.VideoPlay
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.toastOnUi

data class VideoCoverUiState(val path: String?, val name: String, val author: String, val sourceOrigin: String?)

class VideoPlayerViewModel(application: Application) : BaseViewModel(application) {
    private val platformReturn = PlatformMediaReturnSession()
    val handoffStopped = platformReturn.stopEvents
    private val initializationSemaphore = Semaphore(1)
    private var initialization: Coroutine<Intent>? = null

    private var inputGeneration = 0L
    fun receiveHandoff(intent: Intent) {
        initialization?.cancel()
        inputGeneration = platformReturn.receiveHandoff(intent)
    }
    fun needsPreparation(intent: Intent) = platformReturn.needsPreparation(intent)

    fun preparePlayer(intent: Intent, ready: (Intent) -> Unit, failed: () -> Unit) {
        initialization?.cancel()
        val generation = inputGeneration
        initialization = execute(semaphore = initializationSemaphore) {
            val prepared = platformReturn.prepare(intent, BookType.video, generation)
            currentCoroutineContext().ensureActive()
            Intent(intent).apply {
                prepared?.book?.let { book ->
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("inBookshelf", !book.isNotShelf)
                }
            }
        }.onSuccess {
            if (generation != inputGeneration) return@onSuccess
            if (platformReturn.handoffStopped) failed() else ready(it)
        }.onError {
            if (generation != inputGeneration) return@onError
            if (!platformReturn.handoffStopped) AppLog.put("视频播放初始化失败", it)
            failed()
        }
    }

    fun retireIncomingHandoff() = platformReturn.retireIncomingHandoff()

    fun finishPlatformReturn() = platformReturn.close()

    fun handoffTo(intent: Intent, launch: (Intent) -> Unit): Boolean =
        runCatching { platformReturn.handoffTo(intent, launch) }
            .onFailure { AppLog.put("切换视频窗口失败", it, toast = true) }.isSuccess

    private val _cover = MutableStateFlow<VideoCoverUiState?>(null)
    val cover = _cover.asStateFlow()
    fun updateCover(book: Book?) {
        _cover.value = book?.let { VideoCoverUiState(it.getDisplayCover(), it.name, it.author, it.getCoverSourceOrigin()) }
    }

    val upStarMenuData = MutableLiveData<Boolean>()
    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            VideoPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun upSource(success: (() -> Unit)? = null) {
        when (val source = VideoPlay.source) {
            is BookSource -> {
                VideoPlay.source = appDb.bookSourceDao.getBookSource(source.getKey())?.also {
                    success?.invoke()
                }
            }
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = VideoPlay.source ?: return
        val book = VideoPlay.book ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.getTag()}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }
}
