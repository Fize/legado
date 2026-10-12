package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.Book
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.BookshelfReadProgressMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfReadProgressTest {

    @Test
    fun `unopened books do not expose progress`() {
        assertNull(Book(totalChapterNum = 20).readProgress())
    }

    @Test
    fun `single chapter books become complete after reading starts`() {
        val book = Book(totalChapterNum = 1, durChapterPos = 1)
        assertEquals(1f, book.readProgress() ?: 0f, 0f)
    }

    @Test
    fun `chapter index maps to bounded progress`() {
        assertEquals(
            0.5f,
            Book(totalChapterNum = 11, durChapterIndex = 5).readProgress() ?: 0f,
            0f,
        )
        assertEquals(
            1f,
            Book(totalChapterNum = 10, durChapterIndex = 20).readProgress() ?: 0f,
            0f,
        )
    }

    @Test
    fun `all book item composables provide progress views`() {
        val components = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfComposeComponents.kt"
        ).readText()
        assertTrue(components.contains("BookshelfBookListItem"))
        assertTrue(components.contains("BookshelfBookGridItem"))
        assertTrue(components.contains("LinearProgressIndicator"))
        assertTrue(components.contains("AppConfig.showBookshelfReadProgress"))
    }

    @Test
    fun `bookshelf progress modes preserve legacy settings and thickness`() {
        assertEquals(
            BookshelfReadProgressMode.HIDDEN,
            BookshelfReadProgressMode.resolve(null, false),
        )
        assertEquals(
            BookshelfReadProgressMode.STANDARD,
            BookshelfReadProgressMode.resolve(null, true),
        )
        assertEquals(
            BookshelfReadProgressMode.ENHANCED,
            BookshelfReadProgressMode.resolve("2", false),
        )
        assertEquals(
            BookshelfReadProgressMode.STANDARD_THICKNESS_DP,
            BookshelfReadProgressMode.thicknessDp(BookshelfReadProgressMode.HIDDEN),
        )
        assertEquals(
            BookshelfReadProgressMode.STANDARD_THICKNESS_DP,
            BookshelfReadProgressMode.thicknessDp(BookshelfReadProgressMode.STANDARD),
        )
        assertEquals(
            BookshelfReadProgressMode.ENHANCED_THICKNESS_DP,
            BookshelfReadProgressMode.thicknessDp(BookshelfReadProgressMode.ENHANCED),
        )

        val appConfig = projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").readText()
        assertTrue(appConfig.contains("PreferKey.bookshelfReadProgressMode"))
        assertTrue(appConfig.contains("PreferKey.showBookshelfReadProgress"))
        val renderer = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfReadProgress.kt",
        ).readText()
        assertTrue(renderer.contains("BookshelfReadProgressMode"))
        assertTrue(renderer.contains("thicknessDp(AppConfig.bookshelfReadProgressMode)"))
        val restore = projectFile("src/main/java/io/legado/app/help/storage/Restore.kt").readText()
        assertTrue(restore.contains("PreferKey.bookshelfReadProgressMode !in map"))
        assertTrue(restore.contains("BookshelfReadProgressMode.STANDARD"))
    }

    @Test
    fun `group composables remain free of per-book progress`() {
        val components = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfComposeComponents.kt"
        ).readText()
        val groupSection = components.substringAfter("fun BookshelfGroupListItem")
            .substringBefore("data class BookshelfConfigState")
        assertFalse(groupSection.contains("LinearProgressIndicator"))
    }

    @Test
    fun `bookshelf settings expose display switches in order`() {
        val components = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfComposeComponents.kt"
        ).readText()
        assertTrue(components.contains("R.string.read_progress"))
        assertTrue(components.contains("R.array.bookshelf_read_progress"))
        assertTrue(components.contains("R.string.show_unread"))
        assertTrue(components.contains("R.string.show_last_update_time"))
        assertTrue(components.contains("R.string.show_wait_up_count"))
        assertTrue(components.contains("R.string.show_bookshelf_fast_scroller"))
        assertTrue(components.contains("R.string.recent_reading"))
        assertTrue(components.contains("R.string.bookshelf_statistics"))
    }

    @Test
    fun `bookshelf layouts expose the shared header and keep content below it`() {
        val components = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfComposeComponents.kt"
        ).readText()
        assertTrue(components.contains("fun BookshelfHeaderSection"))
        assertTrue(components.contains("R.string.continue_read"))

        val style1 = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/style1/BookshelfFragment1.kt"
        ).readText()
        assertTrue(style1.contains("ShelfHeaderComposable()"))

        val style2 = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/style2/BookshelfFragment2.kt"
        ).readText()
        assertTrue(style2.contains("ShelfHeaderComposable()"))
    }

    @Test
    fun `bookshelf header options default off and gate database work`() {
        val appConfig =
            projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").readText()
        assertTrue(
            appConfig.contains(
                "get() = appCtx.getPrefBoolean(PreferKey.showBookshelfRecentReading, false)",
            ),
        )
        assertTrue(
            appConfig.contains(
                "get() = appCtx.getPrefBoolean(PreferKey.showBookshelfStats, false)",
            ),
        )

        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BaseBookshelfFragment.kt",
        ).readText()
        val disabledGate = fragment.indexOf("if (!showRecentReading && !showBookshelfStats) return")
        val databaseFlow = fragment.indexOf("appDb.bookDao.flowShelfBookCount()")
        assertTrue(disabledGate >= 0)
        assertTrue(databaseFlow > disabledGate)
        assertTrue(fragment.contains("val book = if (showRecentReading)"))
        assertTrue(fragment.contains("val readingCount = if (showBookshelfStats)"))
    }

    @Test
    fun `continue reading query includes every shelf media type`() {
        val source = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val propertyIndex = source.indexOf("val lastReadBookOnShelf")
        val queryStart = source.lastIndexOf("@get:Query(", propertyIndex)
        val query = source.substring(queryStart, propertyIndex)

        assertTrue(query.contains("type & \${BookType.notShelf} = 0"))
        assertFalse(query.replace("BookType.notShelf", "").contains("BookType."))
        assertTrue(query.contains("durChapterIndex > 0 OR durChapterPos > 0"))
        assertTrue(query.contains("durChapterTime DESC limit 1"))
    }

    @Test
    fun `reading count query only includes started shelf books`() {
        val source = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        val propertyIndex = source.indexOf("val readingCount")
        val queryStart = source.lastIndexOf("@get:Query(", propertyIndex)
        val query = source.substring(queryStart, propertyIndex)

        assertTrue(query.contains("(durChapterIndex > 0 OR durChapterPos > 0)"))
        assertTrue(query.contains("and type & \${BookType.notShelf} = 0"))
        assertFalse(query.replace("BookType.notShelf", "").contains("BookType."))
        assertFalse(query.contains("durChapterTime"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
