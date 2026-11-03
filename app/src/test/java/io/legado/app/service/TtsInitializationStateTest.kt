package io.legado.app.service

import org.junit.Assert.*
import org.junit.Test
import io.legado.app.service.TtsInitializationState.Completion

class TtsInitializationStateTest {
    @Test fun initializationAloneCannotStartAnUnrequestedReadingSession() {
        val state = TtsInitializationState(); val token = state.begin()
        assertEquals(Completion.ReadyIdle, state.complete(token, true))
    }
    @Test fun playRequestedBeforeInitializationIsDeliveredOnlyWhenReady() {
        val state = TtsInitializationState(); val token = state.begin()
        assertFalse(state.requestPlay())
        assertEquals(Completion.ReadyPlay, state.complete(token, true))
    }
    @Test fun pauseBeforeInitializationPreventsAutomaticResume() {
        val state = TtsInitializationState(); val token = state.begin()
        state.requestPlay(); state.stopPlayback()
        assertEquals(Completion.ReadyIdle, state.complete(token, true))
    }
    @Test fun aClearedEngineCannotConsumeItsLateSuccessOrFailure() {
        val state = TtsInitializationState(); val token = state.begin()
        state.requestPlay(); state.clear()
        assertEquals(Completion.Stale, state.complete(token, true))
        assertEquals(Completion.Stale, state.complete(token, false))
        assertFalse(state.wantsPlayback)
    }
    @Test fun anOldEngineCannotInitializeTheReplacement() {
        val state = TtsInitializationState(); val old = state.begin()
        state.requestPlay(); val current = state.begin()
        assertEquals(Completion.Stale, state.complete(old, true))
        assertEquals(Completion.ReadyPlay, state.complete(current, true))
    }
    @Test fun duplicateCallbacksCannotRestartPlaybackOrReportAnOldFailure() {
        val state = TtsInitializationState(); val token = state.begin()
        state.requestPlay()
        assertEquals(Completion.ReadyPlay, state.complete(token, true))
        state.stopPlayback()
        assertEquals(Completion.Stale, state.complete(token, true))
        assertEquals(Completion.Stale, state.complete(token, false))
    }
    @Test fun failureDoesNotBecomePlaybackReadiness() {
        val state = TtsInitializationState(); val token = state.begin()
        state.requestPlay()
        assertEquals(Completion.Failed, state.complete(token, false))
        assertFalse(state.requestPlay())
    }
    @Test fun aReadyPausedEngineCanResumeWithoutReinitialization() {
        val state = TtsInitializationState(); val token = state.begin()
        state.complete(token, true)
        assertTrue(state.requestPlay())
        state.stopPlayback()
        assertTrue(state.requestPlay())
    }
    @Test fun playbackRequestedAfterClearCannotReviveTheOldEngine() {
        val state = TtsInitializationState(); val old = state.begin()
        state.clear(); assertFalse(state.requestPlay())
        assertEquals(Completion.Stale, state.complete(old, true))
        val current = state.begin()
        assertEquals(Completion.ReadyPlay, state.complete(current, true))
    }
}
