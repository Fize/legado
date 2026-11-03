package io.legado.app.ui.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.data.repository.PhotoRequest
import io.legado.app.data.repository.PhotoRequestRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Offsets are fractions of the viewport, so resizing never restores stale pixel coordinates.
data class PhotoTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)
data class PhotoViewerUiState(val key: String? = null, val request: PhotoRequest? = null, val loading: Boolean = false,
    val failed: Boolean = false, val closing: Boolean = false, val closed: Boolean = false, val retry: Int = 0, val transform: PhotoTransform = PhotoTransform())

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(private val repository: PhotoRequestRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(PhotoViewerUiState(closed = saved["photo.closed"] ?: false, transform = PhotoTransform(saved["photo.scale"] ?: 1f, saved["photo.x"] ?: 0f, saved["photo.y"] ?: 0f)))
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun load(key: String, retry: Boolean = false) {
        if (_state.value.closing || _state.value.closed) return
        if (!retry && _state.value.key == key && (_state.value.request != null || _state.value.loading)) return
        job?.cancel()
        val same = saved.get<String>("photo.key") == key
        saved["photo.key"] = key
        if (!same) { saved["photo.scale"] = 1f; saved["photo.x"] = 0f; saved["photo.y"] = 0f }
        _state.value = _state.value.copy(key = key, request = null, loading = true, failed = false,
            retry = _state.value.retry + if (retry) 1 else 0, transform = if (same) _state.value.transform else PhotoTransform())
        job = viewModelScope.launch {
            try { val request = repository.read(key); if (_state.value.key == key) _state.value = _state.value.copy(request = request, loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (_state.value.key == key) _state.value = _state.value.copy(loading = false, failed = true) }
        }
    }
    fun close(key: String) {
        if (_state.value.closing || _state.value.closed) return
        job?.cancel()
        _state.value = _state.value.copy(closing = true)
        viewModelScope.launch {
            try { repository.delete(key) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing request must not trap the user in this destination. */ }
            saved["photo.closed"] = true
            _state.value = _state.value.copy(closing = false, closed = true)
        }
    }
    fun transform(value: PhotoTransform) {
        if (_state.value.closing || _state.value.closed) return
        val clean = value.copy(scale = value.scale.takeIf(Float::isFinite)?.coerceIn(1f, 5f) ?: 1f,
            x = value.x.takeIf(Float::isFinite)?.coerceIn(-2f, 2f) ?: 0f, y = value.y.takeIf(Float::isFinite)?.coerceIn(-2f, 2f) ?: 0f)
        saved["photo.scale"] = clean.scale; saved["photo.x"] = clean.x; saved["photo.y"] = clean.y
        _state.value = _state.value.copy(transform = clean)
    }
}

internal fun constrainPhotoTransform(value: PhotoTransform, viewportWidth: Float, viewportHeight: Float, imageWidth: Float, imageHeight: Float): PhotoTransform {
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return PhotoTransform()
    val scale = value.scale.takeIf(Float::isFinite)?.coerceIn(1f, 5f) ?: 1f
    val fit = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val x = ((imageWidth * fit * scale - viewportWidth) / (2 * viewportWidth)).coerceAtLeast(0f)
    val y = ((imageHeight * fit * scale - viewportHeight) / (2 * viewportHeight)).coerceAtLeast(0f)
    return PhotoTransform(scale, if (x == 0f) 0f else (value.x.takeIf(Float::isFinite) ?: 0f).coerceIn(-x, x), if (y == 0f) 0f else (value.y.takeIf(Float::isFinite) ?: 0f).coerceIn(-y, y))
}
