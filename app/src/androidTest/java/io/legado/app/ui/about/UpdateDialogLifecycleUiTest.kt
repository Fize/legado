package io.legado.app.ui.about

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.ui.widget.text.ScrollTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpdateDialogLifecycleUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun postedRenderRunsForLiveAttachedViewAndSkipsDetachedView() {
        val calls = AtomicInteger()
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val detached = View(activity)
                root.addView(detached, FrameLayout.LayoutParams(1, 1))
                detached.postForLiveHost(activity) { calls.incrementAndGet() }
                root.removeView(detached)
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, calls.get())
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val live = View(activity)
                root.addView(live, FrameLayout.LayoutParams(1, 1))
                live.postForLiveHost(activity) { calls.incrementAndGet() }
            }
            instrumentation.waitForIdleSync()
            assertEquals(1, calls.get())
        }
    }

    @Test fun updateMarkdownRemainsVisibleAfterHostRecreation() {
        val marker = "Owned update lifecycle sample"
        val info = AppUpdate.UpdateInfo("fixture-version", "# $marker", "https://fixture.invalid/update", "fixture.apk")
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> UpdateDialog(info).showNow(activity.supportFragmentManager, "owned-update-dialog") }
            fun rendered(): Boolean {
                var found = false
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("owned-update-dialog") as? UpdateDialog
                    fun visit(view: View?): Boolean {
                        if (view is ScrollTextView && view.text.toString().contains(marker)) return true
                        return view is ViewGroup && (0 until view.childCount).any { visit(view.getChildAt(it)) }
                    }
                    found = visit(fragment?.dialog?.window?.decorView)
                }
                return found
            }
            fun awaitRendered() {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!rendered() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                assertTrue("Update text was not rendered in the live dialog", rendered())
            }
            awaitRendered()
            scenario.recreate()
            awaitRendered()
        }
    }
}
