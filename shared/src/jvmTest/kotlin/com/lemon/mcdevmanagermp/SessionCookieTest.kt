package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.api.SessionCookieStorage
import com.lemon.mcdevmanagermp.utils.SessionCookieStore
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.http.encodeCookieValue
import io.ktor.http.parseClientCookiesHeader
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.http.renderCookieHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCookieTest {
    @Test
    fun savesOnlyChangedCookiesForBoundAccount() = runBlocking {
        val store = SessionCookieStore()
        store.addCookie("NTES_SESS", "old")
        store.bindAccount(7, store.getAllCookiesMap())
        val saved = mutableListOf<Pair<Long, Map<String, String>>>()
        val save: suspend (Long, Map<String, String>) -> Unit = { id, cookies -> saved.add(id to cookies) }
        store.persistChanges(save)
        store.addCookie("NTES_SESS", "new==")
        store.persistChanges(save)
        store.persistChanges(save)
        assertEquals(listOf(7L to mapOf("NTES_SESS" to "new==")), saved)
    }

    @Test
    fun failedSaveIsRetriedAndNotAcknowledged() = runBlocking {
        val store = SessionCookieStore()
        store.bindAccount(7, emptyMap())
        store.addCookie("NTES_SESS", "new")
        assertFailsWith<IllegalStateException> {
            store.persistChanges { _, _ -> error("fake disk failure") }
        }
        var saves = 0
        store.persistChanges { _, _ -> saves++ }
        assertEquals(1, saves)
    }

    @Test
    fun loginAndLogoutWithoutBoundAccountNeverSaveOverPreviousAccount() = runBlocking {
        val store = SessionCookieStore()
        store.bindAccount(7, emptyMap())
        store.clearCookies()
        store.addCookie("NTES_SESS", "different-account")
        store.persistChanges { _, _ -> error("must not overwrite previous account") }
    }

    @Test
    fun accountSwitchDuringSaveKeepsNewAccountDirty() = runBlocking {
        val store = SessionCookieStore()
        store.bindAccount(7, emptyMap())
        store.addCookie("NTES_SESS", "account-a")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val saving = launch {
            store.persistChanges { id, cookies ->
                assertEquals(7L, id)
                assertEquals("account-a", cookies["NTES_SESS"])
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        store.clearCookies()
        store.bindAccount(8, emptyMap())
        store.addCookie("NTES_SESS", "account-b")
        finish.complete(Unit)
        saving.join()
        var savedId: Long? = null
        store.persistChanges { id, cookies ->
            savedId = id
            assertEquals("account-b", cookies["NTES_SESS"])
        }
        assertEquals(8L, savedId)
    }

    @Test
    fun updateDuringSaveIsFlushedOnNextCall() = runBlocking {
        val store = SessionCookieStore()
        store.bindAccount(7, emptyMap())
        store.addCookie("NTES_SESS", "first")
        store.persistChanges { _, _ -> store.addCookie("NTES_SESS", "second") }
        var value: String? = null
        store.persistChanges { _, cookies -> value = cookies["NTES_SESS"] }
        assertEquals("second", value)
    }

    @Test
    fun cookiesAreNotSentToGithubOrArbitraryDownloads() = runBlocking {
        val store = SessionCookieStore()
        store.addCookie("NTES_SESS", "example==")
        val storage = SessionCookieStorage(store) {}
        assertTrue(storage.get(Url("https://api.github.com/releases")).isEmpty())
        assertTrue(storage.get(Url("https://mc-launcher.webapp.163.com.evil.example/")).isEmpty())
        assertTrue(storage.get(Url("http://mc-launcher.webapp.163.com/")).isEmpty())
        assertEquals("example==", storage.get(Url("https://mc-launcher.webapp.163.com/")).single().value)
        storage.addCookie(Url("https://example.com/"), Cookie("NTES_SESS", "foreign"))
        assertEquals("example==", store.getCookie("NTES_SESS"))
    }

    @Test
    fun storagePersistsServerUpdatesAndDeletions() = runBlocking {
        val store = SessionCookieStore()
        store.bindAccount(7, emptyMap())
        val saved = mutableListOf<Map<String, String>>()
        val storage = SessionCookieStorage(store) {
            store.persistChanges { _, cookies -> saved.add(cookies) }
        }
        val url = Url("https://mc-launcher.webapp.163.com/")
        storage.addCookie(url, Cookie("NTES_SESS", "new==", encoding = CookieEncoding.RAW))
        storage.addCookie(url, Cookie("NTES_SESS", "deleted", maxAge = 0))
        assertEquals(listOf(mapOf("NTES_SESS" to "new=="), emptyMap()), saved)
        assertNull(store.getCookie("NTES_SESS"))
    }

    @Test
    fun rawCookieValuesSurviveThreeHundredCaptureAndSaveCycles() = runBlocking {
        val expected = linkedMapOf(
            "S_INFO" to "fake|session|value",
            "P_INFO" to "literal%257C|+/%3D==",
            "NTES_SESS" to "fake=="
        )
        val store = SessionCookieStore()
        expected.forEach { (name, value) -> store.addCookie(name, value) }
        store.bindAccount(7, expected)
        var saves = 0
        val storage = SessionCookieStorage(store) { store.persistChanges { _, _ -> saves++ } }
        val url = Url("https://mc-launcher.webapp.163.com/")
        val expectedHeader = expected.entries.joinToString("; ") { "${it.key}=${it.value}" }
        repeat(300) {
            val cookies = storage.get(url)
            assertTrue(cookies.all { it.encoding == CookieEncoding.RAW })
            val header = cookies.joinToString("; ", transform = ::renderCookieHeader)
            assertEquals(expectedHeader, header)
            // 模拟 Ktor 捕获已发送的请求头，以及服务端返回相同 Set-Cookie。
            parseClientCookiesHeader(header).forEach { (name, value) ->
                storage.addCookie(url, Cookie(name, value, encoding = CookieEncoding.RAW))
                storage.addCookie(url, parseServerSetCookieHeader("$name=$value; Path=/"))
            }
            assertEquals(expected, store.getAllCookiesMap())
        }
        assertEquals(0, saves, "未变化的 Cookie 不应因转义反复写入数据库")
    }

    @Test
    fun explicitlyEncodedCookiesAreConvertedToWireFormatExactlyOnce() = runBlocking {
        val store = SessionCookieStore()
        val storage = SessionCookieStorage(store) {}
        val url = Url("https://mc-launcher.webapp.163.com/")
        for (encoding in listOf(CookieEncoding.URI_ENCODING, CookieEncoding.BASE64_ENCODING)) {
            val logicalValue = "fake|value+/%7C=="
            val wireValue = encodeCookieValue(logicalValue, encoding)
            storage.addCookie(url, Cookie("S_INFO", logicalValue, encoding = encoding))
            assertEquals(wireValue, store.getCookie("S_INFO"))
            assertEquals("S_INFO=$wireValue", renderCookieHeader(storage.get(url).single()))
            storage.addCookie(url, Cookie("S_INFO", wireValue, encoding = CookieEncoding.RAW))
            assertEquals(wireValue, store.getCookie("S_INFO"))
        }
    }

    @Test
    fun legacyNestedEncodingIsPreservedUntilExplicitRecovery() = runBlocking {
        val polluted = "fake%" + "25".repeat(267) + "7Cvalue"
        val store = SessionCookieStore()
        store.addCookie("S_INFO", polluted)
        val storage = SessionCookieStorage(store) {}
        val cookie = storage.get(Url("https://mc-launcher.webapp.163.com/")).single()
        assertEquals("S_INFO=$polluted", renderCookieHeader(cookie))
        assertEquals(polluted, store.getCookie("S_INFO"))
    }
}
