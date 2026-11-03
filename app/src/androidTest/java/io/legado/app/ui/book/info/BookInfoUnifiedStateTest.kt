package io.legado.app.ui.book.info

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.data.repository.BookInfoPreviewStore
import io.legado.app.data.repository.BookInfoRepository
import io.legado.app.data.repository.BookInfoEdit
import kotlinx.coroutines.runBlocking
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BookInfoUnifiedStateTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.cronet, PreferKey.autoBackup)
    private val saved = keys.associateWith { prefs.all[it] }
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val repository = BookInfoRepository(appDb)
    private val infoRequests = AtomicInteger()
    private val tocRequests = AtomicInteger()
    private val infoResponses = AtomicInteger()
    private val failInfo = AtomicBoolean()
    private val failToc = AtomicBoolean()
    private var holdInfo: CountDownLatch? = null
    private val blockedInfoRequests = AtomicInteger()
    private val responseName = AtomicReference<String>()
    private lateinit var server: NanoHTTPD
    private lateinit var source: BookSource
    private lateinit var book: Book
    private val added = arrayListOf<Book>()
    private val groups = arrayListOf<BookGroup>()
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val updatedName get() = "Updated $id"

    @Before fun setUp() {
        prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        responseName.set(updatedName)
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/info" -> {
                    infoRequests.incrementAndGet()
                    val name = responseName.get()
                    holdInfo?.let { blockedInfoRequests.incrementAndGet(); it.await(15, TimeUnit.SECONDS) }
                    val response = if (failInfo.get()) newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Info failure")
                    else newFixedLengthResponse("<h1 class='name'>$name</h1><p class='author'>Author $id</p><div class='intro'>Updated description $id</div><a class='toc' href='/toc'>TOC</a>")
                    infoResponses.incrementAndGet()
                    response
                }
                "/toc" -> {
                    tocRequests.incrementAndGet()
                    if (failToc.get()) newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "TOC failure")
                    else newFixedLengthResponse("<a class='chapter' href='/c1'>Chapter 1 $id</a><a class='chapter' href='/c2'>Chapter 2 $id</a>")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Missing")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource(base, "Source $id", ruleBookInfo = BookInfoRule(name = ".name@text", author = ".author@text",
            intro = ".intro@text", tocUrl = ".toc@href", canReName = "true"),
            ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"))
        appDb.bookSourceDao.insert(source)
        book = Book(bookUrl = "$base/info", origin = source.bookSourceUrl, originName = source.bookSourceName,
            name = "Original $id", author = "Original author $id", coverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
        appDb.bookDao.insert(book)
    }

    @After fun tearDown() {
        holdInfo?.countDown()
        server.stop()
        (listOf(book) + added).forEach { item ->
            appDb.bookDao.getBook(item.bookUrl)?.let { BookHelp.clearCache(it) }
            appDb.bookChapterDao.delByBook(item.bookUrl); appDb.bookDao.delete(item); BookHelp.clearCache(item)
        }
        groups.forEach { appDb.bookGroupDao.delete(it) }
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply { saved.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
        LocalConfig.edit().apply { savedLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
    }

    @Test fun recreationRetainsTheInFlightRequestAndTheVisibleLoadingState() {
        holdInfo = CountDownLatch(1)
        launch().use { scenario ->
            awaitState(scenario) { it.book != null && it.loading && infoRequests.get() == 1 }
            assertTrue(composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty())
            scenario.recreate()
            awaitState(scenario) { it.book != null && it.loading }
            assertEquals(1, infoRequests.get())
            holdInfo!!.countDown()
            awaitState(scenario) { !it.loading && it.book?.name == updatedName && it.chapters.size == 2 }
            composeRule.onNodeWithText(updatedName).assertIsDisplayed()
            assertEquals(1, infoRequests.get())
            assertEquals(1, tocRequests.get())
        }
    }

    @Test fun successfulMetadataUpdatesPublishASeparateSnapshotDespiteTheSameBookIdentity() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val before = state(scenario)
            lateinit var detached: Book
            scenario.onActivity { detached = model(it).getBook(false)!! }
            detached.name = "Edited $id"
            assertEquals(updatedName, before.book!!.name)
            scenario.onActivity { model(it).saveBook(detached) }
            awaitState(scenario) { it.book?.name == "Edited $id" && it.bookRevision > before.bookRevision }
            assertEquals(updatedName, before.book!!.name)
            assertEquals(book.bookUrl, state(scenario).book!!.bookUrl)
            composeRule.onNodeWithText("Edited $id").assertIsDisplayed()
            assertEquals("Edited $id", appDb.bookDao.getBook(book.bookUrl)!!.name)
        }
    }

    @Test fun infoAndTocFailuresKeepTheLastSuccessfulBookAndChapters() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val before = state(scenario)
            val chapterTitles = before.chapters.map { it.title }
            failInfo.set(true)
            scenario.onActivity { model(it).loadBookInfo(model(it).getBook(false)!!) }
            awaitState(scenario) { !it.loading && infoRequests.get() == 2 && it.bookRevision > before.bookRevision }
            assertEquals(updatedName, state(scenario).book!!.name)
            assertEquals(chapterTitles, state(scenario).chapters.map { it.title })
            failInfo.set(false)
            failToc.set(true)
            val revision = state(scenario).bookRevision
            scenario.onActivity { model(it).loadChapter(model(it).getBook(false)!!, runPreUpdateJs = false) }
            awaitState(scenario) { !it.loading && tocRequests.get() == 2 && it.bookRevision > revision }
            assertEquals(chapterTitles, state(scenario).chapters.map { it.title })
            assertEquals(updatedName, appDb.bookDao.getBook(book.bookUrl)!!.name)
            assertEquals(chapterTitles, appDb.bookChapterDao.getChapterList(book.bookUrl).map { it.title })
        }
    }

    @Test fun aNewViewModelRestoresTheBookKeyAndCollapsedIntroductionFromPrimitives() {
        book.intro = "Updated description $id"
        appDb.bookDao.update(book)
        val savedState = SavedStateHandle(mapOf("bookUrl" to book.bookUrl, "name" to book.name, "author" to book.author,
            "introExpanded" to false, "introContentKey" to "${book.bookUrl}:${"Updated description $id".hashCode()}"))
        withModel(savedState) { model ->
            main { model.initData("") }
            await { !model.uiState.value.loading && model.uiState.value.chapters.size == 2 }
            assertFalse(model.uiState.value.intro.expanded)
            main { model.initData(book.bookUrl) }
            assertEquals(1, infoRequests.get())
        }
    }

    @Test fun introMeasurementsAndScriptsFromAnOldDocumentAreIgnored() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 && !it.intro.loading }
            val old = state(scenario).intro.contentKey
            scenario.onActivity { model(it).setIntroContent("Replacement intro") }
            awaitState(scenario) { !it.intro.loading && it.intro.contentKey != old }
            scenario.onActivity {
                model(it).onIntroMeasured(old, true)
                model(it).onIntroButtonClick(old, "old", "book.setName('Stale script');")
            }
            assertFalse(state(scenario).intro.canCollapse)
            assertEquals(updatedName, state(scenario).book!!.name)
            val key = state(scenario).intro.contentKey
            scenario.onActivity { model(it).onIntroButtonClick(key, "current", "book.setName('Current script');") }
            awaitState(scenario) { it.book?.name == "Current script" }
        }
    }

    @Test fun collidingIntroHashesStillResetExpansionAndDiscardOldParseResults() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        withModel(SavedStateHandle()) { model ->
            main { model.setIntroContent("Aa") }
            await { !model.uiState.value.intro.loading }
            val old = model.uiState.value.intro.contentKey
            main { model.toggleIntroExpanded(); model.setIntroContent("BB") }
            await { !model.uiState.value.intro.loading }
            assertTrue(model.uiState.value.intro.expanded)
            assertNotEquals(old, model.uiState.value.intro.contentKey)
            val document = model.uiState.value.intro.document!!
            assertEquals("BB", ((document.blocks.single() as io.legado.app.data.entities.IntroBlock.Paragraph).content.single() as io.legado.app.data.entities.IntroInline.Text).text)
        }
    }

    @Test fun largeIntroContentIsParsedWithoutBeingSavedInTheStateHandle() {
        val saved = SavedStateHandle()
        withModel(saved) { model ->
            val content = "<usehtml><img src='data:image/png;base64," + "A".repeat(300_000) + "'></usehtml>"
            main { model.setIntroContent(content) }
            await { !model.uiState.value.intro.loading }
            assertNotNull(model.uiState.value.intro.document)
            saved.keys().forEach { key -> assertTrue("Large content under $key", saved.get<Any>(key).toString().length < 256) }
        }
    }

    @Test fun identicalPendingMessagesHaveDistinctIdentitiesAndAreConsumedSeparately() {
        withModel(SavedStateHandle()) { model ->
            main { model.getBook(); model.getBook() }
            val pending = model.uiState.value.pendingActions
            assertEquals(2, pending.size)
            assertNotEquals(pending.first().id, pending.last().id)
            main { model.consumeUiAction(pending.first()) }
            assertEquals(listOf(pending.last().id), model.uiState.value.pendingActions.map { it.id })
        }
    }

    @Test fun aChapterWriteFailureRollsBackTheBookshelfInsert() {
        val failed = Book(bookUrl = "failed://$id", name = "Failed $id", author = "Author", type = BookType.text or BookType.notShelf)
        added.add(failed)
        val chapter = BookChapter(bookUrl = "missing-parent://$id", url = "chapter", title = "Invalid parent")
        val failure = runCatching { repository.addToBookshelf(failed, listOf(chapter)) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(appDb.bookDao.has(failed.bookUrl))
        assertTrue(appDb.bookChapterDao.getChapterList(failed.bookUrl).isEmpty())
    }

    @Test fun scriptButtonsPublishBookChangesWithoutAnActivityInTheViewModel() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            scenario.onActivity { model(it).setIntroContent("<usehtml><button>Run@onclick:book.setName('Script $id');</button></usehtml>") }
            awaitState(scenario) { !it.intro.loading && it.intro.document != null }
            composeRule.onNodeWithTag("intro_button_0").performScrollTo().assertIsDisplayed().performClick()
            awaitState(scenario) { it.book?.name == "Script $id" }
            composeRule.onNodeWithText("Script $id").assertIsDisplayed()
            assertEquals(updatedName, appDb.bookDao.getBook(book.bookUrl)!!.name)
        }
    }

    @Test fun previewMetadataRemainsInStateAndIsIncludedWhenAGroupAddsTheBookToTheShelf() {
        book.type = BookType.text or BookType.notShelf
        appDb.bookDao.update(book)
        val group = BookGroup(appDb.bookGroupDao.getUnusedId(), "Group $id")
        groups.add(group)
        appDb.bookGroupDao.insert(group)
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 && !it.inBookshelf }
            val cover = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}"
            scenario.onActivity {
                model(it).changeCover(cover)
                model(it).setBookVariable("Preview variable $id")
                model(it).setCanUpdate(false)
            }
            val preview = state(scenario).book!!
            assertEquals(cover, preview.customCoverUrl)
            assertEquals("Preview variable $id", preview.getCustomVariable())
            assertFalse(preview.canUpdate)
            assertNull(appDb.bookDao.getBook(book.bookUrl)!!.customCoverUrl)
            scenario.onActivity { model(it).changeGroup(group.groupId) }
            awaitState(scenario) { it.inBookshelf && !it.shelfMutation }
            val persisted = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(group.groupId, persisted.group)
            assertEquals(cover, persisted.customCoverUrl)
            assertEquals("Preview variable $id", persisted.getCustomVariable())
            assertFalse(persisted.canUpdate)
            assertFalse(persisted.isNotShelf)
            assertEquals(2, appDb.bookChapterDao.getChapterList(book.bookUrl).size)
        }
    }

    @Test fun rapidShelfEditsRetainEveryFieldAndTheLastValue() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val cover = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}"
            scenario.onActivity {
                val model = model(it)
                model.changeCover("obsolete://$id")
                model.setBookVariable("First $id")
                model.setCanUpdate(true)
                model.changeCover(cover)
                model.setBookVariable("Last $id")
                model.setCanUpdate(false)
                model.changeGroup(0)
                assertEquals(cover, model.getBook(false)!!.customCoverUrl)
                assertEquals("Last $id", model.getBook(false)!!.getCustomVariable())
                assertFalse(model.getBook(false)!!.canUpdate)
            }
            awaitState(scenario) { it.metadataWrites == 0 }
            val stored = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(cover, stored.customCoverUrl)
            assertEquals("Last $id", stored.getCustomVariable())
            assertFalse(stored.canUpdate)
            assertEquals(0L, stored.group)
            assertEquals(updatedName, stored.name)
            assertEquals(2, appDb.bookChapterDao.getChapterList(book.bookUrl).size)
            scenario.recreate()
            awaitState(scenario) { it.book?.getCustomVariable() == "Last $id" }
            assertEquals(cover, state(scenario).book!!.customCoverUrl)
            assertFalse(state(scenario).book!!.canUpdate)
        }
    }

    @Test fun fieldEditsPreserveCurrentParserVariablesAndReadingProgress() = runBlocking {
        val stored = appDb.bookDao.getBook(book.bookUrl)!!
        stored.putVariable("parser", "Retained $id")
        stored.durChapterIndex = 7
        stored.durChapterPos = 123
        appDb.bookDao.update(stored)
        repository.applyEdit(book.bookUrl, BookInfoEdit.Variable("Custom $id"))
        repository.applyEdit(book.bookUrl, BookInfoEdit.CanUpdate(false))
        val updated = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals("Retained $id", updated.getVariable("parser"))
        assertEquals("Custom $id", updated.getCustomVariable())
        assertEquals(7, updated.durChapterIndex)
        assertEquals(123, updated.durChapterPos)
        assertFalse(updated.canUpdate)
    }

    @Test fun editsMadeDuringAnInfoRequestSurviveItsMetadataAndChapterCommit() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val gate = CountDownLatch(1)
            holdInfo = gate
            scenario.onActivity { model(it).loadBookInfo(model(it).getBook(false)!!) }
            await { blockedInfoRequests.get() == 1 }
            val cover = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}"
            scenario.onActivity {
                val model = model(it)
                model.changeCover(cover)
                model.setBookVariable("While refreshing $id")
                model.setCanUpdate(false)
                model.changeGroup(-1L)
            }
            awaitState(scenario) { it.metadataWrites == 0 }
            gate.countDown()
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val current = state(scenario).book!!
            val stored = appDb.bookDao.getBook(book.bookUrl)!!
            listOf(current, stored).forEach {
                assertEquals(cover, it.customCoverUrl)
                assertEquals("While refreshing $id", it.getCustomVariable())
                assertFalse(it.canUpdate)
                assertEquals(-1L, it.group)
                assertEquals(updatedName, it.name)
            }
        }
    }

    @Test fun aLaterRequestWinsEvenWhenTheEarlierResponseArrivesLast() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val gate = CountDownLatch(1)
            holdInfo = gate
            responseName.set("Obsolete $id")
            scenario.onActivity { model(it).loadBookInfo(model(it).getBook(false)!!) }
            await { blockedInfoRequests.get() == 1 }
            holdInfo = null
            responseName.set("Newest $id")
            scenario.onActivity { model(it).loadBookInfo(model(it).getBook(false)!!) }
            awaitState(scenario) { !it.loading && it.book?.name == "Newest $id" }
            gate.countDown()
            await { infoResponses.get() == 3 }
            instrumentation.waitForIdleSync()
            assertEquals("Newest $id", state(scenario).book!!.name)
            assertEquals("Newest $id", appDb.bookDao.getBook(book.bookUrl)!!.name)
            assertEquals(2, tocRequests.get())
        }
    }

    @Test fun changingSourceCancelsAnOlderInfoRequest() {
        book.type = BookType.text or BookType.notShelf
        appDb.bookDao.update(book)
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val gate = CountDownLatch(1)
            holdInfo = gate
            scenario.onActivity { model(it).loadBookInfo(model(it).getBook(false)!!) }
            await { blockedInfoRequests.get() == 1 }
            val changed = book.copy(bookUrl = "$base/changed", name = "Changed $id", tocUrl = "$base/changed-toc")
            val chapters = listOf(BookChapter(bookUrl = changed.bookUrl, url = "changed-chapter", title = "Changed chapter"))
            val completed = AtomicBoolean()
            scenario.onActivity { model(it).changeTo(source, changed, chapters) { completed.set(true) } }
            awaitState(scenario) { it.book?.bookUrl == changed.bookUrl && completed.get() && !it.loading }
            gate.countDown()
            await { infoResponses.get() == 2 }
            instrumentation.waitForIdleSync()
            assertEquals(changed.bookUrl, state(scenario).book!!.bookUrl)
            assertEquals(listOf("Changed chapter"), state(scenario).chapters.map { it.title })
            assertFalse(appDb.bookDao.has(changed.bookUrl))
            assertEquals(1, tocRequests.get())
        }
    }

    @Test fun readerReloadCancelsOlderInfoAndKeepsTheReloadedIdentityAndChapters() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            holdInfo = CountDownLatch(1)
            scenario.onActivity { model(it).loadBookInfo(model(it).getBook(false)!!) }
            await { blockedInfoRequests.get() == 1 }
            val returned = book.copy(bookUrl = "$base/reader-return", name = "Reader return $id", tocUrl = "$base/reader-return-toc")
            added.add(returned)
            appDb.bookDao.insert(returned)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = returned.bookUrl, url = "reader-chapter", title = "Reader chapter"))
            scenario.onActivity { model(it).upBook(returned.name, returned.author) }
            awaitState(scenario) { it.book?.bookUrl == returned.bookUrl && !it.loading && it.chapters.map { chapter -> chapter.title } == listOf("Reader chapter") }
            holdInfo!!.countDown()
            await { infoResponses.get() == 2 }
            instrumentation.waitForIdleSync()
            assertEquals(returned.bookUrl, state(scenario).book!!.bookUrl)
            assertEquals(listOf("Reader chapter"), state(scenario).chapters.map { it.title })
            assertTrue(state(scenario).inBookshelf)
            assertEquals(1, tocRequests.get())
        }
    }

    @Test fun latestReaderReloadWinsAndRefreshesTheActualShelfStatus() {
        withModel(SavedStateHandle()) { model ->
            main { model.initData(book.bookUrl) }
            await { !model.uiState.value.loading && model.uiState.value.chapters.size == 2 }
            val earlier = book.copy(bookUrl = "$base/earlier-return", name = "Earlier $id", tocUrl = "$base/earlier-toc")
            val latest = book.copy(bookUrl = "$base/latest-return", name = "Latest $id", tocUrl = "$base/latest-toc", type = BookType.text or BookType.notShelf)
            listOf(earlier, latest).forEach {
                added.add(it)
                appDb.bookDao.insert(it)
                appDb.bookChapterDao.insert(BookChapter(bookUrl = it.bookUrl, url = "return-chapter", title = it.name))
            }
            main {
                model.upBook(earlier.name, earlier.author)
                model.upBook(latest.name, latest.author)
            }
            await { model.uiState.value.book?.bookUrl == latest.bookUrl && model.uiState.value.chapters.map { it.title } == listOf(latest.name) }
            assertFalse(model.uiState.value.inBookshelf)
            assertTrue(model.uiState.value.book!!.isNotShelf)
            assertEquals(1, infoRequests.get())
        }
    }

    @Test fun failedSourceCommitKeepsTheCurrentBookAndDoesNotAcknowledgeSuccess() {
        withModel(SavedStateHandle()) { model ->
            main { model.initData(book.bookUrl) }
            await { !model.uiState.value.loading && model.uiState.value.chapters.size == 2 }
            val changed = book.copy(bookUrl = "$base/failed-source", name = "Failed $id")
            val invalid = BookChapter(bookUrl = "missing-parent://$id", url = "invalid", title = "Invalid")
            val completed = AtomicBoolean()
            main { model.changeTo(source, changed, listOf(invalid)) { completed.set(true) } }
            await { model.uiState.value.pendingActions.any { pending -> pending.action is BookInfoAction.ShowToast } }
            assertFalse(completed.get())
            assertEquals(book.bookUrl, model.uiState.value.book!!.bookUrl)
            assertTrue(appDb.bookDao.has(book.bookUrl))
            assertFalse(appDb.bookDao.has(changed.bookUrl))
            assertEquals(2, appDb.bookChapterDao.getChapterList(book.bookUrl).size)
        }
    }

    @Test fun revertingAnEditDuringRefreshStillOverridesTheParserResult() {
        launch().use { scenario ->
            awaitState(scenario) { !it.loading && it.chapters.size == 2 }
            val gate = CountDownLatch(1)
            holdInfo = gate
            scenario.onActivity {
                model(it).bookSource = source.copy(ruleToc = source.ruleToc!!.copy(preUpdateJs = "book.putCustomVariable('parser value');"))
                model(it).loadBookInfo(model(it).getBook(false)!!)
            }
            await { blockedInfoRequests.get() == 1 }
            scenario.onActivity {
                model(it).setBookVariable("Temporary $id")
                model(it).setBookVariable(null)
                model(it).setCanUpdate(false)
                model(it).setCanUpdate(true)
            }
            gate.countDown()
            awaitState(scenario) { !it.loading && it.metadataWrites == 0 && tocRequests.get() == 2 }
            assertEquals("", state(scenario).book!!.getCustomVariable())
            assertEquals("", appDb.bookDao.getBook(book.bookUrl)!!.getCustomVariable())
            assertTrue(state(scenario).book!!.canUpdate)
            assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.canUpdate)
        }
    }

    @Test fun aFailedChapterRefreshRollsBackBothMetadataAndTheOldChapterList() = runBlocking {
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "retained", title = "Retained"))
        val invalid = BookChapter(bookUrl = "missing-parent://$id", url = "invalid", title = "Invalid")
        assertTrue(runCatching { repository.commitLoadedChapters(book, book.copy(name = "Should roll back"), listOf(invalid)) }.isFailure)
        assertEquals(book.name, appDb.bookDao.getBook(book.bookUrl)!!.name)
        assertEquals(listOf("Retained"), appDb.bookChapterDao.getChapterList(book.bookUrl).map { it.title })
    }

    @Test fun savingAPreviewAndItsChaptersIsOneTransaction() = runBlocking {
        val preview = book.copy(bookUrl = "preview-save://$id", name = "Preview $id", type = BookType.text or BookType.notShelf)
        added.add(preview)
        val invalid = BookChapter(bookUrl = "missing-parent://$id", url = "invalid", title = "Invalid")
        assertTrue(runCatching { repository.saveSnapshot(preview, false, listOf(invalid)) }.isFailure)
        assertFalse(appDb.bookDao.has(preview.bookUrl))
        val chapter = BookChapter(bookUrl = preview.bookUrl, url = "preview-chapter", title = "Preview chapter")
        repository.saveSnapshot(preview, false, listOf(chapter))
        assertTrue(appDb.bookDao.getBook(preview.bookUrl)!!.isNotShelf)
        assertEquals(listOf("Preview chapter"), appDb.bookChapterDao.getChapterList(preview.bookUrl).map { it.title })
    }

    @Test fun explicitChapterSelectionIsSavedWhileMetadataOnlySavesRetainReadingProgress() = runBlocking {
        val previous = book.copy(durChapterIndex = 2, durChapterPos = 123, durChapterTitle = "Previous", durVolumeIndex = 3, chapterInVolumeIndex = 4)
        appDb.bookDao.update(previous)
        val metadata = book.copy(name = "New metadata $id")
        repository.saveSnapshot(metadata, true)
        val retained = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals(2, retained.durChapterIndex)
        assertEquals(123, retained.durChapterPos)
        assertEquals(3, retained.durVolumeIndex)
        assertEquals(4, retained.chapterInVolumeIndex)
        val selected = retained.copy(durChapterIndex = 7, durChapterPos = 456, durChapterTitle = "Selected", durVolumeIndex = 8, chapterInVolumeIndex = 9)
        repository.saveSnapshot(selected, true, preserveReadingProgress = false)
        val stored = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals(7, stored.durChapterIndex)
        assertEquals(456, stored.durChapterPos)
        assertEquals("Selected", stored.durChapterTitle)
        assertEquals(8, stored.durVolumeIndex)
        assertEquals(9, stored.chapterInVolumeIndex)
    }

    private fun model(activity: BookInfoActivity) = ViewModelProvider(activity)[BookInfoViewModel::class.java]
    private fun launch() = ActivityScenario.launch<BookInfoActivity>(Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl))
    private fun state(scenario: ActivityScenario<BookInfoActivity>): BookInfoUiState {
        lateinit var result: BookInfoUiState
        scenario.onActivity { result = model(it).uiState.value }
        return result
    }
    private fun awaitState(scenario: ActivityScenario<BookInfoActivity>, condition: (BookInfoUiState) -> Boolean) = await { condition(state(scenario)) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) { composeRule.mainClock.advanceTimeByFrame(); SystemClock.sleep(50) }
        assertTrue("Book details did not reach the expected state", condition())
        instrumentation.waitForIdleSync()
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun withModel(saved: SavedStateHandle, block: (BookInfoViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: BookInfoViewModel
        main { model = BookInfoViewModel(repository, saved, BookInfoPreviewStore(context), io.legado.app.data.repository.BookGroupRepository(appDb, context), io.legado.app.data.repository.BookInfoLibraryRepository(appDb), io.legado.app.data.repository.CoverSearchRepository(appDb), io.legado.app.data.repository.BookIntroRepository(), io.legado.app.data.repository.BookInfoNavigationRepository(appDb)); store.put("details", model) }
        try { block(model) } finally { main { store.clear() } }
    }
}
