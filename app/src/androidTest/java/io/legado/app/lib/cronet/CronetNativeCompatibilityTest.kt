package io.legado.app.lib.cronet

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Real selected transport, official version-pinned download, native mappings, and local HTTP traffic. */
@RunWith(AndroidJUnit4::class)
class CronetNativeCompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val originalPreference = preferences.all[PreferKey.cronet]
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "cronet-native-compat/$id")
    private val socketPasses = AtomicInteger()
    private val requests = AtomicInteger()
    private val slowStarted = CountDownLatch(1)
    private val releaseSlow = CountDownLatch(1)
    private val text = "Cronet native fixture 中文 $id"
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests.incrementAndGet()
            return when (session.uri) {
                "/redirect" -> newFixedLengthResponse(Response.Status.FOUND, "application/octet-stream", "redirect")
                    .also { it.addHeader("Location", "http://127.0.0.1:$listeningPort/gzip") }
                "/gzip" -> {
                    val bytes = ByteArrayOutputStream().also { output ->
                        GZIPOutputStream(output).use { it.write(text.toByteArray()) }
                    }.toByteArray()
                    newFixedLengthResponse(Response.Status.OK, "application/octet-stream", bytes.inputStream(), bytes.size.toLong())
                        .also { it.addHeader("Content-Encoding", "gzip") }
                }
                "/upload" -> {
                    val bytes = ByteArray(session.headers.getValue("content-length").toInt())
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = session.inputStream.read(bytes, offset, bytes.size - offset)
                        check(count > 0) { "Incomplete upload fixture" }
                        offset += count
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/octet-stream", digest("SHA-256", bytes))
                }
                "/slow" -> {
                    slowStarted.countDown()
                    releaseSlow.await(15, TimeUnit.SECONDS)
                    newFixedLengthResponse(text)
                }
                "/error" -> newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/octet-stream", text)
                else -> newFixedLengthResponse(Response.Status.OK, "application/octet-stream", text)
            }
        }
    }
    private val client by lazy {
        okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            socketPasses.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
    }

    @Before fun setUp() {
        assumeTrue("Explicit opt-in required for official native library download",
            InstrumentationRegistry.getArguments().getString("nativeCronetCompatibility") == "yes")
        check(directory.mkdirs())
        val original = JSONObject().put("id", id).put("type", when (originalPreference) {
            null -> "missing"
            is Boolean -> "boolean"
            is String -> "string"
            else -> error("Unexpected legacy Cronet preference type")
        }).put("value", originalPreference ?: JSONObject.NULL)
        File(directory, "original-pref.json").outputStream().use {
            it.write(original.toString().toByteArray()); it.flush(); it.fd.sync()
        }
        check(preferences.edit().putBoolean(PreferKey.cronet, true).commit())
        server.start()
    }

    @After fun cleanUp() {
        releaseSlow.countDown()
        server.stop()
        if (directory.isDirectory) {
            restoreCronetPreference(preferences, JSONObject(File(directory, "original-pref.json").readText()))
            File(directory, "preference-restored.json").writeText(JSONObject().put("id", id).put("restored", true).toString())
        }
    }

    @Test(timeout = 120_000) fun nativeGetUsesDownloadedLibraryAndSelectedTransport() {
        requireNativeEngine()
        client.newCall(request("/get")).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(text, response.body.string())
        }
        record("native-get")
    }

    @Test(timeout = 120_000) fun redirectAndGzipRemainOnNativeTransport() {
        requireNativeEngine()
        client.newCall(request("/redirect")).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(text, response.body.string())
            assertEquals("/gzip", response.request.url.encodedPath)
        }
        assertEquals(2, requests.get())
        record("native-redirect-gzip")
    }

    @Test(timeout = 120_000) fun largePostUsesTheRealNativeUploadProvider() {
        requireNativeEngine()
        val body = ("upload-$id\n").repeat(4096).toByteArray()
        assertTrue(body.size > BUFFER_SIZE)
        client.newCall(request("/upload").newBuilder().post(body.toRequestBody("text/plain; charset=utf-8".toMediaType())).build())
            .execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(digest("SHA-256", body), response.body.string())
            }
        record("native-large-post", JSONObject().put("uploadBytes", body.size))
    }

    @Test(timeout = 120_000) fun cancellationDoesNotRetryViaTheSocketTransport() {
        requireNativeEngine()
        val completion = CompletableFuture<IOException>()
        val call = client.newCall(request("/slow"))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { completion.complete(e) }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.close(); completion.completeExceptionally(AssertionError("Canceled call returned a response"))
            }
        })
        assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
        val started = android.os.SystemClock.elapsedRealtime()
        call.cancel()
        completion.get(10, TimeUnit.SECONDS)
        assertTrue(call.isCanceled())
        assertEquals(1, requests.get())
        record("native-cancel", JSONObject().put("cancelMillis", android.os.SystemClock.elapsedRealtime() - started))
    }

    @Test(timeout = 120_000) fun serverErrorDoesNotSilentlyChangeTransport() {
        requireNativeEngine()
        client.newCall(request("/error")).execute().use { response ->
            assertEquals(503, response.code)
            assertEquals(text, response.body.string())
        }
        assertEquals(1, requests.get())
        record("native-server-error")
    }

    @Test(timeout = 120_000) fun disabledCronetKeepsTheExistingSocketPath() {
        check(preferences.edit().putBoolean(PreferKey.cronet, false).commit())
        client.newCall(request("/get")).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(text, response.body.string())
        }
        assertEquals(1, socketPasses.get())
        File(directory, "result.json").writeText(JSONObject().put("case", "cronet-disabled")
            .put("status", "passed").put("socketPasses", socketPasses.get()).put("requests", requests.get()).toString(2))
    }

    @Test(timeout = 120_000) fun pinnedHttpsDownloadUsesNativeTlsAndNegotiatedHttp2OrQuic() {
        requireNativeEngine()
        val version = BuildConfig.Cronet_Version
        val url = "https://storage.googleapis.com/chromium-cronet/android/$version/Release/cronet/libs/arm64-v8a/libcronet.$version.so"
        val observed = JSONObject()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.request.url.isHttps)
            val bytes = response.body.bytes()
            val expected = JSONObject(context.assets.open("cronet.json").bufferedReader().use { it.readText() }).getString("arm64-v8a")
            assertEquals(expected, digest("MD5", bytes))
            assertTrue("Official HTTPS resource must negotiate HTTP/2 or QUIC", response.protocol in setOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.QUIC))
            observed.put("https", true).put("protocol", response.protocol.toString())
                .put("downloadBytes", bytes.size).put("downloadSHA256", digest("SHA-256", bytes))
        }
        record("native-https-pinned", observed)
    }

    @Test(timeout = 120_000) fun untrustedTlsCertificateFailsWithoutAnInsecureSocketFallback() {
        requireNativeEngine()
        val keyStore = KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets.open("cronet-tls/untrusted-localhost.p12").use {
            keyStore.load(it, "fixture-only".toCharArray())
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "fixture-only".toCharArray())
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val acceptedRequests = AtomicInteger()
        val tlsServer = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                acceptedRequests.incrementAndGet()
                return newFixedLengthResponse("untrusted TLS fixture")
            }
        }
        tlsServer.makeSecure(ssl.serverSocketFactory, null)
        tlsServer.start()
        try {
            val failure = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("https://127.0.0.1:${tlsServer.listeningPort}/untrusted").build()).execute().use {
                    it.body.string()
                }
            }
            val errors = generateSequence<Throwable>(failure) { it.cause }.take(10).joinToString(" | ") { it.toString() }
            assertTrue("Must reject the untrusted issuer, not hide a TLS fixture error: $errors", errors.contains("ERR_CERT_AUTHORITY_INVALID"))
            assertEquals(0, acceptedRequests.get())
            record("native-untrusted-tls", JSONObject().put("untrustedIssuerRejected", true).put("acceptedHttpRequests", 0))
        } finally {
            tlsServer.stop()
        }
    }

    @Test(timeout = 120_000) fun refusedConnectionReportsNativeFailureWithoutSocketFallback() {
        requireNativeEngine()
        val port = ServerSocket(0).use { it.localPort }
        val failure = assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/refused").build()).execute().use { it.body.string() }
        }
        val errors = generateSequence<Throwable>(failure) { it.cause }.take(10).joinToString(" | ") { it.toString() }
        assertTrue("Must report the native connection error: $errors", errors.contains("ERR_CONNECTION_REFUSED"))
        record("native-refused-connection", JSONObject().put("nativeConnectionErrorReported", true))
    }

    @Test(timeout = 120_000) fun explicitHttpProxyPreservesTheProductionCronetBypass() {
        val accepted = AtomicInteger()
        val destination = "compatibility-proxy-$id.invalid"
        var observedHost = ""
        var observedTarget = ""
        val proxy = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                accepted.incrementAndGet()
                observedHost = session.headers["host"].orEmpty()
                observedTarget = session.uri
                return newFixedLengthResponse(text)
            }
        }
        proxy.start()
        try {
            val proxyClient = getProxyClient("http://127.0.0.1:${proxy.listeningPort}").newBuilder()
                .addNetworkInterceptor { chain -> socketPasses.incrementAndGet(); chain.proceed(chain.request()) }.build()
            assertTrue(AppConfig.isCronet)
            proxyClient.newCall(Request.Builder().url("http://$destination/fixture").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(text, response.body.string())
            }
            assertEquals(1, accepted.get())
            assertEquals(destination, observedHost)
            assertEquals(1, socketPasses.get())
            File(directory, "result.json").writeText(JSONObject().put("case", "explicit-http-proxy")
                .put("status", "passed").put("cronetSwitchStillEnabled", AppConfig.isCronet)
                .put("socketPasses", socketPasses.get()).put("proxyRequests", accepted.get())
                .put("proxyTarget", observedTarget).toString(2))
        } finally {
            proxy.stop()
        }
    }

    private fun request(path: String) = Request.Builder().url("http://127.0.0.1:${server.listeningPort}$path").build()

    private fun requireNativeEngine() {
        val engine = getCronetEngineOrNull()
        assertNotNull("Version-pinned Cronet must initialize; do not count an OkHttp fallback", engine)
        assertTrue(engine!!.javaClass.name.contains("CronetUrlRequestContext"))
        assertTrue(engine.versionString.contains(BuildConfig.Cronet_Version))
    }

    private fun record(name: String, details: JSONObject = JSONObject()) {
        assertEquals("Selected Cronet must never execute the OkHttp socket interceptors", 0, socketPasses.get())
        val library = File(context.getDir("cronet", 0), "arm64-v8a/libcronet.${BuildConfig.Cronet_Version}.so")
        assertTrue(library.isFile)
        assertFalse("Loaded Cronet library must be read-only", library.canWrite())
        val bytes = library.readBytes()
        val expected = JSONObject(context.assets.open("cronet.json").bufferedReader().use { it.readText() }).getString("arm64-v8a")
        assertEquals(expected, digest("MD5", bytes))
        val maps = File("/proc/self/maps").readLines().filter { it.contains(library.name) }
        assertTrue("Actual native executable mapping is required", maps.any { it.contains("r-xp") })
        val engine = getCronetEngineOrNull()!!
        details.put("case", name).put("status", "passed").put("version", engine.versionString)
            .put("engineClass", engine.javaClass.name).put("libraryName", library.name)
            .put("libraryBytes", bytes.size).put("libraryMD5", expected).put("librarySHA256", digest("SHA-256", bytes))
            .put("executableNativeMapping", true).put("libraryReadOnly", true).put("socketPasses", socketPasses.get()).put("requests", requests.get())
        File(directory, "result.json").writeText(details.toString(2))
    }
}

internal fun restoreCronetPreference(preferences: SharedPreferences, original: JSONObject) {
    check(preferences.edit().apply {
        when (original.getString("type")) {
            "missing" -> remove(PreferKey.cronet)
            "boolean" -> putBoolean(PreferKey.cronet, original.getBoolean("value"))
            "string" -> putString(PreferKey.cronet, original.getString("value"))
            else -> error("Unexpected saved Cronet preference type")
        }
    }.commit())
}

private fun digest(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm)
    .digest(bytes).joinToString("") { "%02x".format(it) }
