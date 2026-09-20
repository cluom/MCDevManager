package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.utils.CookiesStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CookieParsingTest {
    @AfterTest
    fun cleanup() = CookiesStore.clearCookies()

    @Test
    fun preservesEqualsInCookieValues() {
        CookiesStore.addCookies(listOf("NTES_SESS=example==; Path=/; HttpOnly"))
        assertEquals("example==", CookiesStore.getCookie("NTES_SESS"))
    }

    @Test
    fun ignoresMalformedHeaders() {
        CookiesStore.addCookies(listOf("", "invalid", "=no-name", "valid=ok; Path=/"))
        assertEquals(mapOf("valid" to "ok"), CookiesStore.getAllCookiesMap())
    }

    @Test
    fun expiredCookieDoesNotResurrectSession() {
        CookiesStore.addCookie("NTES_SESS", "old")
        CookiesStore.addCookies(listOf("NTES_SESS=deleted; Max-Age=0; Path=/"))
        assertNull(CookiesStore.getCookie("NTES_SESS"))
    }

    @Test
    fun emptyValueRemovesCookie() {
        CookiesStore.addCookie("NTES_SESS", "old")
        CookiesStore.addCookies(listOf("NTES_SESS=; Path=/"))
        assertNull(CookiesStore.getCookie("NTES_SESS"))
    }
}
