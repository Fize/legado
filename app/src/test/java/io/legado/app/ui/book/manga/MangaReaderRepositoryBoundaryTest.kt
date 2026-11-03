package io.legado.app.ui.book.manga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaReaderRepositoryBoundaryTest {

    @Test
    fun `ui state layer accesses the injected manga session through its repository`() {
        val activity = projectSource("ui/book/manga/MangaReaderDestination.kt")
        val viewModel = projectSource("ui/book/manga/ReadMangaViewModel.kt")
        val repository = projectSource("data/repository/MangaReaderSessionRepository.kt")
        val preferencesRepository = projectSource(
            "data/repository/MangaReaderPreferencesRepository.kt"
        )
        val contentRepository = projectSource("data/repository/MangaContentRepository.kt")
        val dataRepository = projectSource("data/repository/MangaReaderDataRepository.kt")
        val session = projectSource("model/MangaReaderSession.kt")

        listOf(activity, viewModel).forEach { source ->
            assertFalse(source.contains("ReadManga."))
            assertFalse(source.contains("appDb"))
            assertFalse(source.contains("AppConfig"))
        }
        assertFalse(activity.contains("GSON"))
        assertTrue(viewModel.contains("fun saveFooterConfig"))
        assertTrue(viewModel.contains("fun saveColorFilterConfig"))
        assertFalse(activity.contains("MangaReaderSessionRepository"))
        assertTrue(viewModel.contains("MangaReaderSessionRepository"))
        assertFalse(repository.contains("ReadManga."))
        assertTrue(repository.contains("MangaReaderSession"))
        assertTrue(repository.contains("appDb"))
        listOf("appDb", "BookHelp.", "WebBook.", "AppWebDav", "AppConfig").forEach {
            assertFalse(it, session.contains(it))
        }
        assertTrue(session.contains("MangaReaderDataRepository"))
        assertTrue(dataRepository.contains("appDb"))
        assertTrue(preferencesRepository.contains("DataStoreFactory.create"))
        assertTrue(preferencesRepository.contains("SharedPreferencesMigration"))
        assertTrue(preferencesRepository.contains("ReplaceFileCorruptionHandler"))
        assertTrue(preferencesRepository.contains("MangaReaderPreferences.serializer()"))
        assertFalse(activity.contains("override fun onActivityCreated"))
        listOf("WebBook.", "BookHelp.", "LocalBook.").forEach { legacyDataSource ->
            assertFalse(viewModel.contains(legacyDataSource))
            assertTrue(contentRepository.contains(legacyDataSource))
        }
    }

    private fun projectSource(relativePath: String): String {
        return sequenceOf(
            File("src/main/java/io/legado/app/$relativePath"),
            File("app/src/main/java/io/legado/app/$relativePath"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Missing project source: $relativePath")
    }
}
