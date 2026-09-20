package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.api.installSessionDiagnostics
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.http.encodeCookieValue
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDiagnosticsHttpTest {
    private fun request(
        status: Int,
        truncated: Boolean = false,
        brokenSink: Boolean = false,
        encoding: CookieEncoding = CookieEncoding.RAW
    ): List<String> = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val secret = "FAKE_COOKIE%257C=="
        var observedCookie = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mailbox/unread/count") { exchange ->
            observedCookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val body = "{\"status\":\"ok\",\"secret\":\"FAKE_RESPONSE_SECRET\"}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Set-Cookie", "S_INFO=FAKE_RESPONSE_COOKIE==; Path=/; HttpOnly")
            exchange.sendResponseHeaders(status, body.size.toLong() + if (truncated) 10 else 0)
            runCatching { exchange.responseBody.use { it.write(body) } }
            exchange.close()
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/"
        val client = HttpClient {
            expectSuccess = true
            install(HttpTimeout) { socketTimeoutMillis = 2_000; requestTimeoutMillis = 5_000 }
            install(HttpCookies) {
                default { addCookie(Url(base), Cookie("S_INFO", secret, encoding = encoding)) }
            }
            installSessionDiagnostics {
                snapshot = { mapOf("S_INFO" to secret) }
                write = { if (brokenSink) error("fake logger failure") else lines.add(it) }
            }
        }
        try {
            val result = runCatching { client.get("${base}mailbox/unread/count?token=FAKE_QUERY_SECRET").bodyAsText() }
            if (status == 200 && !truncated) assertTrue(result.isSuccess) else assertTrue(result.isFailure)
            assertTrue(observedCookie.contains(encodeCookieValue(secret, encoding)))
            if (!brokenSink) {
                val requestLine = lines.single { it.contains("event=request ") }
                assertTrue(requestLine.contains("headerBytes=${observedCookie.toByteArray().size}"))
                assertTrue(requestLine.contains("depth=2"))
                val id = Regex("id=(\\d+)").find(requestLine)!!.groupValues[1]
                assertTrue(lines.any { it.contains("event=response id=$id ") && it.contains("status=$status") })
                assertFalse(lines.any { it.contains("elapsedMs=null") || it.contains("id=null") })
                val output = lines.joinToString("\n")
                listOf(secret, "FAKE_COOKIE", "FAKE_QUERY_SECRET", "FAKE_RESPONSE_SECRET", "FAKE_RESPONSE_COOKIE").forEach {
                    assertFalse(output.contains(it), "diagnostics must not contain $it")
                }
            }
        } finally {
            client.close()
            server.stop(0)
        }
        lines.toList()
    }

    @Test
    fun capturesActualSentCookiesAndSuccessfulResponseWithoutSecrets() {
        request(200)
    }

    @Test
    fun distinguishesEncodedWireCookieFromStoredCookie() {
        val line = request(200, encoding = CookieEncoding.URI_ENCODING).single { it.contains("event=request ") }
        assertTrue(line.substringAfter("wire=[").substringBefore("]").contains("depth=3"))
        assertTrue(line.substringAfter("store=[").substringBefore("]").contains("depth=2"))
    }

    @Test
    fun capturesGatewayResponseBeforeStatusValidationThrows() {
        val lines = request(502)
        assertTrue(lines.any { it.contains("event=request_failure") && it.contains("status=502") }, lines.joinToString("\n"))
    }

    @Test
    fun capturesTruncatedBodyFailure() {
        val lines = request(200, truncated = true)
        assertTrue(lines.any { it.contains("event=request_failure") }, lines.joinToString("\n"))
    }

    @Test
    fun failingLoggerDoesNotFailSuccessfulRequest() {
        assertEquals(emptyList(), request(200, brokenSink = true))
    }
}
