package io.legado.app

import fi.iki.elonen.NanoHTTPD
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HttpTtsTest {

    @Test
    fun analyzeUrlPostsSpeechParametersAndReturnsAudio() {
        val audio = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val capturedRequest = AtomicReference<String>()
        val capturedParameters = AtomicReference<Map<String, String>?>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                session.parseBody(HashMap())
                capturedRequest.set(session.method.name)
                capturedParameters.set(session.parameters.mapValues { (_, values) -> values.joinToString() })
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "audio/wav",
                    audio.inputStream(),
                    audio.size.toLong(),
                )
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

        try {
            val url = """
                http://127.0.0.1:${server.listeningPort}/tts,{
                    "method":"POST",
                    "body":"tex={{java.encodeURI(speakText)}}&spd={{speakSpeed}}"
                }
            """.trimIndent()
            val response = runBlocking {
                AnalyzeUrl(url, speakText = "魔神", speakSpeed = 7).getResponseAwait()
            }

            response.use {
                assertEquals(200, it.code)
                assertArrayEquals(audio, it.body!!.bytes())
            }
            assertEquals("POST", capturedRequest.get())
            val parameters = capturedParameters.get().orEmpty()
            assertTrue("TTS text missing from form parameters: $parameters", parameters["tex"].orEmpty().isNotBlank())
            assertEquals("7", parameters["spd"])
        } finally {
            server.stop()
        }
    }
}
