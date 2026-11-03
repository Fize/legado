package io.legado.app.ui.book.read

import android.content.Intent
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookInfoChildPhase
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.ui.navigation.PlatformTextReaderRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class PlatformReaderReturnViewModel @Inject constructor(private val repository: PlatformReaderReturnRepository) : ViewModel() {
    private val observationGeneration = MutableStateFlow(0L)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observe(id: String) = observationGeneration.flatMapLatest {
        repository.observe(id).catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            AppLog.put("读取媒体返回结果失败", error)
            failed = true
        }
    }
    internal var failed by mutableStateOf(false)
        private set
    internal var busy by mutableStateOf(false)
        private set

    internal fun close(bookUrl: String, requestId: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Explicitly leaving a recovered bridge revokes any old Activity callback.
                    repository.closeBridge(requestId, bookUrl)
                }
                failed = false
                // A failed observation completed its flow; restart it after an explicit close succeeds.
                observationGeneration.value += 1
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.put("返回书籍详情失败", error); failed = true
            } finally { busy = false }
        }
    }
}

/** A saved bridge never automatically reopens an external Activity after process restoration. */
@Composable
fun PlatformTextReaderDestination(route: PlatformTextReaderRoute, onBack: () -> Unit,
    model: PlatformReaderReturnViewModel = viewModel()) {
    PlatformReaderDestination(route.bookUrl, route.requestId, ReadBookActivity::class.java, onBack, model)
}

@Composable
fun PlatformReaderDestination(bookUrl: String, requestId: String?, host: Class<out Activity>, onBack: () -> Unit,
    model: PlatformReaderReturnViewModel = viewModel(), requireRequest: Boolean = false, chapterIndex: Int? = null) {
    val context = LocalContext.current
    if (requestId == null) {
        LaunchedEffect(bookUrl, host) {
            if (!requireRequest) {
                runCatching {
                    context.startActivity(Intent(context, host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("bookUrl", bookUrl).apply {
                            chapterIndex?.let { putExtra("chapterIndex", it) }
                        })
                }.onFailure { AppLog.put("打开播放器失败", it) }
            }
            onBack()
        }
        return
    }
    val request = remember(requestId, model) { model.observe(requestId).map { true to it } }
    val observed by request.collectAsStateWithLifecycle(initialValue = false to null)
    val child = observed.second
    var launched by rememberSaveable(requestId) { mutableStateOf(false) }
    var launchFailed by rememberSaveable(requestId) { mutableStateOf(false) }
    val currentBack by rememberUpdatedState(onBack)
    val canOpen = observed.first && child?.phase in setOf(BookInfoChildPhase.Requested, BookInfoChildPhase.Displayed)
    fun open() {
        launched = true
        launchFailed = runCatching {
            context.startActivity(Intent(context, host)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("bookUrl", bookUrl)
                .putExtra(PlatformReaderReturnRepository.REQUEST_ID, requestId))
        }.isFailure
    }
    LaunchedEffect(bookUrl, requestId, canOpen) { if (!launched && canOpen) open() }
    var returned by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(observed.first, child?.phase) {
        if (!returned && observed.first && (child == null || child.phase in setOf(BookInfoChildPhase.Returned,
                BookInfoChildPhase.Cancelled, BookInfoChildPhase.Consumed))) {
            returned = true
            currentBack()
        }
    }
    BackHandler { model.close(bookUrl, requestId) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (launchFailed || model.failed) Text(stringResource(R.string.error))
            if (model.busy) CircularProgressIndicator()
            TextButton(onClick = ::open, enabled = !model.busy && canOpen) { Text(stringResource(R.string.reading)) }
            TextButton(onClick = { model.close(bookUrl, requestId) }, enabled = !model.busy) { Text(stringResource(R.string.back)) }
        }
    }
}
