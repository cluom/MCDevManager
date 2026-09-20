package com.lemon.mcdevmanagermp.data.api

import com.lemon.mcdevmanagermp.utils.SessionCookieStore
import com.lemon.mcdevmanagermp.utils.Logger
import com.lemon.mcdevmanagermp.utils.SessionDiagnostics
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url

/** 会话仅用于已知的网易业务主机，不随 GitHub/任意下载地址发送。 */
internal class SessionCookieStorage(
    private val store: SessionCookieStore,
    private val persist: suspend () -> Unit
) : CookiesStorage {
    private fun isTrusted(url: Url): Boolean = url.protocol.name == "https" && url.host in setOf(
        "dl.reg.163.com", "mc-launcher.webapp.163.com", "mcdev.webapp.163.com", "fp.ps.netease.com"
    )

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (!isTrusted(requestUrl)) return
        val previous = store.getCookie(cookie.name)
        store.addCookie(cookie)
        val current = store.getCookie(cookie.name)
        if (previous != current) {
            // Ktor 也会捕获请求头，因此不能把此回调一概标成“服务端更新”。
            Logger.d("SESSION_DIAG v=1 event=cookie_update source=ktor_storage endpoint=${SessionDiagnostics.endpoint(requestUrl)} " +
                "incomingEncoding=${cookie.encoding} before=[${SessionDiagnostics.cookie(cookie.name, previous)}] " +
                "after=[${SessionDiagnostics.cookie(cookie.name, current)}]")
        }
        persist()
    }

    override suspend fun get(requestUrl: Url): List<Cookie> =
        if (isTrusted(requestUrl)) store.getAllCookiesMap().map { Cookie(it.key, it.value) }
        else emptyList()

    override fun close() = Unit
}
