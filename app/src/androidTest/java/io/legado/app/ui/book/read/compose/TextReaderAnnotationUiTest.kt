package io.legado.app.ui.book.read.compose

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.TextReaderRoute
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.TextReaderImageStore
import io.legado.app.data.repository.*
import io.legado.app.ui.navigation.BookshelfRoute

@RunWith(AndroidJUnit4::class)
class TextReaderAnnotationUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=UUID.randomUUID().toString()
    private val book=Book(bookUrl="fixture://annotation-ui-$id",origin="source-$id",name=id,author=id,
        readConfig=Book.ReadConfig(useReplaceRule=false),totalChapterNum=2)
    private val chapters=(0..1).map { BookChapter(bookUrl=book.bookUrl,url="fixture://annotation-chapter-$id-$it",index=it,title="Chapter $it") }
    private val background=ReadBookConfig.getConfig(ReadBookConfig.readStyleSelect)
    private val oldBackground=background.copy()
    private val oldAlpha=ReadBookConfig.bgAlpha
    private val oldTitle=ReadBookConfig.titleMode
    private var scenario: ActivityScenario<AppActivity>?=null
    private var manualStore: ViewModelStore?=null
    private var manual: TextReaderViewModel?=null
    private val imageRoot=java.io.File(context.cacheDir,"annotation-events-$id")
    @Before fun setup() {
        background.bgType=0; background.bgStr="#ffffff"; background.bgTypeNight=0; background.bgStrNight="#ffffff"
        background.bgTypeEInk=0; background.bgStrEInk="#ffffff"; ReadBookConfig.bgAlpha=100
        ReadBookConfig.titleMode=0; appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
        chapters.forEach { BookHelp.saveText(book,it,(0..80).joinToString("\n") { "Annotation amber 😀 phrase $it." }) }
    }
    @After fun cleanup() {
        scenario?.close(); manualStore?.clear(); imageRoot.deleteRecursively(); io.legado.app.help.globalExecutor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
        appDb.bookmarkDao.getByBook(book.name,book.author).forEach { appDb.bookmarkDao.delete(it) }
        appDb.bookHighlightDao.getByBook(book.bookUrl).forEach { appDb.bookHighlightDao.delete(it) }
        appDb.readerSessionDao.get(id)?.let { appDb.readerSessionDao.close(id,it.ownerToken) }
        appDb.bookDao.delete(book); BookHelp.clearCache(book); ReadBookConfig.titleMode=oldTitle
        background.bgType=oldBackground.bgType; background.bgStr=oldBackground.bgStr
        background.bgTypeNight=oldBackground.bgTypeNight; background.bgStrNight=oldBackground.bgStrNight
        background.bgTypeEInk=oldBackground.bgTypeEInk; background.bgStrEInk=oldBackground.bgStrEInk; ReadBookConfig.bgAlpha=oldAlpha
    }
    private val selection=SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection)
    private fun canvas()=compose.onNode(selection)
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodes(selection).fetchSemanticsNodes().size==1 }; compose.waitForIdle() }
    private fun launch() { scenario=ActivityScenario.launch(AppActivity.startIntent(context,TextReaderRoute(book.bookUrl,requestId=id))); ready() }
    private fun launchWithOwnedViewModel() {
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,BookshelfRoute))
        scenario!!.onActivity { activity ->
            val leases=CacheResourceLeases(); val epubs=TextReaderEpubRepository(appDb,leases)
            val entries=TextReaderEntryRepository(appDb); val loader=TextReaderContentRepository(appDb,leases,epubs)
            val store=ViewModelStore(); manualStore=store
            manual=ViewModelProvider(store,object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=checkNotNull(modelClass.cast(TextReaderViewModel(
                    SavedStateHandle(),ReaderSessionRepository(),entries,loader,TextReaderImageRepository(appDb,leases,TextReaderImageStore(imageRoot,1024L*1024)),epubs,
                    BookContentSearchRepository(appDb,loader,entries),ReaderAnnotationRepository(appDb,loader,entries),TextReaderHighlightRepository(appDb))))
            })[TextReaderViewModel::class.java]
            activity.setContent { MaterialTheme { TextReaderDestination(TextReaderRoute(book.bookUrl,requestId=id),{},{},true,emptySet(),checkNotNull(manual)) } }
        }
        ready()
    }
    private fun select() {
        val text=canvas().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        val start=text.indexOf("amber 😀"); assertTrue(start>=0)
        canvas().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(start,start+"amber 😀".length,false)) }
    }
    private fun editor() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("text_reader_annotation_editor").fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle() }
    private fun menu() { canvas().performTouchInput { click(center) }; compose.onNodeWithTag("text_reader_bookmarks").assertExists() }
    private fun waitSaved(highlight: Boolean=false) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("text_reader_annotation_editor").fetchSemanticsNodes().isEmpty() &&
            (if(highlight) appDb.bookHighlightDao.getByBook(book.bookUrl).size else appDb.bookmarkDao.getByBook(book.name,book.author).size)==1 }
    }
    @Test fun aSelectionBookmarkAndNoteSurviveRecreationAndSaveAtTheCanonicalPosition() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        select(); compose.onNodeWithTag("text_reader_bookmark_selection").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_note").performTextReplacement("Persistent note 😀")
        compose.waitUntil(10_000) { appDb.readerAnnotationDao.draft(id)?.note=="Persistent note 😀" }
        scenario!!.recreate(); editor()
        compose.onNodeWithTag("text_reader_annotation_note").assertTextContains("Persistent note 😀")
        compose.onNodeWithTag("text_reader_annotation_text").assertTextContains("amber 😀")
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved()
        val saved=appDb.bookmarkDao.getByBook(book.name,book.author).single()
        assertEquals("amber 😀",saved.bookText); assertEquals("Persistent note 😀",saved.content)
        assertTrue(saved.chapterPos>0); assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun bookmarkNotesCanBeEditedAndDeletedFromTheReadersOwnList() {
        launch(); select(); compose.onNodeWithTag("text_reader_bookmark_selection").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved(); ready()
        val time=appDb.bookmarkDao.getByBook(book.name,book.author).single().time
        menu(); compose.onNodeWithTag("text_reader_bookmarks").performClick()
        compose.onNodeWithTag("text_reader_annotation_edit_b:$time").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_note").performTextReplacement("Updated note")
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved()
        assertEquals("Updated note",appDb.bookmarkDao.get(time)!!.content)
        compose.onNodeWithTag("text_reader_annotation_edit_b:$time").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_delete").performClick()
        compose.waitUntil(10_000) { appDb.bookmarkDao.get(time)==null }
    }
    @Test fun aHighlightActuallyPaintsAndRetainsItsNoteAfterRecreation() {
        launch(); select(); compose.onNodeWithTag("text_reader_highlight_selection").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_note").performTextReplacement("Highlight note")
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved(true); ready()
        scenario!!.recreate(); ready()
        compose.waitUntil(10_000) { canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions].any { it.label=="Highlight note" } }
        fun yellowPixels(): Int {
            val bitmap=canvas().captureToImage().asAndroidBitmap()
            val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.count { android.graphics.Color.red(it)>230 && android.graphics.Color.green(it)>210 && android.graphics.Color.blue(it)<225 }
        }
        compose.waitUntil(10_000) { yellowPixels()>20 }
        assertTrue("Highlight must paint yellow pixels",yellowPixels()>20)
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { java.io.File(context.cacheDir,"compose-reader-annotation.png").outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { screenshot.recycle() }
        val action=canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label=="Highlight note" }
        compose.runOnIdle { assertTrue(action.action()) }
        editor(); compose.onNodeWithTag("text_reader_annotation_note").assertTextContains("Highlight note")
    }
    @Test fun selectingACrossChapterBookmarkSupportsReturningToTheOriginalPage() {
        val mark=Bookmark(bookName=book.name,bookAuthor=book.author,chapterIndex=1,chapterPos=10,chapterName="Chapter 1",bookText="amber")
        appDb.bookmarkDao.insert(mark); launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        menu(); compose.onNodeWithTag("text_reader_bookmarks").performClick()
        compose.onNodeWithTag("text_reader_annotation_b:${mark.time}").performClick()
        compose.waitUntil(15_000) { appDb.readerSessionDao.get(id)?.chapterIndex==1 }; ready()
        compose.onNodeWithTag("text_reader_link_return").performClick()
        compose.waitUntil(15_000) { appDb.readerSessionDao.get(id)?.chapterIndex==0 && appDb.bookDao.getBook(book.bookUrl)?.durChapterPos==original }
        ready(); assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun cancellingAnEditorDoesNotCreateABookmarkOrChangeProgress() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        select(); compose.onNodeWithTag("text_reader_bookmark_selection").performClick(); editor()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.waitUntil(10_000) { appDb.readerAnnotationDao.draft(id)==null }
        assertTrue(appDb.bookmarkDao.getByBook(book.name,book.author).isEmpty())
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun theMenuCreatesAPageBookmarkWithoutASelection() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        menu(); compose.onNodeWithTag("text_reader_bookmark_add").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_text").assertTextContains("Annotation",substring=true)
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved()
        assertEquals(original,appDb.bookmarkDao.getByBook(book.name,book.author).single().chapterPos)
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun jumpingToABookmarkClearsAnUnrelatedSearchSelection() {
        val mark=Bookmark(bookName=book.name,bookAuthor=book.author,chapterIndex=0,chapterPos=1000,chapterName="Chapter 0",bookText="amber")
        appDb.bookmarkDao.insert(mark); launch(); menu()
        compose.onNodeWithTag("text_reader_search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("content_search_query") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content_search_query").performTextReplacement("amber")
        compose.onNodeWithTag("content_search_submit").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content_search_hit_0").performClick(); ready()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("text_reader_search_selection").fetchSemanticsNodes().isNotEmpty() }
        menu(); compose.onNodeWithTag("text_reader_bookmarks").performClick()
        compose.onNodeWithTag("text_reader_annotation_b:${mark.time}").performClick()
        compose.waitUntil(15_000) { appDb.readerSessionDao.get(id)?.let { it.chapterIndex==0 && it.pageIndex>500 }==true }; ready()
        compose.onNodeWithTag("text_reader_search_selection").assertDoesNotExist()
        assertFalse(canvas().fetchSemanticsNode().config.contains(SemanticsProperties.TextSelectionRange))
    }

    @Test fun aStylePresetSurvivesRecreationAndIsSavedWithTheNote() {
        launch(); select(); compose.onNodeWithTag("text_reader_highlight_selection").performClick(); editor()
        compose.onNodeWithTag("annotation_style_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_preset_3").performScrollTo().performClick()
        compose.waitUntil(10_000) { appDb.readerAnnotationDao.draft(id)?.style?.contains("WAVY")==true }
        scenario!!.recreate(); editor()
        compose.onNodeWithTag("annotation_style_preview").performScrollTo().assertExists()
        compose.waitForIdle()
        // Dialog window animations use the system clock, outside Compose's test frame clock.
        android.os.SystemClock.sleep(300)
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { java.io.File(context.cacheDir,"compose-highlight-style-editor.png").outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { screenshot.recycle() }
        compose.onNodeWithTag("text_reader_annotation_note").performScrollTo().performTextReplacement("Styled note")
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved(true)
        val saved=appDb.bookHighlightDao.getByBook(book.bookUrl).single()
        assertEquals(io.legado.app.help.HighlightStyle.Kind.WAVY,saved.styleObj().underline!!.kind)
        assertEquals("Styled note",saved.note)
    }
    @Test fun cancelledStyleChangesDoNotRewriteTheStoredAnnotation() {
        launch(); select(); compose.onNodeWithTag("text_reader_highlight_selection").performClick(); editor()
        compose.onNodeWithTag("text_reader_annotation_save").performClick(); waitSaved(true); ready()
        val before=appDb.bookHighlightDao.getByBook(book.bookUrl).single()
        menu(); compose.onNodeWithTag("text_reader_bookmarks").performClick()
        compose.onNodeWithTag("text_reader_annotation_edit_h:${before.time}").performClick(); editor()
        compose.onNodeWithTag("annotation_style_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_bold").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.waitUntil(10_000) { appDb.readerAnnotationDao.draft(id)==null }
        assertEquals(before.style,appDb.bookHighlightDao.get(before.time)!!.style)
    }
    @Test fun callbacksFromAnOldEditorCannotSaveOrRestyleTheReplacementDraft() {
        launchWithOwnedViewModel(); select(); compose.onNodeWithTag("text_reader_highlight_selection").performClick(); editor()
        val oldId=appDb.readerAnnotationDao.draft(id)!!.id
        val model=checkNotNull(manual)
        val save={ model.finishAnnotation(true,oldId) }
        val bold={ model.annotationStyle(oldId) { it.copy(bold=true) } }
        val text={ model.annotationText(oldId,note="Late text from the former editor") }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.waitUntil(10_000) { appDb.readerAnnotationDao.draft(id)==null }; ready()
        select(); compose.onNodeWithTag("text_reader_highlight_selection").performClick(); editor()
        val next=appDb.readerAnnotationDao.draft(id)!!; assertNotEquals(oldId,next.id)
        compose.runOnIdle { save(); bold(); text() }; compose.waitForIdle()
        assertEquals(next,appDb.readerAnnotationDao.draft(id))
        assertTrue(appDb.bookHighlightDao.getByBook(book.bookUrl).isEmpty())
    }

    @Test fun invalidColorInputBlocksSaveUntilTheColorIsCorrected() {
        launch(); select(); compose.onNodeWithTag("text_reader_highlight_selection").performClick(); editor()
        compose.onNodeWithTag("annotation_style_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_fill_color").performScrollTo().performTextReplacement("#broken")
        compose.onNodeWithTag("text_reader_annotation_save").assertIsNotEnabled()
        assertTrue(appDb.bookHighlightDao.getByBook(book.bookUrl).isEmpty())
        compose.onNodeWithTag("annotation_fill_color").performTextReplacement("#FFFF0000")
        compose.onNodeWithTag("text_reader_annotation_save").assertIsEnabled().performClick(); waitSaved(true)
        assertEquals(0xffff0000.toInt(),appDb.bookHighlightDao.getByBook(book.bookUrl).single().styleObj().fill)
    }

}
