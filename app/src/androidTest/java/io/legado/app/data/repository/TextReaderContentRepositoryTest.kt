package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.bookCacheKey
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.TextReaderChapterKey
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TextReaderContentRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val resources = CacheResourceLeases()
    private val repository = TextReaderContentRepository(database, resources)
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://text-reader-$id.invalid", "Text reader fixture")
    private val book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl,
        name = "Text $id", author = "Fixture", readConfig = Book.ReadConfig(useReplaceRule = true))
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = book.bookUrl + "/chapter", title = "Chapter")
    private val owner = ReaderProgressOwner(id, "owner-$id", 0)
    private fun key() = TextReaderChapterKey(owner, book.bookUrl, book.origin, 0, 1,
        io.legado.app.model.TextReaderProcessing(io.legado.app.help.config.ReadBookConfig.paragraphIndent,
            AppConfig.adaptSpecialStyle, AppConfig.chineseConverterType.coerceIn(0, 2), AppConfig.manualReplaceRule))
    private val oldManual = AppConfig.manualReplaceRule
    private val extraBooks = mutableListOf<Book>()

    @Before fun setup() {
        AppConfig.manualReplaceRule = false
        database.bookSourceDao.insert(source)
        database.bookDao.insert(book)
        database.bookChapterDao.insert(chapter)
        database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl,
            true, false, updatedAt = System.currentTimeMillis()), owner.token)
    }

    @After fun cleanup() {
        AppConfig.manualReplaceRule = oldManual
        BookHelp.clearCache(book)
        extraBooks.forEach(BookHelp::clearCache)
        database.close()
    }

    private fun cache(body: String = "Body alpha") {
        assertTrue(BookHelp.saveContent(source, book, chapter, body))
    }
    @Test fun aCapturedIndentIsUsedForEveryParagraphDespiteLaterGlobalChanges() = runBlocking {
        val config = io.legado.app.help.config.ReadBookConfig
        val old = config.paragraphIndent
        try {
            cache("Alpha\nBeta\nGamma")
            val captured = key().copy(processing = key().processing.copy(paragraphIndent = "  "))
            config.paragraphIndent = "\u3000\u3000\u3000\u3000"
            val loaded = repository.load(captured)
            assertEquals(listOf("  Alpha", "  Beta", "  Gamma"), loaded.paragraphs)
        } finally { config.paragraphIndent = old }
    }
    @Test fun titleAndBodyUseTheSameCapturedChineseConversion() = runBlocking {
        val old = AppConfig.chineseConverterType
        try {
            val chineseChapter = chapter.copy(title = "漢語書籍")
            database.bookChapterDao.insert(chineseChapter)
            assertTrue(BookHelp.saveContent(source, book, chineseChapter, "這是漢語書籍內容"))
            assertEquals("這是漢語書籍內容", BookHelp.getContent(book, chineseChapter))
            val captured = key().copy(processing = key().processing.copy(chineseConverterType = 1, paragraphIndent = ""))
            AppConfig.chineseConverterType = 2
            val loaded = repository.load(captured)
            assertEquals("汉语书籍", loaded.title)
            assertEquals(listOf("这是汉语书籍内容"), loaded.paragraphs)
        } finally { AppConfig.chineseConverterType = old }
    }
    @Test fun capturedSpecialStyleProtectionSurvivesAGlobalToggle() = runBlocking {
        val old = AppConfig.adaptSpecialStyle
        try {
            database.replaceRuleDao.insert(ReplaceRule(id = 9, name = "HTML protection fixture", pattern = "original",
                replacement = "changed", scope = book.name))
            cache("<usehtml><b>original</b></usehtml>")
            val captured = key().copy(processing = key().processing.copy(adaptSpecialStyle = true))
            AppConfig.adaptSpecialStyle = false
            val loaded = repository.load(captured)
            assertTrue(loaded.paragraphs.single().contains("original"))
            assertFalse(loaded.paragraphs.single().contains("changed"))
        } finally { AppConfig.adaptSpecialStyle = old }
    }
    @Test fun aDelayedDownloadKeepsItsCapturedIndentUntilPublication() = runBlocking {
        val config = io.legado.app.help.config.ReadBookConfig
        val old = config.paragraphIndent
        try {
            withServer { server, entered, release ->
                remoteChapter(server)
                val request = key().copy(processing = key().processing.copy(paragraphIndent = "  "))
                val pending = async(Dispatchers.IO) { repository.load(request) }
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                config.paragraphIndent = "\u3000\u3000\u3000\u3000"
                release.countDown()
                assertEquals(listOf("  Remote body"), withTimeout(20_000) { pending.await() }.paragraphs)
            }
        } finally { config.paragraphIndent = old }
    }

    private suspend fun fails(key: TextReaderChapterKey = key(), message: String) {
        val failure = runCatching { repository.load(key) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.toString(), failure is IllegalStateException && failure.message == message)
    }

    @Test fun cachedContentUsesExactUrlAndDoesNotChangeTheGlobalReaderOrProgress() = runBlocking {
        val other = book.copy(bookUrl = book.bookUrl + "/other", author = "Other fixture")
        extraBooks += other
        database.bookDao.insert(other)
        val otherChapter = chapter.copy(bookUrl = other.bookUrl, url = other.bookUrl + "/chapter")
        database.bookChapterDao.insert(otherChapter)
        assertTrue(BookHelp.saveContent(source, other, otherChapter, "Wrong body"))
        cache()
        val global = ReadBook.book
        val result = repository.load(key())
        assertEquals("Chapter", result.title)
        assertEquals(listOf("Body alpha"), result.paragraphs.map { it.trim() })
        assertSame(global, ReadBook.book)
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

    @Test fun aReclaimedOwnerCannotLoadEvenWhenItsChapterIsCached() = runBlocking {
        cache()
        database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl,
            true, false, updatedAt = System.currentTimeMillis()), "new-owner")
        fails(message = "Reader no longer owns this book")
    }

    @Test fun aStalePersistedRevisionCannotLoadTheCurrentBook() = runBlocking {
        cache()
        fails(key().copy(owner = owner.copy(revision = 1)), "Reader no longer owns this book")
    }

    @Test fun sourceChangesAreRejectedRatherThanSilentlyLoadingAnotherSource() = runBlocking {
        cache()
        fails(key().copy(origin = "other-source"), "Reader source changed")
    }

    @Test fun aMissingChapterCannotFallBackToAnotherChapter() = runBlocking {
        cache()
        fails(key().copy(chapterIndex = 1), "Reader chapter was removed")
    }

    @Test fun titleAndBodyRulesComeFromTheInjectedDatabase() = runBlocking {
        database.replaceRuleDao.insert(
            ReplaceRule(id = 1, name = "Title", pattern = "Chapter", replacement = "Heading",
                scope = book.name, scopeTitle = true, scopeContent = false),
            ReplaceRule(id = 2, name = "Body", pattern = "alpha", replacement = "omega", scope = book.name))
        cache()
        val result = repository.load(key())
        assertEquals("Heading", result.title)
        assertEquals(listOf("Body omega"), result.paragraphs.map { it.trim() })
    }

    @Test fun manualRuleSelectionDoesNotEnableUnselectedRules() = runBlocking {
        AppConfig.manualReplaceRule = true
        database.replaceRuleDao.insert(ReplaceRule(id = 3, name = "Unselected", pattern = "alpha",
            replacement = "Wrong", scope = book.name))
        cache()
        val captured = key()
        AppConfig.manualReplaceRule = false
        val result = repository.load(captured)
        assertEquals(listOf("Body alpha"), result.paragraphs.map { it.trim() })
    }

    @Test fun uncachedRemoteContentUsesTheCapturedSourceAndChapter() = runBlocking {
        withServer { server, _, release ->
            release.countDown()
            val remote = remoteChapter(server)
            val result = repository.load(key())
            assertEquals("Chapter", result.title)
            assertEquals(listOf("Remote body"), result.paragraphs.map { it.trim() })
            assertTrue(BookHelp.getContent(book, remote)!!.contains("Remote body"))
        }
    }

    @Test fun ownerHandoffDuringARealDownloadRejectsItsLateResult() = runBlocking {
        withServer { server, entered, release ->
            remoteChapter(server)
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key()) } }
            assertTrue("HTTP request did not start", entered.await(15, TimeUnit.SECONDS))
            database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl,
                true, false, updatedAt = System.currentTimeMillis()), "replacement-owner")
            release.countDown()
            val result = withTimeout(20_000) { pending.await() }
            assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is IllegalStateException)
            assertEquals("Reader no longer owns this book", result.exceptionOrNull()!!.message)
            assertEquals("replacement-owner", database.readerSessionDao.get(id)!!.ownerToken)
        }
    }

    @Test fun catalogReplacementDuringARealDownloadRejectsTheOldChapter() = runBlocking {
        withServer { server, entered, release ->
            remoteChapter(server)
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key()) } }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            database.bookChapterDao.delByBook(book.bookUrl)
            database.bookChapterDao.insert(chapter.copy(url = chapter.url + "/replacement"))
            release.countDown()
            val failure = withTimeout(20_000) { pending.await() }.exceptionOrNull()
            assertEquals("Reader chapter changed", failure?.message)
        }
    }

    @Test fun sourceReplacementDuringARealDownloadRejectsTheOldSource() = runBlocking {
        withServer { server, entered, release ->
            remoteChapter(server)
            val pending = async(Dispatchers.IO) { runCatching { repository.load(key()) } }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            database.bookDao.update(book.copy(origin = "replacement-source"))
            release.countDown()
            val failure = withTimeout(20_000) { pending.await() }.exceptionOrNull()
            assertEquals("Reader source changed", failure?.message)
        }
    }

    @Test fun aDownloadHoldsItsCacheLeaseAndReleasesItAfterCompletion() = runBlocking {
        withServer { server, entered, release ->
            remoteChapter(server)
            val pending = async(Dispatchers.IO) { repository.load(key()) }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            resources.plan(bookCacheKey(book)).use { plan ->
                assertFalse(plan.removeIfUnchanged { fail("Active content must not be removed") })
                release.countDown()
                assertEquals("Chapter", withTimeout(20_000) { pending.await() }.title)
                var released = false
                assertTrue(plan.removeIfUnchanged { released = true })
                assertTrue(released)
            }
        }
    }

    private fun remoteChapter(server: NanoHTTPD): BookChapter {
        database.bookSourceDao.insert(source.copy(ruleContent = ContentRule(content = "tag.div.0@text")))
        return chapter.copy(url = "http://127.0.0.1:${server.listeningPort}/chapter").also {
            database.bookChapterDao.delByBook(book.bookUrl)
            database.bookChapterDao.insert(it)
        }
    }

    private suspend fun withServer(block: suspend (NanoHTTPD, CountDownLatch, CountDownLatch) -> Unit) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "HTTP fixture timed out" }
                return newFixedLengthResponse("<div>Remote body</div>")
            }
        }
        server.start()
        try { block(server, entered, release) } finally { release.countDown(); server.stop() }
    }
}
