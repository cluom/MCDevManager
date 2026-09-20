package com.lemon.mcdevmanagermp.utils

import io.ktor.client.plugins.ResponseException
import io.ktor.http.Url
import io.ktor.http.encodedPath

/** 只生成结构摘要，不返回凭据、URL 参数、响应正文或稳定的凭据指纹。 */
internal object SessionDiagnostics {
    private val cookieNames = setOf("S_INFO", "P_INFO", "NTES_SESS", "mcdev_cookie_id", "l_s_x19_developerkBSLIYY")
    private val hosts = setOf(
        "mc-launcher.webapp.163.com", "mcdev.webapp.163.com", "dl.reg.163.com",
        "fp.ps.netease.com", "api.github.com", "127.0.0.1", "localhost"
    )
    private val routeParts = setOf(
        "items", "categories", "pe", "pc", "comp", "incomes", "lobby_incomes", "comment", "feedback",
        "mailbox", "unread", "count", "users", "me", "data_analysis", "overview", "new_level",
        "day_detail", "month_detail", "goods", "summary", "activities", "modules", "candidates",
        "discount_activities", "current", "join", "cancel_join", "update", "upload", "apply_review",
        "cancel_review", "self-test-apply", "cancel_self_test", "online", "appoint_online", "change_price",
        "requirements", "item-tag", "mc_consts", "reply", "read_mail", "delete_many", "apply",
        "incentive_fund", "detail", "developer", "add_feedback", "filepicker", "file_token",
        "promotion-banner", "can_apply", "user-applications", "modify", "others", "red-spots",
        "square", "us_rank_list", "rank_list", "repos", "releases", "latest", "dl", "zj", "mail",
        "ini", "powGetP", "gt", "l"
    )

    fun endpoint(url: Url): String {
        val host = if (url.host in hosts) url.host else "external-host"
        val path = if (url.host in hosts) url.encodedPath.split('/').take(12).joinToString("/") {
            if (it.isEmpty() || it in routeParts) it else ":value"
        } else "/:redacted"
        return "$host$path"
    }

    /** 线性扫描 %25 链，不实际解码或修改 Cookie；268 层等异常不会触发反复整串复制。 */
    fun encodingDepth(value: String): Int {
        var maximum = 0
        var index = 0
        fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        while (index < value.length) {
            if (value[index] != '%') {
                index++
                continue
            }
            var cursor = index + 1
            var depth = 0
            while (cursor + 1 < value.length && isHex(value[cursor]) && isHex(value[cursor + 1])) {
                depth++
                val nestedPercent = value[cursor] == '2' && value[cursor + 1] == '5'
                cursor += 2
                if (!nestedPercent) break
            }
            maximum = maxOf(maximum, depth)
            index = maxOf(index + 1, cursor)
        }
        return maximum
    }

    fun cookie(name: String, value: String?): String {
        val label = if (name in cookieNames) name else "other"
        if (value == null) return "$label{present=false}"
        val depth = encodingDepth(value)
        return "$label{len=${value.length},bytes=${value.encodeToByteArray().size}," +
            "eq=${value.count { it == '=' }},tailEq=${value.length - value.trimEnd('=').length}," +
            "pct=${value.count { it == '%' }},depth=$depth," +
            "control=${value.any { it.code < 32 || it.code == 127 }}," +
            "suspicious=${depth >= 2 || value.length >= 2048}}"
    }

    fun snapshot(cookies: Map<String, String>): String {
        val bytes = cookies.entries.sumOf { it.key.encodeToByteArray().size + 1 + it.value.encodeToByteArray().size } +
            (cookies.size - 1).coerceAtLeast(0) * 2
        return "count=${cookies.size} pairBytes=$bytes " +
            cookies.entries.take(16).joinToString(" ") { cookie(it.key, it.value) } +
            if (cookies.size > 16) " omitted=${cookies.size - 16}" else ""
    }

    fun wireCookies(headers: List<String>): String {
        val parts = headers.flatMap { it.split(';') }.filter { it.isNotBlank() }
        val summaries = parts.take(16).joinToString(" ") {
            val part = it.trim()
            val delimiter = part.indexOf('=')
            if (delimiter <= 0) "malformed" else cookie(part.substring(0, delimiter), part.substring(delimiter + 1))
        }
        return "headerBytes=${headers.sumOf { it.encodeToByteArray().size }} count=${parts.size} $summaries"
    }

    fun responseCookies(headers: List<String>): String = "count=${headers.size} " +
        headers.take(16).joinToString(" ") { header ->
            val pair = header.substringBefore(';')
            val delimiter = pair.indexOf('=')
            if (delimiter <= 0) "malformed" else cookie(pair.substring(0, delimiter).trim(), pair.substring(delimiter + 1))
        }

    fun failure(error: Throwable): String {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && causes.size < 5 && causes.none { it === current }) {
            causes.add(current)
            current = current.cause
        }
        val classes = causes.joinToString("->") { it::class.simpleName ?: "Throwable" }
        val status = causes.filterIsInstance<ResponseException>().firstOrNull()?.response?.status?.value
        val mismatch = causes.firstNotNullOfOrNull {
            Regex("Content-Length mismatch: expected (\\d+) bytes, but received (\\d+) bytes").find(it.message.orEmpty())
        }
        return "type=$classes" + (status?.let { " status=$it" } ?: "") +
            (mismatch?.let { " expectedBytes=${it.groupValues[1]} receivedBytes=${it.groupValues[2]}" } ?: "")
    }
}
