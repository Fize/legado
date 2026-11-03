package io.legado.app.ui.compose.component

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

sealed interface LegadoImageState {
    data object Loading : LegadoImageState
    data object Error : LegadoImageState
    data class Success(val bitmap: ImageBitmap) : LegadoImageState
}

/**
 * Stable Compose bridge for the existing authenticated Glide request pipeline.
 *
 * The submitted target remains attached while the caller is in composition so Glide cannot
 * recycle its bitmap while Compose is drawing it. Leaving composition cancels the wait and
 * releases the target.
 */
@Composable
fun rememberLegadoImageState(
    requestKey: Any?,
    retryKey: Any? = Unit,
    request: (Context) -> RequestBuilder<Drawable>,
): State<LegadoImageState> {
    val context = LocalContext.current
    return androidx.compose.runtime.key(requestKey, retryKey) { produceState<LegadoImageState>(LegadoImageState.Loading) {
        val manager = Glide.with(context)
        val target = request(context).submit()
        try {
            val drawable = withContext(IO) { runInterruptible { target.get() } }
            value = LegadoImageState.Success(drawable.toBitmap().asImageBitmap())
            awaitCancellation()
        } catch (throwable: Throwable) {
            currentCoroutineContext().ensureActive()
            value = LegadoImageState.Error
        } finally {
            manager.clear(target)
        }
    } }
}
