package io.legado.app.ui.photo

import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.PhotoRequestRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ImageProvider
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.PhotoViewerRoute
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.test.displayScreenshotBackend

@RunWith(AndroidJUnit4::class)
class PhotoViewerUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = PhotoRequestRepository(context)
    private val keys = arrayListOf<String>()
    private val prefs = context.defaultSharedPreferences
    private val previous = listOf(PreferKey.cronet, PreferKey.autoBackup).associateWith { prefs.all[it] }
    private val privacy = LocalConfig.all["privacyPolicyOk"]
    private lateinit var server: NanoHTTPD
    private lateinit var source: BookSource
    private val token = UUID.randomUUID().toString()
    @Volatile private var gate: java.util.concurrent.CountDownLatch? = null
    private val fail = AtomicBoolean()
    private val requests = AtomicInteger()
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val bytes get() = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.GREEN); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
    } }.toByteArray()
    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit()
        val image = bytes
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (session.uri.endsWith("/slow.png")) gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                if (fail.get()) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unavailable")
                if (session.headers["x-photo"] != token) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
                return newFixedLengthResponse(Response.Status.OK, "image/png", image.inputStream(), image.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource("$base/source", "Photo fixture", header = "{\"X-Photo\":\"$token\"}")
        appDb.bookSourceDao.insert(source)
    }
    @After fun cleanup() {
        gate?.countDown(); server.stop(); appDb.bookSourceDao.delete(source)
        keys.forEach { runBlocking { repository.delete(it) } }
        prefs.edit().apply { previous.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
        LocalConfig.edit().apply { if (privacy is Boolean) putBoolean("privacyPolicyOk", privacy) else remove("privacyPolicyOk") }.commit()
    }
    private fun create(url: String = "$base/$token/image.png", book: Book? = null) = runBlocking { repository.create(url, source.bookSourceUrl, book) }.also(keys::add)
    private fun launch(key: String) = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, PhotoViewerRoute(key)))
    private fun waitFor(tag: String) = compose.waitUntil(15000) {
        // Activity handoff can briefly have no Compose root; keep waiting for the expected node.
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
    private fun description() = compose.onNodeWithTag("photo_gestures").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun displayedPhotoBounds(): android.graphics.Rect {
        val node = compose.onNodeWithTag("photo_image").fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        return android.graphics.Rect(
            kotlin.math.ceil(bounds.left + origin.x).toInt(),
            kotlin.math.ceil(bounds.top + origin.y).toInt(),
            kotlin.math.floor(bounds.right + origin.x).toInt(),
            kotlin.math.floor(bounds.bottom + origin.y).toInt(),
        )
    }
    private fun zoomIn() { val action = compose.onNodeWithTag("photo_gestures").fetchSemanticsNode().config[SemanticsActions.CustomActions].first().also { assertTrue(it.label.isNotBlank()) }
        compose.runOnIdle { assertTrue(action.action()) } }
    @Test fun authenticatedImageLoadsAndZoomSurvivesRecreation() {
        val key = create()
        launch(key).use { scenario ->
            waitFor("photo_image"); assertTrue(requests.get() > 0)
            zoomIn(); compose.waitForIdle(); assertTrue(description().contains("150"))
            scenario.recreate(); waitFor("photo_image"); assertTrue(description().contains("150"))
            var screenshot: Bitmap? = null
            compose.waitUntil(10000) {
                val frame = captureDisplayScreenshot(instrumentation.uiAutomation)
                if (frame.getPixel(frame.width / 2, frame.height / 2) == android.graphics.Color.GREEN) { screenshot = frame; true }
                else { frame.recycle(); false }
            }
            checkNotNull(screenshot).let { frame ->
                File(context.cacheDir, "photo-viewer.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
            }
            compose.onNodeWithTag("photo_close").performClick()
            compose.waitUntil(15000) { runBlocking { runCatching { repository.read(key) }.isFailure } }
        }
    }
    @Test fun doubleTapZoomsAndSingleTapCloses() {
        val key = create()
        launch(key).use {
            waitFor("photo_image")
            compose.onNodeWithTag("photo_gestures").performTouchInput { doubleClick() }
            compose.waitUntil(5000) { description().contains("200") }
            compose.onNodeWithTag("photo_gestures").performTouchInput { click() }
            compose.waitUntil(15000) { runBlocking { runCatching { repository.read(key) }.isFailure } }
        }
    }
    @Test fun failedNetworkImageCanBeRetried() {
        fail.set(true)
        val key = create()
        launch(key).use {
            waitFor("photo_retry"); fail.set(false)
            compose.onNodeWithTag("photo_retry").performClick(); waitFor("photo_image")
            assertTrue(requests.get() >= 2)
        }
    }
    @Test fun readerCacheSnapshotDoesNotDependOnTheCurrentReader() {
        val book = Book(bookUrl = "photo-cache://$token", name = "Photo cache $token", author = "Author")
        val url = "relative-image.png"
        BookHelp.writeImage(book, url, bytes)
        val key = create(url, book)
        BookHelp.clearCache(book)
        launch(key).use { waitFor("photo_image"); assertEquals(0, requests.get()) }
        assertTrue(File(runBlocking { repository.read(key) }.localPath!!).isFile)
    }
    @Test fun transientBitmapSurvivesProducerEviction() {
        val url = "captcha://$token"
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ImageProvider.put(url, bitmap)
        val key = create(url)
        ImageProvider.remove(url)
        launch(key).use { waitFor("photo_image"); assertEquals(0, requests.get()) }
    }
    @Test fun largeInlineImageAndTransformRestoreOutsideBinder() {
        val url = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) + " ".repeat(1000000)
        val key = create(url)
        val saved = SavedStateHandle()
        val owner = ViewModelStore()
        lateinit var first: PhotoViewerViewModel
        instrumentation.runOnMainSync { first = PhotoViewerViewModel(repository, saved); owner.put("photo", first); first.load(key) }
        await { first.state.value.request != null }
        instrumentation.runOnMainSync { first.transform(PhotoTransform(2f, .25f, -.2f)) }
        assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { length -> length < 100 } != false })
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        instrumentation.runOnMainSync { owner.clear() }
        val secondOwner = ViewModelStore()
        lateinit var second: PhotoViewerViewModel
        try {
            instrumentation.runOnMainSync { second = PhotoViewerViewModel(repository, restored); secondOwner.put("photo", second); second.load(key) }
            await { second.state.value.request != null }
            assertEquals(url, second.state.value.request!!.url)
            assertEquals(PhotoTransform(2f, .25f, -.2f), second.state.value.transform)
            launch(key).use { waitFor("photo_image") }
        } finally { instrumentation.runOnMainSync { secondOwner.clear() } }
    }
    @Test fun missingRequestShowsRetryAndStillAllowsClosing() {
        val key = UUID.randomUUID().toString().also(keys::add)
        launch(key).use { waitFor("photo_retry"); compose.onNodeWithTag("photo_close").performClick() }
    }
    @Test fun keyboardCanZoomResetAndClose() {
        val key = create()
        launch(key).use {
            waitFor("photo_image")
            compose.onNodeWithTag("photo_viewer").performKeyInput { keyDown(Key.Equals); keyUp(Key.Equals) }
            compose.waitUntil(5000) { description().contains("150") }
            compose.onNodeWithTag("photo_viewer").performKeyInput { keyDown(Key.Zero); keyUp(Key.Zero) }
            compose.waitUntil(5000) { description().contains("100") }
            compose.onNodeWithTag("photo_viewer").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
            compose.waitUntil(15000) { runBlocking { runCatching { repository.read(key) }.isFailure } }
        }
    }
    @Test fun detailCoverLongPressOpensTheNewViewerAndReturnsToTheBook() {
        val book = Book(bookUrl = "photo-entry://$token", name = "Photo entry $token", author = "Author", origin = source.bookSourceUrl,
            coverUrl = "$base/image.png", tocUrl = "photo-entry://$token/toc")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(io.legado.app.data.entities.BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter"))
        try {
            ActivityScenario.launch<io.legado.app.ui.book.info.BookInfoActivity>(android.content.Intent(context, io.legado.app.ui.book.info.BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl)).use {
                waitFor("book_info_cover")
                compose.onNodeWithTag("book_info_cover").performTouchInput { longClick() }
                waitFor("photo_image")
                compose.onNodeWithTag("photo_close").performClick()
                compose.waitUntil(15000) { compose.onAllNodesWithTag("photo_viewer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("book_info_cover").assertIsDisplayed()
            }
        } finally { appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); BookHelp.clearCache(book) }
    }
    @Test fun animatedGifFramesContinueToRenderOnTheComposeCanvas() {
        val hex = "47494638396101000100800000ff000000ff00" +
            "21ff0b4e45545343415045322e300301000000" +
            "21f904000a0000002c0000000001000100000202440100" +
            "21f904000a0000002c00000000010001000002024c01003b"
        val gif = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = create("data:image/gif;base64," + Base64.encodeToString(gif, Base64.NO_WRAP))
        launch(key).use {
            waitFor("photo_image")
            val bounds = displayedPhotoBounds()
            assertFalse(bounds.isEmpty)
            val colors = mutableSetOf<Int>()
            repeat(10) {
                SystemClock.sleep(80)
                val image = captureDisplayScreenshot(instrumentation.uiAutomation)
                try {
                    assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= image.width && bounds.bottom <= image.height)
                    colors.add(image.getPixel(bounds.centerX(), bounds.centerY()))
                } finally { image.recycle() }
            }
            android.util.Log.i("PhotoViewerPixels", "backend=${displayScreenshotBackend()} bounds=$bounds colors=$colors")
            assertTrue("Expected both animated GIF frames, got $colors", colors.contains(android.graphics.Color.RED) && colors.contains(android.graphics.Color.GREEN))
        }
    }
    @Test fun systemBackClosesTheSceneAndDeletesItsPrivateRequest() {
        val key = create()
        launch(key).use { scenario ->
            waitFor("photo_image")
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(15000) { runBlocking { runCatching { repository.read(key) }.isFailure } }
            compose.waitUntil(15000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }
    @Test fun explicitDefaultCoverOpensWithoutAnInvalidFileRequest() {
        val key = create("use_default_cover")
        launch(key).use { waitFor("photo_image"); assertEquals(0, requests.get()) }
    }
    @Test fun resourceFileAndContentUrisAreDecodedWithoutDependingOnTheOriginalFile() {
        val resource = create("android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
        launch(resource).use { waitFor("photo_image") }
        val file = File(context.cacheDir, "photo-uri-$token.png").apply { writeBytes(bytes) }
        try {
            val key = create(android.net.Uri.fromFile(file).toString())
            file.delete()
            launch(key).use { waitFor("photo_image") }
            file.writeBytes(bytes)
            val content = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
            val contentKey = create(content.toString())
            file.delete()
            launch(contentKey).use { waitFor("photo_image") }
            assertEquals(0, requests.get())
        } finally { file.delete() }
    }
    @Test fun completedCloseRestoresAsClosedRatherThanReloadingADeletedRequest() {
        val key = create()
        val saved = SavedStateHandle()
        val owner = ViewModelStore()
        lateinit var first: PhotoViewerViewModel
        instrumentation.runOnMainSync { first = PhotoViewerViewModel(repository, saved); owner.put("photo", first); first.load(key) }
        await { first.state.value.request != null }
        instrumentation.runOnMainSync { first.close(key) }
        await { first.state.value.closed }
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        instrumentation.runOnMainSync { owner.clear() }
        val secondOwner = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val second = PhotoViewerViewModel(repository, restored)
                secondOwner.put("photo", second); second.load(key)
                assertTrue(second.state.value.closed)
                assertFalse(second.state.value.loading)
                assertFalse(second.state.value.failed)
            }
        } finally { instrumentation.runOnMainSync { secondOwner.clear() } }
    }
    @Test fun sharedImageLoaderDropsThePreviousImageWhileANewRequestIsPending() {
        gate = java.util.concurrent.CountDownLatch(1)
        val path = androidx.compose.runtime.mutableStateOf("$base/$token/first.png")
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val state = io.legado.app.ui.compose.component.rememberLegadoImageState(path.value) { ctx ->
                    io.legado.app.help.glide.ImageLoader.load(ctx, path.value).apply(com.bumptech.glide.request.RequestOptions().set(io.legado.app.help.glide.OkHttpModelLoader.sourceOriginOption, source.bookSourceUrl))
                }.value
                when (state) {
                    is io.legado.app.ui.compose.component.LegadoImageState.Success -> Image(state.bitmap, null, Modifier.fillMaxSize().testTag("shared_image"))
                    io.legado.app.ui.compose.component.LegadoImageState.Loading -> androidx.compose.material3.Text("Loading", Modifier.testTag("shared_loading"))
                    else -> androidx.compose.material3.Text("Error", Modifier.testTag("shared_error"))
                }
            } }
            waitFor("shared_image")
            instrumentation.runOnMainSync { path.value = "$base/$token/slow.png" }
            waitFor("shared_loading")
            compose.onAllNodesWithTag("shared_image").assertCountEquals(0)
            gate!!.countDown()
            waitFor("shared_image")
        }
    }
    @Test fun corruptRequestFieldsProduceARetryStateInsteadOfCrashingComposition() {
        val key = create()
        File(context.noBackupFilesDir, "photo-requests/$key.json").writeText("{}")
        launch(key).use {
            waitFor("photo_retry")
            compose.onNodeWithTag("photo_close").performClick()
        }
    }
    @Test fun requestKeysCannotTraverseOutsidePrivateStorage() {
        assertTrue(runBlocking { runCatching { repository.read("../other") }.exceptionOrNull() } is IllegalArgumentException)
        assertTrue(runBlocking { runCatching { repository.delete("../other") }.exceptionOrNull() } is IllegalArgumentException)
    }
    private fun await(condition: () -> Boolean) { val end = SystemClock.elapsedRealtime() + 15000; while (!condition()) { check(SystemClock.elapsedRealtime() < end); SystemClock.sleep(20) } }
}
