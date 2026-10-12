package io.legado.app.ui.book.audio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayLayoutContractTest {

    @Test
    fun subtitleRemainsAnchoredWhenLyricViewIsGone() {
        val activity = listOf(
            File("src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt"),
            File("app/src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt")
        ).first { it.isFile }.readText()

        val middleIdx = activity.indexOf("// Middle content area")
        val subtitleIdx = activity.indexOf("// Subtitle & Chapter Index area")
        val progressIdx = activity.indexOf("// Progress bar row")

        assertTrue(
            "The middle cover/lyric area must precede the subtitle area and fill available weight",
            middleIdx in 0 until subtitleIdx &&
                activity.substring(middleIdx, subtitleIdx).contains(".weight(1f)")
        )
        assertTrue(
            "The subtitle and chapter index must remain above the progress bar",
            subtitleIdx in 0 until progressIdx
        )
    }
}
