package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.api.SessionCookieStorage
import com.lemon.mcdevmanagermp.utils.SessionCookieStore
import io.ktor.http.Cookie
import io.ktor.http.Url
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
        storage.addCookie(url, Cookie("NTES_SESS", "new=="))
        storage.addCookie(url, Cookie("NTES_SESS", "deleted", maxAge = 0))
        assertEquals(listOf(mapOf("NTES_SESS" to "new=="), emptyMap()), saved)
        assertNull(store.getCookie("NTES_SESS"))
    }
}
