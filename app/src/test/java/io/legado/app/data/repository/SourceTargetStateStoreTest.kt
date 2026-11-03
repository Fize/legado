package io.legado.app.data.repository

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceTargetStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = File(temporary.root, "snapshots")
    private fun store() = SourceTargetStateStore(root())

    @Test fun largeSnapshotsSurviveNewStoreAndDeduplicateRepeatedSaves() {
        val store = store()
        val lease = store.create(17)
        val urls = List(8_000) { "https://source.invalid/$it/" + "中a".repeat(110) }
        val reference = store.save(lease, listOf(urls, urls.reversed()))
        repeat(4) { assertEquals(reference, store.save(lease, listOf(urls, urls.reversed()))) }
        assertEquals(listOf(urls, urls.reversed()), store().read(reference))
        assertEquals(1, File(root(), lease.owner).listFiles()!!.count { it.extension == "bin" })
    }

    @Test fun olderReferenceNeverChangesWhenSelectionChanges() {
        val store = store()
        val lease = store.create(17)
        val first = store.save(lease, listOf(listOf("original")))
        val second = store.save(lease, listOf(listOf("replacement")))
        assertNotEquals(first, second)
        assertEquals(listOf(listOf("original")), store.read(first))
        assertEquals(listOf(listOf("replacement")), store.read(second))
    }

    @Test fun retiredHostCannotSaveOrDeleteRestoredHostsSnapshots() {
        val store = store()
        val old = store.create(17)
        val reference = store.save(old, listOf(listOf("original")))
        val current = store().claim(reference)
        assertTrue(runCatching { store.save(old, listOf(listOf("stale"))) }.isFailure)
        store.release(old)
        assertEquals(listOf(listOf("original")), store.read(reference))
        val next = store.save(current, listOf(listOf("current")))
        store.release(current)
        assertTrue(runCatching { store.read(next) }.isFailure)
    }

    @Test fun prunePreservesOldLiveTasksAndRemovesOnlyRemovedTasks() {
        val store = store()
        val live = store.create(17)
        val removed = store.create(18)
        val keep = store.save(live, listOf(listOf("keep")))
        val discard = store.save(removed, listOf(listOf("discard")))
        File(root(), live.owner).walkTopDown().forEach { it.setLastModified(1) }
        store.pruneRemovedTasks(setOf(17))
        assertEquals(listOf(listOf("keep")), store.read(keep))
        assertTrue(runCatching { store.read(discard) }.isFailure)
    }

    @Test fun missingAndCorruptedFilesFailInsteadOfSubstitutingAnotherSnapshot() {
        val store = store()
        val lease = store.create(17)
        val ref = store.save(lease, listOf(listOf("target")))
        val file = File(root(), "$ref.bin")
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertTrue(runCatching { store.read(ref) }.isFailure)
        file.delete()
        assertTrue(runCatching { store.read(ref) }.isFailure)
    }

    @Test fun referencesCannotEscapePrivateDirectory() {
        for (reference in listOf("../owner", "${java.util.UUID.randomUUID()}/../../outside", "invalid")) {
            assertTrue(runCatching { store().read(reference) }.isFailure)
            assertTrue(runCatching { store().claim(reference) }.isFailure)
        }
    }

    @Test fun unavailableStorageNeverProducesSuccessfulOwner() {
        root().writeText("not a directory")
        assertTrue(runCatching { store().create(17) }.isFailure)
    }

    @Test fun excessiveTargetCountFailsWithoutPublishingPartialSnapshot() {
        val store = store()
        val lease = store.create(17)
        assertTrue(runCatching { store.save(lease, listOf(List(100_001) { "x" })) }.isFailure)
        assertEquals(0, File(root(), lease.owner).listFiles()!!.count { it.extension == "bin" })
    }
}
