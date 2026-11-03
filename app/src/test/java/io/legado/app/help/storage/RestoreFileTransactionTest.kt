package io.legado.app.help.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RestoreFileTransactionTest {
    private fun fixture(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("restore-files-test").toFile()
        val source = File(root, "input").apply { mkdirs() }
        val media = File(root, "media").apply { mkdirs() }
        val journal = File(root, "private/restore-files-v1.bin").apply { parentFile!!.mkdirs() }
        try { block(source, media, journal) } finally { root.deleteRecursively() }
    }
    private fun put(root: File, path: String, value: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(value) }
    private fun prepare(source: File, media: File, journal: File, styles: Boolean = false) = checkNotNull(
        RestoreFileTransaction.prepare(source, media, journal, listOf("covers", "bg"),
            if (styles) mapOf("readConfig.json" to "new-style".toByteArray()) else emptyMap()))

    @Test fun preparationDoesNotPublishFilesAndRollbackRestoresTheWholeBundle() = fixture { source, media, journal ->
        put(source, "covers/shared", "new"); put(source, "covers/added", "added")
        put(media, "covers/shared", "old"); put(media, "covers/keep", "keep")
        val style = put(journal.parentFile!!, "readConfig.json", "old-style")
        val transaction = prepare(source, media, journal, styles = true)
        assertEquals("old", File(media, "covers/shared").readText()); assertEquals("old-style", style.readText())
        transaction.activate()
        assertEquals("new", File(media, "covers/shared").readText()); assertEquals("new-style", style.readText())
        transaction.resolve(false); transaction.cleanup()
        assertEquals("old", File(media, "covers/shared").readText()); assertEquals("keep", File(media, "covers/keep").readText())
        assertFalse(File(media, "covers/added").exists()); assertEquals("old-style", style.readText()); assertFalse(journal.exists())
    }

    @Test fun committedRecoveryKeepsNewFilesAndCleansBeforeImages() = fixture { source, media, journal ->
        put(source, "covers/shared", "new"); put(media, "covers/shared", "old")
        val transaction = prepare(source, media, journal); transaction.activate()
        val recovered = checkNotNull(RestoreFileTransaction.read(journal, media))
        recovered.resolve(true); recovered.cleanup()
        assertEquals("new", File(media, "covers/shared").readText()); assertFalse(journal.exists())
        assertFalse(File(media, ".restore-files-${transaction.id}").exists())
    }

    @Test fun recoveryBeforeActivationLeavesOriginalDirectoriesAlone() = fixture { source, media, journal ->
        put(source, "covers/shared", "new"); put(media, "covers/shared", "old")
        prepare(source, media, journal)
        checkNotNull(RestoreFileTransaction.read(journal, media)).apply { resolve(false); cleanup() }
        assertEquals("old", File(media, "covers/shared").readText())
    }

    @Test fun recoveryAfterPartialActivationRollsBackBothDirectories() = fixture { source, media, journal ->
        put(source, "covers/shared", "new"); put(source, "bg/shared", "new-bg")
        put(media, "covers/shared", "old"); put(media, "bg/shared", "old-bg")
        val transaction = prepare(source, media, journal)
        assertTrue(runCatching { transaction.activate {
            if (File(media, "covers/shared").takeIf { it.isFile }?.readText() == "new") throw IOException("interrupted")
        } }.isFailure)
        checkNotNull(RestoreFileTransaction.read(journal, media)).apply { resolve(false); cleanup() }
        assertEquals("old", File(media, "covers/shared").readText()); assertEquals("old-bg", File(media, "bg/shared").readText())
    }

    @Test fun rollbackRestoresOriginallyAbsentFilesAndDirectoriesToAbsence() = fixture { source, media, journal ->
        put(source, "covers/added", "new")
        val transaction = prepare(source, media, journal, styles = true); transaction.activate()
        checkNotNull(RestoreFileTransaction.read(journal, media)).apply { resolve(false); cleanup() }
        assertFalse(File(media, "covers").exists()); assertFalse(File(journal.parentFile, "readConfig.json").exists())
    }

    @Test fun cancellingPreparationDoesNotChangeExistingFiles() = fixture { source, media, journal ->
        put(source, "covers/large", "x".repeat(100_000)); put(media, "covers/original", "old")
        var checks = 0
        assertTrue(runCatching { RestoreFileTransaction.prepare(source, media, journal, listOf("covers"), checkActive = {
            if (++checks > 8) throw IOException("copy cancelled")
        }) }.isFailure)
        assertEquals("old", File(media, "covers/original").readText())
        assertFalse(File(media, "covers/large").exists()); assertFalse(journal.exists())
    }

    @Test fun concurrentChangesDetectedAtActivationArePreserved() = fixture { source, media, journal ->
        put(source, "covers/shared", "backup"); put(media, "covers/shared", "original")
        val transaction = prepare(source, media, journal)
        put(media, "covers/added-later", "concurrent")
        assertTrue(runCatching { transaction.activate() }.isFailure)
        transaction.resolve(false); transaction.cleanup()
        assertEquals("original", File(media, "covers/shared").readText())
        assertEquals("concurrent", File(media, "covers/added-later").readText())
    }

    @Test fun wrongVolumeAndMalformedJournalDoNotTouchLiveMedia() = fixture { source, media, journal ->
        put(source, "covers/shared", "new"); put(media, "covers/shared", "old")
        prepare(source, media, journal)
        assertTrue(runCatching { RestoreFileTransaction.read(journal, File(media.parentFile, "other-volume")) }.isFailure)
        assertEquals("old", File(media, "covers/shared").readText())
        journal.writeText("truncated")
        assertTrue(runCatching { RestoreFileTransaction.read(journal, media) }.isFailure)
        assertEquals("old", File(media, "covers/shared").readText())
    }

    @Test fun aFileInPlaceOfAMediaDirectoryIsNotDeleted() = fixture { source, media, journal ->
        put(source, "covers/new", "new"); put(media, "covers", "keep-file")
        assertTrue(runCatching { prepare(source, media, journal) }.isFailure)
        assertEquals("keep-file", File(media, "covers").readText()); assertFalse(journal.exists())
    }

    @Test fun additionalFontValidationFailureIsStillPreparationOnly() = fixture { source, media, journal ->
        put(media, "font/old.ttf", "old-font")
        assertTrue(runCatching { RestoreFileTransaction.prepare(source, media, journal, emptyList(),
            additionalDirectories = mapOf("font" to { throw IOException("invalid font") })) }.isFailure)
        assertEquals("old-font", File(media, "font/old.ttf").readText()); assertFalse(journal.exists())
    }

    @Test fun symlinksCannotEscapeTheMediaSnapshot() = fixture { source, media, journal ->
        val outside = put(source.parentFile!!, "outside/keep", "keep")
        File(source, "covers").mkdirs()
        Files.createSymbolicLink(File(source, "covers/link").toPath(), outside.parentFile!!.toPath())
        assertTrue(runCatching { prepare(source, media, journal) }.isFailure)
        assertEquals("keep", outside.readText())
        // Do not let the fixture's own recursive cleanup follow the test link.
        Files.delete(File(source, "covers/link").toPath())
    }

    @Test fun aCommitWithoutActivationIsRejectedAndCanStillRollBack() = fixture { source, media, journal ->
        put(source, "covers/new", "new"); put(media, "covers/old", "old")
        val transaction = prepare(source, media, journal)
        assertTrue(runCatching { transaction.resolve(true) }.isFailure)
        transaction.resolve(false); transaction.cleanup()
        assertEquals("old", File(media, "covers/old").readText()); assertFalse(File(media, "covers/new").exists())
    }
}
