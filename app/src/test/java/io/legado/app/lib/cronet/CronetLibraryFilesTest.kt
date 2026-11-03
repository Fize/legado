package io.legado.app.lib.cronet

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class CronetLibraryFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun checksum(file: File) = MessageDigest.getInstance("MD5").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    @Test fun newLibraryIsVerifiedAndReadOnlyBeforePublishing() {
        val source = temporary.newFile("download").apply { writeText("verified native bytes") }
        val target = File(temporary.root, "installed/libcronet.so")
        installVerifiedCronetLibrary(source, target, checksum(source))
        assertArrayEquals(source.readBytes(), target.readBytes())
        assertFalse(target.canWrite())
        assertEquals(listOf("libcronet.so"), target.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun previouslyWritableValidLibraryIsPreparedWithoutReplacingItsBytes() {
        val target = temporary.newFile("legacy.so").apply { writeText("old writable installation") }
        assertTrue(target.canWrite())
        val expected = target.readBytes()
        assertEquals(target, prepareVerifiedCronetLibrary(target, checksum(target)))
        assertArrayEquals(expected, target.readBytes())
        assertFalse(target.canWrite())
    }

    @Test fun invalidCachedLibraryIsNotPreparedForLoading() {
        val target = temporary.newFile("bad.so").apply { writeText("corrupt") }
        assertThrows(IOException::class.java) { prepareVerifiedCronetLibrary(target, "0".repeat(32)) }
        assertEquals("corrupt", target.readText())
        assertTrue(target.canWrite())
    }

    @Test fun atomicInstallCanReplaceAnExistingReadOnlyLibrary() {
        val source = temporary.newFile("download").apply { writeText("new checked bytes") }
        val target = temporary.newFile("installed.so").apply { writeText("old bytes"); setReadOnly() }
        installVerifiedCronetLibrary(source, target, checksum(source))
        assertArrayEquals(source.readBytes(), target.readBytes())
        assertFalse(target.canWrite())
    }

    @Test fun failedChecksumKeepsTheOriginalInstallationAndRemovesPendingFile() {
        val source = temporary.newFile("download").apply { writeText("corrupt download") }
        val directory = temporary.newFolder("installed")
        val target = File(directory, "libcronet.so").apply { writeText("known-good"); setReadOnly() }
        assertThrows(IOException::class.java) { installVerifiedCronetLibrary(source, target, "0".repeat(32)) }
        assertEquals("known-good", target.readText())
        assertFalse(target.canWrite())
        assertEquals(listOf("libcronet.so"), directory.listFiles()!!.map { it.name })
    }

    @Test fun directoryTargetIsRejectedWithoutChangingItsContents() {
        val source = temporary.newFile("download").apply { writeText("valid bytes") }
        val target = temporary.newFolder("target")
        File(target, "keep").writeText("untouched")
        assertThrows(IOException::class.java) { installVerifiedCronetLibrary(source, target, checksum(source)) }
        assertEquals("untouched", File(target, "keep").readText())
    }
}
