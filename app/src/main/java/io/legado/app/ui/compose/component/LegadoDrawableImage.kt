package io.legado.app.ui.compose.component

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal sealed interface LegadoDrawableImageState {
    data object Loading : LegadoDrawableImageState
    data object Error : LegadoDrawableImageState
    data class Ready(val drawable: Drawable) : LegadoDrawableImageState
}

@Composable
internal fun rememberLegadoDrawableImageState(key: Any?, retry: Int, request: (Context) -> RequestBuilder<Drawable>): State<LegadoDrawableImageState> {
    val context = LocalContext.current
    return androidx.compose.runtime.key(key, retry) { produceState<LegadoDrawableImageState>(LegadoDrawableImageState.Loading) {
        value = LegadoDrawableImageState.Loading
        val manager = Glide.with(context)
        val target = request(context).submit()
        try {
            value = LegadoDrawableImageState.Ready(withContext(IO) { runInterruptible { target.get() } })
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = LegadoDrawableImageState.Error }
        finally { manager.clear(target) }
    } }
}

/** Draws static, vector and animated image decoders on Compose's canvas without an ImageView. */
internal class LegadoDrawablePainter(private val drawable: Drawable) : Painter(), Drawable.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private var frame by mutableIntStateOf(0)
    override val intrinsicSize get() = Size(drawable.intrinsicWidth.coerceAtLeast(1).toFloat(), drawable.intrinsicHeight.coerceAtLeast(1).toFloat())
    fun start() { drawable.callback = this; (drawable as? Animatable)?.start() }
    fun stop() { (drawable as? Animatable)?.stop(); drawable.callback = null; handler.removeCallbacksAndMessages(this) }
    override fun invalidateDrawable(who: Drawable) { frame++ }
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, this, `when`) }
    override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what, this) }
    override fun DrawScope.onDraw() {
        frame
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

@Composable
internal fun rememberLegadoDrawablePainter(drawable: Drawable): Painter {
    val painter = remember(drawable) { LegadoDrawablePainter(drawable) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(painter, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) painter.start()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) painter.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); painter.stop() }
    }
    return painter
}
