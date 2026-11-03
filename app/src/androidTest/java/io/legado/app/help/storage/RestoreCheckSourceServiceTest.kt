package io.legado.app.help.storage

import android.app.ActivityManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.IntentData
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.service.CheckSourceService
import io.legado.app.ui.AppActivity
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.lang.ref.WeakReference

@RunWith(AndroidJUnit4::class)
class RestoreCheckSourceServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val normalRestore = NormalRestoreFixture(context, token)
    private val prefs = context.defaultSharedPreferences
    private val previousPrefs = listOf(PreferKey.cronet, PreferKey.autoBackup).associateWith { prefs.all[it] }
    private val previousPrivacy = LocalConfig.all["privacyPolicyOk"]
    private val previousConfig = listOf(CheckSource.checkDomain, CheckSource.checkSearch, CheckSource.checkDiscovery,
        CheckSource.checkInfo, CheckSource.checkCategory, CheckSource.checkContent, CheckSource.wSourceComment)
    private val previousTimeout = CheckSource.timeout
    private val release = CountDownLatch(1)
    private val arrived = CountDownLatch(1)
    private val requests = AtomicInteger()
    @Volatile private var hold = false
    private var server: NanoHTTPD? = null
    private var source: BookSource? = null
    private var scenario: ActivityScenario<AppActivity>? = null
    private var session: Long? = null
    private var payload: String? = null
    private var ownsJournal = false

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(Debug.isChecking)
        assertFalse(running())
        prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit()
        CheckSource.checkDomain = false; CheckSource.checkSearch = true; CheckSource.checkDiscovery = false
        CheckSource.checkInfo = false; CheckSource.checkCategory = false; CheckSource.checkContent = false
        CheckSource.wSourceComment = false; CheckSource.timeout = 20000
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet(); arrived.countDown()
                if (hold) check(release.await(15, TimeUnit.SECONDS)) { "Owned response was not released" }
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                    "<article><h2>Owned $token</h2><a href='/book'>Read</a></article>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val base = "http://127.0.0.1:${server!!.listeningPort}"
        source = BookSource(bookSourceUrl = "$base/$token", bookSourceName = token,
            searchUrl = "$base/search?key={{key}}", respondTime = 4242,
            ruleSearch = SearchRule(bookList = "article", name = "h2@text", bookUrl = "a@href"))
        appDb.bookSourceDao.insert(source!!)
        appDb.bookSourceDao.saveCheckStates(BookSourceCheckState(source!!.bookSourceUrl,
            status = BookSourceCheckState.PASSED, checkedAt = 1234, detail = "owned baseline"))
        scenario = ActivityScenario.launch(Intent(context, AppActivity::class.java))
        session = checkNotNull(Debug.tryStartCheckSession())
    }

    @After fun cleanup() = runCleanupSteps(
        {
            release.countDown()
            if (session != null) {
                instrumentation.runOnMainSync { context.stopService(Intent(context, CheckSourceService::class.java)) }
                await { !running() }
            }
        },
        { server?.stop() },
        { normalRestore.cleanup() },
        {
            if (ownsJournal) runBlocking { backupRestoreMutex.recover {
                RestoreFileRecovery.recover(context, cleanup = true)
                RestoreRecoveryGate.onStartupReady()
            } }
        },
        { payload?.let { IntentData.get<Any>(it) }; session?.let { Debug.finishChecking(it) } },
        { source?.let { appDb.bookSourceDao.delete(it) } },
        {
            CheckSource.checkDomain = previousConfig[0]; CheckSource.checkSearch = previousConfig[1]
            CheckSource.checkDiscovery = previousConfig[2]; CheckSource.checkInfo = previousConfig[3]
            CheckSource.checkCategory = previousConfig[4]; CheckSource.checkContent = previousConfig[5]
            CheckSource.wSourceComment = previousConfig[6]; CheckSource.timeout = previousTimeout
            check(prefs.edit().apply { previousPrefs.forEach { (key, value) ->
                if (value == null) remove(key) else putBoolean(key, value as Boolean)
            } }.commit())
            check(LocalConfig.edit().apply {
                if (previousPrivacy == null) remove("privacyPolicyOk") else putBoolean("privacyPolicyOk", previousPrivacy as Boolean)
            }.commit())
        },
        {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<RestoreRecoveryActivity>().forEach(Activity::finish)
            }
            scenario?.close()
        },
    )

    @Test fun quarantinedModelDoesNotResetThePersistedCheckState() {
        val before = state()
        quarantine()
        val failure = runCatching { runBlocking {
            payload = CheckSource.start(context, listOf(selected()), session!!)
        } }.exceptionOrNull()
        Log.i("RestoreCheckWriter", "model before=$before after=${state()} failure=$failure")
        assertEquals(before, state())
        assertTrue("Expected recovery rejection, got $failure", failure is RestoreRecoveryRequiredException)
        assertFalse(Debug.isChecking(session!!))
        assertEquals(0, requests.get())
    }

    @Test fun coldServiceRejectsOwnedPayloadWithoutWritingOrStartingNetwork() {
        val queued = appDb.bookSourceDao.beginCheck(listOf(selected()))
        Debug.prepareCheckSession(session!!, queued.map { it.bookSourceUrl })
        payload = IntentData.put(queued)
        val before = state()
        quarantine()
        context.startForegroundService(Intent(context, CheckSourceService::class.java).apply {
            action = IntentAction.start
            putExtra(CheckSource.EXTRA_SESSION_ID, session!!)
            putExtra(CheckSource.EXTRA_SELECTED_SOURCES_KEY, payload)
        })
        await { !Debug.isChecking(session!!) && !running() }
        SystemClock.sleep(300)
        Log.i("RestoreCheckWriter", "cold before=$before after=${state()} requests=${requests.get()}")
        assertEquals(before, state())
        assertEquals(0, requests.get())
        assertNull(IntentData.get<Any>(payload))
    }

    @Test fun responseReleasedAfterQuarantineCannotCompleteTheCheck() {
        hold = true
        payload = runBlocking { CheckSource.start(context, listOf(selected()), session!!) }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        val before = state()
        assertEquals(BookSourceCheckState.NEEDS_CHECK, before.status)
        quarantine()
        release.countDown()
        await { !Debug.isChecking(session!!) && !running() }
        Log.i("RestoreCheckWriter", "late response before=$before after=${state()} requests=${requests.get()}")
        assertEquals(before, state())
        assertEquals(4242L, appDb.bookSourceDao.getBookSource(source!!.bookSourceUrl)!!.respondTime)
    }

    @Test fun readyServiceChecksTheRealResponseAndPersistsSuccess() {
        val selection = listOf(selected())
        payload = runBlocking { withContext(Dispatchers.Main) { CheckSource.start(context, selection, session!!) } }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        await { !Debug.isChecking(session!!) && !running() }
        val after = state()
        Log.i("RestoreCheckWriter", "ready result=$after requests=${requests.get()}")
        assertEquals(BookSourceCheckState.PASSED, after.status)
        assertTrue(after.checkedAt > 1234)
        assertTrue(after.detail.isEmpty())
        assertTrue(requests.get() > 0)
    }

    @Test fun cancellationWhileWaitingForTheRestoreLockDoesNotResetCheckState() = runBlocking {
        val before = state()
        val selection = listOf(selected())
        var pending: Deferred<String>? = null
        backupRestoreMutex.recover {
            pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                CheckSource.start(context, selection, session!!).also { payload = it }
            }
            assertFalse(pending!!.isCompleted)
            assertTrue(Debug.finishChecking(session!!))
        }
        val failure = runCatching { pending!!.await() }.exceptionOrNull()
        Log.i("RestoreCheckWriter", "cancelled preparation before=$before after=${state()} failure=$failure")
        assertEquals(before, state())
        assertTrue(failure is CancellationException)
        assertEquals(0, requests.get())
    }

    @Test fun workerRecoveryRejectionIsCancellationBeforeTheMainCollectorRuns() {
        hold = true
        payload = runBlocking { CheckSource.start(context, listOf(selected()), session!!) }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        val before = state()
        val reference = AtomicReference<Job>()
        instrumentation.runOnMainSync {
            @Suppress("UNCHECKED_CAST")
            val services = LifecycleHelp::class.java.getDeclaredField("services").apply { isAccessible = true }
                .get(LifecycleHelp) as List<WeakReference<*>>
            val service = services.mapNotNull { it.get() }.filterIsInstance<CheckSourceService>().single()
            reference.set(CheckSourceService::class.java.getDeclaredField("checkJob").apply { isAccessible = true }.get(service) as Job)
        }
        val completed = CountDownLatch(1)
        val cause = AtomicReference<Throwable?>()
        reference.get().invokeOnCompletion { cause.set(it); completed.countDown() }
        instrumentation.runOnMainSync {
            // Emit on IO while Main is occupied, so cancellation by the status collector
            // cannot conceal an unhandled rejection in the actual worker write path.
            runBlocking(Dispatchers.IO) { quarantine() }
            release.countDown()
            assertTrue("Worker did not finish while Main was occupied", completed.await(3, TimeUnit.SECONDS))
            Log.i("RestoreCheckWriter", "worker causes=${generateSequence(cause.get()) { it.cause }.map { it.javaClass.name }.toList()}")
            assertTrue(cause.get() is CancellationException)
            assertTrue(generateSequence(cause.get()) { it.cause }.any { it is RestoreRecoveryRequiredException })
        }
        await { !Debug.isChecking(session!!) && !running() }
        assertEquals(before, state())
    }

    private fun selected(): BookSourcePart = checkNotNull(appDb.bookSourceDao.getBookSourcePart(source!!.bookSourceUrl))

    @Test fun successfulRestoreRejectsTheOldResponseAndAllowsANewCheck() = runBlocking {
        hold = true
        payload = CheckSource.start(context, listOf(selected()), session!!)
        assertTrue(arrived.await(10, TimeUnit.SECONDS)); val before = state()
        normalRestore.restore(mapOf("bookSource.json" to "[]"))
        release.countDown(); await { !Debug.isChecking(session!!) && !running() }
        Log.i("RestoreGeneration", "source before=$before after=${state()}")
        assertEquals(before, state())
        hold = false; session = checkNotNull(Debug.tryStartCheckSession())
        payload = CheckSource.start(context, listOf(selected()), session!!)
        await { !Debug.isChecking(session!!) && !running() }
        assertEquals(BookSourceCheckState.PASSED, state().status); assertTrue(requests.get() >= 2)
    }

    @Test fun preparationQueuedBehindARealRestoreDoesNotResetTheCheckState() = runBlocking {
        val before = state(); val selection = listOf(selected())
        var restored: Deferred<Unit>? = null; var prepared: Deferred<String>? = null
        backupRestoreMutex.recover {
            restored = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                normalRestore.restore(mapOf("bookSource.json" to "[]"))
            }
            prepared = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                CheckSource.start(context, selection, session!!).also { payload = it }
            }
            assertFalse(restored!!.isCompleted); assertFalse(prepared!!.isCompleted)
        }
        restored!!.await(); val failure = runCatching { prepared!!.await() }.exceptionOrNull()
        Log.i("RestoreGeneration", "queued preparation failure=$failure before=$before after=${state()}")
        assertTrue(failure is CancellationException); assertEquals(before, state()); assertEquals(0, requests.get())
    }

    @Test fun savedUrlPreparationQueuedBehindARealRestoreDoesNotResetTheCheckState() = runBlocking {
        val before = state()
        val generation = restoreWriteGeneration.capture()
        val urls = listOf(source!!.bookSourceUrl)
        var restored: Deferred<Unit>? = null
        var prepared: Deferred<String>? = null
        backupRestoreMutex.recover {
            restored = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                normalRestore.restore(mapOf("bookSource.json" to "[]"))
            }
            prepared = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                CheckSource.startSelected(context, urls, session!!, generation).also { payload = it }
            }
            assertFalse(restored!!.isCompleted)
            assertFalse(prepared!!.isCompleted)
        }
        restored!!.await()
        val failure = runCatching { prepared!!.await() }.exceptionOrNull()
        assertTrue("Saved URL preparation must retain the pre-restore generation", failure is CancellationException)
        assertEquals(before, state())
        assertEquals(0, requests.get())
        assertFalse(Debug.isChecking(session!!))
    }

    @Test fun savedUrlPreparationChecksOnlyRequestedExistingSources() = runBlocking {
        val untouched = source!!.copy(bookSourceUrl = source!!.bookSourceUrl + "-untouched",
            bookSourceName = "untouched-$token", customOrder = appDb.bookSourceDao.maxOrder + 1)
        val missing = source!!.bookSourceUrl + "-deleted"
        appDb.bookSourceDao.insert(untouched)
        appDb.bookSourceDao.saveCheckStates(BookSourceCheckState(untouched.bookSourceUrl,
            status = BookSourceCheckState.PASSED, checkedAt = 9876, detail = "must remain untouched"))
        val beforeSource = GSON.toJson(appDb.bookSourceDao.getBookSource(untouched.bookSourceUrl))
        val beforeState = appDb.bookSourceDao.getCheckState(untouched.bookSourceUrl)
        try {
            payload = CheckSource.startSelected(context, listOf(source!!.bookSourceUrl, missing),
                session!!, restoreWriteGeneration.capture())
            await { !Debug.isChecking(session!!) && !running() }
            assertEquals(BookSourceCheckState.PASSED, state().status)
            assertEquals(1, requests.get())
            assertEquals(beforeSource, GSON.toJson(appDb.bookSourceDao.getBookSource(untouched.bookSourceUrl)))
            assertEquals(beforeState, appDb.bookSourceDao.getCheckState(untouched.bookSourceUrl))
            assertNull(appDb.bookSourceDao.getBookSource(missing))
            assertNull(appDb.bookSourceDao.getCheckState(missing))
        } finally {
            appDb.bookSourceDao.delete(untouched)
        }
    }
    private fun state(): BookSourceCheckState = checkNotNull(appDb.bookSourceDao.getCheckState(source!!.bookSourceUrl))
    private fun quarantine() = runBlocking { backupRestoreMutex.recover {
        val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
        ownsJournal = true
        check(transaction.editDefault().putString("check-source-$token", "pending").commit())
        RestoreRecoveryGate.requireRecovery()
    } }
    @Suppress("DEPRECATION")
    private fun running(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == CheckSourceService::class.java.name }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < until) { "Owned check service did not reach its expected state" }; SystemClock.sleep(30) }
    }
}
