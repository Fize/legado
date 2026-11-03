package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.book.ContentProcessor
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.EpubTocNode
import io.legado.app.model.localBook.PdfOutline
import io.legado.app.model.localBook.PdfOutlineNode
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.toc.TocListItem
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.help.book.update
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class BookTocContent(
    val book: Book,
    val chapters: List<BookChapter>,
    val epubOutline: List<EpubTocNode>?,
    val pdfOutline: List<PdfOutlineNode>,
)

@Singleton
class BookTocRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun supportsHighlightPosition(book: Book): Boolean =
        !book.isAudio && !book.isVideo &&
            (book.isLocal || !book.isImage || !AppConfig.showMangaUi)

    suspend fun load(bookUrl: String): BookTocContent? = withContext(IO) {
        val book = appDb.bookDao.getBook(bookUrl) ?: return@withContext null
        val end = book.simulatedTotalChapterNum() - 1
        val chapters = appDb.bookChapterDao.getChapterList(bookUrl, 0, end)
        val epubOutline = if (book.isEpub) runCatching { EpubFile.getToc(book) }.getOrNull() else null
        val pdfOutline = if (book.isPdf) runCatching { PdfOutline.read(book) }.getOrDefault(emptyList()) else emptyList()
        BookTocContent(book, chapters, epubOutline, pdfOutline)
    }

    suspend fun searchChapterIndexes(book: Book, key: String): List<Int> = withContext(IO) {
        val end = book.simulatedTotalChapterNum() - 1
        appDb.bookChapterDao.searchIndexes(book.bookUrl, key, 0, end)
    }

    fun observeBookmarks(book: Book, key: String): Flow<List<Bookmark>> =
        if (key.isBlank()) appDb.bookmarkDao.flowByBook(book.name, book.author)
        else appDb.bookmarkDao.flowSearch(book.name, book.author, key)

    fun observeHighlights(book: Book, key: String): Flow<List<BookHighlight>> =
        if (key.isBlank()) appDb.bookHighlightDao.flowByBook(book.bookUrl)
        else appDb.bookHighlightDao.flowSearch(book.bookUrl, key)

    suspend fun chapterIndexes(bookUrl: String): Map<String, Int> = withContext(IO) {
        appDb.bookChapterDao.getChapterList(bookUrl).associate { it.url to it.index }
    }

    suspend fun setReverseToc(book: Book, reversed: Boolean) = withContext(IO) {
        if (book.isPdf || book.isEpub) {
            book.setReverseToc(reversed)
            appDb.bookDao.updateReverseToc(book.bookUrl, reversed)
        } else {
            book.setReverseTocDisplay(reversed)
            appDb.bookDao.updateReverseTocDisplay(book.bookUrl, reversed)
        }
    }

    suspend fun setTocExpanded(book: Book, expanded: Boolean) = withContext(IO) {
        book.setTocExpanded(expanded)
        appDb.bookDao.updateTocExpanded(book.bookUrl, expanded)
    }

    suspend fun saveBookmark(bookmark: Bookmark) = withContext(IO) {
        appDb.bookmarkDao.insert(bookmark)
    }

    suspend fun deleteBookmark(bookmark: Bookmark) = withContext(IO) {
        appDb.bookmarkDao.delete(bookmark)
    }

    suspend fun saveHighlight(highlight: BookHighlight) = withContext(IO) {
        appDb.bookHighlightDao.update(highlight)
        if (ReadBook.book?.bookUrl == highlight.bookUrl) ReadBook.updateHighlight(highlight)
    }

    suspend fun deleteHighlight(highlight: BookHighlight) = withContext(IO) {
        appDb.bookHighlightDao.delete(highlight)
        if (ReadBook.book?.bookUrl == highlight.bookUrl) ReadBook.removeHighlight(highlight)
    }

    suspend fun exportBookmarks(book: Book, uri: Uri, markdown: Boolean) = withContext(IO) {
        val bookmarks = appDb.bookmarkDao.getByBook(book.name, book.author)
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Unable to open selected export document")
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            if (!markdown) {
                writer.write(GSON.toJson(bookmarks))
            } else {
                writer.append("## ${book.name} ${book.author}\n\n")
                bookmarks.forEach { bookmark ->
                    writer.append("#### ${bookmark.chapterName}\n\n")
                    writer.append("###### 原文\n ${bookmark.bookText}\n\n")
                    writer.append("###### 摘要\n ${bookmark.content}\n\n")
                }
            }
        }
    }

    fun observeTxtTocRules(): Flow<List<TxtTocRule>> = appDb.txtTocRuleDao.observeAll()

    suspend fun setTxtTocRuleEnabled(rule: TxtTocRule, enabled: Boolean) = withContext(IO) {
        rule.enable = enabled
        appDb.txtTocRuleDao.update(rule)
    }

    suspend fun applyTxtTocRule(book: Book, rule: TxtTocRule) = withContext(IO) {
        book.tocUrl = rule.rule + TextFile.spaceChars + rule.replacement
        book.update()
        val chapters = LocalBook.getChapterList(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        book.update()
        ReadBook.onChapterListUpdated(book)
    }

    fun useReplaceInToc() = AppConfig.tocUiUseReplace

    fun countWordsInToc() = AppConfig.tocCountWords

    fun setUseReplaceInToc(enabled: Boolean) { AppConfig.tocUiUseReplace = enabled }

    fun setCountWordsInToc(enabled: Boolean) { AppConfig.tocCountWords = enabled }

    suspend fun displayTitles(book: Book, items: List<TocListItem>, enabled: Boolean): Map<String, String> =
        withContext(IO) {
            val rules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val replaceBook = book.toReplaceBook()
            items.associate { item ->
                val chapter = item.readingChapter?.let { reading ->
                    if (reading.index == item.chapter.index) item.chapter
                    else item.chapter.copy(index = reading.index)
                } ?: item.chapter
                item.key to chapter.getDisplayTitle(
                    replaceRules = rules,
                    useReplace = enabled && book.getUseReplaceRule(),
                    replaceBook = replaceBook,
                )
            }
        }

    suspend fun setSplitLongChapter(book: Book, split: Boolean) = withContext(IO) {
        book.setSplitLongChapter(split)
        book.update()
        val chapters = LocalBook.getChapterList(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        book.update()
        ReadBook.onChapterListUpdated(book)
    }
}
