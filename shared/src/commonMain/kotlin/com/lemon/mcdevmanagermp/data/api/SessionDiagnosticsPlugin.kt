package com.lemon.mcdevmanagermp.data.api

import com.lemon.mcdevmanagermp.utils.CookiesStore
import com.lemon.mcdevmanagermp.utils.Logger
import com.lemon.mcdevmanagermp.utils.SessionDiagnostics
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.utils.HttpRequestIsReadyForSending
import io.ktor.client.utils.HttpResponseReceiveFailed
import io.ktor.client.utils.HttpResponseReceived
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class SessionDiagnosticsConfig {
    var write: (String) -> Unit = Logger::d
    var snapshot: () -> Map<String, String> = CookiesStore::getAllCookiesMap
}

private data class DiagnosticTrace(val id: Long, val started: TimeMark)
private val traceKey = AttributeKey<DiagnosticTrace>("SessionDiagnosticTrace")
private val sequence = MutableStateFlow(0L)

/** 校验器也覆盖发送完成后才抛出的状态校验/响应体读取异常。 */
internal fun HttpClientConfig<*>.installSessionDiagnostics(configure: SessionDiagnosticsConfig.() -> Unit = {}) {
    val diagnostics = SessionDiagnosticsConfig().apply(configure)
    install(SessionDiagnosticsPlugin) {
        write = diagnostics.write
        snapshot = diagnostics.snapshot
    }
    HttpResponseValidator {
        handleResponseExceptionWithRequest { error, request ->
            val trace = request.attributes.getOrNull(traceKey)
            val event = if (error is CancellationException) "cancelled" else "request_failure"
            runCatching {
                diagnostics.write("SESSION_DIAG v=1 event=$event id=${trace?.id} endpoint=${SessionDiagnostics.endpoint(request.url)} " +
                    "elapsedMs=${trace?.started?.elapsedNow()?.inWholeMilliseconds} ${SessionDiagnostics.failure(error)}")
            }
        }
    }
}

/** 所有客户端共用请求编号；发送事件读取最终头，不用存储快照冒充线上发送值。 */
internal val SessionDiagnosticsPlugin = createClientPlugin("SessionDiagnostics", ::SessionDiagnosticsConfig) {
    val write = pluginConfig.write
    val snapshot = pluginConfig.snapshot
    fun emit(message: String) {
        // 日志异常不得改变请求结果、会话状态或重试行为。
        runCatching { write("SESSION_DIAG v=1 $message") }
    }

    onRequest { request, _ ->
        request.attributes.put(traceKey, DiagnosticTrace(sequence.updateAndGet { it + 1 }, TimeSource.Monotonic.markNow()))
    }

    val sending = client.monitor.subscribe(HttpRequestIsReadyForSending) { request ->
        val trace = request.attributes.getOrNull(traceKey)
        emit("event=request id=${trace?.id} method=${request.method.value} endpoint=${SessionDiagnostics.endpoint(request.url.build())} " +
            "wire=[${SessionDiagnostics.wireCookies(request.headers.getAll(HttpHeaders.Cookie).orEmpty())}] " +
            "store=[${SessionDiagnostics.snapshot(snapshot())}]")
    }
    val received = client.monitor.subscribe(HttpResponseReceived) { response ->
        val trace = response.call.request.attributes.getOrNull(traceKey)
        val contentType = response.headers[HttpHeaders.ContentType]?.substringBefore(';')
        val kind = when (contentType) {
            "application/json", "text/html", "text/plain", "application/octet-stream" -> contentType
            null -> "missing"
            else -> "other"
        }
        val server = response.headers[HttpHeaders.Server]?.lowercase()?.let {
            when {
                it.startsWith("openresty") -> "openresty"
                it.startsWith("nginx") -> "nginx"
                else -> "other"
            }
        } ?: "missing"
        emit("event=response id=${trace?.id} endpoint=${SessionDiagnostics.endpoint(response.call.request.url)} " +
            "status=${response.status.value} elapsedMs=${trace?.started?.elapsedNow()?.inWholeMilliseconds} " +
            "contentType=$kind declaredBytes=${response.headers[HttpHeaders.ContentLength]?.toLongOrNull()} server=$server " +
            "setCookies=[${SessionDiagnostics.responseCookies(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())}]")
    }
    val failed = client.monitor.subscribe(HttpResponseReceiveFailed) { failure ->
        val request = failure.response.call.request
        val trace = request.attributes.getOrNull(traceKey)
        emit("event=receive_failure id=${trace?.id} endpoint=${SessionDiagnostics.endpoint(request.url)} " +
            "elapsedMs=${trace?.started?.elapsedNow()?.inWholeMilliseconds} ${SessionDiagnostics.failure(failure.cause)}")
    }
    on(Send) { request ->
        try {
            proceed(request)
        } catch (error: Exception) {
            val trace = request.attributes.getOrNull(traceKey)
            val event = if (error is CancellationException) "cancelled" else "send_failure"
            emit("event=$event id=${trace?.id} endpoint=${SessionDiagnostics.endpoint(request.url.build())} " +
                "elapsedMs=${trace?.started?.elapsedNow()?.inWholeMilliseconds} ${SessionDiagnostics.failure(error)}")
            throw error
        }
    }
    onClose {
        sending.dispose()
        received.dispose()
        failed.dispose()
    }
}
