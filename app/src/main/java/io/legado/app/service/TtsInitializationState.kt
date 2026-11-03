package io.legado.app.service

/** Per-engine initialization and playback intent, independent of Android callbacks. */
internal class TtsInitializationState {
    enum class Completion { Stale, ReadyIdle, ReadyPlay, Failed }
    private var generation = 0L
    private var active = false
    private var completed = false
    private var ready = false
    var wantsPlayback: Boolean = false
        private set

    @Synchronized fun begin(): Long {
        generation++
        active = true; completed = false; ready = false
        return generation
    }
    @Synchronized fun clear() {
        generation++
        active = false; completed = false; ready = false; wantsPlayback = false
    }
    @Synchronized fun requestPlay(): Boolean {
        wantsPlayback = true
        return active && ready
    }
    @Synchronized fun stopPlayback() { wantsPlayback = false }
    @Synchronized fun complete(token: Long, success: Boolean): Completion {
        if (!active || token != generation || completed) return Completion.Stale
        completed = true; ready = success
        return when {
            !success -> Completion.Failed
            wantsPlayback -> Completion.ReadyPlay
            else -> Completion.ReadyIdle
        }
    }
}
