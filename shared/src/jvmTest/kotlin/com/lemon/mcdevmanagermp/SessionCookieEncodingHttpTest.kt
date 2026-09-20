package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.api.SessionCookieStorage
import com.lemon.mcdevmanagermp.utils.SessionCookieStore
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionCookieEncodingHttpTest {
    @Test
    fun realHttpRoundTripsPreserveRawAndPercentEncodedValuesWithoutPersistingChanges() = runBlocking {
        val expected = linkedMapOf(
            "S_INFO" to "fake|session|value",
            "P_INFO" to "literal%257C|+/%3D==",
            "NTES_SESS" to "fake=="
        )
        val observed = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            observed.add(cookie)
            parseClientCookiesHeader(cookie).forEach { (name, value) ->
                exchange.responseHeaders.add("Set-Cookie", "$name=$value; Path=/; HttpOnly")
            }
            val response = "ok".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
        val store = SessionCookieStore()
        expected.forEach { (name, value) -> store.addCookie(name, value) }
        store.bindAccount(7, expected)
        var saves = 0
        val storage = SessionCookieStorage(store) { store.persistChanges { _, _ -> saves++ } }
        val trustedUrl = Url("https://mc-launcher.webapp.163.com/")
        val client = HttpClient {
            expectSuccess = true
            install(HttpTimeout) { socketTimeoutMillis = 2_000; requestTimeoutMillis = 5_000 }
            install(HttpCookies) {
                // 只在测试中把本机地址映射到可信地址，生产域名限制保持不变。
                this.storage = object : CookiesStorage {
                    override suspend fun get(requestUrl: Url) = storage.get(trustedUrl)
                    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = storage.addCookie(trustedUrl, cookie)
                    override fun close() = storage.close()
                }
            }
        }
        try {
            repeat(8) {
                assertEquals("ok", client.get("http://127.0.0.1:${server.address.port}/").bodyAsText())
                assertEquals(expected, store.getAllCookiesMap())
            }
            val expectedHeader = expected.entries.joinToString("; ") { "${it.key}=${it.value}" }
            assertEquals(List(8) { expectedHeader }, observed.toList())
            assertEquals(0, saves)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
