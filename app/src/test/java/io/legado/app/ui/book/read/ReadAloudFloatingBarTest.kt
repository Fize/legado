package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudFloatingBarTest {

    @Test
    fun `each control follows its own switch and all controls hide under menus or after stopping`() {
        assertTrue(ReadAloudBarVisibility.shouldShow(true, true, false))
        assertTrue(ReadAloudBarVisibility.shouldShow(true, false, false))
        for (following in listOf(false, true)) {
            for (pause in listOf(false, true)) {
                for (position in listOf(false, true)) {
                    org.junit.Assert.assertEquals(if (following) pause else position,
                        ReadAloudBarVisibility.shouldShow(true, following, false, pause, position))
                    assertFalse(ReadAloudBarVisibility.shouldShow(false, following, false, pause, position))
                    assertFalse(ReadAloudBarVisibility.shouldShow(true, following, true, pause, position))
                }
            }
        }
    }

    @Test
    fun `floating actions and host include remain wired`() {
        val controls = projectFile("src/main/java/io/legado/app/ui/book/read/ReadAloudControls.kt").readText()
        val host = projectFile("src/main/java/io/legado/app/ui/book/read/BaseReadBookActivity.kt").readText()
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(controls.contains("R.id.ll_back_to_speech"))
        assertTrue(controls.contains("R.id.ll_read_from_here"))
        assertTrue(controls.contains("ViewGroup.LayoutParams.MATCH_PARENT"))
        assertTrue(controls.contains("maxLines = 2"))
        assertTrue(host.contains("ReadAloudFloatBarViewHolder"))
        assertTrue(activity.contains("backToSpeakingPosition()"))
        assertTrue(activity.contains("ReadBook.readAloud()"))
        assertTrue(controls.contains("ReadAloudBarVisibility.shouldShow"))
    }

    @Test
    fun `follow changes publish a dedicated refresh event`() {
        val eventBus = projectFile(
            "src/main/java/io/legado/app/constant/EventBus.kt"
        ).readText()
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()

        assertTrue(eventBus.contains("READ_ALOUD_FOLLOW"))
        assertTrue(service.section("fun detachReadAloudFollow", "fun restoreReadAloudFollow")
            .contains("postEvent(EventBus.READ_ALOUD_FOLLOW"))
        assertTrue(service.section("fun restoreReadAloudFollow", "fun shouldSyncSpeechNavigation")
            .contains("postEvent(EventBus.READ_ALOUD_FOLLOW"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)
}
