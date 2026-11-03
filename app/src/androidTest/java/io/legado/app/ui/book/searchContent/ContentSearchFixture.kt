package io.legado.app.ui.book.searchContent

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import java.util.UUID

class ContentSearchFixture: AutoCloseable {
    private val id=UUID.randomUUID().toString()
    val book=Book(bookUrl="fixture://search-ui-$id",origin="source-$id",name="Search $id",author=id,
        readConfig=Book.ReadConfig(useReplaceRule=false))
    val chapter=BookChapter(bookUrl=book.bookUrl,url="fixture://chapter-$id",title="Literal chapter")
    init {
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book,chapter,"prefix needle suffix")
    }
    override fun close() { appDb.bookDao.delete(book); BookHelp.clearCache(book) }
}
