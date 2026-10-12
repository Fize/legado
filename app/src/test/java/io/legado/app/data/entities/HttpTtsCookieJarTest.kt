package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HttpTtsCookieJarTest {

    @Test
    fun `auxiliary fields survive import export and editing`() {
        val jsLib = "function sign(text) { return java.md5Encode(text) }"
        val imported = HttpTTS.fromJson(
            """{"name":"test","url":"https://example.com","jsLib":"$jsLib","enabledCookieJar":true}"""
        ).getOrThrow()
        val legacy = HttpTTS.fromJson(
            """{"name":"test","url":"https://example.com"}"""
        ).getOrThrow()
        val roundTrip = HttpTTS.fromJson(GSON.toJson(imported)).getOrThrow()

        assertEquals(jsLib, imported.jsLib)
        assertTrue(imported.enabledCookieJar == true)
        assertNull(legacy.jsLib)
        assertEquals(false, legacy.enabledCookieJar)
        assertEquals(jsLib, roundTrip.jsLib)
        assertTrue(roundTrip.enabledCookieJar == true)

        val source = appFile("src/main/java/io/legado/app/ui/book/read/config/HttpTtsEditDialog.kt")
            .readText()
        assertTrue(source.contains("jsLibState = httpTTS.jsLib.orEmpty()"))
        assertTrue(source.contains("jsLib = jsLibState"))
        assertTrue(source.contains("enabledCookieJarState = httpTTS.enabledCookieJar == true"))
        assertTrue(source.contains("enabledCookieJar = enabledCookieJarState"))
        assertTrue(source.contains("label = \"jsLib\""))
        assertTrue(source.contains("R.string.auto_save_cookie"))
    }

    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.isFile }
}
