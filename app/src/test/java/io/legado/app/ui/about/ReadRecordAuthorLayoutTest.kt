package io.legado.app.ui.about

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadRecordAuthorLayoutTest {

    @Test
    fun `read record row reserves an optional author line before reading time`() {
        val file = File("app/src/main/java/io/legado/app/ui/about/ReadRecordActivity.kt")
            .takeIf(File::isFile)
            ?: File("src/main/java/io/legado/app/ui/about/ReadRecordActivity.kt")
        val source = file.readText()
        val compactRow = source.substringAfter("private fun ReadRecordCompactRow(")
        val authorPos = compactRow.indexOf("R.string.author_show")
        val readingTimePos = compactRow.indexOf("R.string.reading_time_tag")
        assertTrue(authorPos >= 0)
        assertTrue(readingTimePos > authorPos)
    }
}
