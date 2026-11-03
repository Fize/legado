package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.model.TextReaderChapterContent
import io.legado.app.model.linkAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextReaderEntryRepositoryTest {
    @Test fun processingOptionsAreCapturedFromTheExplicitReadingProfile() = runBlocking {
        val config = io.legado.app.help.config.ReadBookConfig
        val selected = config.getConfig(config.readStyleSelect)
        val layout = if (config.shareLayout) config.shareConfig else selected
        val indent = layout.paragraphIndent
        val adapt = io.legado.app.help.config.AppConfig.adaptSpecialStyle
        val conversion = io.legado.app.help.config.AppConfig.chineseConverterType
        val manual = io.legado.app.help.config.AppConfig.manualReplaceRule
        try {
            layout.paragraphIndent = "   "
            io.legado.app.help.config.AppConfig.adaptSpecialStyle = true
            io.legado.app.help.config.AppConfig.chineseConverterType = 2
            io.legado.app.help.config.AppConfig.manualReplaceRule = true
            val captured = repository.entry(checkNotNull(database.readerSessionDao.get(id))).processing
            layout.paragraphIndent = ""
            assertEquals("   ", captured.paragraphIndent); assertTrue(captured.adaptSpecialStyle)
            assertEquals(2, captured.chineseConverterType); assertTrue(captured.manualReplaceRules)
        } finally {
            layout.paragraphIndent = indent
            io.legado.app.help.config.AppConfig.adaptSpecialStyle = adapt
            io.legado.app.help.config.AppConfig.chineseConverterType = conversion
            io.legado.app.help.config.AppConfig.manualReplaceRule = manual
        }
    }
    @Test fun onlyInsertedPlainPrefixesAreRecordedForIndentPositionConversion() = runBlocking {
        val entry = metadata.copy(adaptSpecialStyle = true, processing = io.legado.app.model.TextReaderProcessing("  ", true))
        val result = document(entry, body = arrayOf("  One", "  <usehtml><p>  HTML</p></usehtml>",
            "  <img src=\"https://example.invalid/image.png\">", "  Last"))
        assertEquals(2, result.indents.size)
        assertEquals(listOf("One", "Last"), result.indents.map { result.text.substring(it.end).substringBefore('\n') })
        val empty = document(entry.copy(processing = entry.processing.copy(paragraphIndent = "")), body = arrayOf("One", "Last"))
        assertEquals(2, empty.indents.size)
        assertTrue(empty.indents.all { it.start == it.end })
    }
    @Test fun sharedUnderlineSettingsAreImmutableAndExplicitTransparentIsPreserved() = runBlocking {
        val config = io.legado.app.help.config.ReadBookConfig
        config.underlineMode // Finish legacy normalization before retaining the shared profile fields.
        val profiles = (config.configList + config.shareConfig).distinctBy { System.identityHashCode(it) }
        val saved = profiles.map { it.copy() }
        try {
            config.underlineMode = 6; config.underlineWidth = 3f; config.underlineDistance = 12f
            config.underlineColor = 0; config.underlineBodyEnabled = false; config.underlineTitleEnabled = true
            val session = checkNotNull(database.readerSessionDao.get(id))
            val snapshot = repository.entry(session).typography.underline
            assertEquals(6, snapshot.mode); assertEquals(3f, snapshot.width, 0f)
            assertEquals(12f, snapshot.distance, 0f); assertEquals(0, snapshot.color)
            assertFalse(snapshot.body); assertTrue(snapshot.title)
            config.underlineMode = 0
            assertEquals(6, snapshot.mode); assertEquals(0, repository.entry(session).typography.underline.mode)
        } finally {
            profiles.zip(saved).forEach { (target, old) ->
                target.underlineMode = old.underlineMode; target.underlineWidth = old.underlineWidth
                target.underlineDistance = old.underlineDistance; target.underlineColor = old.underlineColor
                target.underlineColorSet = old.underlineColorSet; target.underlineBodyEnabled = old.underlineBodyEnabled
                target.underlineTitleEnabled = old.underlineTitleEnabled; target.underlineConfigVersion = old.underlineConfigVersion
            }
        }
    }
    @Test fun backgroundSnapshotsSelectDayNightAndInkWithoutKeepingMutableConfiguration() = runBlocking {
        val config = io.legado.app.help.config.ReadBookConfig.durConfig
        val saved = config.copy()
        val mode = io.legado.app.help.config.AppConfig.themeMode
        val ink = io.legado.app.help.config.AppConfig.isEInkMode
        try {
            config.bgType = 0; config.bgStr = "#112233"
            config.bgTypeNight = 1; config.bgStrNight = "night.jpg"
            config.bgTypeEInk = 0; config.bgStrEInk = "#ffffff"
            io.legado.app.help.config.AppConfig.themeMode = "1"
            io.legado.app.help.config.AppConfig.isEInkMode = false
            val session = checkNotNull(database.readerSessionDao.get(id))
            val day = repository.entry(session).background!!
            assertEquals(0, day.kind); assertEquals("#112233", day.value)
            io.legado.app.help.config.AppConfig.themeMode = "2"
            val night = repository.entry(session).background!!
            assertEquals(1, night.kind); assertEquals("night.jpg", night.value)
            io.legado.app.help.config.AppConfig.isEInkMode = true
            assertEquals("#ffffff", repository.entry(session).background!!.value)
            config.bgStr = "#334455"
            assertEquals("#112233", day.value)
        } finally {
            config.bgType = saved.bgType; config.bgStr = saved.bgStr
            config.bgTypeNight = saved.bgTypeNight; config.bgStrNight = saved.bgStrNight
            config.bgTypeEInk = saved.bgTypeEInk; config.bgStrEInk = saved.bgStrEInk
            io.legado.app.help.config.AppConfig.themeMode = mode
            io.legado.app.help.config.AppConfig.isEInkMode = ink
        }
    }
    @Test fun typographyIsAnIndependentSnapshotOfTheSelectedReaderConfiguration() = runBlocking {
        val config = io.legado.app.help.config.ReadBookConfig.config
        val saved = config.copy()
        try {
            config.letterSpacing = 0.24f; config.textBold = 2; config.titleBold = 0
            config.titleMode = 3; config.textFont = "fixture-body.ttf"; config.titleFont = "fixture-title.ttf"
            config.paddingLeft = 13; config.paddingRight = 37
            config.lineSpacingExtra = 18; config.titleLineSpacingExtra = 15; config.paragraphSpacing = 7
            config.titleTopSpacing = 20; config.titleBottomSpacing = 30
            val entry = repository.entry(checkNotNull(database.readerSessionDao.get(id)))
            assertEquals(0.24f, entry.typography.letterSpacing, 0f)
            assertEquals(300, entry.typography.bodyWeight); assertEquals(400, entry.typography.titleWeight)
            assertEquals(3, entry.typography.titleAlignment)
            assertEquals("fixture-body.ttf", entry.typography.bodyFont)
            assertEquals("fixture-title.ttf", entry.typography.titleFont)
            assertEquals(13, entry.typography.paddingLeft); assertEquals(37, entry.typography.paddingRight)
            assertEquals(1.8f, entry.typography.lineHeight!!, .001f)
            assertEquals(1.15f, entry.typography.titleLineHeight!!, .001f)
            assertEquals(.7f, entry.typography.paragraphSpacing, .001f)
            assertEquals(20, entry.typography.titleTopSpacing); assertEquals(30, entry.typography.titleBottomSpacing)
            config.letterSpacing = Float.NaN; config.paddingLeft = -100; config.paddingRight = 10000
            val repaired = repository.entry(checkNotNull(database.readerSessionDao.get(id)))
            assertEquals(0f, repaired.typography.letterSpacing, 0f)
            assertEquals(0, repaired.typography.paddingLeft); assertEquals(256, repaired.typography.paddingRight)
            assertEquals(0.24f, entry.typography.letterSpacing, 0f)
        } finally {
            config.letterSpacing = saved.letterSpacing; config.textBold = saved.textBold; config.titleBold = saved.titleBold
            config.titleMode = saved.titleMode; config.textFont = saved.textFont; config.titleFont = saved.titleFont
            config.paddingLeft = saved.paddingLeft; config.paddingRight = saved.paddingRight
            config.lineSpacingExtra = saved.lineSpacingExtra; config.titleLineSpacingExtra = saved.titleLineSpacingExtra
            config.paragraphSpacing = saved.paragraphSpacing
            config.titleTopSpacing = saved.titleTopSpacing; config.titleBottomSpacing = saved.titleBottomSpacing
        }
    }
    private val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java).addLegadoMigrations().build()
    private val repository = TextReaderEntryRepository(database)
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "fixture://text-entry-$id", origin = "source", name = id, author = id)
    private val owner = ReaderProgressOwner(id, "owner", 0)
    private val metadata = TextReaderEntry(book.bookUrl, book.origin, book.name, 0, 0, 1,
        false, null, 0, false, true, 20, 4, android.graphics.Color.BLACK)
    @Before fun setup() {
        database.bookDao.insert(book)
        database.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter"))
        database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl, true, false,
            updatedAt = System.currentTimeMillis()), owner.token)
    }
    @Test fun chapterNavigationCommitsOnlyTheExpectedRevisionAndRevokesOldProgress() = runBlocking {
        val next=BookChapter(bookUrl=book.bookUrl,url="next",index=1,title="Next")
        database.bookChapterDao.insert(next)
        val sessions=ReaderSessionRepository(dagger.Lazy { database })
        val moved=checkNotNull(sessions.navigateChapter(owner,book.bookUrl,book.origin,1,next.url,73))
        assertEquals(1,moved.chapterIndex); assertEquals(73,moved.pageIndex); assertEquals(1L,moved.revision)
        assertNull(sessions.navigateChapter(owner,book.bookUrl,book.origin,0,"chapter",0))
        assertFalse(repository.save(owner,metadata,0,"Late old chapter"))
        assertTrue(repository.save(moved.progressOwner(),metadata.copy(chapterIndex=1),73,"Next"))
        assertEquals(1,database.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }
    @Test fun aDatabaseFailureRollsBackThePreparedNavigationRevisionAndPosition() = runBlocking {
        database.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url="next",index=1,title="Next"))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_reader_navigation BEFORE UPDATE OF revision ON reader_sessions BEGIN SELECT RAISE(ABORT,'navigation fixture'); END")
        try {
            val result=runCatching { ReaderSessionRepository(dagger.Lazy { database }).navigateChapter(owner,book.bookUrl,book.origin,1,"next",73) }
            assertTrue(result.isFailure)
            assertEquals(0L,database.readerSessionDao.get(id)!!.revision)
            assertEquals(-1,database.readerSessionDao.get(id)!!.chapterIndex)
            assertTrue(database.readerSessionDao.ownsBook(id,owner.token,owner.revision,book.bookUrl))
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reader_navigation") }
    }
    @Test fun replacedChapterAndChangedSourceCannotCommitAPreparedNavigation() = runBlocking {
        database.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url="next",index=1,title="Next"))
        val sessions=ReaderSessionRepository(dagger.Lazy { database })
        assertNull(sessions.navigateChapter(owner,book.bookUrl,book.origin,1,"removed-url",73))
        database.bookDao.update(book.copy(origin="new-source"))
        assertNull(sessions.navigateChapter(owner,book.bookUrl,book.origin,1,"next",73))
        assertEquals(0L,database.readerSessionDao.get(id)!!.revision)
    }
    @After fun cleanup() {
        io.legado.app.help.globalExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        database.close()
    }
    private suspend fun document(entry: TextReaderEntry = metadata, title: String = "Chapter", vararg body: String) =
        repository.document(entry, TextReaderChapterContent(title, body.toList(), false))

    @Test fun htmlAnchorsAndNoteReferencesUseTheFinalChapterCoordinates() = runBlocking {
        val result = document(metadata.copy(adaptSpecialStyle = true), body = arrayOf("Before",
            "<usehtml><p><a epub:type='noteref' href='#note'>Reference</a></p>" +
                "<aside id='note' role='doc-footnote'><b>Footnote β😀</b></aside></usehtml>",
            "<usehtml><p id='note'>Duplicate</p><a name='legacy'>Legacy</a></usehtml>"))
        val note = result.anchors.single { it.id == "note" }
        assertTrue(note.note)
        assertEquals(result.text.indexOf("Footnote"), note.start)
        assertTrue(result.text.substring(note.start, note.end).contains("β😀"))
        val link = result.links.single()
        assertTrue(link.noteReference); assertEquals("Reference", result.text.substring(link.start, link.end))
        assertEquals(io.legado.app.model.TextReaderLinkAction.Note(note),
            result.linkAction(link, "chapter"))
        assertEquals(result.text.indexOf("Legacy"), result.anchors.single { it.id == "legacy" }.start)
    }

    @Test fun plainTitleAndParagraphsUseTheExistingPersistedCoordinateSpace() = runBlocking {
        val result = document(body = arrayOf("  First", "Second"))
        assertEquals("Chapter\n  First\nSecond\n", result.text)
        assertEquals(8, result.titleLength)
    }
    @Test fun hiddenTitleDoesNotShiftBodyCharacters() = runBlocking {
        val result = document(metadata.copy(titleMode = 2), body = arrayOf("Body"))
        assertEquals("Body\n", result.text); assertEquals(0, result.titleLength)
    }
    @Test fun splitChapterNumberIncludesBothLegacyNewlines() = runBlocking {
        val result = document(metadata.copy(splitTitle = true), "第一章 标题", "正文")
        assertEquals("第一章\n标题\n正文\n", result.text); assertEquals(7, result.titleLength)
    }
    @Test fun volumeAndEmptyChaptersKeepTheirTitleWhenTheTitleSettingIsHidden() = runBlocking {
        assertEquals("Chapter\nBody\n", document(metadata.copy(titleMode = 2, isVolume = true), body = arrayOf("Body")).text)
        assertEquals("Chapter\n", document(metadata.copy(titleMode = 2)).text)
    }
    @Test fun canonicalCachedImageMarkupOccupiesOneCharacterInsteadOfItsUrlLength() = runBlocking {
        val result = document(body = arrayOf("Before<img src=\"https://example.invalid/image.png\">After"))
        assertEquals("Chapter\nBefore\uFFFCAfter\n", result.text)
    }
    @Test fun htmlBlocksUseDecodedCharactersWithoutAnExtraParagraphNewline() = runBlocking {
        val result = document(body = arrayOf("<usehtml><p>Alpha &amp; beta</p></usehtml>", "After"))
        assertEquals("Chapter\nAlpha & beta\nAfter\n", result.text)
    }
    @Test fun explicitPageBreaksDoNotInventPersistedCharacters() = runBlocking {
        assertEquals("Chapter\nBefore\nAfter\n", document(body = arrayOf("Before", "  [newpage]", "After")).text)
    }
    @Test fun acceptedViewportPositionUpdatesTheBookAndItsSessionTogether() = runBlocking {
        assertTrue(repository.save(owner, metadata, 71, "Chapter"))
        assertEquals(71, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertEquals(71, database.readerSessionDao.get(id)!!.pageIndex)
    }
    @Test fun anotherReaderTakingTheBookPreventsBothPositionWrites() = runBlocking {
        database.readerSessionDao.claim(ReaderSessionState("other", book.bookUrl, book.bookUrl, true, false,
            updatedAt = System.currentTimeMillis()), "other-owner")
        assertFalse(repository.save(owner, metadata, 71, "Chapter"))
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertEquals(0, database.readerSessionDao.get(id)!!.pageIndex)
    }
    @Test fun aStaleRevisionCannotPublishAnOldViewport() = runBlocking {
        assertFalse(repository.save(owner.copy(revision = 1), metadata, 71, "Chapter"))
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun aReplacedSourceCannotReceiveTheOldViewportPosition() = runBlocking {
        database.bookDao.update(book.copy(origin = "other-source"))
        assertFalse(repository.save(owner, metadata, 71, "Chapter"))
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun richStylesKeepTheirRangesInTheDecodedDisplayText() = runBlocking {
        val result = document(body = arrayOf("<usehtml><p><b>Bold</b> <i>Italic</i> <u>Under</u> <font color='#ff0000'>Red</font></p></usehtml>"))
        assertTrue(result.text.contains("Bold Italic Under Red"))
        assertEquals("Bold", result.spans.single { it.bold }.let { result.text.substring(it.start, it.end) })
        assertEquals("Italic", result.spans.single { it.italic }.let { result.text.substring(it.start, it.end) })
        assertEquals("Under", result.spans.single { it.underline }.let { result.text.substring(it.start, it.end) })
        assertEquals("Red", result.spans.single { it.foreground == android.graphics.Color.RED }.let { result.text.substring(it.start, it.end) })
    }
    @Test fun htmlLinksAndImagesHaveDecodedOffsetsAndResolveAgainstTheChapter() = runBlocking {
        val result = document(metadata.copy(chapterUrl = "https://example.invalid/book/part.html"),
            body = arrayOf("<usehtml><p>😀 <a href='next.html'>Link</a><img src='cover.png'></p></usehtml>"))
        assertEquals("https://example.invalid/book/next.html", result.links.single().target)
        assertEquals("Link", result.links.single().let { result.text.substring(it.start, it.end) })
        assertEquals("https://example.invalid/book/cover.png", result.images.single().source)
        assertEquals('\uFFFC', result.text[result.images.single().offset])
        assertTrue(result.images.single().inline)
    }
    @Test fun localImagePathsAndFootnoteFragmentsAreNotRewrittenIntoRemoteUrls() = runBlocking {
        val result = document(metadata.copy(localImages = true), body = arrayOf("<usehtml><p><a href='#note'>Note</a><img src='../images/a.png'></p></usehtml>"))
        assertEquals("#note", result.links.single().target)
        assertEquals("../images/a.png", result.images.single().source)
    }
    @Test fun explicitPageBreaksRecordOffsetsWithoutChangingTheDisplayText() = runBlocking {
        val result = document(body = arrayOf("Before", "[newpage]", "After"))
        assertEquals(setOf("Chapter\nBefore\n".length), result.breaks)
        assertEquals("Chapter\nBefore\nAfter\n", result.text)
    }
    @Test fun singleImageStyleRecordsBothPageBoundariesAtTheOneCharacterImage() = runBlocking {
        val result = document(metadata.copy(imageStyle = "SINGLE"), body = arrayOf("Before<img src=\"https://example.invalid/a.png\">After"))
        val image = result.images.single()
        assertEquals(setOf(image.offset, image.offset + 1), result.breaks)
        assertFalse(image.inline)
    }
    @Test fun titleImagesUseTheSamePrefixSpaceAndDoNotReplaceTheChapterTitle() = runBlocking {
        val result = document(metadata.copy(titleImage = "https://example.invalid/title.png", splitTitle = true), "第一章 标题", "Body")
        assertEquals("第一章\n标题\uFFFC\nBody\n", result.text)
        assertEquals(result.titleLength - 2, result.images.single().offset)
        assertTrue(result.images.single().inline)
    }
    @Test fun aSingleImageParagraphDoesNotRetainAnInvisibleIndentOrCreateATrailingBlankPage() = runBlocking {
        val result = document(metadata.copy(titleMode = 2, imageStyle = "SINGLE"), body = arrayOf("  <img src=\"https://example.invalid/a.png\">"))
        assertEquals("\uFFFC\n", result.text)
        assertEquals(setOf(0), result.breaks)
        assertEquals(0, result.images.single().offset)
    }
    @Test fun blockImagesRetainTheLegacyWhitespaceOrderBeforeFollowingText() = runBlocking {
        val result = document(metadata.copy(titleMode = 2), body = arrayOf("  <img src=\"https://example.invalid/a.png\">After"))
        assertEquals("\uFFFC  After\n", result.text)
    }
}
