package io.legado.app.ui.login

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoginEntryRoutingTest {

    @Test
    fun `login entry points use unified login capability`() {
        val paths = listOf(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt",
            "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt",
            "src/main/java/io/legado/app/ui/book/read/ReadMenu.kt",
            "src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt",
            "src/main/java/io/legado/app/ui/video/VideoPlayerActivity.kt"
        )

        paths.forEach { path ->
            assertTrue("$path should use hasLogin()", File(path).readText().contains("hasLogin()"))
        }
    }
}
