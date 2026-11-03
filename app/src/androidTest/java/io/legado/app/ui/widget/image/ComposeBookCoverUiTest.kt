package io.legado.app.ui.widget.image

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.CoverImageStatus
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoGroupCover
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.utils.defaultSharedPreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ComposeBookCoverUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.useDefaultCover, PreferKey.coverShowName, PreferKey.coverShowNameN, PreferKey.coverShowAuthor, PreferKey.coverShowAuthorN)
    private val previous = keys.associateWith { prefs.all[it] }
    private val privacy = LocalConfig.all["privacyPolicyOk"]
    private val id = UUID.randomUUID().toString()
    private lateinit var server: NanoHTTPD
    private lateinit var first: BookSource
    private lateinit var second: BookSource
    private val requests = AtomicInteger()
    private var gate: CountDownLatch? = null
    private val path = mutableStateOf<String?>(null)
    private val source = mutableStateOf<String?>(null)
    private val name = mutableStateOf("First title")
    private val completions = AtomicInteger()
    private val completionsOffMain = AtomicInteger()
    private val base get() = "http://127.0.0.1:${server.listeningPort}/$id"
    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.useDefaultCover, false).putBoolean(PreferKey.coverShowName, true)
            .putBoolean(PreferKey.coverShowNameN, true).putBoolean(PreferKey.coverShowAuthor, true)
            .putBoolean(PreferKey.coverShowAuthorN, true).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit()
        instrumentation.runOnMainSync { AppConfig.useDefaultCover = false; BookCover.upDefaultCover() }
        fun image(color: Int) = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(90,120,Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.PNG,100,out); recycle()
        } }.toByteArray()
        val green = image(android.graphics.Color.GREEN); val blue = image(android.graphics.Color.BLUE)
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (session.uri.endsWith("/slow")) gate?.await(10, TimeUnit.SECONDS)
                if (session.uri.endsWith("/error")) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed cover")
                if (session.uri.endsWith("/source-error") && session.headers["x-cover"] == "first-$id") return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "First source denied")
                val bytes = when (session.headers["x-cover"]) {
                    "first-$id" -> green
                    "second-$id" -> blue
                    else -> return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Missing source credentials")
                }
                return newFixedLengthResponse(Response.Status.OK,"image/png",bytes.inputStream(),bytes.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT,false) }
        first = BookSource("$base/first", "First cover source", header="{\"X-Cover\":\"first-$id\"}")
        second = BookSource("$base/second", "Second cover source", header="{\"X-Cover\":\"second-$id\"}")
        appDb.bookSourceDao.insert(first,second)
        source.value = first.bookSourceUrl
    }
    @After fun cleanup() {
        gate?.countDown(); server.stop()
        appDb.bookSourceDao.delete(first);appDb.bookSourceDao.delete(second)
        prefs.edit().apply { previous.forEach { (key,value) -> when(value) {
            null -> remove(key); is Boolean -> putBoolean(key,value)
        } } }.commit()
        LocalConfig.edit().apply { if(privacy==null) remove("privacyPolicyOk") else putBoolean("privacyPolicyOk",privacy as Boolean) }.commit()
        instrumentation.runOnMainSync { BookCover.upDefaultCover() }
    }
    private fun launch() = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).also { scenario ->
        scenario.onActivity { activity -> activity.setContent { MaterialTheme {
            LegadoBookCover(path.value, name.value, "Fixture author", Modifier.width(120.dp).testTag("cover"),
                sourceOrigin=source.value, onLoadFinish={
                    completions.incrementAndGet()
                    if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) completionsOffMain.incrementAndGet()
                })
        } } }
    }
    private fun waitStatus(status:String) = compose.waitUntil(10_000) {
        compose.onAllNodes(SemanticsMatcher.expectValue(CoverImageStatus,status)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun pixels():IntArray {
        val bitmap=compose.onNodeWithTag("cover").captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    }
    private fun centerColor():Int = compose.onNodeWithTag("cover").captureToImage().asAndroidBitmap().let { it.getPixel(it.width/2,it.height/2) }
    @Test fun blankCoverIsImmediateAndRecompositionDoesNotRepeatCompletion() {
        launch().use {
            waitStatus("default");compose.waitForIdle()
            assertEquals(0,requests.get());assertEquals(1,completions.get())
            val before=pixels()
            instrumentation.runOnMainSync { name.value="Completely different title" }
            compose.waitForIdle()
            assertFalse(before.contentEquals(pixels()))
            assertEquals(1,completions.get());assertEquals(0,completionsOffMain.get())
            compose.onNodeWithContentDescription("Completely different title, Fixture author").assertIsDisplayed()
        }
    }
    @Test fun sameImageUrlUsesEachSelectedSourcesCredentialsAndSeparateMemoryEntries() {
        path.value="$base/image"
        launch().use {
            waitStatus("ready");assertEquals(android.graphics.Color.GREEN,centerColor())
            instrumentation.runOnMainSync { source.value=second.bookSourceUrl }
            waitStatus("ready");assertEquals(android.graphics.Color.BLUE,centerColor())
            assertEquals(2,requests.get());assertEquals(2,completions.get());assertEquals(0,completionsOffMain.get())
            instrumentation.runOnMainSync { com.bumptech.glide.Glide.get(context).clearMemory(); source.value=first.bookSourceUrl }
            waitStatus("ready");assertEquals(android.graphics.Color.GREEN,centerColor())
            assertEquals("Source-specific disk cache is reusable",2,requests.get())
        }
    }
    @Test fun failedSourceDoesNotPreventTheSameUrlLoadingWithAnotherSource() {
        path.value="$base/source-error"
        launch().use {
            waitStatus("error")
            instrumentation.runOnMainSync { source.value=second.bookSourceUrl }
            waitStatus("ready")
            assertEquals(android.graphics.Color.BLUE,centerColor())
            assertEquals(2,requests.get())
        }
    }
    @Test fun newPendingImageDropsThePreviousImageAndIgnoresTheOldCompletion() {
        path.value="$base/image"
        gate=CountDownLatch(1)
        launch().use {
            waitStatus("ready")
            instrumentation.runOnMainSync { path.value="$base/slow" }
            waitStatus("loading")
            assertNotEquals(android.graphics.Color.GREEN,centerColor())
            instrumentation.runOnMainSync { path.value="$base/error" }
            waitStatus("error")
            val failed=pixels()
            gate!!.countDown();compose.waitForIdle()
            compose.onNode(SemanticsMatcher.expectValue(CoverImageStatus,"error")).assertIsDisplayed()
            assertArrayEquals(failed,pixels())
            assertEquals(2,completions.get())
        }
    }
    @Test fun useDefaultCoverSkipsNetworkAndPreservesText() {
        instrumentation.runOnMainSync {
            prefs.edit().putBoolean(PreferKey.useDefaultCover,true).commit()
            AppConfig.useDefaultCover=true;BookCover.upDefaultCover()
        }
        path.value="$base/image"
        launch().use { waitStatus("default");assertEquals(0,requests.get());assertEquals(1,completions.get()) }
    }
    private fun fixtureBook(index: Int) = BookshelfBook("book-$id-$index", if(index % 2 == 0) first.bookSourceUrl else second.bookSourceUrl,
        "Book $index", "Author", "$base/image", null, 0, 0, false, 0, 0, index)
    @Test fun groupGridUsesEachBooksSourceAndRemovesMissingCells() {
        val books=mutableStateOf((0..3).map(::fixtureBook))
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                LegadoGroupCover(null,books.value,Modifier.width(240.dp).testTag("group"))
            } } }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(CoverImageStatus,"ready")).fetchSemanticsNodes().size==4 }
            for(index in 0..3) {
                val bitmap=compose.onNodeWithTag("group_cover_$index").captureToImage().asAndroidBitmap()
                assertEquals(if(index%2==0) android.graphics.Color.GREEN else android.graphics.Color.BLUE,bitmap.getPixel(bitmap.width/2,bitmap.height/2))
            }
            compose.onNodeWithTag("group").captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.cacheDir,"compose-group-cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            instrumentation.runOnMainSync { books.value=listOf(fixtureBook(1)) }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(CoverImageStatus,"ready")).fetchSemanticsNodes().size==1 }
            compose.onNodeWithTag("group_cover_1").assertDoesNotExist()
            compose.onNodeWithTag("group_cover_2").assertDoesNotExist()
            compose.onNodeWithTag("group_cover_3").assertDoesNotExist()
            compose.onNodeWithContentDescription("Book 1, Author").assertIsDisplayed()
        }
    }
    @Test fun aCustomGroupCoverSuppressesBookImageRequests() {
        val file=File(context.cacheDir,"group-cover-$id.png")
        Bitmap.createBitmap(90,120,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA);file.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle() }
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,AboutRoute)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    LegadoGroupCover(file.toURI().toString(),(0..3).map(::fixtureBook),Modifier.width(120.dp).testTag("custom_group"))
                } } }
                waitStatus("ready")
                val bitmap=compose.onNodeWithTag("custom_group").captureToImage().asAndroidBitmap()
                assertEquals(android.graphics.Color.MAGENTA,bitmap.getPixel(bitmap.width/2,bitmap.height/2))
                assertEquals(0,requests.get())
                compose.onNodeWithTag("group_cover_0").assertDoesNotExist()
            }
        } finally { file.delete() }
    }
    @Test fun visibleTitleSurvivesManyOtherCoverRenders() {
        val other=mutableStateOf("Other")
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                Row {
                    LegadoBookCover(null,"Retained title","Author",Modifier.width(120.dp).testTag("retained"))
                    LegadoBookCover(null,other.value,"Author",Modifier.width(120.dp))
                }
            } } }
            compose.waitForIdle()
            fun snapshot():IntArray {
                val bitmap=compose.onNodeWithTag("retained").captureToImage().asAndroidBitmap()
                return IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
            }
            val before=snapshot()
            repeat(60) { index -> instrumentation.runOnMainSync { other.value="Different title $index" };compose.waitForIdle() }
            assertArrayEquals(before,snapshot())
            compose.onNodeWithContentDescription("Retained title, Author").assertIsDisplayed()
        }
    }
    @Test fun videoCoverStateUsesImmutableValuesEvenWhenTheBookIdentityIsUnchanged() {
        val model=io.legado.app.ui.video.VideoPlayerViewModel(context.applicationContext as android.app.Application)
        val store=androidx.lifecycle.ViewModelStore()
        instrumentation.runOnMainSync { store.put("video",model) }
        try {
            val book=io.legado.app.data.entities.Book(bookUrl="video-$id",origin=first.bookSourceUrl,name="Original",author="Author",coverUrl="$base/image")
            model.updateCover(book)
            val before=model.cover.value!!
            book.name="Changed";book.customCoverUrl="$base/changed"
            model.updateCover(book)
            assertEquals("Original",before.name)
            assertEquals("Changed",model.cover.value!!.name)
            assertEquals("$base/changed",model.cover.value!!.path)
            assertEquals(first.bookSourceUrl,model.cover.value!!.sourceOrigin)
            model.updateCover(null);assertNull(model.cover.value)
        } finally {instrumentation.runOnMainSync { store.clear() }}
    }
    @Test fun textStrokeIsFollowedByAnActualFilledGlyph() {
        val center=java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.geometry.Offset>()
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                val text=androidx.compose.ui.text.rememberTextMeasurer().measure("█",androidx.compose.ui.text.TextStyle(fontSize=64.sp))
                center.set(text.getBoundingBox(0).center)
                val drawing=androidx.compose.runtime.remember(text) { io.legado.app.ui.compose.component.CoverTextLayout(
                    listOf(io.legado.app.ui.compose.component.CoverTextLayout.Glyph(text,androidx.compose.ui.geometry.Offset.Zero,8f)),
                    androidx.compose.ui.graphics.Color.White,androidx.compose.ui.graphics.Color.Black) }
                androidx.compose.foundation.Canvas(Modifier.size(120.dp).testTag("filled_glyph")) { drawing.draw(this) }
            } } }
            val bitmap=compose.onNodeWithTag("filled_glyph").captureToImage().asAndroidBitmap()
            assertEquals("The glyph interior must be filled after drawing its outline",android.graphics.Color.BLACK,
                bitmap.getPixel(center.get().x.toInt(),center.get().y.toInt()))
        }
    }
    @Test fun fileAndResourceUrisDecodeOnTheComposeCanvas() {
        val file=File(context.cacheDir,"cover-$id.png")
        Bitmap.createBitmap(90,120,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA);file.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle() }
        try {
            path.value=file.toURI().toString()
            launch().use {
                waitStatus("ready");assertEquals(android.graphics.Color.MAGENTA,centerColor())
                instrumentation.runOnMainSync { path.value="android.resource://${context.packageName}/${io.legado.app.R.mipmap.ic_launcher}" }
                waitStatus("ready");assertEquals(0,requests.get())
            }
        } finally {file.delete()}
    }
}
