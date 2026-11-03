package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.CoverSourceRef
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.hasEditedNetworkCover
import io.legado.app.help.book.isLocal
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@androidx.annotation.Keep
enum class BookInfoEditField { Name, Author, Type, Cover, Intro }
@androidx.annotation.Keep
data class BookInfoEditDraft(
    val bookName: String = "",
    val bookAuthor: String = "",
    val selectedTypeIndex: Int = 0,
    val coverUrl: String = "",
    val coverSource: String? = null,
    val bookIntro: String = "",
)

@Singleton
class BookInfoEditRepository @Inject constructor(private val database: AppDatabase) {
    suspend fun getBook(bookUrl: String): Book? = withContext(IO) { database.bookDao.getBook(bookUrl) }

    suspend fun saveDraft(key: String, draft: BookInfoEditDraft, dirty: Set<BookInfoEditField>,
        owner: io.legado.app.data.entities.BookInfoChildRequest? = null): Book = withContext(IO) {
        var previous: Book? = null
        val saved = database.withTransaction {
            if (owner != null) database.bookInfoRequestDao.requireChildOwner(owner.id, owner.producerToken,
                key, io.legado.app.data.entities.BookInfoChildKind.Edit)
            val current = checkNotNull(database.bookDao.getBook(key)) { "Book was removed." }
            previous = current.copy()
            if (BookInfoEditField.Name in dirty) {
                require(draft.bookName.isNotBlank()) { "Book name is required." }
                current.name = draft.bookName.trim()
            }
            if (BookInfoEditField.Author in dirty) current.author = draft.bookAuthor.trim()
            if (BookInfoEditField.Type in dirty) {
                val local = if (current.isLocal) BookType.local else 0
                require(draft.selectedTypeIndex in 0..4 && !(local != 0 && draft.selectedTypeIndex == 3)) { "Unsupported book type." }
                val type = when (draft.selectedTypeIndex) {
                    1 -> BookType.audio
                    2 -> BookType.image
                    3 -> BookType.text or BookType.webFile
                    4 -> BookType.video
                    else -> BookType.text
                }
                current.type = (current.type and BookType.allBookType.inv()) or type or local
            }
            if (BookInfoEditField.Cover in dirty) {
                val url = draft.coverUrl.takeIf(String::isNotBlank)
                val reference = draft.coverSource?.takeIf { url != null }?.let { CoverSourceRef(checkNotNull(url), it) }
                if (hasEditedNetworkCover(url, current.customCoverUrl, current.coverUrl) || current.customCoverSource != reference) current.persistedCoverUrl = null
                current.customCoverUrl = if (url == current.coverUrl && reference == null) null else url
                current.customCoverSource = reference
            }
            if (BookInfoEditField.Intro in dirty) current.customIntro = draft.bookIntro.takeUnless { it == current.intro }
            database.bookDao.update(current)
            if (BookInfoEditField.Name in dirty || BookInfoEditField.Author in dirty) database.bookHighlightDao.updateBookMetadata(key, current.name, current.author)
            if (owner != null) check(database.bookInfoRequestDao.deliverEdit(owner.id, owner.producerToken, key, System.currentTimeMillis()))
            current
        }
        previous?.takeIf { it.name != saved.name }?.let {
            runCatching { BookHelp.updateCacheFolder(it, saved) }.onFailure { error -> AppLog.put("更新书籍缓存目录失败", error) }
        }
        fun updateSession(active: Book?) {
            active?.takeIf { it.bookUrl == key }?.let {
                if (BookInfoEditField.Name in dirty) it.name = saved.name
                if (BookInfoEditField.Author in dirty) it.author = saved.author
                if (BookInfoEditField.Type in dirty) it.type = saved.type
                if (BookInfoEditField.Intro in dirty) it.customIntro = saved.customIntro
                if (BookInfoEditField.Cover in dirty) {
                    it.customCoverUrl = saved.customCoverUrl; it.customCoverSource = saved.customCoverSource; it.persistedCoverUrl = saved.persistedCoverUrl
                }
            }
        }
        updateSession(ReadBook.book); updateSession(AudioPlay.book)
        ReadBook.book?.takeIf { it.bookUrl == key && (BookInfoEditField.Name in dirty || BookInfoEditField.Author in dirty) }?.let(ReadBook::loadHighlights)
        saved
    }
}
