package io.legado.app.ui.font

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FontSelectionStyleTest {

    @Test
    fun `selected font uses an accent stroke`() {
        val dialog = readProjectFile("src/main/java/io/legado/app/ui/font/FontSelectDialog.kt")
            .substringAfter("internal fun FontItemRow(")

        assertTrue(dialog.contains("val selected = doc.toString() == curFilePath"))
        assertTrue(dialog.contains(".background(Color.Transparent)"))
        assertTrue(dialog.contains("Modifier.border(2.dp, accent, RoundedCornerShape(4.dp))"))
        assertTrue(dialog.contains("R.string.font_item_private"))
        assertTrue(dialog.contains("R.string.font_item_external"))
    }

    @Test
    fun `font preview falls back when loading a recycled row fails`() {
        val dialog = readProjectFile("src/main/java/io/legado/app/ui/font/FontSelectDialog.kt")
            .substringAfter("internal fun loadFontTypeface(")

        assertTrue(dialog.contains("return kotlin.runCatching"))
        assertTrue(dialog.contains("}.getOrNull() ?: Typeface.DEFAULT"))
    }

    @Test
    fun `private fonts load independently of the optional external folder`() {
        val dialog = readProjectFile("src/main/java/io/legado/app/ui/font/FontSelectDialog.kt")
        val setup = dialog.substringAfter("val fontPath = getPrefString(PreferKey.fontFolder)")
            .substringBefore("private fun importFont(uri: Uri)")
        val localLoaderMarker =
            "private fun loadLocalFonts(openFolderWhenEmpty: Boolean = false)"
        assertTrue(dialog.contains(localLoaderMarker))
        val localLoader = dialog.substringAfter(localLoaderMarker)
            .substringBefore("private fun getLocalFonts()")

        assertTrue(setup.contains("loadLocalFonts(openFolderWhenEmpty = true)"))
        assertTrue(setup.contains("loadFontFiles(FileDoc.fromDocumentFile(doc))"))
        val readableFolder = setup.substringAfter("if (doc?.canRead() == true)")
            .substringBefore("} else {")
        assertFalse(readableFolder.contains("loadLocalFonts"))
        assertTrue(localLoader.contains("getLocalFonts()"))
        assertTrue(localLoader.contains("if (it.isNotEmpty())"))
        assertTrue(localLoader.contains("fontItems.addAll(it)"))
        assertTrue(localLoader.contains("else if (openFolderWhenEmpty)"))
        assertTrue(localLoader.contains("openFolder()"))

        val permissionLoader = dialog.substringAfter("private fun loadFontFilesByPermission")
            .substringBefore("private fun loadFontFiles(fileDoc")
        assertTrue(permissionLoader.contains(".onDenied"))
        assertTrue(permissionLoader.contains("loadLocalFonts()"))

        val externalLoader = dialog.substringAfter("private fun loadFontFiles(fileDoc")
            .substringBefore("private fun mergeFontItems")
        assertTrue(externalLoader.substringAfter(".onError").contains("loadLocalFonts()"))
    }

    @Test
    fun `font import installs valid files without overwriting name conflicts`() {
        val root = Files.createTempDirectory("font-import").toFile()
        try {
            val fonts = root.resolve("font")
            val firstBytes = "font-one".encodeToByteArray()
            val first = installFontFile(
                ByteArrayInputStream(firstBytes),
                "folder\\Demo.ttf",
                fonts,
            ) { it.readBytes().contentEquals(firstBytes) }
            val duplicate = installFontFile(
                ByteArrayInputStream(firstBytes),
                "Demo.ttf",
                fonts,
            ) { true }
            val secondBytes = "font-two".encodeToByteArray()
            val second = installFontFile(
                ByteArrayInputStream(secondBytes),
                "Demo.ttf",
                fonts,
            ) { true }

            assertEquals("Demo.ttf", first.name)
            assertEquals(first, duplicate)
            assertEquals("Demo (1).ttf", second.name)
            assertArrayEquals(firstBytes, first.readBytes())
            assertArrayEquals(secondBytes, second.readBytes())
            assertTrue(fonts.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `font import rejects unsupported and invalid files without residue`() {
        val root = Files.createTempDirectory("font-import-invalid").toFile()
        try {
            val fonts = root.resolve("font")
            assertThrows(IllegalArgumentException::class.java) {
                installFontFile(ByteArrayInputStream(byteArrayOf(1)), "font.txt", fonts) { true }
            }
            assertThrows(IllegalArgumentException::class.java) {
                installFontFile(ByteArrayInputStream(byteArrayOf(1)), "font.otf", fonts) { false }
            }

            assertTrue(fonts.listFiles()?.isEmpty() == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent font imports never overwrite the same target`() {
        val root = Files.createTempDirectory("font-import-concurrent").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val fonts = root.resolve("font")
            val start = CountDownLatch(1)
            val contents = listOf("first-font", "second-font")
            val futures = contents.map { content ->
                executor.submit<File> {
                    start.await()
                    installFontFile(
                        ByteArrayInputStream(content.encodeToByteArray()),
                        "Concurrent.ttf",
                        fonts,
                    ) { true }
                }
            }
            start.countDown()
            val installed = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(
                setOf("Concurrent.ttf", "Concurrent (1).ttf"),
                installed.mapTo(hashSetOf(), File::getName),
            )
            assertEquals(contents.toSet(), installed.mapTo(hashSetOf(), File::readText))
            assertTrue(fonts.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun `font picker exposes a single file import and keeps same names by path`() {
        val dialog = readProjectFile("src/main/java/io/legado/app/ui/font/FontSelectDialog.kt")
        val importIo = dialog.substringAfter("private fun importFont(uri: Uri)")
            .substringAfter("execute {")
            .substringBefore("}.onSuccess")

        assertTrue(dialog.contains("R.drawable.ic_import"))
        assertTrue(dialog.contains("mode = HandleFileContract.FILE"))
        assertTrue(importIo.contains("FileDoc.fromUri(uri, false)"))
        assertTrue(importIo.contains("source.openInputStream().getOrThrow()"))
        assertTrue(importIo.contains("installFontFile(input, source.name, directory, ::isValidFont)"))
        assertTrue(dialog.contains("if (paths.add(item.toString()))"))
        assertFalse(dialog.contains("if (item2.name == item1.name)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
