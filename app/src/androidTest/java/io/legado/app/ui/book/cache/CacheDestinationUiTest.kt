package io.legado.app.ui.book.cache

import android.app.job.JobScheduler
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.preferences.*
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.CacheRoute
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class CacheDestinationUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=UUID.randomUUID().toString()
    private val requests=CopyOnWriteArrayList<String>()
    @Volatile private var downloadGate: java.util.concurrent.CountDownLatch?=null
    private val server=object: NanoHTTPD("127.0.0.1",0) {
        override fun serve(session: IHTTPSession): Response {
            requests.add(session.uri)
            downloadGate?.await(20,java.util.concurrent.TimeUnit.SECONDS)
            return newFixedLengthResponse(Response.Status.OK,"text/html","<html><body>Downloaded fixture ${session.uri}</body></html>")
        }
    }
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var group: BookGroup
    private lateinit var chapters: List<BookChapter>
    private var scenario: ActivityScenario<AppActivity>?=null
    private val folder=File(context.cacheDir,"cache-ui-$id")
    private val preferences by lazy { EntryPointAccessors.fromApplication(context,CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences() }
    private var previous: CacheExportOptions?=null
    private var safTree: Uri?=null
    private var safFolderCreated=false
    private fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Before fun setup()=runBlocking {
        server.start(); folder.mkdirs()
        val groupId=(0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it)==null }
        group=BookGroup(groupId,"Cache fixture $id"); appDb.bookGroupDao.insert(group)
        source=BookSource(bookSourceUrl="http://127.0.0.1:${server.listeningPort}",bookSourceName="Cache fixture $id",ruleContent=ContentRule(content="body@text"))
        book=Book(bookUrl="${source.bookSourceUrl}/book/$id",tocUrl="${source.bookSourceUrl}/toc/$id",origin=source.bookSourceUrl,
            name="Cache fixture $id",author="Author",group=groupId,totalChapterNum=3,durChapterIndex=1,canUpdate=false)
        chapters=(0..2).map { BookChapter(bookUrl=book.bookUrl,url="${source.bookSourceUrl}/chapter/$it",title="Chapter $it",index=it) }
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
        previous=preferences.current()
        preferences.update { CacheExportOptions(directory=folder.path,fileName="'cache-ui-output'") }
    }
    @After fun cleanup()=runBlocking {
        downloadGate?.countDown()
        scenario?.close()
        if(::book.isInitialized) {
            val ids=appDb.bookTransferDao.active("download").plus(appDb.bookTransferDao.active("export")).filter { it.bookUrl==book.bookUrl }.map { it.id }
            EntryPointAccessors.fromApplication(context,BookTransferEntryPoint::class.java).transferScheduler().cancelTasks(ids)
            BookHelp.clearCache(book); appDb.bookDao.delete(book)
        }
        if(::source.isInitialized) appDb.bookSourceDao.delete(source)
        if(::group.isInitialized) appDb.bookGroupDao.delete(group)
        previous?.let { old -> preferences.update { old } }
        safTree?.let { tree ->
            if(context.contentResolver.persistedUriPermissions.any { it.uri==tree })
                context.contentResolver.releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if(safFolderCreated) shell("rm -r /sdcard/Documents/legado-cache-saf-$id")
        server.stop(); folder.deleteRecursively(); Unit
    }
    private fun open() {
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,CacheRoute(group.groupId)))
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("cacheExport:${book.bookUrl}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cacheExport:${book.bookUrl}").assertIsEnabled()
    }
    private fun task(kind: String)=appDb.bookTransferDao.lease(book.bookUrl,kind)?.let(appDb.bookTransferDao::get)
    private fun startLoopbackDownloadJob() {
        if(Build.VERSION.SDK_INT<34) return
        val scheduler=context.getSystemService(JobScheduler::class.java).forNamespace("legado.book-transfer")
        compose.waitUntil(10_000) { task("download")?.let { current ->
            current.phase=="completed" || scheduler.allPendingJobs.any { it.extras.getString("batchId")==current.batchId }
        }==true }
        val current=checkNotNull(task("download"))
        val job=scheduler.allPendingJobs.firstOrNull { it.extras.getString("batchId")==current.batchId } ?: return
        assertTrue(job.isUserInitiated)
        // This emulator has no validated Internet. Only this fixture job uses loopback HTTP;
        // force its connectivity constraint while retaining the real JobService and notification.
        val command="cmd jobscheduler run -f -n legado.book-transfer ${context.packageName} ${job.id}"
        val result=ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        assertTrue(result,result.contains("Running job"))
    }
    @Test fun defaultToolbarDownloadUsesTheCurrentChapterAndRunsTheNativeJobToCompletion() {
        open()
        compose.onNodeWithTag("cacheDownloadMenu").performClick()
        compose.onNodeWithTag("cacheDialog:download").assertExists()
        compose.onNodeWithText(context.getString(R.string.cache_download_after_description)).assertExists()
        assertNull(task("download"))
        compose.onNodeWithTag("cacheConfirm").performClick()
        startLoopbackDownloadJob()
        compose.waitUntil(30_000) { task("download")?.phase in listOf("completed","failed") }
        val complete=checkNotNull(task("download")); assertEquals(complete.error,"completed",complete.phase)
        assertEquals(2,complete.progress); assertEquals(2,complete.total)
        assertFalse(requests.contains("/chapter/0")); assertTrue(requests.containsAll(listOf("/chapter/1","/chapter/2")))
        assertTrue(BookHelp.getContent(book,chapters[1])!!.contains("Downloaded fixture /chapter/1"))
        if(Build.VERSION.SDK_INT>=34) {
            val scheduler=context.getSystemService(JobScheduler::class.java).forNamespace("legado.book-transfer")
            compose.waitUntil(10_000) { scheduler.allPendingJobs.none { it.extras.getString("batchId")==complete.batchId } }
        }
    }
    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun validatedNetworkSchedulesTheUserInitiatedDownloadWithoutAnyShellOverride() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        Assume.assumeTrue("Requires a system-validated Internet network, not a forced JobScheduler run",
            network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        downloadGate = java.util.concurrent.CountDownLatch(1)
        open()
        compose.onNodeWithTag("cacheDownloadMenu").performClick()
        compose.onNodeWithTag("cacheConfirm").performClick()
        // Hold the first actual response so the native job can be inspected while it runs.
        compose.waitUntil(15_000) { requests.contains("/chapter/1") }
        val current = checkNotNull(task("download"))
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace("legado.book-transfer")
        assertTrue(scheduler.allPendingJobs.any { it.isUserInitiated && it.extras.getString("batchId") == current.batchId })
        downloadGate!!.countDown()
        compose.waitUntil(30_000) { task("download")?.phase in listOf("completed", "failed") }
        val complete = checkNotNull(task("download"))
        assertEquals(complete.error, "completed", complete.phase)
        assertEquals(2, complete.progress)
        assertFalse(requests.contains("/chapter/0"))
        assertTrue(requests.containsAll(listOf("/chapter/1", "/chapter/2")))
        assertTrue(BookHelp.getContent(book, chapters[1])!!.contains("Downloaded fixture /chapter/1"))
        assertTrue(BookHelp.getContent(book, chapters[2])!!.contains("Downloaded fixture /chapter/2"))
    }
    @Test fun exportConfirmationSurvivesRecreationAndWritesARealFileThroughTheForegroundService() {
        chapters.forEach { assertTrue(BookHelp.saveContent(source,book,it,"Exported fixture ${it.index}")) }
        open(); compose.onNodeWithTag("cacheExport:${book.bookUrl}").performClick()
        compose.onNodeWithTag("cacheDialog:export").assertExists()
        assertNull(task("export")); scenario!!.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("cacheDialog:export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cacheExportDirectory").assertTextEquals(folder.path)
        compose.onNodeWithTag("cacheConfirm").performClick()
        compose.waitUntil(30_000) { task("export")?.phase in listOf("completed","failed") }
        val complete=checkNotNull(task("export")); assertEquals(complete.error,"completed",complete.phase)
        val result=File(folder,"cache-ui-output.txt").readText()
        chapters.forEach { assertTrue(result.contains("Exported fixture ${it.index}")) }
        assertEquals(1,folder.listFiles()!!.size)
        assertEquals(3,complete.progress); assertEquals(3,complete.total)
    }
    @Test fun rowDownloadStartsFromTheFirstChapterAndDismissingDoesNotEnqueueWork() {
        open(); compose.onNodeWithTag("cacheDownload:${book.bookUrl}").performClick()
        compose.onNodeWithText(context.getString(R.string.cache_download_all_description)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("cacheDialog:download").assertDoesNotExist(); assertNull(task("download"))
        compose.onNodeWithTag("cacheDownload:${book.bookUrl}").performClick(); compose.onNodeWithTag("cacheConfirm").performClick()
        startLoopbackDownloadJob()
        compose.waitUntil(30_000) { task("download")?.phase in listOf("completed","failed") }
        val complete=checkNotNull(task("download")); assertEquals(complete.error,"completed",complete.phase)
        assertEquals(3,complete.progress); assertTrue(requests.contains("/chapter/0"))
    }
    private fun nativeClick(labels: Set<String>) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.text?.toString()?.lowercase() in labels && node.isEnabled) return node
            repeat(node.childCount) { index -> find(node.getChild(index))?.let { return it } }
            return null
        }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        var target: AccessibilityNodeInfo?=null
        compose.waitUntil(15_000) { target=find(automation.rootInActiveWindow); target!=null }
        var node=checkNotNull(target)
        while(!node.isClickable && node.parent!=null) node=node.parent
        assertTrue("Native button $labels",node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun systemDirectoryGrantSurvivesRecreationAndExportsThroughTheRealDocumentProvider()=runBlocking {
        shell("mkdir -p /sdcard/Documents/legado-cache-saf-$id")
        safFolderCreated=true
        val tree=DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents","primary:Documents/legado-cache-saf-$id")
        // The initial URI points the system picker at our fixture; no grant exists yet.
        preferences.update { it.copy(directory=tree.toString()) }
        chapters.forEach { assertTrue(BookHelp.saveContent(source,book,it,"SAF fixture ${it.index}")) }
        open(); compose.onNodeWithTag("cacheExport:${book.bookUrl}").performClick()
        compose.onNodeWithTag("cacheChooseFolder").performClick()
        nativeClick(setOf("use this folder","使用此文件夹"))
        nativeClick(setOf("allow","允许"))
        safTree=tree
        compose.waitUntil(15_000) { context.contentResolver.persistedUriPermissions.any { it.uri==tree && it.isWritePermission && it.isReadPermission } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cacheConfirm").fetchSemanticsNodes().isNotEmpty() }
        assertNull(task("export")); scenario!!.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cacheDialog:export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cacheExportDirectory").assertTextEquals(tree.toString())
        compose.onNodeWithTag("cacheConfirm").performClick()
        compose.waitUntil(30_000) { task("export")?.phase in listOf("completed","failed") }
        val complete=checkNotNull(task("export")); assertEquals(complete.error,"completed",complete.phase)
        val output=DocumentsContract.buildDocumentUriUsingTree(tree,"primary:Documents/legado-cache-saf-$id/cache-ui-output.txt")
        val body=checkNotNull(context.contentResolver.openInputStream(output)).bufferedReader().use { it.readText() }
        chapters.forEach { assertTrue(body.contains("SAF fixture ${it.index}")) }
    }
    private fun onlyOneChapter() {
        book=book.copy(totalChapterNum=1,durChapterIndex=0)
        chapters=chapters.take(1)
        appDb.bookDao.update(book); appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }
    private fun nativeJob()=context.getSystemService(JobScheduler::class.java).forNamespace("legado.book-transfer")
        .allPendingJobs.single { it.extras.getString("batchId")==checkNotNull(task("download")).batchId }
    @Test fun notificationCancelStopsTheActualJobAndRejectsItsBlockedResponse() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT>=34)
        onlyOneChapter(); downloadGate=java.util.concurrent.CountDownLatch(1)
        open(); compose.onNodeWithTag("cacheDownload:${book.bookUrl}").performClick()
        compose.onNodeWithTag("cacheConfirm").performClick(); startLoopbackDownloadJob()
        compose.waitUntil(10_000) { task("download")?.phase=="running" && requests.isNotEmpty() }
        val job=nativeJob()
        val notifications=context.getSystemService(android.app.NotificationManager::class.java)
        compose.waitUntil(10_000) { notifications.activeNotifications.any { it.id==job.id } }
        val notification=notifications.activeNotifications.single { it.id==job.id }.notification
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT!=0)
        notification.actions.single().actionIntent.send()
        compose.waitUntil(10_000) { task("download")?.phase=="cancelled" }
        downloadGate!!.countDown()
        compose.waitUntil(10_000) { context.getSystemService(JobScheduler::class.java).forNamespace("legado.book-transfer")
            .allPendingJobs.none { it.id==job.id } }
        assertNull(BookHelp.getContent(book,chapters.single()))
    }
    @Test fun aSystemTimeoutCanResumeTheSameNativeBatchWithANewTaskOwner() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT>=34)
        onlyOneChapter(); val firstGate=java.util.concurrent.CountDownLatch(1); downloadGate=firstGate
        open(); compose.onNodeWithTag("cacheDownload:${book.bookUrl}").performClick()
        compose.onNodeWithTag("cacheConfirm").performClick(); startLoopbackDownloadJob()
        compose.waitUntil(10_000) { task("download")?.phase=="running" && requests.isNotEmpty() }
        val first=checkNotNull(task("download")); val job=nativeJob()
        val stopped=shell("cmd jobscheduler timeout -n legado.book-transfer ${context.packageName} ${job.id}")
        assertTrue(stopped,stopped.contains("Stopping job:") && stopped.contains(job.id.toString()))
        try { compose.waitUntil(10_000) { task("download")?.phase=="queued" && appDb.bookTransferDao.batch(first.batchId!!)?.phase=="waiting" } }
        catch(error: AssertionError) { throw AssertionError("Stopped task=${task("download")?.phase}, batch=${appDb.bookTransferDao.batch(first.batchId!!)?.phase}",error) }
        assertNull(BookHelp.getContent(book,chapters.single()))
        val secondGate=java.util.concurrent.CountDownLatch(1); downloadGate=secondGate; firstGate.countDown()
        startLoopbackDownloadJob()
        compose.waitUntil(10_000) { task("download")?.phase=="running" && requests.size>=2 }
        val resumed=checkNotNull(task("download")); assertEquals(first.id,resumed.id)
        assertEquals(first.batchId,resumed.batchId); assertNotEquals(first.owner,resumed.owner)
        secondGate.countDown()
        compose.waitUntil(20_000) { task("download")?.phase in listOf("completed","failed") }
        val complete=checkNotNull(task("download")); assertEquals(complete.error,"completed",complete.phase)
        assertTrue(BookHelp.getContent(book,chapters.single())!!.contains("Downloaded fixture /chapter/0"))
    }
    private fun runForegroundDownloadWorker(oldProcess: Boolean=false) {
        onlyOneChapter(); open()
        val tasks=io.legado.app.data.repository.BookTransferRepository(appDb)
        val ids=runBlocking { tasks.enqueue(listOf(io.legado.app.data.repository.CacheBookRef(book.bookUrl,
            io.legado.app.data.repository.transferBookKey(book))),"download",io.legado.app.data.repository.BookTransferConfig(threads=1)) }
        val batch=runBlocking { tasks.dispatchCandidates(ids).single() }
        if(oldProcess) {
            appDb.bookTransferDao.update(batch.copy(process="expired-process"))
            appDb.bookTransferDao.update(checkNotNull(task("download")).copy(process="expired-process"))
        }
        val work=androidx.work.OneTimeWorkRequestBuilder<io.legado.app.work.BookDownloadWorker>()
            .setInputData(androidx.work.workDataOf("batchId" to batch.id,"dispatch" to batch.dispatch)).build()
        val manager=androidx.work.WorkManager.getInstance(context)
        val name=io.legado.app.service.BookTransferScheduler.workName(batch.id)
        try {
            // Loopback HTTP does not require validated Internet. This exercises the real Hilt
            // Worker/foreground service on API 35; it does not replace API 26-33 device coverage.
            manager.enqueueUniqueWork(name,androidx.work.ExistingWorkPolicy.KEEP,work).result.get(10,java.util.concurrent.TimeUnit.SECONDS)
            compose.waitUntil(30_000) { manager.getWorkInfoById(work.id).get(10,java.util.concurrent.TimeUnit.SECONDS)?.state?.isFinished==true }
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(work.id).get()?.state)
            val complete=checkNotNull(task("download"))
            assertEquals(complete.error,if(oldProcess) "interrupted" else "completed",complete.phase)
            if(oldProcess) { assertTrue(requests.isEmpty()); assertNull(BookHelp.getContent(book,chapters.single())) }
            else assertTrue(BookHelp.getContent(book,chapters.single())!!.contains("Downloaded fixture /chapter/0"))
        } finally { manager.cancelUniqueWork(name).result.get(10,java.util.concurrent.TimeUnit.SECONDS) }
    }
    @Test fun theActualHiltWorkerAndForegroundServiceDownloadAndCompleteTheBatch()=runForegroundDownloadWorker()
    @Test fun theLegacyWorkerCannotAutomaticallyResumeAnOldProcessesDownload()=runForegroundDownloadWorker(true)
}
