package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.isXml
import io.legado.app.utils.printOnDebug
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

class EpubFile(var book: Book) : AutoCloseable {

    companion object : BaseLocalBookParse {
        private val cache = CloseableCache<EpubFile>()

        @Synchronized
        private fun getEFile(book: Book): EpubFile {
            return cache.getOrCreate(
                matches = { it.openedBookUrl == book.bookUrl },
                create = { EpubFile(book) },
            ).also { it.book = book }
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        @Synchronized
        fun getToc(book: Book): List<EpubTocNode> =
            epubTocNodes(getEFile(book).epubBook?.tableOfContents?.tocReferences.orEmpty())

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        @Synchronized
        fun getReaderChapter(book: Book, chapter: BookChapter): EpubReadingChapter? =
            getEFile(book).getReaderChapter(chapter)

        @Synchronized
        fun getReaderResource(book: Book, address: EpubResourceAddress): EpubReadingResource? =
            getEFile(book).getReaderResource(address)

        @Synchronized
        fun getReaderResourceByKey(book: Book, key: String): EpubReadingResource? {
            if (!key.matches(Regex("[0-9a-f]{64}"))) return null
            val file = getEFile(book)
            val address = file.epubBook?.resources?.all?.mapNotNull { EpubResourceAddress.fromPath(it.href) }
                ?.singleOrNull { it.key == key } ?: return null
            return file.getReaderResource(address)
        }

        @Synchronized
        fun repairCachedContent(book: Book, chapter: BookChapter, content: String): String =
            getEFile(book).repairCachedContent(chapter, content)

        @Synchronized
        override fun getImage(
            book: Book,
            href: String
        ): InputStream? {
            return getEFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            return getEFile(book).upBookInfo()
        }

        @Synchronized
        fun clear() {
            cache.clear()
        }

        @Synchronized
        fun clear(bookUrl: String) {
            cache.clearIf { it.openedBookUrl == bookUrl }
        }
    }

    private val openedBookUrl = book.bookUrl

    private var mCharset: Charset = Charset.defaultCharset()

    /**
     *持有引用，避免被回收
     */
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var zipFile: AndroidZipFile? = null
    private var readingBoundaries: Map<String, BookChapter>? = null
    private val checkedContentCaches = HashSet<String>()
    private var epubBook: EpubBook? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = readEpub()
            }
            return field
        }
    private var epubBookContents: List<Resource>? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = epubBook?.contents
            }
            return field
        }

    init {
        upBookCover(true)
    }

    /**
     * 重写epub文件解析代码，直接读出压缩包文件生成Resources给epublib，这样的好处是可以逐一修改某些文件的格式错误
     */
    private fun readEpub(): EpubBook? {
        return kotlin.runCatching {
            close()
            BookHelp.getBookPFD(book)?.let { descriptor ->
                val openedZip = try {
                    AndroidZipFile(descriptor, book.originName)
                } catch (error: Throwable) {
                    descriptor.close()
                    throw error
                }
                fileDescriptor = descriptor
                zipFile = openedZip
                EpubReader().readEpubLazy(openedZip, "utf-8")
            }
        }.onFailure {
            close()
            AppLog.put("读取Epub文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrThrow()
    }

    private fun readingBoundary(chapter: BookChapter): BookChapter {
        if (readingBoundaries == null) getChapterList()
        return readingBoundaries?.get(chapter.url) ?: chapter
    }

    private fun repairCachedContent(chapter: BookChapter, content: String): String {
        val boundary = readingBoundary(chapter)
        if (!checkedContentCaches.add(chapter.url)) return content
        val changedBoundary = boundary.startFragmentId != chapter.startFragmentId ||
            boundary.endFragmentId != chapter.endFragmentId ||
            boundary.getVariable("nextUrl") != chapter.getVariable("nextUrl")
        val crossResourceFragment = !chapter.endFragmentId.isNullOrBlank() &&
            chapter.url.substringBeforeLast("#") != chapter.getVariable("nextUrl").substringBeforeLast("#")
        if (!changedBoundary && !crossResourceFragment) return content
        // Only replace an unchanged output of the old reader. A user's edited text wins.
        if (content != getContent(chapter, legacyBoundary = true)) return content
        return getContent(chapter) ?: content
    }

    private fun chapterElements(chapter: BookChapter, legacyBoundary: Boolean = false,
        reader: Boolean = false): List<Pair<String, Element>>? {
        /*获取当前章节文本*/
        val contents = epubBookContents ?: return null
        val boundary = if (legacyBoundary) chapter else readingBoundary(chapter)
        val nextChapterFirstResourceHref = boundary.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = boundary.startFragmentId
        val endFragmentId = boundary.endFragmentId
        val elements = mutableListOf<Pair<String, Element>>()
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        /*一些书籍依靠href索引的resource会包含多个章节，需要依靠fragmentId来截取到当前章节的内容*/
        /*注:这里较大增加了内容加载的时间，所以首次获取内容后可存储到本地cache，减少重复加载*/
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.href) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                elements.add(res.href to
                    getBody(res, startFragmentId,
                        endFragmentId.takeIf { legacyBoundary || currentChapterFirstResourceHref == nextChapterFirstResourceHref }, reader)
                )
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.href == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.href) {
                // 其余部分
                elements.add(res.href to getBody(res, null, null, reader))
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    //有Fragment 则添加到上一章节
                    elements.add(res.href to getBody(res, null, endFragmentId, reader))
                }
                break
            }
        }
        return elements
    }

    private fun getContent(chapter: BookChapter, legacyBoundary: Boolean = false): String? {
        val elements = Elements(chapterElements(chapter, legacyBoundary)?.map { it.second } ?: return null)
        //title标签中的内容不需要显示在正文中，去除
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        elements.select("img[src=\"cover.jpeg\"]").forEachIndexed { i, it ->
            if (i > 0) it.remove()
        }
        elements.select("img").forEach {
            if (it.attributesSize() <= 1) {
                return@forEach
            }
            val src = it.attr("src")
            it.clearAttributes()
            it.attr("src", src)
        }
        val tag = Book.rubyTag
        if (book.getDelTag(tag)) {
            elements.select("rp, rt").remove()
        }
        val html = elements.outerHtml()
        return HtmlFormatter.formatKeepImg(html)
    }

    private fun getReaderChapter(chapter: BookChapter): EpubReadingChapter? {
        val parts = chapterElements(chapter, reader = true) ?: return null
        if (parts.isEmpty()) return null
        return EpubReadingChapter(parts.mapIndexed { index, (href, body) ->
            val address = checkNotNull(EpubResourceAddress.fromPath(href)) { "Invalid EPUB resource path" }
            readerResource(address, body).copy(startsAtRoot = index > 0 || readingBoundary(chapter).startFragmentId.isNullOrBlank())
        }, getContent(chapter)?.let(::epubCacheText))
    }

    private fun getReaderResource(address: EpubResourceAddress): EpubReadingResource? {
        // Use manifest resources, including non-spine notes. Never interpret the path as a file/URL.
        val resource = epubBook?.resources?.all?.singleOrNull {
            EpubResourceAddress.fromPath(it.href)?.path == address.path &&
                it.mediaType.name in setOf("application/xhtml+xml", "text/html")
        } ?: return null
        val body = Jsoup.parse(resource.reader.use { it.readText() }).body()
        return readerResource(address.copyWithoutFragment(), body)
    }

    private fun readerResource(address: EpubResourceAddress, body: Element): EpubReadingResource {
        body.select("script, style, title, base").remove()
        body.getAllElements().forEach { element ->
            element.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { element.removeAttr(it.key) }
        }
        body.select("a[href], area[href]").forEach { element ->
            val href = element.attr("href").trim()
            val target = EpubResourceAddress.resolve(address.path, href)
            when {
                target != null -> element.attr("href", target.href)
                href.startsWith("https://", true) || href.startsWith("http://", true) -> element.attr("href", href)
                else -> element.removeAttr("href")
            }
        }
        body.select("image").forEach { element ->
            element.tagName("img", Parser.NamespaceHtml); element.attr("src", element.attr("xlink:href"))
        }
        body.select("img[src]").forEach { element ->
            val source = element.attr("src").trim()
            val target = EpubResourceAddress.resolve(address.path, source)
            when {
                target != null -> element.attr("src", target.path)
                source.startsWith("https://", true) || source.startsWith("http://", true) -> element.attr("src", source)
                else -> element.removeAttr("src")
            }
        }
        return EpubReadingResource(address, body.outerHtml())
    }

    private fun EpubResourceAddress.copyWithoutFragment() = checkNotNull(EpubResourceAddress.fromPath(path))

    private fun getBody(res: Resource, startFragmentId: String?, endFragmentId: String?, reader: Boolean = false): Element {
        if (reader && res.mediaType.name.startsWith("image/")) {
            val body = Jsoup.parseBodyFragment("<img>").body()
            body.selectFirst("img")!!.attr("src", checkNotNull(EpubResourceAddress.fromPath(res.href)).href)
            return body
        }
        /**
         * <image width="1038" height="670" xlink:href="..."/>
         * ...titlepage.xhtml
         * 大多数epub文件的封面页都会带有cover，可以一定程度上解决封面读取问题
         */
        if (!reader && (res.href.contains("titlepage.xhtml") || res.href.contains("cover"))) {
            return Jsoup.parseBodyFragment("<img src=\"cover.jpeg\" />")
        }

        // Jsoup可能会修复不规范的xhtml文件 解析处理后再获取
        var bodyElement = Jsoup.parse(if (reader) res.reader.use { it.readText() } else String(res.data, mCharset)).body()
        bodyElement.children().run {
            select("script").remove()
            select("style").remove()
        }
        // 获取body对应的文本
        var bodyString = bodyElement.outerHtml()
        val originBodyString = bodyString
        /**
         * 某些xhtml文件 章节标题和内容不在一个节点或者不是兄弟节点
         * <div>
         *    <a class="mulu1>目录1</a>
         * </div>
         * <p>....</p>
         * <div>
         *    <a class="mulu2>目录2</a>
         * </div>
         * <p>....</p>
         * 先找到FragmentId对应的Element 然后直接截取之间的html
         */
        if (!startFragmentId.isNullOrBlank()) {
            bodyElement.getElementById(startFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = tagStart + bodyString.substringAfter(tagStart)
            }
        }
        if (!endFragmentId.isNullOrBlank() && endFragmentId != startFragmentId) {
            bodyElement.getElementById(endFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = bodyString.substringBefore(tagStart)
            }
        }
        //截取过再重新解析
        if (bodyString != originBodyString) {
            bodyElement = Jsoup.parse(bodyString).body()
        }
        /*选择去除正文中的H标签，部分书籍标题与阅读标题重复待优化*/
        val tag = Book.hTag
        if (book.getDelTag(tag)) {
            bodyElement.run {
                select("h1, h2, h3, h4, h5, h6").remove()
                //getElementsMatchingOwnText(chapter.title)?.remove()
            }
        }
        if (reader) return bodyElement
        bodyElement.select("image").forEach {
            it.tagName("img", Parser.NamespaceHtml)
            it.attr("src", it.attr("xlink:href"))
        }
        bodyElement.select("img").forEach {
            val src = it.attr("src").trim()
            it.attr("src", EpubResourceAddress.resolve(res.href, src)?.path ?: src)
        }
        return bodyElement
    }

    private fun getImage(href: String): InputStream? {
        if (href == "cover.jpeg") return epubBook?.coverImage?.inputStream
        val resources = epubBook?.resources ?: return null
        if (href.startsWith("data:", true)) return resources.getByHref(href)?.inputStream
        // New extraction supplies decoded paths. Resolve an encoded legacy URL only after exact lookup.
        val exact = resources.all.firstOrNull { it.href == href }
        val path = if (exact == null) EpubResourceAddress.resolve("resource", href)?.path else null
        return (exact ?: resources.all.firstOrNull { it.href == path })?.inputStream
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            epubBook?.let {
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                /*部分书籍DRM处理后，封面获取异常，待优化*/
                it.coverImage?.inputStream?.use { input ->
                    val cover = BitmapFactory.decodeStream(input)
                    val out = FileOutputStream(FileUtils.createFileIfNotExist(book.coverUrl!!))
                    cover.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                } ?: AppLog.putDebug("Epub: 封面获取为空. path: ${book.bookUrl}")
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        if (epubBook == null) {
            cache.clearIf { it === this }
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val metadata = epubBook!!.metadata
            book.name = metadata.firstTitle
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".epub", "")
            }

            if (metadata.authors.isNotEmpty()) {
                val author =
                    metadata.authors[0].toString().replace("^, |, $".toRegex(), "")
                book.author = author
            }
            if (metadata.descriptions.isNotEmpty()) {
                val desc = metadata.descriptions[0]
                book.intro = if (desc.isXml()) {
                    Jsoup.parse(metadata.descriptions[0]).text()
                } else {
                    desc
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        epubBook?.let { eBook ->
            val refs = eBook.tableOfContents.tocReferences
            if (refs == null || refs.isEmpty()) {
                AppLog.putDebug("Epub: NCX file parse error, check the file: ${book.bookUrl}")
                val spineReferences = eBook.spine.spineReferences
                var i = 0
                val size = spineReferences.size
                while (i < size) {
                    val resource = spineReferences[i].resource
                    var title = resource.title
                    if (TextUtils.isEmpty(title)) {
                        try {
                            val doc =
                                Jsoup.parse(String(resource.data, mCharset))
                            val elements = doc.getElementsByTag("title")
                            if (elements.isNotEmpty()) {
                                title = elements[0].text()
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    val chapter = BookChapter()
                    chapter.index = i
                    chapter.bookUrl = book.bookUrl
                    chapter.url = resource.href
                    if (i == 0 && title.isEmpty()) {
                        chapter.title = "封面"
                    } else {
                        chapter.title = title
                    }
                    chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                    chapterList.add(chapter)
                    i++
                }
            } else {
                parseFirstPage(chapterList, refs)
                parseMenu(chapterList, refs)
            }
        }
        // Multiple navigation labels may point to the same content. Keep the established
        // reading identities, and link boundaries between actual, distinct resources only.
        val readingChapters = ArrayList(chapterList.distinctBy { it.url })
        readingChapters.forEachIndexed { index, chapter ->
            chapter.index = index
            val next = readingChapters.getOrNull(index + 1)
            chapter.endFragmentId = next?.startFragmentId
            chapter.putVariable("nextUrl", next?.url)
        }
        getWordCount(readingChapters, book)
        readingBoundaries = readingChapters.associate { it.url to it.copy() }
        return readingChapters
    }

    /*获取书籍起始页内容。部分书籍第一章之前存在封面，引言，扉页等内容*/
    /*tile获取不同书籍风格杂乱，格式化处理待优化*/
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?
    ) {
        val contents = epubBook?.contents
        if (epubBook == null || contents == null || refs == null) return
        val firstHref = epubTocNodes(refs).firstOrNull { it.href != null }?.href ?: return
        var i = 0
        while (i < contents.size) {
            val content = contents[i]
            if (!content.mediaType.toString().contains("htm")) {
                i++
                continue
            }
            /**
             * 检索到第一章href停止
             * completeHref可能有fragment(#id) 必须去除
             * fix https://github.com/gedoor/legado/issues/1932
             */
            if (firstHref.substringBeforeLast("#") == content.href) break
            val chapter = BookChapter()
            var title = content.title
            if (TextUtils.isEmpty(title)) {
                val elements = Jsoup.parse(
                    String(epubBook!!.resources.getByHref(content.href).data, mCharset)
                ).getElementsByTag("title")
                title =
                    if (elements.isNotEmpty() && elements[0].text().isNotBlank())
                        elements[0].text()
                    else
                        "--卷首--"
            }
            chapter.bookUrl = book.bookUrl
            chapter.title = title
            chapter.url = content.href
            chapter.startFragmentId =
                if (content.href.substringAfter("#") == content.href) null
                else content.href.substringAfter("#")

            chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
            chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            i++
        }
    }

    private fun parseMenu(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?,
    ) {
        refs?.forEach { ref ->
            if (ref.resource != null) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title
                chapter.url = ref.completeHref
                chapter.startFragmentId = ref.fragmentId
                chapter.isVolume = !ref.children.isNullOrEmpty()
                chapterList.add(chapter)
            }
            if (ref.children != null && ref.children.isNotEmpty()) {
                parseMenu(chapterList, ref.children)
            }
        }
    }


    override fun close() {
        readingBoundaries = null
        checkedContentCaches.clear()
        epubBookContents = null
        epubBook = null
        val openedZip = zipFile
        val descriptor = fileDescriptor
        zipFile = null
        fileDescriptor = null
        kotlin.runCatching {
            if (openedZip != null) {
                openedZip.close()
            } else {
                descriptor?.close()
            }
        }.onFailure {
            kotlin.runCatching { descriptor?.close() }
            it.printOnDebug()
        }
    }

    private fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

}
