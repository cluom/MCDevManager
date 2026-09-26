package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.utils.SessionCookieStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetSessionTest {
    @Test
    fun bridgeOnlyIdentifiesBoundAccountAndPreservesWireEncoding() = runBlocking {
        val store = SessionCookieStore()
        store.addCookie("S_INFO", "abc%2Bdef==")
        assertNull(store.widgetSessions().first().accountId)
        store.bindAccount(17, store.getAllCookiesMap())
        val bound = store.widgetSessions().first()
        assertEquals(17L, bound.accountId)
        assertEquals("abc%2Bdef==", bound.cookies["S_INFO"])
        store.clearCookies()
        val loggedOut = store.widgetSessions().first()
        assertNull(loggedOut.accountId)
        assertEquals(emptyMap(), loggedOut.cookies)
        assertEquals(bound.generation + 1, loggedOut.generation)
        store.addCookie("S_INFO", "second")
        store.bindAccount(18, store.getAllCookiesMap())
        assertEquals(18L, store.widgetSessions().first().accountId)
    }
}
