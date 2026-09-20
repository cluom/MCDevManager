package com.lemon.mcdevmanagermp.data.api

import com.lemon.mcdevmanagermp.data.common.JSONConverter
import com.lemon.mcdevmanagermp.data.consts.TRAILING_SLASH_MARKER
import com.lemon.mcdevmanagermp.data.repository.SessionCookiePersistence
import com.lemon.mcdevmanagermp.utils.CookiesStore
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json

object ApiFactory {
    private val cookiesStorage = SessionCookieStorage(CookiesStore, SessionCookiePersistence::persist)

    private val TrailingSlashPlugin = createClientPlugin("TrailingSlashPlugin") {
        onRequest { request, _ ->
            // 检查是否有我们自定义的 Header 标记
            if (request.headers[TRAILING_SLASH_MARKER] == "true") {
                request.headers.remove(TRAILING_SLASH_MARKER)

                val path = request.url.encodedPath
                if (!path.endsWith("/")) {
                    request.url.encodedPath = "$path/"
                }
            }
        }
    }


    private val jsonHttpClient: HttpClient by lazy {
        HttpClient {
            expectSuccess = true
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
            install(ContentNegotiation) { json(JSONConverter) }

            install(TrailingSlashPlugin)
            install(HttpTimeout) {
                connectTimeoutMillis = 60_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
            install(HttpCookies) {
                storage = cookiesStorage
            }
            installSessionDiagnostics()
        }
    }

    private val loggerHttpClient: HttpClient by lazy {
        HttpClient {
            expectSuccess = true
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
            install(ContentNegotiation) { json(JSONConverter) }
            install(HttpTimeout) {
                connectTimeoutMillis = 60_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 60_000
            }
            install(HttpCookies) {
                storage = cookiesStorage
            }
            installSessionDiagnostics()
        }
    }

    private val uploadHttpClient: HttpClient by lazy {
        HttpClient {
            expectSuccess = true
            install(ContentNegotiation) { json(JSONConverter) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000  // 上传文件需要更长超时
                socketTimeoutMillis = 60_000
            }
            install(HttpCookies) {
                storage = cookiesStorage
            }
            installSessionDiagnostics()
        }
    }

    private val downloadHttpClient: HttpClient by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000 // 连接超时还是要有的
                requestTimeoutMillis = Long.MAX_VALUE  // 请求时间无限，防止下载大文件中断
                socketTimeoutMillis = Long.MAX_VALUE
            }

            install(HttpCookies) {
                storage = cookiesStorage
            }
            installSessionDiagnostics()
        }
    }

    fun provideLoggerKtorfit(baseUrl: String): Ktorfit {
        return Ktorfit.Builder().baseUrl(baseUrl).httpClient(loggerHttpClient).build()
    }


    fun provideKtorfit(baseUrl: String): Ktorfit {
        return Ktorfit.Builder().baseUrl(baseUrl).httpClient(jsonHttpClient).build()
    }


    fun provideUploadHttpClient(): HttpClient = uploadHttpClient

    fun provideDownloadKtorfit(): Ktorfit {
        return Ktorfit.Builder().baseUrl("https://localhost/") // 占位符，实际会被 @Url 覆盖
            .httpClient(downloadHttpClient).build()
    }
}
