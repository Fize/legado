package io.legado.app.ui.menu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CachePopupActionMigrationTest {
    private fun source(path: String)=sequenceOf(File(path),File("app/$path")).first(File::isFile).readText()
    private val screen get()=source("src/main/java/io/legado/app/ui/book/cache/CacheScreen.kt")
    @Test fun `cache long press uses Compose DropdownMenu`() {
        val value=screen
        assertTrue(value.contains("combinedClickable")); assertTrue(value.contains("DropdownMenu"))
        val after=value.indexOf("R.string.menu_download_after")
        val all=value.indexOf("R.string.menu_download_all",after)
        assertTrue(after>=0 && all>after)
        assertTrue(value.substring(after,all).contains("model.download(remote,true)"))
        assertTrue(value.substring(all).contains("model.download(remote,false)"))
        assertFalse(value.contains("PopupMenu")); assertFalse(value.contains("inflate(R.menu"))
        assertFalse(sequenceOf(File("src/main/res/menu/book_cache_download.xml"),File("app/src/main/res/menu/book_cache_download.xml")).any(File::exists))
        assertFalse(sequenceOf(File("src/main/java/io/legado/app/ui/book/cache/CacheActivity.kt"),File("app/src/main/java/io/legado/app/ui/book/cache/CacheActivity.kt")).any(File::exists))
    }
    @Test fun `download confirmation records explicit ranges and cancellation uses task identities`() {
        val repository=source("src/main/java/io/legado/app/data/repository/CacheManagerRepository.kt")
        assertTrue(repository.contains("if(dialog.afterCurrent) book.durChapterIndex.coerceAtLeast(0) else 0,end=-1"))
        assertTrue(repository.contains("tasks.enqueueManyNow(requests)"))
        assertTrue(screen.contains("model.stop(downloading.map { it.id })"))
        assertTrue(screen.contains("cache_download_after_description")); assertTrue(screen.contains("cache_download_all_description"))
        assertFalse(screen.contains("CacheBook.")); assertFalse(screen.contains("observeEvent"))
    }
    @Test fun `toolbar state and accessibility labels come from observable tasks`() {
        assertTrue(screen.contains("state.transfers.filter { it.kind==\"download\""))
        assertTrue(screen.contains("R.string.cache_stop_all")); assertTrue(screen.contains("R.string.cache_download_title"))
        assertTrue(screen.contains("contentDescription=downloadDescription")); assertTrue(screen.contains("role=Role.Button"))
        assertTrue(screen.contains("R.drawable.ic_stop_black_24dp")); assertTrue(screen.contains("R.drawable.ic_download"))
    }
}
