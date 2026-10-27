package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.domain.update.AltStoreUpdate
import io.ktor.http.Url
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AltStoreUpdateTest {
    private val url = "https://github.com/cluom/MCDevManager/releases/download/altstore-ios-123/MCDevManager-1.2.10.1-11.ipa"

    private fun entry(version: String, display: String? = null, downloadUrl: String = url) = buildJsonObject {
        put("version", version)
        display?.let { put("marketingVersion", it) }
        put("downloadURL", downloadUrl)
        put("buildVersion", "11")
        put("size", 123)
    }

    private fun source(
        vararg versions: kotlinx.serialization.json.JsonObject,
        bundleId: String = AltStoreUpdate.BUNDLE_ID
    ): String = buildJsonObject {
        put("apps", buildJsonArray {
            add(buildJsonObject {
                put("bundleIdentifier", bundleId)
                put("versions", buildJsonArray { versions.forEach { add(it) } })
            })
        })
    }.toString()

    @Test
    fun findsNewUpstreamBaseInOwnSourceRegardlessOfOrder() {
        val selected = AltStoreUpdate.select(source(
            entry("1.2.9", "1.2.9.999"), entry("1.2.10", "1.2.10.1"), entry("1.2.7")
        ), "1.2.9.1", "v1.2.10")
        assertEquals("1.2.10.1", assertNotNull(selected).displayVersion)
        assertEquals(url, selected.downloadUrl)
    }

    @Test
    fun neverHandsOffOldCustomPackageWhenUpstreamIsNewer() {
        assertNull(AltStoreUpdate.select(source(entry("1.2.9", "1.2.9.999")), "1.2.9.1", "1.2.10"))
    }

    @Test
    fun willNotDowngradeOrReinstallCurrentVersion() {
        for (display in listOf("1.2.9", "1.2.9.1")) {
            assertNull(AltStoreUpdate.select(source(entry("1.2.9", display)), "1.2.9.1", "1.2.9"))
        }
    }

    @Test
    fun rejectsOtherAppsAndMalformedSource() {
        for (value in listOf(source(entry("1.2.10"), bundleId = "com.other.app"), "not json", "{}")) {
            assertFailsWith<Exception> { AltStoreUpdate.select(value, "1.2.9.1", "1.2.10") }
        }
    }

    @Test
    fun rejectsUntrustedDownloads() {
        val invalid = listOf(
            url.replace("https:", "http:"), url.replace("github.com", "evil.example"),
            url.replace("/cluom/", "/BitterLemonn/"), url.replace("github.com", "github.com.evil.example"),
            url.replace("github.com", "user:password@github.com"), "$url#fragment", "$url?redirect=elsewhere",
            url.replace("altstore-ios-123", ".."), url.replace(".ipa", ".zip"),
            url.replace("altstore-ios-123", "%2e%2e"), url.replace("altstore-ios-123", "tag%2fother"),
            url.replace("github.com", "github.com:8443")
        )
        for (value in invalid) {
            assertFalse(AltStoreUpdate.isAllowedDownload(value), value)
            assertNull(AltStoreUpdate.select(source(entry("1.2.10", downloadUrl = value)), "1.2.9.1", "1.2.10"))
            assertFailsWith<IllegalArgumentException> { AltStoreUpdate.installUrls(value) }
        }
    }

    @Test
    fun rejectsForgedDisplayVersions() {
        for (display in listOf("2.0.0", "1.2.10.0", "1.2.10.1.1", "1.2.10-beta", "999999999999999.0.0")) {
            assertNull(AltStoreUpdate.select(source(entry("1.2.10", display)), "1.2.9.1", "1.2.10"))
        }
    }

    @Test
    fun sortsRevisionsNumericallyAndSupportsOlderSourceWithoutMarketingVersion() {
        val selected = AltStoreUpdate.select(source(
            entry("1.2.10", "1.2.10.2"), entry("1.2.10", "1.2.10.10"), entry("1.2.10")
        ), "1.2.9.1", "1.2.10")
        assertEquals("1.2.10.10", assertNotNull(selected).displayVersion)
        assertEquals("1.2.10", AltStoreUpdate.select(source(entry("1.2.10")), "1.2.9.1", "1.2.10")?.displayVersion)
    }

    @Test
    fun installSchemeKeepsCompleteDownloadUrlInOneEncodedParameter() {
        val links = AltStoreUpdate.installUrls(url)
        assertTrue(links[0].startsWith("altstore-classic://install?url=https%3A%2F%2F"))
        assertTrue(links[1].startsWith("altstore://install?url="))
        for (link in links) {
            val parsed = Url(link)
            assertEquals(url, parsed.parameters["url"])
            assertEquals(setOf("url"), parsed.parameters.names())
        }
    }

    @Test
    fun duplicateAppDefinitionsAreNotSilentlyTrusted() {
        val duplicated = buildJsonObject {
            put("apps", buildJsonArray {
                repeat(2) {
                    add(buildJsonObject {
                        put("bundleIdentifier", AltStoreUpdate.BUNDLE_ID)
                        put("versions", buildJsonArray { add(entry("1.2.10")) })
                    })
                }
            })
        }.toString()
        assertFailsWith<Exception> { AltStoreUpdate.select(duplicated, "1.2.9.1", "1.2.10") }
    }
}
