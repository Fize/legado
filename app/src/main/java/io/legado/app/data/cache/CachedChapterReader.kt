package io.legado.app.data.cache

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import javax.inject.Inject

interface CachedChapterReader { fun read(book: Book, chapter: BookChapter): String? }
class ParserCachedChapterReader @Inject constructor() : CachedChapterReader {
    override fun read(book: Book, chapter: BookChapter) = BookHelp.getContent(book, chapter)
}
@Module
@InstallIn(SingletonComponent::class)
abstract class BookCacheDataModule {
    @Binds abstract fun cachedChapterReader(reader: ParserCachedChapterReader): CachedChapterReader
}
