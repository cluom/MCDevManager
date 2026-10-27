package com.lemon.mcdevmanagermp.domain.update

import com.lemon.mcdevmanagermp.utils.VersionComparator
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only this fork's published packages can be handed off for an in-place update. */
object AltStoreUpdate {
    const val SOURCE_URL = "https://raw.githubusercontent.com/cluom/MCDevManager/altstore-source/source.json"
    const val RELEASES_URL = "https://github.com/cluom/MCDevManager/releases"
    const val BUNDLE_ID = "com.lemon.mcdevmanagermp.MCDevManagerMPR"
    private const val DOWNLOAD_PREFIX = "/cluom/MCDevManager/releases/download/"
    private val releasePart = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val json = Json { ignoreUnknownKeys = true }
    private const val NUMBER = "(?:0|[1-9][0-9]{0,8})"
    private val baseVersion = Regex("$NUMBER\\.$NUMBER\\.$NUMBER")
    private val displayVersion = Regex("$NUMBER\\.$NUMBER\\.$NUMBER(?:\\.[1-9][0-9]{0,8})?")

    fun select(source: String, currentVersion: String, requiredVersion: String): Package? {
        val required = requiredVersion.removePrefix("v")
        require(displayVersion.matches(currentVersion) && displayVersion.matches(required)) {
            "无法识别当前或目标版本，请查看发布页面。"
        }
        val app = json.decodeFromString<Source>(source).apps.singleOrNull { it.bundleIdentifier == BUNDLE_ID }
            ?: error("软件源中没有唯一匹配的 MCDevManager，请查看发布页面。")
        var selected: Package? = null
        for (entry in app.versions) {
            val display = entry.marketingVersion ?: entry.version
            if (!baseVersion.matches(entry.version) || !displayVersion.matches(display)) continue
            if (display.split('.').take(3).joinToString(".") != entry.version) continue
            if (!VersionComparator.needsUpdate(currentVersion, display)) continue
            if (VersionComparator.compare(display, required) > 0) continue
            if (!isAllowedDownload(entry.downloadURL)) continue
            if (selected == null || VersionComparator.needsUpdate(selected.displayVersion, display)) {
                selected = Package(display, entry.downloadURL)
            }
        }
        return selected
    }

    fun isAllowedDownload(value: String): Boolean = runCatching {
        val url = Url(value)
        // No arbitrary hosts, credentials, redirect parameters or path traversal.
        val assetPath = url.encodedPath.removePrefix(DOWNLOAD_PREFIX).split('/')
        url.protocol == URLProtocol.HTTPS && url.host == "github.com" && url.port == 443 &&
            url.user == null && url.password == null && url.fragment.isEmpty() &&
            url.parameters.isEmpty() && url.encodedPath.startsWith(DOWNLOAD_PREFIX) &&
            assetPath.size == 2 && assetPath.all { releasePart.matches(it) } &&
            assetPath.last().endsWith(".ipa")
    }.getOrDefault(false)

    fun installUrls(downloadUrl: String): List<String> {
        require(isAllowedDownload(downloadUrl)) { "更新包地址不属于可信的定制发布。" }
        val encoded = downloadUrl.encodeURLParameter()
        return listOf("altstore-classic://install?url=$encoded", "altstore://install?url=$encoded")
    }

    data class Package(val displayVersion: String, val downloadUrl: String)

    @Serializable
    private data class Source(val apps: List<App>)

    @Serializable
    private data class App(val bundleIdentifier: String, val versions: List<Version> = emptyList())

    @Serializable
    private data class Version(val version: String, val downloadURL: String, val marketingVersion: String? = null)
}
