package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.MangaReaderDataRepository
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import javax.inject.Inject

/** Parses a captured chapter without reading or mutating an active reader session. */
open class MangaChapterDecoder @Inject constructor(private val dataRepository: MangaReaderDataRepository) {
    open suspend fun decode(
        chapter: BookChapter,
        content: String,
        owner: Book,
        hideTitle: Boolean,
        chapterSize: Int,
    ): MangaChapter {
        val sources = dataRepository.imageUrls(chapter, content).distinctUntilChanged().toList()
        val imageCount = sources.size
        val list = sources.mapIndexed { index, src ->
                val path = dataRepository.imageCachePath(owner, src)
                val dimensions = dataRepository.cachedImageDimensions(path)
                MangaPage(
                    chapterIndex = chapter.index,
                    chapterSize = chapterSize,
                    mImageUrl = src,
                    cachePath = path,
                    imageWidth = dimensions?.first ?: 0,
                    imageHeight = dimensions?.second ?: 0,
                    imageCount = imageCount,
                    index = index,
                    mChapterName = chapter.title
                )
            }

        if (hideTitle && imageCount > 0) {
            return MangaChapter(chapter, list, imageCount)
        }

        val pages = mutableListOf<BaseMangaPage>()

        if (imageCount == 0 && chapter.isVolume) {
            pages.add(ReaderLoading(chapter.index, -1, chapter.title, true))
        } else {
            pages.add(ReaderLoading(chapter.index, -1, "阅读 ${chapter.title}"))
            pages.addAll(list)
            pages.add(ReaderLoading(chapter.index, imageCount, "已读完 ${chapter.title}"))
        }

        return MangaChapter(chapter, pages, imageCount)
    }

}
