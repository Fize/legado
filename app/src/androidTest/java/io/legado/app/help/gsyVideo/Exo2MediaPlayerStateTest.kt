package io.legado.app.help.gsyVideo

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Exo2MediaPlayerStateTest {
    @Test
    fun playIntentReasonsDoNotPretendThePlayerIsReadyOrEnded() {
        val player = Exo2MediaPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
        var prepared = 0
        var completed = 0
        player.setOnPreparedListener { prepared++ }
        player.setOnCompletionListener { completed++ }

        player.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        assertEquals(0, prepared)
        player.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(1, prepared)
        player.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        assertEquals(0, completed)
        player.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, completed)
        player.release()
    }
}
