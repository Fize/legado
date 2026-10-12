package io.legado.app.data.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourcePartHasJsTest {

    private val partSource = projectFile(
        "src/main/java/io/legado/app/data/entities/BookSourcePart.kt"
    ).readText()
    private val normalizedPartSource = partSource.replace(Regex("\\s+"), " ")
    private val activitySource = projectFile(
        "src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
    ).readText()
    private val databaseSource = projectFile(
        "src/main/java/io/legado/app/data/AppDatabase.kt"
    ).readText()
    private val schemaSource = projectFile(
        "schemas/io.legado.app.data.AppDatabase/93.json"
    ).readText()

    @Test
    fun `database view exposes js source state`() {
        assertTrue(
            partSource.contains("(mainJs is not null and trim(mainJs) <> '') hasJs")
        )
        assertTrue(
            normalizedPartSource.contains(
                "eventListener, bookSourceType, " +
                    "(mainJs is not null and trim(mainJs) <> '') hasJs"
            )
        )
        assertFalse(BookSourcePart().hasJs)
        assertTrue(BookSourcePart(hasJs = true).hasJs)
    }

    @Test
    fun `schema history preserves js source projection`() {
        val databaseVersion = Regex("""version = (\d+)""")
            .find(databaseSource)
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertTrue(requireNotNull(databaseVersion) >= 93)
        assertTrue(schemaSource.contains("\"version\": 93"))
        assertTrue(
            schemaSource.contains("(mainJs is not null and trim(mainJs) <> '') hasJs")
        )
    }

    @Test
    fun `compose row renders js badge conditionally when source hasJs`() {
        assertTrue(activitySource.contains("if (source.hasJs)"))
        assertTrue(activitySource.contains("stringResource(R.string.js_source_badge)"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
    }
}
