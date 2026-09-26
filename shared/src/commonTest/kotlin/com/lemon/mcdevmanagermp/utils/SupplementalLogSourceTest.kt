package com.lemon.mcdevmanagermp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupplementalLogSourceTest {
    @Test
    fun includesWidgetLinesAndReadsFreshSnapshotEachTime() {
        var snapshot = "[DEBUG] WIDGET_DIAG event=reserveSkipped"
        val source = SupplementalLogSource({ snapshot }, {})
        assertTrue(source.lines().last().contains("reserveSkipped"))
        snapshot = "[DEBUG] WIDGET_DIAG event=completionDropped"
        assertTrue(source.lines().last().contains("completionDropped"))
        assertFalse(source.lines().any { it.contains("reserveSkipped") })
    }

    @Test
    fun emptyProviderKeepsOtherPlatformsUnchanged() {
        assertTrue(SupplementalLogSource({ "" }, {}).lines().isEmpty())
    }

    @Test
    fun providerFailureDoesNotLeakExceptionOrBreakExport() {
        val source = SupplementalLogSource({ error("SECRET_COOKIE") }, { error("SECRET_COOKIE") })
        assertEquals(listOf("[WARN] WIDGET_DIAG export_failed"), source.lines())
        source.clearSafely()
    }

    @Test
    fun nativeCleanupIsCalledAndExportIsBounded() {
        var cleared = false
        val source = SupplementalLogSource({ "x\n".repeat(500_000) }, { cleared = true })
        assertTrue(source.lines().size <= 515)
        source.clearSafely()
        assertTrue(cleared)
    }
}
