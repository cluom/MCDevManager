package com.lemon.mcdevmanagermp.data.api

import com.lemon.mcdevmanagermp.utils.SessionCookieStore
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
        store.addCookie(cookie)
        persist()
    }

    override suspend fun get(requestUrl: Url): List<Cookie> =
        if (isTrusted(requestUrl)) store.getAllCookiesMap().map { Cookie(it.key, it.value) }
        else emptyList()

    override fun close() = Unit
}
