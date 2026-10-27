package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.utils.VersionComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionComparatorTest {
    @Test
    fun customRevisionsStayBetweenTheirUpstreamBaseAndNextUpstreamRelease() {
        assertTrue(VersionComparator.needsUpdate("1.2.9", "1.2.9.1"))
        assertTrue(VersionComparator.needsUpdate("1.2.9.1", "v1.2.10"))
        assertTrue(VersionComparator.needsUpdate("1.2.9.999", "1.2.10"))
        assertFalse(VersionComparator.needsUpdate("1.2.9.1", "v1.2.9"))
        assertFalse(VersionComparator.needsUpdate("1.2.10", "1.2.9.999"))
        assertEquals(0, VersionComparator.compare("1.2.9", "1.2.9.0"))
    }

    @Test
    fun revisionOrderingIsNumeric() {
        assertTrue(VersionComparator.needsUpdate("1.2.9.2", "1.2.9.10"))
        assertFalse(VersionComparator.needsUpdate("1.2.9.10", "1.2.9.2"))
    }
}
