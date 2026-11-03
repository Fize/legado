package io.legado.app.help.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.help.IntentData
import io.legado.app.lib.permission.PermissionActivity
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.permission.RequestPlugins
import io.legado.app.ui.file.HandleFileActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RestoreAuxiliaryActivityUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()

    @Before fun ready() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
    }

    @Test fun healthyFilePickerExportsToItsOwnedDirectoryAndReturnsTheActualFile() {
        val directory = File(context.cacheDir, "healthy-picker-$id").apply { mkdirs() }
        val target = File(directory, "owned.txt")
        val key = IntentData.put("healthy-picker-$id", "owned export $id")
        try {
            ActivityScenario.launchActivityForResult<HandleFileActivity>(exportIntent(context, key, target.name)).use { scenario ->
                scenario.onActivity { it.onResult(Intent().setData(Uri.fromFile(directory))) }
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(Uri.fromFile(target), result.resultData.data)
                assertEquals("owned export $id", target.readText())
            }
        } finally { IntentData.get<Any>(key); directory.deleteRecursively() }
    }

    @Test fun healthyPermissionPageReturnsTheResultToItsOriginalCallback() {
        val previous = RequestPlugins.sRequestCallback
        val callback = RecoveryPermissionCallback()
        RequestPlugins.sRequestCallback = callback
        try {
            ActivityScenario.launch<PermissionActivity>(Intent(context, PermissionActivity::class.java)
                .putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS, arrayOf(android.Manifest.permission.INTERNET))
                .putExtra(PermissionActivity.KEY_RATIONALE, "Owned permission fixture")).use { scenario ->
                var action = ""
                scenario.onActivity { action = it.getString(R.string.dialog_setting) }
                onView(withText(action)).perform(click())
                assertTrue(callback.settingReturned.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(1, callback.settings.get())
                assertEquals(0, callback.errors.get())
            }
        } finally { RequestPlugins.sRequestCallback = previous }
    }

    @Test fun alreadyGrantedPermissionRequestsDrainNormallyInOrder() {
        val calls = CopyOnWriteArrayList<Int>()
        val done = CountDownLatch(2)
        instrumentation.runOnMainSync {
            for (index in listOf(1, 2)) PermissionsCompat.Builder()
                .addPermissions(android.Manifest.permission.INTERNET)
                .onGranted { calls += index; done.countDown() }
                .onError { throw AssertionError("Healthy permission request failed", it) }.request()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        assertEquals(listOf(1, 2), calls.toList())
        assertNull(RequestPlugins.sRequestCallback)
    }

    @Test fun aLateCompletionCannotDiscardTheRequestStartedByItsOwnCallback() {
        val calls = CopyOnWriteArrayList<Int>()
        val done = CountDownLatch(2)
        val original = PermissionsCompat.Builder().addPermissions(android.Manifest.permission.INTERNET)
            .onGranted {
                calls += 1; done.countDown()
                PermissionsCompat.Builder().addPermissions(android.Manifest.permission.INTERNET)
                    .onGranted { calls += 2; done.countDown() }
                    .onError { throw AssertionError("Replacement request failed", it) }.request()
            }.build()
        // Exercise the existing five-second stale-request policy without changing a production clock.
        SystemClock.sleep(5200)
        instrumentation.runOnMainSync { original.request() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        assertEquals(listOf(1, 2), calls.toList())
        assertNull(RequestPlugins.sRequestCallback)
    }
}
