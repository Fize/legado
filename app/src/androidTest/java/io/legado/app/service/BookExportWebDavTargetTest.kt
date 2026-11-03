package io.legado.app.service

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class BookExportWebDavTargetTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val file=File(context.cacheDir,"export-upload-${UUID.randomUUID()}.txt")
    private data class Received(val method: String,val path: String,val authorization: String?,val bytes: ByteArray)
    private val received=CopyOnWriteArrayList<Received>()
    private var rejected=false
    private val server=object: NanoHTTPD("127.0.0.1",0) {
        override fun serve(session: IHTTPSession): Response {
            val files=hashMapOf<String,String>(); session.parseBody(files)
            received.add(Received(session.method.name,session.uri,session.headers["authorization"],
                files["content"]?.let { File(it).readBytes() } ?: byteArrayOf()))
            return newFixedLengthResponse(if(rejected) Response.Status.INTERNAL_ERROR else Response.Status.CREATED,"text/plain","fixture")
        }
    }
    private fun values()=mutableMapOf(PreferKey.webDavUrl to "http://127.0.0.1:${server.listeningPort}/dav",
        PreferKey.webDavDir to "Legado",PreferKey.webDavAccount to "fixture",PreferKey.webDavPassword to "fixture-password")
    @Before fun setup() { file.writeText("真实导出字节\n"); server.start() }
    @After fun cleanup() { server.stop(); file.delete() }

    @Test fun uploadUsesTheCapturedDirectoryCredentialsAndExactBytes()=runBlocking(Dispatchers.IO) {
        val values=values(); val target=BookExportWebDavTarget.from(values)
        values[PreferKey.webDavDir]="Changed"; values[PreferKey.webDavPassword]="changed-password"
        target.upload(file.toUri(),"export.txt")
        val request=received.single()
        assertEquals("PUT",request.method); assertEquals("/dav/Legado/books/export.txt",request.path)
        assertEquals(Credentials.basic("fixture","fixture-password"),request.authorization)
        assertArrayEquals(file.readBytes(),request.bytes)
    }
    @Test fun nonSuccessResponsesAreReturnedAsFailures()=runBlocking(Dispatchers.IO) {
        rejected=true
        assertTrue(runCatching { BookExportWebDavTarget.from(values()).upload(file.toUri(),"export.txt") }.isFailure)
        assertEquals(1,received.size)
    }
    @Test fun unicodeAndReservedCharactersAreEncodedAsOneFilename()=runBlocking(Dispatchers.IO) {
        BookExportWebDavTarget.from(values()).upload(file.toUri(),"书 #?.txt")
        assertEquals("/dav/Legado/books/书 #?.txt",received.single().path)
        assertArrayEquals(file.readBytes(),received.single().bytes)
    }
}
