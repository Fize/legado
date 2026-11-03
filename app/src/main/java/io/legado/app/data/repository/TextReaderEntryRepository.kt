package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReaderSessionState
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.TextReaderChapterContent
import io.legado.app.model.TextReaderDocument
import io.legado.app.model.TextReaderTypography
import io.legado.app.model.TextReaderBackground
import io.legado.app.model.TextReaderUnderline
import io.legado.app.model.TextReaderProcessing
import io.legado.app.help.book.ResourceThemeGeneration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class TextReaderEntry(
    val bookUrl: String, val origin: String, val bookName: String,
    val chapterIndex: Int, val position: Int, val chapterCount: Int,
    val isVolume: Boolean, val titleImage: String?,
    val titleMode: Int, val splitTitle: Boolean, val adaptSpecialStyle: Boolean,
    val fontSize: Int, val titleSize: Int, val textColor: Int,
    val chapterUrl: String = "", val localImages: Boolean = false, val imageStyle: String = "default",
    val typography: TextReaderTypography = TextReaderTypography(),
    val background: TextReaderBackground? = null,
    val processing: TextReaderProcessing = TextReaderProcessing(),
)

@Singleton
class TextReaderEntryRepository @Inject constructor(private val database: AppDatabase) {
    suspend fun entry(session: ReaderSessionState, chapterIndex: Int? = null, position: Int? = null): TextReaderEntry =
        snapshotEntry(session.bookUrl,session,chapterIndex,position)

    suspend fun cachedSearchEntry(bookUrl: String, chapterIndex: Int): TextReaderEntry =
        snapshotEntry(bookUrl,null,chapterIndex,0)

    private suspend fun snapshotEntry(bookUrl: String, session: ReaderSessionState?, chapterIndex: Int?, position: Int?): TextReaderEntry =
        withContext(Dispatchers.IO) {
            // Underline settings are shared across profiles; normalize the legacy value before copying.
            val underline = TextReaderUnderline(ReadBookConfig.underlineMode.coerceIn(0, 6),
                ReadBookConfig.underlineColor.takeIf { ReadBookConfig.underlineColorSet },
                ReadBookConfig.underlineWidth.takeIf { it.isFinite() }?.coerceIn(0f, 10f) ?: 1f,
                ReadBookConfig.underlineDistance.takeIf { it.isFinite() }?.coerceIn(0f, 30f) ?: 4f,
                ReadBookConfig.underlineBodyEnabled, ReadBookConfig.underlineTitleEnabled)
            val selected = ReadBookConfig.getConfig(ReadBookConfig.readStyleSelect).copy()
            val layout = if (ReadBookConfig.shareLayout) ReadBookConfig.shareConfig.copy() else selected
            val color = selected.curTextColor()
            val black = 0xff000000.toInt(); val white = 0xffffffff.toInt()
            val fallback = if (androidx.core.graphics.ColorUtils.calculateContrast(color, black) >=
                androidx.core.graphics.ColorUtils.calculateContrast(color, white)) black else white
            val appearance = typography(layout, color).copy(underline = underline)
            val processing = TextReaderProcessing(layout.paragraphIndent, AppConfig.adaptSpecialStyle,
                AppConfig.chineseConverterType.coerceIn(0, 2), AppConfig.manualReplaceRule)
            val background = TextReaderBackground(selected.curBgType().coerceIn(0, 2),
                selected.curBgStr(), layout.bgAlpha.coerceIn(0, 100), ResourceThemeGeneration.current(), fallback)
            database.runInTransaction<TextReaderEntry> {
                check(session == null || database.readerSessionDao.ownsBook(session.id, session.ownerToken, session.revision, session.bookUrl)) {
                    "Reader no longer owns this book"
                }
                val book = checkNotNull(database.bookDao.getBook(bookUrl)) { "Book does not exist" }
                val index = chapterIndex ?: session?.chapterIndex?.takeIf { it >= 0 } ?: book.durChapterIndex
                val chapter = checkNotNull(database.bookChapterDao.getChapter(book.bookUrl, index)) { "Chapter does not exist" }
                TextReaderEntry(book.bookUrl, book.origin, book.name, index,
                    position ?: if (session != null && session.chapterIndex >= 0) session.pageIndex else book.durChapterPos,
                    database.bookChapterDao.getChapterCount(book.bookUrl), chapter.isVolume, chapter.imgUrl,
                    layout.titleMode, layout.splitChapterTitle, processing.adaptSpecialStyle,
                    layout.textSize, layout.titleSize, color,
                    chapter.url, book.isLocal, book.getImageStyle().orEmpty(), appearance, background, processing)
            }
        }

    private fun typography(layout: ReadBookConfig.Config, textColor: Int): TextReaderTypography = with(layout) {
        val bodyWeight = when (textBold) { 1 -> 700; 2 -> 300; else -> 400 }
        TextReaderTypography(
            letterSpacing = letterSpacing.takeIf { it.isFinite() }?.coerceIn(-0.5f, 2f) ?: 0f,
            bodyWeight = bodyWeight,
            titleWeight = when (titleBold) {
                0 -> 400; 1 -> 700; 2 -> 300
                else -> when (textBold) { 1 -> 900; 2 -> 400; else -> 700 }
            },
            titleColor = titleColor.takeIf { it != 0 } ?: textColor, titleAlignment = titleMode.coerceIn(0, 3),
            justify = ReadBookConfig.textFullJustify, bodyFont = textFont, titleFont = titleFont.ifEmpty { textFont },
            systemFont = AppConfig.systemTypefaces.coerceIn(0, 2),
            paddingLeft = paddingLeft.coerceIn(0, 256), paddingTop = paddingTop.coerceIn(0, 256),
            paddingRight = paddingRight.coerceIn(0, 256), paddingBottom = paddingBottom.coerceIn(0, 256),
            lineHeight = (lineSpacingExtra / 10f).coerceIn(0.5f, 6f),
            titleLineHeight = (1f + titleLineSpacingExtra / 100f).coerceIn(0.5f, 6f),
            paragraphSpacing = (paragraphSpacing / 10f).coerceIn(0f, 4f),
            titleTopSpacing = titleTopSpacing.coerceIn(0, 1024), titleBottomSpacing = titleBottomSpacing.coerceIn(0, 1024))
    }

    suspend fun document(entry: TextReaderEntry, content: TextReaderChapterContent): TextReaderDocument = withContext(Dispatchers.Default) {
        TextReaderDocumentDecoder.decode(entry, content)
    }

    /** Same queue as reader claim/close; an inactive or stale owner cannot publish a position. */
    suspend fun save(owner: ReaderProgressOwner, entry: TextReaderEntry, position: Int, title: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            io.legado.app.help.globalExecutor.execute {
                val result = runCatching {
                    database.runInTransaction<Boolean> {
                        if (!database.readerSessionDao.ownsBook(owner.sessionId, owner.token, owner.revision, entry.bookUrl)) false
                        else database.readerSessionDao.saveProgress(owner.sessionId, owner.token, owner.revision,
                            entry.bookUrl, entry.origin, entry.chapterIndex, position, title, System.currentTimeMillis()) > 0
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }
}
