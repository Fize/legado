package io.legado.app.ui.book.remoteimport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Loopback HTTP/1.1 fixture which accepts WebDAV verbs, including PROPFIND. */
internal class WebDavFixtureServer(private val handler: (Request) -> Reply) {
    data class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: ByteArray = ByteArray(0))
    data class Reply(val status: Int, val type: String, val body: ByteArray) {
        constructor(status: Int, type: String, body: String) : this(status, type, body.toByteArray())
    }
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "webdav-fixture-request").apply { isDaemon = true } }
    val listeningPort: Int get() = listener.localPort
    private val acceptor = thread(name = "webdav-fixture-accept", isDaemon = true) {
        while (!listener.isClosed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: break
            sockets.add(socket)
            workers.execute {
                try {
                    socket.use {
                        it.soTimeout = 10_000
                        val input = it.getInputStream().buffered()
                        val requestLine = readLine(input).split(' ')
                        if (requestLine.size < 2) return@execute
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = readLine(input)
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase(Locale.ROOT)] = line.substringAfter(':').trim()
                        }
                        var remaining = headers["content-length"]?.toIntOrNull() ?: 0
                        val buffer = ByteArray(4096)
                        val body = ByteArrayOutputStream()
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                            check(read > 0)
                            body.write(buffer, 0, read)
                            remaining -= read
                        }
                        val reply = handler(Request(requestLine[0], URI("http://localhost${requestLine[1]}").path, headers, body.toByteArray()))
                        val description = when (reply.status) {
                            200 -> "OK"; 207 -> "Multi-Status"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Internal Server Error"
                        }
                        val header = "HTTP/1.1 ${reply.status} $description\r\nContent-Type: ${reply.type}\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n"
                        it.getOutputStream().apply { write(header.toByteArray()); write(reply.body); flush() }
                    }
                } catch (_: java.io.IOException) {
                    // Cancellation may close a client socket while the fixture is replying.
                } finally { sockets.remove(socket) }
            }
        }
    }
    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == 10) break
            if (byte != 13) bytes.write(byte)
        }
        return bytes.toString("UTF-8")
    }
    fun stop() {
        listener.close()
        acceptor.join(1000)
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
