package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.api.ApiFactory
import com.lemon.mcdevmanagermp.data.api.createMailboxApi
import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.consts.LoginException
import com.lemon.mcdevmanagermp.utils.UnifiedExceptionHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpFailureTest {
    private fun response(status: Int, check: (NetworkState<*>) -> Unit) = runBlocking {
        // 只访问本机测试服务，不使用真实 Cookie，不请求网易接口。
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mailbox/unread/count") { exchange ->
            val body = "<html>test response</html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = ApiFactory.provideKtorfit("http://127.0.0.1:${server.address.port}/").createMailboxApi()
            check(UnifiedExceptionHandler.handleRequest { api.getUnReadCount() })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun gatewayFailureIsNotJsonOrLoginFailure() = response(502) { state ->
        val error = assertIs<NetworkState.Error<*>>(state)
        assertTrue(error.msg.contains("502"))
        assertFalse(error.e is LoginException)
        assertFalse(error.e is CookiesExpiredException)
    }

    @Test
    fun unauthorizedResponseIsStillRecognized() = response(401) { state ->
        assertIs<LoginException>(assertIs<NetworkState.Error<*>>(state).e)
    }
}
