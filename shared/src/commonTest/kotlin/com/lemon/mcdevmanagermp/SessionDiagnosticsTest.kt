package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.utils.SessionDiagnostics
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDiagnosticsTest {
    @Test
    fun detectsDeepEncodingWithoutReturningTheValue() {
        assertEquals(0, SessionDiagnostics.encodingDepth("fake|plain|value"))
        assertEquals(1, SessionDiagnostics.encodingDepth("fake%7Cvalue"))
        assertEquals(2, SessionDiagnostics.encodingDepth("fake%257cvalue"))
        assertEquals(268, SessionDiagnostics.encodingDepth("fake%" + "25".repeat(267) + "7Cvalue"))
        assertEquals(1, SessionDiagnostics.encodingDepth("%25"))
        assertEquals(0, SessionDiagnostics.encodingDepth("invalid%XZ%"))
    }

    @Test
    fun cookieSummaryContainsStructureButNoCredential() {
        val secret = "FAKE_SECRET%257C=="
        val result = SessionDiagnostics.cookie("S_INFO", secret)
        assertTrue(result.contains("eq=2,tailEq=2"))
        assertTrue(result.contains("depth=2"))
        assertTrue(result.contains("suspicious=true"))
        assertFalse(result.contains("FAKE_SECRET"))
        assertEquals("S_INFO{present=false}", SessionDiagnostics.cookie("S_INFO", null))
    }

    @Test
    fun unknownCookieNamesAndControlCharactersCannotInjectLogs() {
        val result = SessionDiagnostics.cookie("secret-name\nCookie: secret", "abc\nsecret")
        assertTrue(result.startsWith("other{"))
        assertTrue(result.contains("control=true"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains('\n'))
    }

    @Test
    fun measuresActualHeaderBytesAndPreservesDuplicateCounts() {
        val headers = listOf("S_INFO=a%257Cb; S_INFO=x", "NTES_SESS=fake==")
        val summary = SessionDiagnostics.wireCookies(headers)
        assertTrue(summary.contains("headerBytes=${headers.sumOf { it.encodeToByteArray().size }} count=3"))
        assertTrue(summary.contains("depth=2"))
        assertFalse(summary.contains("fake"))
        assertFalse(summary.contains("a%257Cb"))
    }

    @Test
    fun responseSummaryDoesNotLogAttributesOrMalformedHeaders() {
        val summary = SessionDiagnostics.responseCookies(listOf("S_INFO=secret==; Domain=private.example; Path=/secret", "invalid-secret"))
        assertTrue(summary.contains("count=2"))
        assertTrue(summary.contains("malformed"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("private.example"))
    }

    @Test
    fun endpointDropsQueryCredentialsFragmentsAndDynamicPath() {
        assertEquals("mc-launcher.webapp.163.com/items/categories/pe/:value/incomes/", SessionDiagnostics.endpoint(
            Url("https://user:password@mc-launcher.webapp.163.com/items/categories/pe/123456/incomes/?token=secret#secret")
        ))
        assertEquals("external-host/:redacted", SessionDiagnostics.endpoint(Url("https://private.example/secret?token=secret")))
    }

    @Test
    fun exceptionSummaryKeepsOnlyTypesAndNumericMismatchDetails() {
        val error = IllegalStateException("Content-Length mismatch: expected 558 bytes, but received 0 bytes secret-token")
        val result = SessionDiagnostics.failure(RuntimeException("Authorization: secret", error))
        assertTrue(result.contains("RuntimeException->IllegalStateException"))
        assertTrue(result.contains("expectedBytes=558 receivedBytes=0"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("Authorization"))
    }

    @Test
    fun snapshotsCountUtf8BytesAndBoundTheNumberOfDetails() {
        val snapshot = SessionDiagnostics.snapshot(mapOf("S_INFO" to "é", "NTES_SESS" to "=="))
        assertTrue(snapshot.contains("pairBytes=23"))
        assertTrue(SessionDiagnostics.snapshot((1..20).associate { "unknown$it" to "secret" }).contains("omitted=4"))
        assertTrue(SessionDiagnostics.snapshot(emptyMap()).contains("pairBytes=0"))
    }
}
