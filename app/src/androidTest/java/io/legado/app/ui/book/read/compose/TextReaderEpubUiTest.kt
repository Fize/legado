package io.legado.app.ui.book.read.compose

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.TextReaderImageStore
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.TextReaderChapterContent
import io.legado.app.model.localBook.EpubFile
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.TextReaderRoute
import io.legado.app.ui.navigation.AboutRoute
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class TextReaderEpubUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val file = File(context.cacheDir, "epub-ui-$id.epub")
    private val book = Book(bookUrl = file.absolutePath, origin = BookType.localTag, originName = file.name,
        name = "EPUB UI $id", author = id, type = BookType.local or BookType.text,
        readConfig = Book.ReadConfig(useReplaceRule = false))
    private lateinit var chapter: BookChapter
    private lateinit var canonical: String
    private var scenario: ActivityScenario<AppActivity>? = null
    private var manualStore: ViewModelStore? = null
    private lateinit var manual: TextReaderViewModel
    private var handle = SavedStateHandle()
    private val imageRoot = File(context.cacheDir,"epub-ui-images-$id")
    private val releases = mutableListOf<java.util.concurrent.CountDownLatch>()
    private val oldTitle = ReadBookConfig.titleMode
    private val oldFont = ReadBookConfig.textSize
    private val oldAdapt = AppConfig.adaptSpecialStyle
    private val selection = SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection)
    private fun main() = compose.onNode(selection and !hasAnyAncestor(hasTestTag("text_reader_footnote")))
    private fun note() = compose.onNode(selection and hasAnyAncestor(hasTestTag("text_reader_footnote")))
    private fun text(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun position() = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
    @Before fun setup() {
        ReadBookConfig.titleMode = 2; ReadBookConfig.textSize = 20; AppConfig.adaptSpecialStyle = false
        writeEpub()
        val chapters = EpubFile.getChapterList(book); chapter = chapters.first(); book.totalChapterNum = chapters.size
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
        val raw = BookHelp.getContent(book, chapter)!!
        val processed = ContentProcessor.create(book, appDb).getContent(book, chapter, raw, includeTitle = false, useReplace = false)
        canonical = TextReaderDocumentDecoder.decode(TextReaderEntry(book.bookUrl, book.origin, book.name, 0, 0, 1,
            false, null, 2, false, false, 20, 0, 0xff000000.toInt(), chapter.url, true),
            TextReaderChapterContent(chapter.title, processed.textList, processed.sameTitleRemoved)).text
    }
    @After fun cleanup() {
        releases.forEach { it.countDown() }
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { manualStore?.clear() }
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        appDb.readerSessionDao.get(id)?.let { appDb.readerSessionDao.close(it.id, it.ownerToken) }
        appDb.bookDao.delete(book); BookHelp.clearCache(book); EpubFile.clear(book.bookUrl)
        book.coverUrl?.let { File(it).delete() }; file.delete()
        imageRoot.deleteRecursively()
        ReadBookConfig.titleMode = oldTitle; ReadBookConfig.textSize = oldFont; AppConfig.adaptSpecialStyle = oldAdapt
    }
    private fun launch() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, TextReaderRoute(book.bookUrl, requestId = id)))
        ready()
    }
    private fun manualReader(saved: SavedStateHandle = handle, source: EpubReadingSource? = null) {
        scenario!!.onActivity { activity ->
            val leases = CacheResourceLeases()
            val epubs = if (source == null) TextReaderEpubRepository(appDb,leases) else TextReaderEpubRepository(appDb,leases,source)
            val store = ViewModelStore(); manualStore = store; handle = saved
            manual = ViewModelProvider(store,object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(TextReaderViewModel(saved,
                    ReaderSessionRepository(), TextReaderEntryRepository(appDb), TextReaderContentRepository(appDb,leases,epubs),
                    TextReaderImageRepository(appDb,leases,TextReaderImageStore(imageRoot,1024L*1024)),epubs,
                    BookContentSearchRepository(appDb,TextReaderContentRepository(appDb,leases,epubs),TextReaderEntryRepository(appDb)),
                    ReaderAnnotationRepository(appDb,TextReaderContentRepository(appDb,leases,epubs),TextReaderEntryRepository(appDb)),TextReaderHighlightRepository(appDb))))
            })[TextReaderViewModel::class.java]
            val model = manual
            activity.setContent { MaterialTheme { TextReaderDestination(TextReaderRoute(book.bookUrl,requestId=id),{}, {},true,emptySet(),model) } }
        }
    }
    private fun ready(chapterIndex: Int = 0) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(selection).fetchSemanticsNodes().size == 1 &&
                appDb.readerSessionDao.get(id)?.chapterIndex == chapterIndex }
            compose.waitForIdle()
        } catch (error: Throwable) {
            val own=appDb.readerSessionDao.get(id)
            println("EPUB_READY expected=$chapterIndex actual=${own?.chapterIndex} revision=${own?.revision}")
            println(compose.onRoot(useUnmergedTree=true).printToString().take(6000))
            throw error
        }
    }
    private fun noteReady() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("text_reader_footnote_canvas").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }
    private fun link(caption: String, node: SemanticsNodeInteraction = main()) {
        val action = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label.endsWith(caption) }
        compose.runOnIdle { assertTrue(action.action()) }; compose.waitForIdle()
    }
    @Test fun originalEpubHtmlActuallyPaintsStylesAndDecodesEntitiesWithoutShowingHiddenNotes() {
        launch()
        assertTrue(text(main()).contains("entity & literal")); assertFalse(text(main()).contains("&amp;"))
        assertFalse(text(main()).contains("Inline hidden note"))
        val pixels = main().captureToImage().toPixelMap()
        assertTrue((0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x ->
            val c = pixels[x,y]; c.red > .8f && c.green < .3f && c.blue < .3f
        } } > 20)
        File(context.cacheDir,"compose-epub-native-body.png").outputStream().use {
            main().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun crossFileFootnotesPaginateAndRecreateWithoutChangingBookProgress() {
        launch(); val original = position(); link("Reference"); noteReady()
        assertTrue(text(note()).contains("Cross note")); assertFalse(text(note()).contains("&amp;"))
        val first = text(note())
        compose.onNodeWithTag("text_reader_footnote_next").performClick(); compose.waitForIdle()
        val next = text(note()); assertNotEquals(first,next); assertEquals(original,position())
        scenario!!.recreate(); noteReady(); assertEquals(next,text(note())); assertEquals(original,position())
        File(context.cacheDir,"compose-epub-cross-file-note.png").outputStream().use {
            compose.onNodeWithTag("text_reader_footnote").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready(); assertEquals(original,position())
    }
    @Test fun hiddenSameFileNotesLoadOnDemandAndTheirBackReferenceReturnsToTheMainDocument() {
        launch(); link("Inline"); noteReady(); assertTrue(text(note()).contains("Inline hidden note"))
        link("Back", note()); ready()
        compose.waitUntil(10_000) { position() == canonical.indexOf("Native") }
        assertTrue(text(main()).contains("Reference"))
    }
    @Test fun noteReferencesCanAddressAWholeFileWithoutAFragment() {
        launch(); val original = position(); link("Whole note"); noteReady()
        assertTrue(text(note()).contains("Cross note"))
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready(); assertEquals(original,position())
    }
    @Test fun internalAnchorsSaveCanonicalOffsetsAndReturnToTheOriginalPosition() {
        launch(); val original = position(); link("Jump")
        val target = canonical.indexOf("FarTarget")
        assertTrue(target > 1000)
        compose.waitUntil(10_000) { position() == target }
        assertTrue(text(main()).contains("FarTarget"))
        scenario!!.recreate(); ready(); assertEquals(target,position())
        compose.onNodeWithTag("text_reader_link_return").performClick()
        compose.waitUntil(10_000) { position() == original }
    }
    @Test fun anExistingBookmarkAndReadingPositionRestoreAcrossAFontReflow() {
        val target = canonical.indexOf("Paragraph 103")
        val mark = Bookmark(bookName = book.name, bookAuthor = book.author, chapterIndex = 0, chapterPos = target, bookText = "Paragraph 103")
        appDb.bookmarkDao.insert(mark); appDb.bookDao.upProgress(book.bookUrl,target)
        try {
            launch(); assertEquals(target,position()); assertTrue(text(main()).contains("Paragraph 103"))
            ReadBookConfig.textSize = 28; scenario!!.recreate(); ready()
            assertEquals(target,position()); assertTrue(text(main()).contains("Paragraph 103"))
            assertEquals(listOf(mark),appDb.bookmarkDao.getByBook(book.name,book.author))
        } finally { appDb.bookmarkDao.delete(mark) }
    }
    @Test fun crossChapterAnchorsPersistCanonicalProgressAndReturnAfterRecreation() {
        launch(); val original = position()
        link("Across"); ready(1)
        assertTrue(text(main()).contains("Destination second chapter"))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex == 1 }
        val destination = position(); assertTrue(destination > 1000)
        scenario!!.recreate(); ready(1)
        assertEquals(destination,position()); assertTrue(text(main()).contains("Destination second chapter"))
        compose.onNodeWithTag("text_reader_link_return").performClick(); ready()
        assertEquals(original,position()); assertTrue(text(main()).contains("Crimson"))
    }
    @Test fun fragmentlessChapterLinksStartAtTheirOwnFileAndPreserveReturnHistory() {
        launch(); val original=position(); link("File start"); ready(1)
        assertTrue(text(main()).contains("Second chapter opening"))
        compose.onNodeWithTag("text_reader_link_return").performClick(); ready(); assertEquals(original,position())
    }
    @Test fun crossChapterHistoryRestoresInAFreshViewModelAndInterleavesWithBackLinks() {
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,AboutRoute)); manualReader(); ready()
        val original=position(); link("Across"); ready(1)
        val destination=position()
        val saved=handle.keys().associateWith { handle.get<Any?>(it) }
        compose.runOnIdle { manual.pause(); manualStore!!.clear() }
        manualReader(SavedStateHandle(saved)); ready(1)
        assertTrue(text(main()).contains("Destination second chapter"))
        link("Main reference"); ready()
        compose.onNodeWithTag("text_reader_link_return").performClick(); ready(1); assertEquals(destination,position())
        compose.onNodeWithTag("text_reader_link_return").performClick(); ready(); assertEquals(original,position())
    }
    @Test fun anEditedDestinationWithoutTheAnchorDoesNotMoveTheSession() {
        val next=appDb.bookChapterDao.getChapter(book.bookUrl,1)!!
        BookHelp.saveText(book,next,"Edited destination without its old anchor")
        launch(); val original=position(); link("Across")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.text_reader_link_target_missing)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,appDb.readerSessionDao.get(id)!!.chapterIndex); assertEquals(original,position())
    }
    @Test fun aRemovedReturnChapterDoesNotTrapBackOrChangeTheCurrentProgress() {
        launch(); link("Across"); ready(1); val destination=position()
        appDb.bookChapterDao.insert(chapter.copy(url="OEBPS/Text/replaced.xhtml"))
        compose.onNodeWithTag("text_reader_link_return").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.text_reader_link_target_missing)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("text_reader_link_return").assertDoesNotExist()
        assertEquals(1,appDb.readerSessionDao.get(id)!!.chapterIndex); assertEquals(destination,position())
        assertTrue(text(main()).contains("Destination second chapter"))
    }
    @Test fun invalidCrossChapterAnchorsDoNotMoveTheReadingSession() {
        launch(); val original=position(); link("Missing chapter anchor")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.text_reader_link_target_missing)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,appDb.readerSessionDao.get(id)!!.chapterIndex); assertEquals(original,position())
        assertTrue(text(main()).contains("Crimson"))
    }
    @Test fun fullTextSearchReturnsToTheRenderedChapterAndHighlightsTheActualMatch() {
        launch(); val original=position()
        main().performTouchInput { click(center) }
        compose.onNodeWithTag("text_reader_search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("content_search_query") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content_search_query").performTextReplacement("Destination")
        compose.onNodeWithTag("content_search_submit").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content_search_hit_0").performClick(); ready(1)
        val selected=main().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("Destination",text(main()).substring(selected.min,selected.max))
        compose.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex==1 && position()>1000 }
        val destination=position(); assertTrue(destination>1000)
        scenario!!.recreate(); ready(1); assertEquals(destination,position())
        val restored=main().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("Destination",text(main()).substring(restored.min,restored.max))
        compose.onNodeWithTag("text_reader_link_return").performClick(); ready(); compose.waitUntil(10_000) { position()==original }; assertEquals(original,position())
    }
    @Test fun selectedTextCanStartAFullTextSearchWithoutLosingItsReadingPosition() {
        launch(); val original=position(); val at=text(main()).indexOf("Crimson")
        main().performSemanticsAction(SemanticsActions.SetSelection) { action -> assertTrue(action(at,at+7,false)) }
        compose.onNodeWithTag("text_reader_search_selection").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content_search_query").assertTextContains("Crimson")
        compose.onNodeWithTag("content_search_hit_0").performClick(); ready()
        compose.waitUntil(10_000) { compose.onAllNodes(selection).fetchSemanticsNodes().singleOrNull()?.config?.getOrElse(SemanticsProperties.TextSelectionRange) { androidx.compose.ui.text.TextRange.Zero }?.length==7 }
        val selected=main().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("Crimson",text(main()).substring(selected.min,selected.max))
        compose.onNodeWithTag("text_reader_link_return").performClick(); ready(); compose.waitUntil(10_000) { position()==original }; assertEquals(original,position())
    }
    @Test fun cancellingFullTextSearchDoesNotCreateAReturnPositionOrChangeProgress() {
        launch(); val original=position(); main().performTouchInput { click(center) }
        compose.onNodeWithTag("text_reader_search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("content_search_query") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick(); ready()
        assertEquals(original,position()); compose.onNodeWithTag("text_reader_link_return").assertDoesNotExist()
    }
    @Test fun missingCrossFileTargetsShowAnErrorWithoutOpeningAnotherReader() {
        launch(); val original = position(); link("Missing")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.text_reader_link_target_missing)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("text_reader_footnote").assertDoesNotExist(); assertEquals(original,position())
    }
    @Test fun reflowDoesNotRewriteAnExistingPositionInsideLegacyInsertedIndentation() {
        val target = canonical.indexOf("Persisted gap") + "Persisted gap".length + 2
        assertTrue(target > 1000); appDb.bookDao.upProgress(book.bookUrl,target)
        launch(); assertEquals(target,position())
        assertTrue(text(main()).contains("gap\\nmarker"))
        ReadBookConfig.textSize = 24; scenario!!.recreate(); ready(); assertEquals(target,position())
    }
    @Test fun aFreshViewModelRestoresTheCrossFileNoteUsingOnlyOpaqueSavedKeys() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context,AboutRoute)); manualReader(); ready()
        link("Reference"); noteReady()
        compose.onNodeWithTag("text_reader_footnote_next").performClick(); compose.waitForIdle()
        val shownText = text(note()); val original = position()
        val stale = note().fetchSemanticsNode().config[SemanticsActions.CustomActions][1]
        val saved = handle.keys().associateWith { handle.get<Any?>(it) }
        assertTrue((saved["textReader.links.resource"] as String).matches(Regex("[0-9a-f]{64}")))
        assertTrue((saved["textReader.links.note"] as String).matches(Regex("[0-9a-f]{64}")))
        assertTrue(saved.values.all { it == null || it is String || it is Int || it is Long || it is ArrayList<*> })
        val previous = manual
        compose.runOnIdle { previous.pause(); manualStore!!.clear() }
        manualReader(SavedStateHandle(saved)); noteReady()
        assertNotSame(previous,manual); assertEquals(shownText,text(note())); assertEquals(original,position())
        compose.runOnIdle { assertFalse(stale.action()) }
    }
    @Test fun cancellingAResourceRequestPreventsItsLatePopupFromOpening() {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        releases += release
        val source = object : EpubReadingSource {
            override fun chapter(book: Book, chapter: BookChapter) = EpubFile.getReaderChapter(book,chapter)
            override fun resource(book: Book, address: io.legado.app.model.localBook.EpubResourceAddress): io.legado.app.model.localBook.EpubReadingResource? {
                entered.countDown(); check(release.await(15,java.util.concurrent.TimeUnit.SECONDS))
                return EpubFile.getReaderResource(book,address)
            }
        }
        scenario = ActivityScenario.launch(AppActivity.startIntent(context,AboutRoute)); manualReader(source=source); ready()
        val original = position(); link("Reference"); assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
        compose.runOnIdle {
            val ready = manual.state.value.ready!!
            val history = arrayListOf(io.legado.app.model.TextReaderLinkPosition(0,
                io.legado.app.model.textReaderLinkKey("0:${ready.entry.chapterUrl}"),original+1,
                ready.document.storageTitleLength,ready.document.storageIndentLength).encode())
            handle["textReader.links.historyV2"] = history
            assertFalse(manual.returnFromLink())
            assertTrue(manual.state.value.linkLoading)
            assertEquals(history,handle.get<ArrayList<String>>("textReader.links.historyV2"))
            manual.cancelPendingLink()
        }
        release.countDown(); compose.waitForIdle()
        compose.waitUntil(10_000) { !manual.state.value.linkLoading }
        compose.onNodeWithTag("text_reader_footnote").assertDoesNotExist(); assertEquals(original,position())
        assertEquals(0L,manual.state.value.linkFailure)
    }
    @Test fun cancellingWhilePreparingTheDestinationDoesNotCommitANavigation() {
        val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        releases+=release
        val source=object : EpubReadingSource {
            override fun chapter(book: Book, chapter: BookChapter): io.legado.app.model.localBook.EpubReadingChapter? {
                if (chapter.index==1) { entered.countDown(); check(release.await(15,java.util.concurrent.TimeUnit.SECONDS)) }
                return EpubFile.getReaderChapter(book,chapter)
            }
            override fun resource(book: Book,address: io.legado.app.model.localBook.EpubResourceAddress)=EpubFile.getReaderResource(book,address)
        }
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,AboutRoute)); manualReader(source=source); ready()
        val original=position(); link("Across"); assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
        compose.runOnIdle { manual.cancelPendingLink() }; release.countDown(); compose.waitForIdle()
        compose.waitUntil(10_000) { !manual.state.value.linkLoading }
        val own=appDb.readerSessionDao.get(id)!!
        assertEquals(0,own.chapterIndex); assertEquals(0L,own.revision); assertEquals(original,position())
        assertEquals(0L,manual.state.value.linkFailure); assertTrue(text(main()).contains("Crimson"))
    }
    @Test fun changingIndentPreservesTheNoteSourcePositionInANewViewModel() {
        val selected = ReadBookConfig.getConfig(ReadBookConfig.readStyleSelect)
        val layout = if(ReadBookConfig.shareLayout) ReadBookConfig.shareConfig else selected
        val old = layout.paragraphIndent
        try {
            layout.paragraphIndent = "  "
            scenario = ActivityScenario.launch(AppActivity.startIntent(context,AboutRoute)); manualReader(); ready()
            link("Reference"); noteReady(); compose.onNodeWithTag("text_reader_footnote_next").performClick(); compose.waitForIdle()
            val source = handle.get<Int>("textReader.links.notePosition")!!
            val word = Regex("Note paragraph \\d+").find(text(note()))!!.value
            val saved = handle.keys().associateWith { handle.get<Any?>(it) }
            compose.runOnIdle { manual.pause(); manualStore!!.clear() }
            layout.paragraphIndent = "      "
            manualReader(SavedStateHandle(saved)); noteReady()
            assertEquals(source,handle.get<Int>("textReader.links.notePosition"))
            assertTrue(text(note()).contains(word)); assertEquals(0,position())
        } finally { layout.paragraphIndent = old }
    }
    @Test fun anEditedChapterRemainsTheAuthoritativeContent() {
        BookHelp.saveText(book,chapter,"User edited EPUB chapter $id")
        launch(); assertTrue(text(main()).contains("User edited EPUB chapter $id"))
        assertFalse(text(main()).contains("Crimson"))
    }
    @Test fun cachedEditsRemainReadableWhenTheOriginalEpubIsUnavailable() {
        BookHelp.saveText(book,chapter,"Cached edit $id"); EpubFile.clear(book.bookUrl); assertTrue(file.delete())
        launch(); assertTrue(text(main()).contains("Cached edit $id"))
    }
    @Test fun crossFileFootnoteIllustrationsLoadAndPreviewWithoutWritingBookProgress() {
        EpubFile.clear(book.bookUrl); writeEpub(image=true); BookHelp.clearCache(book)
        launch(); val original = position(); link("Reference"); noteReady()
        val label = context.getString(R.string.text_reader_image,1)
        repeat(4) {
            if (compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("text_reader_footnote_next").performClick(); compose.waitForIdle()
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().any {
            it.config[SemanticsProperties.StateDescription] == context.getString(R.string.preview_image_by_click)
        } }
        compose.onNodeWithContentDescription(label).performClick()
        val preview = compose.onNodeWithTag("text_reader_image_preview"); preview.assertIsDisplayed()
        val pixels = preview.captureToImage().asAndroidBitmap()
        val center = pixels.getPixel(pixels.width/2,pixels.height/2)
        assertTrue(android.graphics.Color.green(center)>160 && android.graphics.Color.red(center)<40)
        val zoom = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("text_reader_image_preview")))
        val action = zoom.fetchSemanticsNode().config[SemanticsActions.CustomActions][0]
        compose.runOnIdle { assertTrue(action.action()) }; compose.waitForIdle()
        zoom.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"150%"))
        compose.onNode(hasText(context.getString(R.string.close)) and hasAnyAncestor(hasTestTag("text_reader_image_preview"))).performClick()
        compose.onNodeWithTag("text_reader_footnote_close").performClick(); ready(); assertEquals(original,position())
    }
    private fun writeEpub(image: Boolean = false) {
        fun html(body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Fixture</title></head><body>$body</body></html>"""
        val body = html("""<p id="ref">Native <b><font color="#ff0000">Crimson</font></b> entity &amp; literal <a epub:type="noteref" href="../Notes/notes.xhtml#n">Reference</a> <a href="#target">Jump</a> <a epub:type="noteref" href="#inline-note">Inline</a> <a epub:type="noteref" href="../Notes/notes.xhtml#missing">Missing</a> <a epub:type="noteref" href="../Notes/notes.xhtml">Whole note</a> <a href="next.xhtml#destination">Across</a> <a href="next.xhtml">File start</a> <a href="next.xhtml#missing">Missing chapter anchor</a></p>""" +
            (0..199).joinToString("") { "<p>Paragraph $it alpha &amp; beta &#x1F600; gamma delta.</p>" +
                if (it == 103) "<p>Persisted gap\\nmarker across formatting.</p>" else "" } +
            """<p id="target">FarTarget ending.</p><aside id="inline-note" role="doc-footnote" style="display:none"><p>Inline hidden note <a href="#ref">Back</a></p></aside>""")
        val notes = html("""<aside id="n" epub:type="footnote" style="display:none"><p>Cross note entity &amp; decoded <a href="../Text/main.xhtml#ref">Back</a></p>""" +
            (if(image) """<p><img src="../Images/note.png"/></p>""" else "") +
            (0..119).joinToString("") { "<p>Note paragraph $it independent pagination.</p>" } + "</aside>")
        val imageItem = if(image) """<item id="image" href="Images/note.png" media-type="image/png"/>""" else ""
        val entries = linkedMapOf("mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<package version="2.0" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>Fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="main" href="Text/main.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="Notes/notes.xhtml" media-type="application/xhtml+xml"/><item id="next" href="Text/next.xhtml" media-type="application/xhtml+xml"/>$imageItem</manifest><spine toc="ncx"><itemref idref="main"/><itemref idref="next"/></spine></package>""",
            "OEBPS/toc.ncx" to """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Fixture</text></docTitle><navMap><navPoint id="main" playOrder="1"><navLabel><text>Fixture</text></navLabel><content src="Text/main.xhtml"/></navPoint><navPoint id="next" playOrder="2"><navLabel><text>Second</text></navLabel><content src="Text/next.xhtml"/></navPoint></navMap></ncx>""",
            "OEBPS/Text/main.xhtml" to body, "OEBPS/Notes/notes.xhtml" to notes,
            "OEBPS/Text/next.xhtml" to html("<p>Second chapter opening.</p>" +
                (0..79).joinToString("") { "<p>Second paragraph $it independent chapter.</p>" } +
                """<p id="destination">Destination second chapter <a href="main.xhtml#ref">Main reference</a></p>"""))
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path,text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        }
            if(image) {
                val bitmap = android.graphics.Bitmap.createBitmap(160,80,android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xff00cc22.toInt()); zip.putNextEntry(ZipEntry("OEBPS/Images/note.png"))
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,zip); zip.closeEntry()
                } finally { bitmap.recycle() }
            }
        }
    }
}
