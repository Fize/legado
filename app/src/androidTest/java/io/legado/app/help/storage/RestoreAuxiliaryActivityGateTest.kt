package io.legado.app.help.storage

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.permission.*
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.edit.BookSourceEditViewModel
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.browser.WebViewModel
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.HandleFileViewModel
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class RecoveryPermissionCallback : OnRequestPermissionsResultCallback {
    val results = AtomicInteger()
    val settings = AtomicInteger()
    val errors = AtomicInteger()
    val settingReturned = CountDownLatch(1)
    override fun onRequestPermissionsResult(permissions: Array<String>, grantResults: IntArray) { results.incrementAndGet() }
    override fun onSettingActivityResult() { settings.incrementAndGet(); settingReturned.countDown() }
    override fun onError(e: Exception) { errors.incrementAndGet() }
}

@RunWith(AndroidJUnit4::class)
class RestoreAuxiliaryActivityGateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "auxiliary-recovery-$id")
    private val preferences = context.defaultSharedPreferences
    private val original = HashMap(preferences.all)
    private val journal = File(context.filesDir, "restore-settings-v1.bin")

    @Before fun setup() = runBlocking {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        check(root.mkdirs())
        backupRestoreMutex.recover {
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            transaction.editDefault().putString("auxiliary-$id", "pending").apply()
            RestoreRecoveryGate.requireRecovery()
        }
    }

    @After fun cleanup() = runBlocking {
        finishRecovery()
        backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true)
            assertEquals(original, preferences.all)
            root.deleteRecursively()
            RestoreRecoveryGate.onStartupReady()
        }
    }

    @Test fun detailsSkipContentAndRedirectToRecovery() = nativeEntry(BookInfoActivity::class.java)
    @Test fun sourceEditorSkipsContentAndRedirectsToRecovery() = nativeEntry(BookSourceEditActivity::class.java)
    @Test fun sourceManagerSkipsContentAndRedirectsToRecovery() = nativeEntry(BookSourceActivity::class.java)
    @Test fun codeEditorSkipsContentAndRedirectsToRecovery() = nativeEntry(CodeEditActivity::class.java)
    @Test fun browserSkipsContentAndRedirectsToRecovery() = nativeEntry(WebViewActivity::class.java)
    @Test fun filePickerSkipsContentAndRedirectsToRecovery() = nativeEntry(HandleFileActivity::class.java)

    @Test fun permissionPageRejectsItsOwnRequestOnceWithoutGrantingOrAskingTheSystem() {
        val previous = RequestPlugins.sRequestCallback
        val callback = RecoveryPermissionCallback()
        RequestPlugins.sRequestCallback = callback
        try {
            nativeEntry(PermissionActivity::class.java)
            assertEquals(1, callback.errors.get())
            assertEquals(0, callback.results.get()); assertEquals(0, callback.settings.get())
        } finally { RequestPlugins.sRequestCallback = previous }
    }

    private fun nativeEntry(type: Class<out Activity>) = runBlocking {
        val application = context.applicationContext as Application
        val observed = AtomicReference<Activity>()
        val children = AtomicInteger(-1)
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass == type) {
                    observed.set(activity)
                    children.set(activity.findViewById<ViewGroup>(android.R.id.content)?.childCount ?: 0)
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        val before = journal.readBytes()
        application.registerActivityLifecycleCallbacks(callback)
        try {
            context.startActivity(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { observed.get()?.isDestroyed == true && recoveryVisible() }
            assertEquals(0, children.get())
            assertArrayEquals(before, journal.readBytes())
            assertTrue(RestoreRecoveryGate.isQuarantined)
        } finally {
            application.unregisterActivityLifecycleCallbacks(callback)
            finishRecovery()
        }
    }

    @Test fun alreadyGrantedPermissionsDoNotStartBusinessCallbacksDuringRecovery() {
        val done = CountDownLatch(1)
        val granted = AtomicInteger(); val denied = AtomicInteger()
        val error = AtomicReference<Exception>()
        instrumentation.runOnMainSync {
            PermissionsCompat.Builder().addPermissions(android.Manifest.permission.INTERNET)
                .onGranted { granted.incrementAndGet() }.onDenied { denied.incrementAndGet() }
                .onError { error.set(it); done.countDown() }.request()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        assertTrue(error.get() is RestoreRecoveryRequiredException)
        assertEquals(0, granted.get()); assertEquals(0, denied.get())
    }

    @Test fun latePermissionResultsCannotPublishGrantedCallbacks() {
        val granted = AtomicInteger(); val errors = AtomicInteger()
        val request = Request()
        request.addPermissions(android.Manifest.permission.INTERNET)
        request.setOnGrantedCallback(object : OnPermissionsGrantedCallback {
            override fun onPermissionsGranted() { granted.incrementAndGet() }
        })
        request.setOnErrorCallBack(object : OnErrorCallback {
            override fun onError(e: Exception) { assertTrue(e is RestoreRecoveryRequiredException); errors.incrementAndGet() }
        })
        instrumentation.runOnMainSync {
            request.onRequestPermissionsResult(arrayOf(android.Manifest.permission.INTERNET), intArrayOf(0))
            request.onSettingActivityResult()
        }
        assertEquals(0, granted.get()); assertEquals(2, errors.get())
    }

    @Test fun aThrowingErrorHandlerDoesNotStrandTheNextPermissionRequest() {
        val first = AtomicInteger(); val second = CountDownLatch(1)
        instrumentation.runOnMainSync {
            PermissionsCompat.Builder().addPermissions(android.Manifest.permission.INTERNET).onError {
                first.incrementAndGet(); throw IllegalStateException("owned error callback")
            }.request()
            PermissionsCompat.Builder().addPermissions(android.Manifest.permission.INTERNET)
                .onError { second.countDown() }.request()
        }
        assertTrue(second.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        assertEquals(1, first.get())
    }

    @Test fun anOldPermissionPageCannotCancelAReplacementRequest() = runBlocking {
        val previous = RequestPlugins.sRequestCallback
        val first = RecoveryPermissionCallback(); val replacement = RecoveryPermissionCallback()
        var scenario: ActivityScenario<PermissionActivity>? = null
        try {
            backupRestoreMutex.recover {
                RestoreRecoveryGate.onStartupReady()
                RequestPlugins.sRequestCallback = first
                scenario = ActivityScenario.launch(permissionIntent())
                scenario!!.onActivity {
                    RequestPlugins.sRequestCallback = replacement
                    RestoreRecoveryGate.requireRecovery()
                }
            }
            await { recoveryVisible() }
            instrumentation.waitForIdleSync()
            assertSame(replacement, RequestPlugins.sRequestCallback)
            assertEquals(0, replacement.errors.get()); assertEquals(0, replacement.settings.get())
        } finally {
            RestoreRecoveryGate.requireRecovery()
            scenario?.close()
            RequestPlugins.sRequestCallback = previous
        }
    }

    @Test fun sourceSaveCannotReplaceAnExistingSourceDuringRecovery() = runBlocking {
        val source = BookSource("https://source-$id.invalid", "Original $id")
        var model: BookSourceEditViewModel? = null
        appDb.bookSourceDao.insert(source)
        try {
            instrumentation.runOnMainSync {
                val created = BookSourceEditViewModel(context.applicationContext as Application, SavedStateHandle())
                model = created
                created.save(source.copy(bookSourceName = "Changed $id"))
            }
            awaitModel(requireNotNull(model))
            assertEquals(source.bookSourceName, appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceName)
        } finally {
            model?.viewModelScope?.cancel()
            appDb.bookSourceDao.delete(source)
        }
    }

    @Test fun localExportCannotOverwriteAnExistingFileDuringRecovery() = runBlocking {
        val directory = File(root, "model-export").apply { mkdirs() }
        val target = File(directory, "owned.txt").apply { writeText("old") }
        var model: HandleFileViewModel? = null
        val published = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                val created = HandleFileViewModel(context.applicationContext as Application)
                model = created
                created.saveToLocal(Uri.fromFile(directory), target.name, "new") { published.incrementAndGet() }
            }
            awaitModel(requireNotNull(model))
            assertEquals("old", target.readText()); assertEquals(0, published.get())
        } finally { model?.viewModelScope?.cancel() }
    }

    @Test fun aLatePickerResultCannotOverwriteItsTargetOrConsumeThePendingExport() = runBlocking {
        val directory = File(root, "picker-export").apply { mkdirs() }
        val target = File(directory, "owned.txt").apply { writeText("old") }
        val key = IntentData.put("auxiliary-$id", "new")
        var scenario: ActivityScenario<HandleFileActivity>? = null
        lateinit var host: HandleFileActivity
        try {
            backupRestoreMutex.recover {
                RestoreRecoveryGate.onStartupReady()
                scenario = ActivityScenario.launch(exportIntent(context, key, target.name))
                scenario!!.onActivity {
                    host = it
                    RestoreRecoveryGate.requireRecovery()
                    it.onResult(Intent().setData(Uri.fromFile(directory)))
                }
            }
            // Recovery may close the old caller's task; do not wait for that caller's activity result.
            await { host.isDestroyed && recoveryVisible() }
            assertEquals("old", target.readText())
            assertEquals("new", IntentData.get<String>(key))
        } finally {
            RestoreRecoveryGate.requireRecovery()
            scenario?.close()
            IntentData.get<Any>(key)
        }
    }

    @Test fun aLateWebPageCannotReplaceCookiesOrPublishVerificationSuccess() = runBlocking {
        val url = "https://browser-$id.invalid/"
        val domain = NetworkUtils.getSubDomain(url)
        assertTrue(domain.contains(id)); assertNull(appDb.cookieDao.get(domain))
        var scenario: ActivityScenario<WebViewActivity>? = null
        lateinit var host: WebViewActivity
        var web: WebView? = null
        lateinit var model: WebViewModel
        val published = AtomicInteger()
        try {
            backupRestoreMutex.recover {
                RestoreRecoveryGate.onStartupReady()
                scenario = ActivityScenario.launch(Intent(context, WebViewActivity::class.java)
                    .putExtra("url", url).putExtra("html", "<html><body>Owned recovery fixture</body></html>"))
                await {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<WebViewActivity>().firstOrNull()?.let {
                            host = it
                            web = findWeb(it.window.decorView)
                        }
                    web != null
                }
                val cookieSet = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    android.webkit.CookieManager.getInstance().setCookie(url, "recovery=new-$id") { cookieSet.countDown() }
                }
                assertTrue(cookieSet.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    model = ViewModelProvider(host)[WebViewModel::class.java]
                    CookieStore.setCookie(url, "recovery=old-$id")
                    RestoreRecoveryGate.requireRecovery()
                    host.CustomWebViewClient().onPageFinished(requireNotNull(web), url)
                    model.saveVerificationResult(requireNotNull(web)) { published.incrementAndGet() }
                }
            }
            await { host.isDestroyed && recoveryVisible() }
            assertEquals("recovery=old-$id", appDb.cookieDao.get(domain)!!.cookie)
            assertEquals(0, published.get())
        } finally {
            RestoreRecoveryGate.requireRecovery()
            scenario?.close()
            CookieStore.removeCookie(url)
        }
    }

    private fun permissionIntent() = Intent(context, PermissionActivity::class.java)
        .putExtra(PermissionActivity.KEY_INPUT_PERMISSIONS, arrayOf("owned.permission.$id"))
        .putExtra(PermissionActivity.KEY_RATIONALE, "Owned permission fixture")

    private suspend fun awaitModel(model: ViewModel) {
        withTimeout(10_000) { model.viewModelScope.coroutineContext[Job]?.children?.toList()?.joinAll() }
        instrumentation.waitForIdleSync()
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) {
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) break
            delay(25)
        }
    }

    private fun recoveryVisible() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .any { it is RestoreRecoveryActivity }

    private fun finishRecovery() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<RestoreRecoveryActivity>().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun findWeb(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }
}

internal fun exportIntent(context: android.content.Context, key: String, name: String) =
    Intent(context, HandleFileActivity::class.java).putExtra("mode", HandleFileContract.EXPORT)
        .putExtra("fileName", name).putExtra("fileKey", key).putExtra("contentType", "text/plain")
