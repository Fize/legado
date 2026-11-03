package io.legado.app.help.storage

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.DownloadManager
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.Download
import io.legado.app.service.DownloadService
import io.legado.app.ui.AppActivity
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real system downloads. The controller restores legacy storage permission after instrumentation exits. */
@RunWith(AndroidJUnit4::class)
class RestorePublicDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val token = UUID.randomUUID().toString()
    private val normalRestore = NormalRestoreFixture(context, token)
    private val prefs = context.defaultSharedPreferences
    private val autoBackup = prefs.all[PreferKey.autoBackup]
    private val privacy = LocalConfig.all["privacyPolicyOk"]
    private val release = CountDownLatch(1)
    private val arrived = CountDownLatch(1)
    private var server: NanoHTTPD? = null
    private var scenario: ActivityScenario<AppActivity>? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private var ownsJournal = false
    private var beforeIds = emptySet<Long>()
    @Volatile private var hold = false
    private val base get() = "http://127.0.0.1:${server!!.listeningPort}/public/$token/"
    private fun url(name: String = "first") = "$base$name.bin"
    private fun filename(name: String = "first") = "compat-public-$token-$name.bin"
    private fun bytes(name: String = "first") = "Owned public download $token $name\n".repeat(256).toByteArray()

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(running())
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        assertTrue("Real validated system network is required", network != null &&
            network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        if (Build.VERSION.SDK_INT <= 28) assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        beforeIds = records().keys
        check(prefs.edit().putBoolean(PreferKey.autoBackup, false).commit())
        check(LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit())
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                arrived.countDown()
                if (hold && !release.await(40, TimeUnit.SECONDS))
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                val name = session.uri.substringAfterLast('/').removeSuffix(".bin")
                val payload = bytes(name)
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", payload.inputStream(), payload.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        scenario = ActivityScenario.launch(Intent(context, AppActivity::class.java))
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("content"); addDataType("*/*") }
        monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
    }

    @After fun cleanup() = runCleanupSteps(
        { release.countDown(); context.stopService(Intent(context, DownloadService::class.java)); await { !running() } },
        { monitor?.let(instrumentation::removeMonitor); server?.stop() },
        { normalRestore.cleanup() },
        { if (ownsJournal) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true); RestoreRecoveryGate.onStartupReady()
        } } },
        {
            val owned = records().filterValues { it.first.startsWith(base) }.keys
            assertTrue(owned.none { it in beforeIds })
            if (owned.isNotEmpty()) manager.remove(*owned.toLongArray())
            assertTrue(records().values.none { it.first.startsWith(base) })
            assertTrue(records().keys.containsAll(beforeIds))
        },
        {
            check(prefs.edit().apply {
                if (autoBackup == null) remove(PreferKey.autoBackup) else putBoolean(PreferKey.autoBackup, autoBackup as Boolean)
            }.commit())
            check(LocalConfig.edit().apply {
                if (privacy == null) remove("privacyPolicyOk") else putBoolean("privacyPolicyOk", privacy as Boolean)
            }.commit())
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<RestoreRecoveryActivity>().forEach(Activity::finish)
            }
            scenario?.close()
        },
    )

    @Test fun quarantinedModelDoesNotEnqueueAFile() {
        quarantine(); Download.start(context, url(), filename()); settle()
        assertTrue(owned().isEmpty()); assertEquals(0, monitor!!.hits)
    }

    @Test fun coldForegroundStartDoesNotEnqueueAFile() {
        quarantine(); context.startForegroundService(startIntent()); settle()
        assertTrue(owned().isEmpty()); assertFalse(running())
    }

    @Test fun requestWaitingBehindRestoreDoesNotEnqueue() = runBlocking {
        var restored: Deferred<Unit>? = null
        backupRestoreMutex.recover {
            restored = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { restore() }
            Download.start(context, url(), filename())
            instrumentation.waitForIdleSync(); SystemClock.sleep(250)
            assertTrue(owned().isEmpty())
        }
        restored!!.await(); settle(); assertTrue(owned().isEmpty())
    }

    @Test fun acceptedSystemDownloadSurvivesQuarantineWithoutOpening() {
        hold = true; Download.start(context, url(), filename()); assertTrue(arrived.await(15, TimeUnit.SECONDS))
        val id = owned().keys.single(); quarantine(); await { !running() }
        assertTrue(records().containsKey(id)); release.countDown(); completed(id)
        settle(); assertEquals(0, monitor!!.hits); assertPayload(id)
    }

    @Test fun successfulRestoreSuppressesOldCompletionAndAllowsFreshDownload() = runBlocking {
        hold = true; Download.start(context, url(), filename()); assertTrue(arrived.await(15, TimeUnit.SECONDS))
        val id = owned().keys.single(); restore(); release.countDown(); completed(id); settle()
        assertEquals(0, monitor!!.hits); assertPayload(id)
        hold = false; Download.start(context, url("fresh"), filename("fresh"))
        await { owned().size == 2 }; val fresh = owned().keys.single { it != id }; completed(fresh)
        await { monitor!!.hits == 1 }; assertPayload(fresh, "fresh")
    }

    @Test fun readyDownloadOpensTheExactCompletedFile() {
        Download.start(context, url(), filename()); await { owned().isNotEmpty() }
        val id = owned().keys.single(); completed(id); await { monitor!!.hits == 1 }; assertPayload(id)
    }

    @Test fun oldVersionedOpenIntentCannotOpenAfterRestore() = runBlocking {
        val generation = restoreWriteGeneration.capture()
        Download.start(context, url(), filename()); await { owned().isNotEmpty() }
        val id = owned().keys.single(); completed(id); await { monitor!!.hits == 1 }
        restore()
        context.startService(Intent(context, DownloadService::class.java).apply {
            action = IntentAction.play; putExtra("downloadId", id); putExtra("fileName", filename())
            putExtra("downloadRestoreSequence", generation.sequence); putExtra("downloadRestoreProcess", generation.processId)
        })
        settle(); assertEquals(1, monitor!!.hits); assertPayload(id)
    }

    @Test fun invalidPreflightKeepsTheAcceptedDownloadValid() = runBlocking {
        hold = true; Download.start(context, url(), filename()); assertTrue(arrived.await(15, TimeUnit.SECONDS))
        val id = owned().keys.single()
        assertTrue(runCatching { normalRestore.restore(mapOf("bookshelf.json" to "[{")) }.isFailure)
        release.countDown(); completed(id); await { monitor!!.hits == 1 }; assertPayload(id)
    }

    @Test fun explicitCancelStillRemovesTheCurrentSystemTask() {
        hold = true; Download.start(context, url(), filename()); assertTrue(arrived.await(15, TimeUnit.SECONDS))
        val id = owned().keys.single()
        context.startService(Intent(context, DownloadService::class.java).apply {
            action = IntentAction.stop; putExtra("downloadId", id)
        })
        await { id !in records() }; release.countDown(); settle(); assertEquals(0, monitor!!.hits)
    }

    private fun startIntent() = Intent(context, DownloadService::class.java).apply {
        action = IntentAction.start; putExtra("url", url()); putExtra("fileName", filename())
    }
    private suspend fun restore() = normalRestore.restore(mapOf("bookSource.json" to "[]"))
    private fun quarantine() = runBlocking { backupRestoreMutex.recover {
        val pending = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
        ownsJournal = true
        check(pending.editDefault().putString("public-download-$token", "pending").commit())
        RestoreRecoveryGate.requireRecovery()
    } }
    private fun records(): Map<Long, Pair<String, Int>> = buildMap {
        manager.query(DownloadManager.Query()).use { c -> while (c.moveToNext()) {
            put(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) to
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
        } }
    }
    private fun owned() = records().filterValues { it.first.startsWith(base) }
    private fun completed(id: Long) = await {
        val state = records()[id]?.second
        check(state != DownloadManager.STATUS_FAILED) { "Owned system download failed: $id" }
        state == DownloadManager.STATUS_SUCCESSFUL
    }
    private fun assertPayload(id: Long, name: String = "first") {
        val actual = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { it.readBytes() }
        assertArrayEquals(bytes(name), actual)
    }
    private fun running() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == DownloadService::class.java.name }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(1500) }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (!condition()) { check(SystemClock.uptimeMillis() < until) { "Owned public download did not reach expected state" }; SystemClock.sleep(40) }
    }
}
