package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadMangaOfflineActionsTest {

    @Test
    fun `manga reader exposes offline cache and long press image saving`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/MangaReaderDestination.kt"
        ).readText()
        val surface = projectFile("src/main/java/io/legado/app/ui/book/manga/MangaReaderSurface.kt").readText()
        val menus = projectFile("src/main/java/io/legado/app/ui/book/manga/MangaReaderMenus.kt").readText()
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/ReadMangaViewModel.kt"
        ).readText()
        val sessionRepository = projectFile(
            "src/main/java/io/legado/app/data/repository/MangaReaderSessionRepository.kt"
        ).readText()
        val preferKey = projectFile("src/main/java/io/legado/app/constant/PreferKey.kt").readText()
        val preferencesRepository = projectFile(
            "src/main/java/io/legado/app/data/repository/MangaReaderPreferencesRepository.kt"
        ).readText()
        val cacheBook = projectFile("src/main/java/io/legado/app/model/CacheBook.kt").readText()
        val dataRepository = projectFile(
            "src/main/java/io/legado/app/data/repository/MangaReaderDataRepository.kt"
        ).readText()
        val mangaContent = projectFile(
            "src/main/java/io/legado/app/ui/book/manga/MangaReaderContent.kt"
        ).readText()
        val bookCover = projectFile("src/main/java/io/legado/app/model/BookCover.kt").readText()

        assertTrue(projectFile("src/main/java/io/legado/app/ui/book/manga/MangaReaderDialogs.kt").readText().contains("R.string.offline_cache"))
        assertTrue(menus.contains("R.string.manga_long_click_save_image"))
        assertTrue(viewModel.contains("MangaReaderDialog.DownloadRange"))
        assertTrue(mangaContent.contains("onLongPress = { onSaveImage(page.mImageUrl) }"))
        assertTrue(surface.contains("if (state.longClickSaveEnabled) onAction(MangaReaderAction.SaveImage(it))"))
        assertTrue(surface.contains("onSaveImage ="))
        assertTrue(activity.contains("viewModel.savedImageDirectory()"))
        assertTrue(activity.contains("viewModel.rememberImageDirectory(uri)"))
        assertTrue(dataRepository.contains("ACache.get()"))
        assertTrue(dataRepository.contains("AppConst.imagePathKey"))
        assertTrue(viewModel.contains("sessionRepository.saveImage(src, uri)"))
        assertTrue(sessionRepository.contains("BookHelp.saveImage(session.bookSource, book, sourceUrl)"))
        assertTrue(sessionRepository.contains("createFileIfNotExist(image.name).writeFile(image)"))
        val cacheSuccess = cacheBook.substringAfter("val ticket = downloads.claimManual(index)")
            // Ordinary offline caching still finishes only after its images are saved.
            // Theme resource refresh now has a separate staged transaction above this branch.
            .substringAfter("} else {")
            .substringBefore("private fun startManual")
        assertTrue(cacheSuccess.contains("val content = BookHelp.getContent(requestBook, chapter)"))
        assertTrue(cacheSuccess.contains("BookHelp.saveImages(source, requestBook, chapter, content, 1)"))
        assertTrue(cacheSuccess.contains("val currentContent = BookHelp.getContent(requestBook, chapter) ?: content"))
        assertTrue(cacheSuccess.indexOf("BookHelp.saveImages") < cacheSuccess.indexOf("onSuccess(ticket"))
        assertTrue(mangaContent.contains("mangaImagePath(page)"))
        assertTrue(mangaContent.contains("page.cachePath?.takeIf { File(it).isFile } ?: page.mImageUrl"))
        assertTrue(bookCover.contains("ImageLoader.loadFile(context, path).apply(options)"))
        assertTrue(preferKey.contains("mangaLongClickSaveImage = \"mangaLongClickSaveImage\""))
        assertTrue(preferencesRepository.contains("longClickSaveEnabled: Boolean = true"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
