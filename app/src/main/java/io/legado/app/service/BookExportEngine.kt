package io.legado.app.service

import io.legado.app.model.parseExportScope
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withSave
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.ImageProvider
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.mapAsync
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.writeFile
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import me.ag2s.epublib.domain.Author
import me.ag2s.epublib.domain.Date
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.FileResourceProvider
import me.ag2s.epublib.domain.LazyResource
import me.ag2s.epublib.domain.LazyResourceProvider
import me.ag2s.epublib.domain.Metadata
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.epub.EpubWriterProcessor
import me.ag2s.epublib.util.ResourceUtil
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

import android.content.Context
import io.legado.app.data.AppDatabase
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.infoSnapshot
import io.legado.app.data.repository.persistLocalTransferChapters

/** One immutable export invocation. The platform service only owns task scheduling/notification. */
class BookExportEngine(private val context: Context,private val db: AppDatabase,
    private val options: CacheExportOptions,private val progress: (Int,Int) -> Unit,private val ownerCheck: () -> Unit,
    private val upload: suspend (android.net.Uri,String) -> Unit = { _,_ -> error("WebDAV target is missing") },
    private val publication: (() -> FileDoc) -> FileDoc = { it() },
    private val localRefresh: ((Book,List<BookChapter>) -> Unit)? = null,
    private val journal: io.legado.app.data.repository.BookExportJournal=io.legado.app.data.repository.exportJournal(context)) {
    private var chapters: List<BookChapter> = emptyList()
    private var chapterKey: String? = null
    private var bookUrl: String = ""
    private var localInput: Book? = null
    private var localModified: Long? = null
    private fun chapterKey(values: List<BookChapter>) = io.legado.app.utils.GSON.toJson(values.map {
        listOf(it.url,it.index.toString(),it.title,it.isVolume.toString(),it.start?.toString(),it.end?.toString(),
            it.startFragmentId,it.endFragmentId,it.baseUrl)
    })
    private fun verify() {
        ownerCheck()
        localInput?.let { input ->
            check(LocalBook.getLastModified(input).getOrThrow()==localModified) { "Local book changed during export" }
        }
    }
    private fun verifyCatalog() {
        verify()
        chapterKey?.let { expected ->
            check(chapterKey(db.bookChapterDao.getChapterList(bookUrl)) == expected) {
                "Book chapters changed"
            }
        }
    }
    private fun getString(id: Int,vararg args: Any)=context.getString(id,*args)
    suspend fun export(book: Book,section: String?=null,chunk: Int=1) {
        check(chapterKey == null) { "An export engine belongs to one invocation" }
        bookUrl = book.bookUrl
        options.validate(); require(chunk > 0); verify(); refreshChapterList(book)
        chapters = db.runInTransaction<List<BookChapter>> {
            verify(); db.bookChapterDao.getChapterList(book.bookUrl).map { it.infoSnapshot().also { snapshot -> snapshot.deferUpdates = true } }
        }
        chapterKey = chapterKey(chapters)
        book.totalChapterNum = chapters.size
        when(options.type) {
            "epub" -> if(section.isNullOrBlank()) exportEpub(options.directory,book) else CustomExporter(section,chunk).export(options.directory,book)
            "pdf" -> exportPdf(options.directory,book)
            else -> exportTxt(options.directory,book)
        }
        verifyCatalog()
    }
    private fun reportProgress(value: Int,total: Int) { verify(); progress(value,total) }
    private fun refreshChapterList(book: Book) {
        if(!book.isLocal) return
        val modified=LocalBook.getLastModified(book).getOrThrow()
        localInput=book; localModified=modified
        if(modified<=book.latestChapterTime) return
        val refreshed=LocalBook.getChapterList(book)
        check(LocalBook.getLastModified(book).getOrThrow()==modified) { "Local book changed while parsing" }
        book.latestChapterTime=modified
        verify()
        localRefresh?.invoke(book,refreshed) ?: db.runInTransaction {
            verify()
            persistLocalTransferChapters(db,book,refreshed)
        }
    }

    private data class SrcData(
        val chapterTitle: String,
        val index: Int,
        val src: String
    )

    private fun getChapterContentForExport(book: Book, chapter: BookChapter): String? {
        verify()
        return resolveExportChapterContent(
            content = BookHelp.getContent(book, chapter),
            isVolume = chapter.isVolume
        )?.let(::sanitizeExportContent)
    }

    private suspend fun exportTxt(path: String, book: Book) {

        val fileDoc = FileDoc.fromDir(path)
        exportTxt(fileDoc, book)
    }

    private suspend fun exportTxt(fileDoc: FileDoc, book: Book) {
        val filename = book.getExportFileName("txt",options.fileName)

        val bookDoc = stagedExport(fileDoc, filename) { staging ->
            val charset = Charset.forName(options.charset)
            staging.openOutputStream().getOrThrow().use { output ->
                output.bufferedWriter(charset).use { bw ->
                    getAllContents(book) { text, srcList ->
                        verify()
                        bw.write(text)
                        srcList?.forEach {
                            val vFile = BookHelp.getImage(book, it.src)
                            if (vFile.exists()) {
                                fileDoc.createFileIfNotExist(
                                    "${it.index}-${MD5Utils.md5Encode16(it.src)}.jpg",
                                    subDirs = arrayOf(
                                        "${book.name}_${book.author}",
                                        "images",
                                        it.chapterTitle
                                    )
                                ).writeFile(vFile)
                            }
                        }
                    }
                }
            }
        }
        if (options.webDav) {
            // 导出到webdav
            verify(); upload(bookDoc.uri, filename); verify()
        }
    }

    private suspend fun exportPdf(path: String, book: Book) {

        exportPdf(FileDoc.fromDir(path), book)
    }

    private suspend fun exportPdf(fileDoc: FileDoc, book: Book) {
        if (book.isPdf) {
            throw NoStackTraceException("PDF 导出暂不支持本地 PDF")
        }
        val filename = book.getExportFileName("pdf",options.fileName)
        journal.checkDestination(fileDoc,filename)
        val fileStem = filename.substringBeforeLast('.', filename)
        val nonce = System.nanoTime().toString(16)
        val stagingName = ".$fileStem.$nonce.pending.pdf"
        val backupName = ".$fileStem.$nonce.previous.pdf"
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val contentWidth = (pageWidth - margin * 2).toInt()
        val contentBottom = pageHeight - margin
        val paragraphSpacing = 6f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 12f
        }
        val chapterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
        }
        val pdf = PdfDocument()
        var pageNumber = 0
        var currentPage: PdfDocument.Page? = null
        var y = margin

        fun finishPage() {
            currentPage?.let { pdf.finishPage(it) }
            currentPage = null
        }

        fun startPage() {
            finishPage()
            val pageInfo = PdfDocument.PageInfo.Builder(
                pageWidth,
                pageHeight,
                ++pageNumber
            ).create()
            currentPage = pdf.startPage(pageInfo).apply {
                canvas.drawColor(Color.WHITE)
            }
            y = margin
        }

        fun drawTextBlock(
            rawText: String,
            paint: TextPaint,
            spacing: Float = paragraphSpacing,
            preserveLeadingWhitespace: Boolean = false
        ) {
            val text = if (preserveLeadingWhitespace) rawText.trimEnd() else rawText.trim()
            if (text.isBlank()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.15f)
                .setIncludePad(false)
                .build()
            if (layout.lineCount == 0) return
            val lineTops = IntArray(layout.lineCount) { layout.getLineTop(it) }
            val lineBottoms = IntArray(layout.lineCount) { layout.getLineBottom(it) }
            var startLine = 0
            while (startLine < layout.lineCount) {
                if (currentPage == null) startPage()
                var availableHeight = (contentBottom - y).toInt().coerceAtLeast(0)
                val firstLineHeight = lineBottoms[startLine] - lineTops[startLine]
                if (availableHeight < firstLineHeight && y > margin) {
                    startPage()
                    availableHeight = (contentBottom - y).toInt()
                }
                val endLine = findPdfPageLineEnd(
                    lineTops,
                    lineBottoms,
                    startLine,
                    availableHeight
                )
                val blockTop = lineTops[startLine]
                val blockBottom = lineBottoms[endLine - 1]
                val blockHeight = (blockBottom - blockTop).toFloat()
                currentPage!!.canvas.withSave {
                    clipRect(margin, y, margin + contentWidth.toFloat(), y + blockHeight)
                    translate(margin, y - blockTop.toFloat())
                    layout.draw(this)
                }
                y += blockHeight
                startLine = endLine
                if (startLine < layout.lineCount) startPage()
            }
            y += spacing
        }

        fun drawImage(path: String): Boolean {
            val bitmap = decodePdfBitmap(
                path,
                contentWidth,
                (contentBottom - margin).toInt()
            ) ?: return false
            try {
                if (currentPage == null) startPage()
                val fullPageHeight = contentBottom - margin
                val fullPageScale = minOf(
                    1f,
                    contentWidth / bitmap.width.toFloat(),
                    fullPageHeight / bitmap.height.toFloat()
                )
                if (y + bitmap.height * fullPageScale > contentBottom && y > margin) {
                    startPage()
                }
                val availableHeight = (contentBottom - y).coerceAtLeast(1f)
                val scale = minOf(
                    1f,
                    contentWidth / bitmap.width.toFloat(),
                    availableHeight / bitmap.height.toFloat()
                )
                val drawWidth = max(1f, bitmap.width * scale)
                val drawHeight = max(1f, bitmap.height * scale)
                val left = margin + (contentWidth - drawWidth) / 2f
                currentPage!!.canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(left, y, left + drawWidth, y + drawHeight),
                    null
                )
                y += drawHeight + paragraphSpacing
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            return true
        }

        suspend fun renderPdf(stagingDoc: FileDoc) {
            var renderingError: Throwable? = null
            try {
                drawTextBlock(book.name, titlePaint, 12f)
                drawTextBlock(getString(R.string.author_show, book.getRealAuthor()), metaPaint, 6f)
                drawTextBlock(
                    getString(
                        R.string.intro_show,
                        "\n" + HtmlFormatter.format(book.getDisplayIntro())
                    ),
                    metaPaint,
                    16f
                )
                val useReplace = options.useReplace && book.getUseReplaceRule()
                val contentProcessor = ContentProcessor.create(book,db)
                val titleReplaceRules = contentProcessor.getTitleReplaceRules()
                val replaceBook = book.toReplaceBook()
                val bookSource = book.getBookSource()
                chapters
                    .forEachIndexed { index, chapter ->
                        currentCoroutineContext().ensureActive()

                        reportProgress(index,book.totalChapterNum)
                        val exportChapter = chapter.infoSnapshot().also { it.isVip=false; it.deferUpdates=true }
                        val rawContent = getChapterContentForExport(book, exportChapter)
                        val contentBlocks: List<PdfContentBlock> = if (book.isImage) {
                            imagePdfContentBlocks(rawContent, exportChapter.isVolume)
                        } else {
                            val textContent = rawContent ?: return@forEachIndexed
                            val displayContent = sanitizeExportContent(
                                contentProcessor.getContent(
                                    book,
                                    exportChapter,
                                    textContent,
                                    includeTitle = false,
                                    useReplace = useReplace,
                                    chineseConvert = false,
                                    reSegment = false
                                ).toString()
                            )
                            splitPdfContentBlocks(displayContent)
                        }
                        if (!options.omitNames) {
                            drawTextBlock(
                                exportChapter.getDisplayTitle(
                                    titleReplaceRules,
                                    useReplace = useReplace,
                                    replaceBook = replaceBook
                                ).replace("\uD83D\uDD12", ""),
                                chapterPaint,
                                10f
                            )
                        }
                        contentBlocks.forEach { block ->
                            when (block) {
                                is PdfContentBlock.Text -> block.value.lineSequence()
                                    .forEach { line ->
                                        drawTextBlock(
                                            line,
                                            bodyPaint,
                                            preserveLeadingWhitespace = true
                                        )
                                    }

                                is PdfContentBlock.Image -> {
                                    currentCoroutineContext().ensureActive()
                                    val src = NetworkUtils.getAbsoluteURL(
                                        exportChapter.url,
                                        block.source
                                    )
                                    val image = ImageProvider.cacheImage(book, src, bookSource)
                                    val rendered = image.exists() && drawImage(image.absolutePath)
                                    if (book.isImage && !rendered) {
                                        throw NoStackTraceException(
                                            "图片导出失败: ${exportChapter.title}"
                                        )
                                    }
                                }
                            }
                        }
                        y += 8f
                    }
                finishPage()
                stagingDoc.openOutputStream().getOrThrow().use { pdf.writeTo(it) }
            } catch (error: Throwable) {
                renderingError = error
                throw error
            } finally {
                var closeError: Throwable? = null
                currentPage?.let { page ->
                    runCatching { pdf.finishPage(page) }
                        .onFailure { closeError = it }
                    currentPage = null
                }
                runCatching { pdf.close() }.onFailure { error ->
                    closeError?.addSuppressed(error) ?: run { closeError = error }
                }
                closeError?.let { error ->
                    renderingError?.addSuppressed(error) ?: throw error
                }
            }
        }

        val stagingDoc = fileDoc.createFileIfNotExist(stagingName)
        val bookDoc = try {
            renderPdf(stagingDoc)
            currentCoroutineContext().ensureActive()
            installPdfExport(fileDoc, stagingDoc, filename, backupName)
        } finally {
            cleanupStaging(fileDoc,stagingDoc,"PDF 临时文件")
        }

        if (options.webDav) {
            verify(); upload(bookDoc.uri, filename); verify()
        }
    }

    private fun decodePdfBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return SvgUtils.createBitmap(path, reqWidth, reqHeight)
        }
        val sampleSize = calculatePdfBitmapSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            reqWidth,
            reqHeight
        )
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private suspend fun stagedExport(folder: FileDoc, filename: String, write: suspend (FileDoc) -> Unit): FileDoc {
        verify()
        journal.checkDestination(folder,filename)
        val nonce = java.util.UUID.randomUUID().toString()
        val staging = folder.createFileIfNotExist(".$nonce.pending.$filename")
        return try {
            write(staging)
            currentCoroutineContext().ensureActive()
            installPdfExport(folder,staging,filename,".$nonce.previous.$filename")
        } finally {
            cleanupStaging(folder,staging,"导出临时文件")
        }
    }

    private fun cleanupStaging(folder: FileDoc,staging: FileDoc,description: String) {
        if(!journal.isRetained(staging)) folder.find(staging.name)?.let { cleanupExportFile(it,description) }
    }

    private fun installPdfExport(
        folder: FileDoc,
        staging: FileDoc,
        filename: String,
        backupName: String
    ): FileDoc {
        verifyCatalog()
        return journal.publish(folder,staging,filename,backupName,::verifyCatalog,publication)
    }

    private fun cleanupExportFile(file: FileDoc, description: String) {
        val error = runCatching {
            if (file.exists()) {
                file.delete()
                check(!file.exists()) { "无法删除$description: ${file.name}" }
            }
        }.exceptionOrNull() ?: return
        AppLog.put("清理${description}失败", error, true)
    }

    private suspend fun getAllContents(
        book: Book,
        append: (text: String, srcList: ArrayList<SrcData>?) -> Unit
    ) = coroutineScope {
        val useReplace = options.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.create(book,db)
        val qy = "${book.name}\n${
            getString(R.string.author_show, book.getRealAuthor())
        }\n${
            getString(
                R.string.intro_show,
                "\n" + HtmlFormatter.format(book.getDisplayIntro())
            )
        }"
        append(sanitizeExportContent(qy), null)
        val threads = if (options.parallel) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        flow {
            chapters.forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(threads) { chapter ->
            getExportData(book, chapter, contentProcessor, useReplace)
        }.collectIndexed { index, result ->

            reportProgress(index,book.totalChapterNum)
            append.invoke(result.first, result.second)
        }

    }

    private fun getExportData(
        book: Book,
        chapter: BookChapter,
        contentProcessor: ContentProcessor,
        useReplace: Boolean
    ): Pair<String, ArrayList<SrcData>?> {
        val content = getChapterContentForExport(book, chapter)
            ?: return Pair("", null)
        val content1 = HtmlFormatter.format(
            contentProcessor.getContent(
                book,
                // 不导出vip标识
                chapter.apply { isVip = false },
                removeExportImages(content),
                includeTitle = !options.omitNames,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false
            ).toString()
        )
        if (options.pictures) {
            //txt导出图片文件
            val srcList = arrayListOf<SrcData>()
            content.split("\n").forEachIndexed { index, text ->
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let {
                        val src = NetworkUtils.getAbsoluteURL(chapter.url, it)
                        srcList.add(SrcData(chapter.title, index, src))
                    }
                }
            }
            return Pair("\n\n$content1", srcList)
        } else {
            return Pair("\n\n$content1", null)
        }
    }

    /**
     * 导出Epub
     */
    private suspend fun exportEpub(path: String, book: Book) {

        val fileDoc = FileDoc.fromDir(path)
        exportEpub(fileDoc, book)
    }

    private suspend fun exportEpub(fileDoc: FileDoc, book: Book) {
        val filename = book.getExportFileName("epub",options.fileName)

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook)
        //set css
        val contentModel = setAssets(fileDoc, book, epubBook)

        //设置正文
        setEpubContent(contentModel, book, epubBook)

        val bookDoc = stagedExport(fileDoc, filename) { staging ->
            staging.openOutputStream().getOrThrow().buffered().use { bookOs ->
                EpubWriter().write(epubBook, bookOs)
            }
        }

        if (options.webDav) {
            // 导出到webdav
            verify(); upload(bookDoc.uri, filename); verify()
        }
    }

    private fun setAssets(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        val customPath = doc.find("Asset")
        val contentModel = if (customPath == null) {//使用内置模板
            setAssets(book, epubBook)
        } else {//外部模板
            setAssetsExternal(customPath, book, epubBook)
        }

        return contentModel
    }

    private fun setAssetsExternal(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        var contentModel = ""
        doc.list()!!.forEach { folder ->
            if (folder.isDir && folder.name == "Text") {
                folder.list()!!.sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }.forEach loop@{ file ->
                    if (file.isDir) {
                        return@loop
                    }
                    when {
                        //正文模板
                        file.name.equals("chapter.html", true)
                                || file.name.equals("chapter.xhtml", true) -> {
                            contentModel = file.readText()
                        }
                        //封面等其他模板
                        file.name.endsWith("html", true) -> {
                            epubBook.addSection(
                                FileUtils.getNameExcludeExtension(file.name),
                                ResourceUtil.createPublicResource(
                                    book.name,
                                    book.getRealAuthor(),
                                    book.getDisplayIntro(),
                                    book.kind,
                                    book.wordCount,
                                    file.readText(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                        //其他格式文件当做资源文件
                        else -> {
                            epubBook.resources.add(
                                Resource(
                                    file.readBytes(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                    }
                }
            } else if (folder.isDir) {
                //资源文件
                folder.list()!!.forEach loop2@{
                    if (it.isDir) {
                        return@loop2
                    }
                    epubBook.resources.add(
                        Resource(
                            it.readBytes(),
                            "${folder.name}/${it.name}"
                        )
                    )
                }
            } else {//Asset下面的资源文件
                epubBook.resources.add(
                    Resource(
                        folder.readBytes(),
                        folder.name
                    )
                )
            }
        }
        return contentModel
    }

    private fun setAssets(book: Book, epubBook: EpubBook): String {
        epubBook.resources.add(
            Resource(
                context.assets.open("epub/fonts.css").readBytes(),
                "Styles/fonts.css"
            )
        )
        epubBook.resources.add(
            Resource(
                context.assets.open("epub/main.css").readBytes(),
                "Styles/main.css"
            )
        )
        epubBook.resources.add(
            Resource(
                context.assets.open("epub/logo.png").readBytes(),
                "Images/logo.png"
            )
        )
        epubBook.addSection(
            getString(R.string.img_cover),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(context.assets.open("epub/cover.html").readBytes()),
                "Text/cover.html"
            )
        )
        epubBook.addSection(
            getString(R.string.book_intro),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(context.assets.open("epub/intro.html").readBytes()),
                "Text/intro.html"
            )
        )
        return String(context.assets.open("epub/chapter.html").readBytes())
    }

    private fun setCover(book: Book, epubBook: EpubBook) {
        kotlin.runCatching {
            val request = Glide.with(context)
                .asFile()
                .load(book.getDisplayCover())
            book.getCoverSourceOrigin()?.let { sourceOrigin ->
                request.apply(
                    RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
                )
            }
            val file = request
                .submit()
                .get()
            val provider = LazyResourceProvider { _ ->
                file.inputStream()
            }
            epubBook.coverImage = LazyResource(provider, "Images/cover.jpg")
        }.onFailure {
            AppLog.put("获取书籍封面出错\n${it.localizedMessage}", it)
        }
    }

    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook
    ) = coroutineScope {
        //正文
        val useReplace = options.useReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.create(book,db)
        val replaceBook = book.toReplaceBook()
        val threads = if (options.parallel) {
            AppConst.MAX_THREAD
        } else {
            1
        }
        var parentSection: TOCReference? = null
        flow {
            chapters.forEach { chapter ->
                emit(chapter)
            }
        }.mapAsyncIndexed(threads) { index, chapter ->
            val content = getChapterContentForExport(book, chapter)
                ?: return@mapAsyncIndexed null
            val (contentFix, resources) = fixPic(
                book,
                content,
                chapter
            )
            // 不导出vip标识
            chapter.isVip = false
            val content1 = sanitizeExportContent(
                contentProcessor.getContent(
                    book,
                    chapter,
                    contentFix,
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString()
            )
            val title = chapter.run {
                // 不导出vip标识
                isVip = false
                getDisplayTitle(
                    contentProcessor.getTitleReplaceRules(),
                    useReplace = useReplace,
                    replaceBook = replaceBook
                )
            }
            val chapterResource = ResourceUtil.createChapterResource(
                title.replace("\uD83D\uDD12", ""),
                content1,
                contentModel,
                "Text/chapter_${index}.html"
            )
            ExportChapter(title, chapterResource, resources, chapter)
        }.collectIndexed { index, exportChapter ->

            reportProgress(index,book.totalChapterNum)
            if (exportChapter == null) {
                return@collectIndexed
            }
            val (title, chapterResource, resources, chapter) = exportChapter
            epubBook.resources.addAll(resources)
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(title, chapterResource)
            } else if (parentSection == null) {
                epubBook.addSection(title, chapterResource)
            } else {
                epubBook.addSection(parentSection, title, chapterResource)
            }
        }
    }

    data class ExportChapter(
        val title: String,
        val chapterResource: Resource,
        val resources: ArrayList<Resource>,
        val chapter: BookChapter
    )

    private fun fixPic(
        book: Book,
        content: String,
        chapter: BookChapter
    ): Pair<String, ArrayList<Resource>> {
        val data = StringBuilder("")
        val resources = arrayListOf<Resource>()
        content.split("\n").forEach { text ->
            var text1 = text
            val matcher = AppPattern.imgPattern.matcher(text)
            while (matcher.find()) {
                matcher.group(1)?.let {
                    val src = NetworkUtils.getAbsoluteURL(chapter.url, it)
                    val originalHref =
                        "${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val href =
                        "Images/${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val vFile = BookHelp.getImage(book, src)
                    val fp = FileResourceProvider(vFile.parent)
                    if (vFile.exists()) {
                        val img = LazyResource(fp, href, originalHref)
                        resources.add(img)
                    }
                    text1 = text1.replace(src, "../${href}")
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString() to resources
    }

    private fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    //////end of EPUB

    //////start of custom exporter
    /**
     * 自定义Exporter
     * @param scope 导出范围
     * @param size epub 文件包含最大章节数
     */
    inner class CustomExporter(scopeStr: String, private val size: Int) {

        private val scopeText = scopeStr
        private var scope: Set<Int> = emptySet()

        /**
         * 导出Epub
         * @param path 导出的路径
         * @param book 书籍
         */
        suspend fun export(
            path: String,
            book: Book
        ) {
            reportProgress(0,book.totalChapterNum)

            val currentTimeMillis = System.currentTimeMillis()
            val count = chapters.size
            scope = parseExportScope(scopeText,count)

            val fileDoc = FileDoc.fromDir(path)

            val (contentModel, epubList) = createEpubs(book, fileDoc)
            var progressBar = 0.0
            epubList.forEachIndexed { index, ep ->
                val (filename, epubBook) = ep
                //设置正文
                setEpubContent(
                    contentModel,
                    book,
                    epubBook,
                    index
                ) { _, _ ->
                    // 将章节写入内存时更新进度条

                    progressBar += book.totalChapterNum.toDouble() / scope.size / 2
                    reportProgress(progressBar.toInt(),book.totalChapterNum)
                }
                save2Drive(filename, epubBook, fileDoc) { total, _ ->
                    //写入硬盘时更新进度条
                    progressBar += book.totalChapterNum.toDouble() / epubList.size / total / 2

                    reportProgress(progressBar.toInt(),book.totalChapterNum)
                }
            }

            val elapsed = System.currentTimeMillis() - currentTimeMillis
            AppLog.put("分割导出书籍 ${book.name} 一共耗时 $elapsed")
        }


        /**
         * 设置epub正文
         *
         * @param contentModel 正文模板
         * @param book 书籍
         * @param epubBook 分割后的epub
         * @param epubBookIndex 分割后的epub序号
         */
        private suspend fun setEpubContent(
            contentModel: String,
            book: Book,
            epubBook: EpubBook,
            epubBookIndex: Int,
            updateProgress: (chapterList: MutableList<BookChapter>, index: Int) -> Unit
        ) {
            //正文
            val useReplace = options.useReplace && book.getUseReplaceRule()
            val contentProcessor = ContentProcessor.create(book,db)
            val replaceBook = book.toReplaceBook()
            var chapterList: MutableList<BookChapter> = ArrayList()
            chapters.forEachIndexed { index, chapter ->
                if (scope.contains(index)) {
                    chapterList.add(chapter)
                }
                if (scope.size == chapterList.size) {
                    return@forEachIndexed
                }
            }
            // val totalChapterNum = book.totalChapterNum / scope.size
            if (chapterList.isEmpty()) {
                throw RuntimeException("书籍<${book.name}>(${epubBookIndex + 1})未找到章节信息")
            }
            chapterList = chapterList.subList(
                epubBookIndex * size,
                min(scope.size, (epubBookIndex + 1) * size)
            )
            chapterList.forEachIndexed { index, chapter ->
                currentCoroutineContext().ensureActive()
                updateProgress(chapterList, index)
                val content = getChapterContentForExport(book, chapter)
                    ?: return@forEachIndexed
                val (contentFix, resources) = fixPic(book, content, chapter)
                epubBook.resources.addAll(resources)
                val content1 = sanitizeExportContent(
                    contentProcessor.getContent(
                        book,
                        chapter,
                        contentFix,
                        includeTitle = false,
                        useReplace = useReplace,
                        chineseConvert = false,
                        reSegment = false
                    ).toString()
                )
                val title = chapter.run {
                    // 不导出vip标识
                    isVip = false
                    getDisplayTitle(
                        contentProcessor.getTitleReplaceRules(),
                        useReplace = useReplace,
                        replaceBook = replaceBook
                    )
                }
                epubBook.addSection(
                    title,
                    ResourceUtil.createChapterResource(
                        title.replace("\uD83D\uDD12", ""),
                        content1,
                        contentModel,
                        "Text/chapter_${index}.html"
                    )
                )
            }
        }

        /**
         * 创建多个epub 对象
         *
         * 分割epub时，一个书籍需要创建多个epub对象
         * @param book 书籍
         * @param fileDoc 导出文件夹文档
         *
         * @return <内容模板字符串, <epub文件名, epub对象>>
         */
        private fun createEpubs(
            book: Book,
            fileDoc: FileDoc
        ): Pair<String, List<Pair<String, EpubBook>>> {
            val paresNumOfEpub = paresNumOfEpub(scope.size, size)
            val result: MutableList<Pair<String, EpubBook>> = ArrayList(paresNumOfEpub)
            var contentModel = ""
            val names=(1..paresNumOfEpub).map { book.getExportFileName("epub",it,options.episodeName) }
            require(names.distinct().size==names.size) { "Export parts must have different filenames" }
            for (filename in names) {

                val epubBook = EpubBook()
                epubBook.version = "2.0"
                //set metadata
                setEpubMetadata(book, epubBook)
                //set cover
                setCover(book, epubBook)
                //set css
                contentModel = setAssets(fileDoc, book, epubBook)

                // add epubBook
                result.add(Pair(filename, epubBook))
            }
            return Pair(contentModel, result)
        }

        /**
         * 保存文件到 设备
         */
        private suspend fun save2Drive(
            filename: String,
            epubBook: EpubBook,
            fileDoc: FileDoc,
            callback: (total: Int, progress: Int) -> Unit
        ) {
            val bookDoc = stagedExport(fileDoc, filename) { staging ->
            staging.openOutputStream().getOrThrow().buffered().use { bookOs ->
                EpubWriter()
                    .setCallback(object : EpubWriterProcessor.Callback {
                        override fun onProgressing(total: Int, progress: Int) {
                            verify()
                            callback(total, progress)
                        }
                    })
                    .write(epubBook, bookOs)
            }

            }

            if (options.webDav) {
                // 导出到webdav
                verify(); upload(bookDoc.uri, filename); verify()
            }
        }

        /**
         * 解析 分割epub后的数量
         *
         * @param total 章节总数
         * @param size 每个epub文件包含多少章节
         */
        private fun paresNumOfEpub(total: Int, size: Int): Int {
            val i = total % size
            var result = total / size
            if (i > 0) {
                result++
            }
            return result
        }

    }
}
