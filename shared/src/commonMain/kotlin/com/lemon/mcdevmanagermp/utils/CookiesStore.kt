package com.lemon.mcdevmanagermp.utils

import io.ktor.http.Cookie
import io.ktor.http.encodeCookieValue
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** 使用不可变快照；字符串值统一保存为可直接发送的 HTTP Cookie 原始值。 */
open class SessionCookieStore {
    private data class State(
        val cookies: Map<String, String> = emptyMap(),
        val accountId: Long? = null,
        val generation: Long = 0,
        val persisted: Map<String, String> = emptyMap()
    )

    private val state = MutableStateFlow(State())
    private val persistenceMutex = Mutex()

    fun addCookies(list: List<String>) {
        list.forEach { header ->
            val pair = header.substringBefore(';')
            if (pair.substringBefore('=').isBlank() || pair.indexOf('=') < 1) return@forEach
            // 使用 HTTP 库解析，只按第一个等号分隔，并处理服务端删除指令。
            val cookie = runCatching { parseServerSetCookieHeader(header) }.getOrNull()
                ?: return@forEach
            addCookie(cookie)
        }
    }

    fun addCookie(cookie: Cookie) {
        val expired = cookie.maxAge?.let { it <= 0 }
            ?: (cookie.expires?.timestamp?.let { it <= Clock.System.now().toEpochMilliseconds() } ?: false)
        if (cookie.value.isEmpty() || expired) removeCookie(cookie.name)
        // 通常 Set-Cookie/请求头捕获都是 RAW；显式编码的 Cookie 先转成线格式，
        // 否则丢掉 encoding 元数据后会改变它的含义。已有百分号转义不做递归解码。
        else addCookie(cookie.name, encodeCookieValue(cookie.value, cookie.encoding))
    }

    fun removeCookie(key: String) {
        state.update { it.copy(cookies = it.cookies - key) }
    }

    fun addCookie(key: String, value: String) {
        if (key.isBlank()) return
        if (value.isEmpty()) removeCookie(key)
        else state.update { it.copy(cookies = it.cookies + (key to value)) }
    }

    fun getCookie(key: String): String? {
        return state.value.cookies[key]
    }

    fun getAllCookiesString(): String {
        return state.value.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getAllCookiesMap(): Map<String, String>{
        return state.value.cookies.toMap()
    }

    fun clearCookies() {
        Logger.d("SESSION_DIAG v=1 event=session_clear previousCount=${state.value.cookies.size}")
        state.update { State(generation = it.generation + 1) }
    }

    /** 绑定已保存账号，禁止用“最后使用账号”猜测新 Cookie 属于谁。 */
    fun bindAccount(accountId: Long, persistedCookies: Map<String, String>) {
        state.update { it.copy(accountId = accountId, persisted = persistedCookies.toMap()) }
        Logger.d("SESSION_DIAG v=1 event=session_bind account=$accountId ${SessionDiagnostics.snapshot(state.value.cookies)}")
    }

    suspend fun persistChanges(save: suspend (Long, Map<String, String>) -> Unit) {
        persistenceMutex.withLock {
            val snapshot = state.value
            val accountId = snapshot.accountId ?: return
            if (snapshot.cookies == snapshot.persisted) return
            save(accountId, snapshot.cookies)
            state.update {
                if (it.generation == snapshot.generation && it.accountId == accountId) {
                    it.copy(persisted = snapshot.cookies)
                } else it
            }
        }
    }
}

object CookiesStore : SessionCookieStore()
