package io.legado.app.ui.book.search

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchReadRecordContractTest {

    @Test
    fun `reading a book stores the author with the record`() {
        listOf(
            Triple("src/main/java/io/legado/app/model/ReadBook.kt",
                "val currentBook = book?.copy() ?: return", "getRecord(AppConst.androidId, book.name, book.author)"),
        ).forEach { (path, authorCapture, authorAssignment) ->
            val source = projectFile(path).readText().replace(Regex("\\s+"), " ")
            assertTrue(path, source.contains(authorCapture))
            assertTrue(path, source.contains(authorAssignment))
        }

        val mangaSession = projectFile("src/main/java/io/legado/app/model/MangaReaderSession.kt")
            .readText().replace(Regex("\\s+"), " ")
        val mangaRepository = projectFile("src/main/java/io/legado/app/data/repository/MangaReaderDataRepository.kt")
            .readText().replace(Regex("\\s+"), " ")
        assertTrue(mangaSession.contains("val currentBook = book?.copy() ?: return"))
        assertTrue(mangaSession.contains("dataRepository.readRecord(book)"))
        assertTrue(mangaRepository.contains("getRecord(AppConst.androidId, book.name, book.author)"))

        val audioPlay = projectFile("src/main/java/io/legado/app/model/AudioPlay.kt")
            .readText().replace(Regex("\\s+"), " ")
        assertTrue(audioPlay.contains("book?.takeUnless(readTimeTracker::isForBook)?.let { resetReadRecord(it) }"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
