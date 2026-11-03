package io.legado.app.ui.video

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFullscreenPendingSeekTest {
    @Test
    fun fullscreenReceivesTheSeekWaitingForPreparation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val player = VideoPlayer(activity)
                activity.addContentView(player, ViewGroup.LayoutParams(600, 400))
                player.setUp("https://example.invalid/video.mp4", false, null, "test")
                player.seekOnStart = 114_304L

                val fullscreen = player.startWindowFullscreen(activity, false, false)
                assertNotNull(fullscreen)
                assertEquals(114_304L, fullscreen!!.seekOnStart)
                assertEquals(0L, player.seekOnStart)
            }
        }
    }
}
