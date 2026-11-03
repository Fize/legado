package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoredConfigurationWriteTest {
    private fun fixture(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "restore-write-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(File(root, "style.json")) } finally { root.deleteRecursively() }
    }

    @Test fun partialWriteFailureKeepsOriginalAndRemovesPendingFile() = fixture { target ->
        target.writeText("original")
        val failure = runCatching {
            writeRestoredConfiguration(target) { output ->
                output.write("partial replacement".toByteArray())
                throw IOException("Injected input failure")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("original", target.readText())
        assertFalse(File(target.path + ".new").exists())
    }

    @Test fun successfulWriteReplacesOriginal() = fixture { target ->
        target.writeText("original")
        writeRestoredConfiguration(target) { it.write("replacement".toByteArray()) }
        assertEquals("replacement", target.readText())
        assertFalse(File(target.path + ".new").exists())
    }

    @Test fun stalePendingWriteCanBeReplacedWithoutExposingIt() = fixture { target ->
        target.writeText("original")
        File(target.path + ".new").writeText("interrupted")
        writeRestoredConfiguration(target) { output ->
            assertEquals("original", target.readText())
            output.write("replacement".toByteArray())
        }
        assertEquals("replacement", target.readText())
        assertFalse(File(target.path + ".new").exists())
    }

    @Test fun directoryTargetIsRejectedWithoutDeletingItsContents() = fixture { target ->
        target.mkdir()
        val child = File(target, "sentinel").apply { writeText("keep") }
        assertTrue(runCatching { writeRestoredConfiguration(target) { it.write(1) } }.isFailure)
        assertEquals("keep", child.readText())
    }
}
