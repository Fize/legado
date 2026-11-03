package io.legado.app.help.storage

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import kotlinx.coroutines.launch

/** Opted-in native hosts must skip both content creation and persistence on a blocked launch. */
internal class RestoreActivityRecovery(
    private val activity: ComponentActivity,
    private val finishWithoutSaving: () -> Unit,
) {
    private var redirected = false
    val isBlocked get() = redirected || RestoreRecoveryGate.isQuarantined

    fun redirectIfRequired(): Boolean {
        if (!isBlocked) return false
        if (!redirected) {
            redirected = true
            activity.startActivity(RestoreRecoveryActivity.intent(activity))
            // Calling the host's overridden finish() could save stale reader state.
            finishWithoutSaving()
        }
        return true
    }

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RestoreRecoveryGate.status.collect { if (it != RestoreRecoveryStatus.Ready) redirectIfRequired() }
            }
        }
    }
}
