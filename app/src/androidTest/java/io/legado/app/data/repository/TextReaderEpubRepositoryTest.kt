package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.bookCacheKey
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.model.TextReaderChapterKey
import io.legado.app.model.localBook.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class TextReaderEpubRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val leases = CacheResourceLeases()
    private val repository = TextReaderEpubRepository(database, leases)
    private val id = UUID.randomUUID().toString()
    private val file = File(context.cacheDir, "owned-reader-$id.epub")
    private val book = Book(bookUrl = file.absolutePath, origin = BookType.localTag, originName = file.name,
        name = "EPUB fixture $id", author = id, type = BookType.local or BookType.text, charset = "UTF-8")
    private val owner = ReaderProgressOwner(id, "epub-owner-$id", 0)
    private lateinit var chapters: List<BookChapter>
    private fun key(index: Int = 0) = TextReaderChapterKey(owner, book.bookUrl, book.origin, index, 1)
    private fun address(reference: String) = checkNotNull(EpubResourceAddress.resolve("OEBPS/Text/main.xhtml", reference))
    @Before fun setup() {
        writeEpub(file)
        chapters = EpubFile.getChapterList(book)
        database.bookDao.insert(book); database.bookChapterDao.insert(*chapters.toTypedArray())
        database.readerSessionDao.claim(ReaderSessionState(id, book.bookUrl, book.bookUrl, true, false,
            updatedAt = System.currentTimeMillis()), owner.token)
    }
    @After fun cleanup() {
        BookHelp.clearCache(book); EpubFile.clear(book.bookUrl)
        book.coverUrl?.let { File(it).delete() }; file.delete(); database.close()
    }

    @Test fun anExplicitTopAnchorInALaterFragmentWinsOverTheFileRootFallback() = runBlocking {
        val path="OEBPS/Text/main.xhtml"
        val source=object : EpubReadingSource {
            override fun chapter(book: Book, chapter: BookChapter)=EpubReadingChapter(listOf(EpubReadingResource(
                EpubResourceAddress.fromPath(path)!!,
                if(chapter.index==0) "<p>File beginning</p>" else "<p id='top'>Later target</p>",
                startsAtRoot=chapter.index==0)))
            override fun resource(book: Book,address: EpubResourceAddress)=EpubReadingResource(
                EpubResourceAddress.fromPath(path)!!,"<p>File beginning</p><p id='top'>Later target</p>")
        }
        val repository=TextReaderEpubRepository(database,leases,source)
        assertEquals(1,repository.targetChapter(key(),address("#top"))!!.index)
        assertEquals(0,repository.targetChapter(key(),address("#TOP"))!!.index)
        assertEquals(0,repository.targetChapter(key(),address(""))!!.index)
    }
    @Test fun rawChaptersRetainResourceIdentityAnchorsStylesAndSemanticNoteReferences() = runBlocking {
        val original = EpubFile.getContent(book, chapters[0])
        val chapter = repository.chapter(key())
        assertEquals(listOf("OEBPS/Text/main.xhtml"), chapter.parts.map { it.address.path })
        val html = Jsoup.parse(chapter.parts.single().html)
        assertEquals("epub:/OEBPS/Notes/notes.xhtml#same", html.selectFirst("a[epub:type=noteref]")!!.attr("href"))
        assertEquals("epub:/OEBPS/Text/main.xhtml#same", html.selectFirst("a.local")!!.attr("href"))
        assertEquals("font-weight:bold;color:#ff0000", html.getElementById("same")!!.attr("style"))
        assertEquals("OEBPS/Images/picture.png", html.selectFirst("img")!!.attr("src"))
        assertEquals("120", html.selectFirst("img")!!.attr("width"))
        assertEquals("footnote", html.getElementById("inline-note")!!.attr("epub:type"))
        assertTrue(html.getElementById("inline-note")!!.text().contains("Inline hidden note"))
        assertEquals(original, EpubFile.getContent(book, chapters[0]))
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun nonSpineNotesRetainHiddenContentAriaAndBackReferences() = runBlocking {
        assertFalse(chapters.any { it.url.contains("notes.xhtml") })
        val resource = repository.resource(key(), address("../Notes/notes.xhtml#same"))!!
        assertEquals("OEBPS/Notes/notes.xhtml", resource.address.path); assertNull(resource.address.fragment)
        val html = Jsoup.parse(resource.html)
        assertEquals("doc-footnote", html.getElementById("same")!!.attr("role"))
        assertTrue(html.text().contains("Hidden note β😀"))
        assertEquals("epub:/OEBPS/Text/main.xhtml#same", html.selectFirst("a")!!.attr("href"))
        val parsed = TextReaderHtml.parse(resource.html)
        assertTrue(parsed.anchors.single { it.id == "same" }.note)
        assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun identicalIdsInDifferentFilesNeverResolveToTheWrongResource() = runBlocking {
        val main = repository.resource(key(), address("#same"))!!
        val notes = repository.resource(key(), address("../Notes/notes.xhtml#same"))!!
        assertNotEquals(main.address, notes.address)
        assertTrue(Jsoup.parse(main.html).getElementById("same")!!.text().contains("Main target"))
        assertTrue(Jsoup.parse(notes.html).getElementById("same")!!.text().contains("Hidden note"))
    }
    @Test fun onlyManifestHtmlResourcesAreReturnedAndActiveContentIsRemoved() = runBlocking {
        assertNull(repository.resource(key(), address("../unlisted.xhtml")))
        assertNull(repository.resource(key(), address("../Images/picture.png")))
        val html = Jsoup.parse(repository.resource(key(), address("#same"))!!.html)
        assertTrue(html.select("script, style, base").isEmpty())
        assertFalse(html.getAllElements().any { element -> element.attributes().asList().any { it.key.startsWith("on", true) } })
        assertFalse(html.selectFirst("a.unsafe")!!.hasAttr("href"))
        assertEquals("https://example.invalid/target", html.selectFirst("a.external")!!.attr("href"))
    }
    @Test fun xmlDeclaredUtf16IsDecodedThroughTheResourceReader() = runBlocking {
        val html = repository.resource(key(), address("../Notes/utf16.xhtml"))!!.html
        assertTrue(html.contains("UTF16 注释 β😀")); assertFalse(html.contains('\uFFFD'))
    }
    @Test fun plusSpacesAndLiteralPercentPathsKeepTheirExactManifestResource() = runBlocking {
        val target = address("../Notes/n+ote%20%2520.xhtml#same+id")
        val note = repository.resource(key(), target)
        assertNotNull("The manifest's plus and literal percent filename must resolve", note)
        assertEquals("OEBPS/Notes/n+ote %20.xhtml", note!!.address.path)
        assertTrue(Jsoup.parse(note.html).getElementById("same+id")!!.text().contains("Encoded filename"))
    }
    @Test fun imagesKeepLiteralPlusAndPercentNamesInBothRawAndLegacyExtraction() = runBlocking {
        val raw = "OEBPS/Images/image+ %20.png"
        val document = Jsoup.parse(repository.chapter(key()).parts.single().html)
        assertEquals(raw, document.select("img").last()!!.attr("src"))
        assertTrue(EpubFile.getContent(book, chapters[0])!!.contains(raw))
        val expected = EpubFile.getImage(book, "OEBPS/Images/picture.png")!!.use { it.readBytes() }
        assertArrayEquals(expected, EpubFile.getImage(book, raw)!!.use { it.readBytes() })
        assertArrayEquals(expected, EpubFile.getImage(book, "OEBPS/Images/image+%20%2520.png")!!.use { it.readBytes() })
    }
    @Test fun ncxAndNav3ResolveEncodedChapterNamesWithoutFormOrDoubleDecoding() = runBlocking {
        for (nav3 in listOf(false, true)) {
            EpubFile.clear(book.bookUrl)
            writeEpub(file, "Text/next+chapter %20.xhtml", nav3)
            val actual = EpubFile.getChapterList(book)
            assertEquals("Navigation format nav3=$nav3", listOf("OEBPS/Text/main.xhtml", "OEBPS/Text/next+chapter %20.xhtml"), actual.map { it.url })
            val resource = repository.resource(key(), address("../Text/next+chapter%20%2520.xhtml"))
            assertNotNull(resource); assertTrue(resource!!.html.contains("Next chapter"))
        }
    }
    @Test fun generatedCachesAndUserEditsAreNotOverwrittenByRawExtraction() = runBlocking {
        BookHelp.saveText(book, chapters[0], "User edited chapter $id")
        val cached = BookHelp.getContent(book, chapters[0])
        assertTrue(repository.chapter(key()).parts.single().html.contains("Main target"))
        assertEquals(cached, BookHelp.getContent(book, chapters[0]))
    }
    @Test fun aReaderWithoutTheCurrentOwnerCannotReadOtherBookResources() = runBlocking {
        database.readerSessionDao.close(id, owner.token)
        val error = runCatching { repository.resource(key(), address("../Notes/notes.xhtml")) }.exceptionOrNull()
        assertEquals("Reader no longer owns this book", error?.message)
    }
    @Test fun aLateResourceIsRejectedAfterOwnerRevocationAndItsLeaseIsReleased() = runBlocking {
        blockedRead { pending, release ->
            database.readerSessionDao.close(id, owner.token); release.countDown()
            assertEquals("Reader no longer owns this book", pending.await().exceptionOrNull()?.message)
        }
    }
    @Test fun aLateResourceIsRejectedWhenTheOwningChapterWasReplaced() = runBlocking {
        blockedRead { pending, release ->
            database.bookChapterDao.insert(chapters[0].copy(url = "OEBPS/replaced.xhtml")); release.countDown()
            assertEquals("Reader chapter changed", pending.await().exceptionOrNull()?.message)
        }
    }
    @Test fun cancelledResourceReadsReleaseTheirLeaseWithoutPublishingAResult() = runBlocking {
        blockedRead { pending, release ->
            pending.cancel(); release.countDown(); pending.join(); assertTrue(pending.isCancelled)
            assertEquals(0, database.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        }
    }
    private suspend fun blockedRead(assertRead: suspend (Deferred<Result<EpubReadingResource?>>, CountDownLatch) -> Unit) = coroutineScope {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = object : EpubReadingSource {
            override fun chapter(book: Book, chapter: BookChapter) = EpubFile.getReaderChapter(book, chapter)
            override fun resource(book: Book, address: EpubResourceAddress): EpubReadingResource? {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                return EpubFile.getReaderResource(book, address)
            }
        }
        val pending = async(Dispatchers.IO) { runCatching { TextReaderEpubRepository(database, leases, source).resource(key(), address("../Notes/notes.xhtml")) } }
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            leases.plan(bookCacheKey(book)).use { assertFalse(it.removeIfUnchanged { fail("Active EPUB must retain its resource lease") }) }
            assertRead(pending, release)
        } finally { release.countDown(); pending.join() }
        leases.plan(bookCacheKey(book)).use { assertTrue(it.removeIfUnchanged {}) }
    }

    private fun writeEpub(file: File, nextPath: String = "Text/next.xhtml", nav3: Boolean = false) {
        val nextHref = java.net.URI(null, null, nextPath, null).toASCIIString()
        val toc = if (nav3) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
            else """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        val spine = if (nav3) "<spine>" else """<spine toc="ncx">"""
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package version="${if (nav3) "3.0" else "2.0"}" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>Reader fixture</dc:title><dc:language>en</dc:language></metadata><manifest>$toc<item id="main" href="Text/main.xhtml" media-type="application/xhtml+xml"/><item id="next" href="$nextHref" media-type="application/xhtml+xml"/><item id="notes" href="Notes/notes.xhtml" media-type="application/xhtml+xml"/><item id="encoded" href="Notes/n+ote%20%2520.xhtml" media-type="application/xhtml+xml"/><item id="utf16" href="Notes/utf16.xhtml" media-type="application/xhtml+xml"/><item id="image" href="Images/picture.png" media-type="image/png"/><item id="encodedImage" href="Images/image+%20%2520.png" media-type="image/png"/></manifest>$spine<itemref idref="main"/><itemref idref="next"/></spine></package>"""
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Reader fixture</text></docTitle><navMap><navPoint id="one" playOrder="1"><navLabel><text>Main</text></navLabel><content src="Text/main.xhtml"/></navPoint><navPoint id="two" playOrder="2"><navLabel><text>Next</text></navLabel><content src="$nextHref"/></navPoint></navMap></ncx>"""
        fun xhtml(body: String, encoding: String = "UTF-8") = """<?xml version="1.0" encoding="$encoding"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Fixture</title></head><body>$body</body></html>"""
        val main = xhtml("""<p id="same" style="font-weight:bold;color:#ff0000">Main target</p><p><a epub:type="noteref" href="../Notes/notes.xhtml#same">Reference</a> <a class="local" href="#same">Local</a><a class="unsafe" href="javascript:alert(1)" onclick="alert(2)">Unsafe</a><a class="external" href="https://example.invalid/target">External</a></p><img src="../Images/picture.png" width="120"/><img src="../Images/image+%20%2520.png"/><aside id="inline-note" epub:type="footnote" style="display:none">Inline hidden note</aside><script>unwanted()</script><style>.bad{}</style><base href="https://other.invalid/"/>""")
        val notes = xhtml("""<aside id="same" role="doc-footnote" style="display:none"><p>Hidden note β😀 <a href="../Text/main.xhtml#same">Back</a></p></aside>""")
        val nav = xhtml("""<nav epub:type="toc"><ol><li><a href="Text/main.xhtml">Main</a></li><li><a href="$nextHref">Next</a></li></ol></nav>""")
        val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val image = try { java.io.ByteArrayOutputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
        } } finally { bitmap.recycle() }
        val entries = linkedMapOf("mimetype" to "application/epub+zip".toByteArray(), "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(), "OEBPS/toc.ncx" to ncx.toByteArray(), "OEBPS/nav.xhtml" to nav.toByteArray(), "OEBPS/Text/main.xhtml" to main.toByteArray(),
            "OEBPS/$nextPath" to xhtml("<p>Next chapter</p>").toByteArray(), "OEBPS/Notes/notes.xhtml" to notes.toByteArray(),
            "OEBPS/Notes/n+ote %20.xhtml" to xhtml("<p id='same+id'>Encoded filename</p>").toByteArray(),
            "OEBPS/Notes/utf16.xhtml" to xhtml("<p>UTF16 注释 β😀</p>", "UTF-16").toByteArray(Charsets.UTF_16),
            "OEBPS/unlisted.xhtml" to xhtml("<p>Not in manifest</p>").toByteArray(), "OEBPS/Images/picture.png" to image,
            "OEBPS/Images/image+ %20.png" to image)
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, data) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(data); zip.closeEntry()
        } }
    }
}
