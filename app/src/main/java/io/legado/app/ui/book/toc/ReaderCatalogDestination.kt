package io.legado.app.ui.book.toc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.repository.ReaderSessionRepository
import io.legado.app.model.localBook.PdfFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderCatalogReturnViewModel @Inject constructor(private val repository: ReaderSessionRepository) : ViewModel() {
    private val _completed = MutableStateFlow(false)
    val completed = _completed.asStateFlow()
    private var requestId: String? = null
    private var bookUrl: String? = null
    private var observer: Job? = null
    private var delivery: Job? = null

    fun initialize(id: String, book: String) {
        if (requestId == id && bookUrl == book) return
        requestId = id; bookUrl = book
        observer?.cancel()
        observer = viewModelScope.launch {
            repository.observeCatalogRequest(id).collect { request ->
                _completed.value = request == null || request.bookUrl != book || request.consumed || request.chapterIndex != null
            }
        }
    }

    fun select(selection: BookTocSelection) {
        if (_completed.value || delivery?.isActive == true) return
        val id = requestId ?: return
        val book = bookUrl ?: return
        delivery = viewModelScope.launch {
            val page = selection.pdfPageIndex?.takeIf { it >= 0 }?.rem(PdfFile.PAGE_SIZE) ?: selection.chapterPosition
            try {
                repository.deliverCatalog(id, book, selection.chapterIndex, page)
                _completed.value = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { io.legado.app.constant.AppLog.put("目录选择保存失败\n$error", error, true) }
        }
    }
}

@Composable
fun ReaderCatalogDestination(bookUrl: String, requestId: String, onBack: () -> Unit,
    onOpenLogs: () -> Unit, onOpenRuleManager: () -> Unit,
    controller: ReaderCatalogReturnViewModel = viewModel()) {
    val completed by controller.completed.collectAsStateWithLifecycle()
    LaunchedEffect(requestId, bookUrl) { controller.initialize(requestId, bookUrl) }
    LaunchedEffect(completed) { if (completed) onBack() }
    BookTocDestination(bookUrl, onBack = onBack, onOpenLogs = onOpenLogs,
        onOpenRuleManager = onOpenRuleManager, onSelect = controller::select)
}
