package com.lemon.mcdevmanagermp.data.api

import com.lemon.mcdevmanagermp.data.common.JSONConverter
import com.lemon.mcdevmanagermp.data.consts.NETEASE_MC_DEV_LINK
import com.lemon.mcdevmanagermp.data.dto.netease.feedback.ReplyDTO
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyOutcome
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplySender
import com.lemon.mcdevmanagermp.domain.lottery.GiftReplyStatus
import com.lemon.mcdevmanagermp.utils.SessionCookieStore
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

/** 独立 Cookie 快照，不会因全局账号切换而改用另一账号发送。 */
class GiftCommentReplyClient(cookies: Map<String, String>) : GiftReplySender {
    private val session = SessionCookieStore().apply { cookies.forEach { (key, value) -> addCookie(key, value) } }
    private val client = HttpClient {
        expectSuccess = true
        followRedirects = false
        defaultRequest { contentType(ContentType.Application.Json) }
        install(ContentNegotiation) { json(JSONConverter) }
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
        install(HttpCookies) { storage = SessionCookieStorage(session) {} }
    }
    private val api = Ktorfit.Builder().baseUrl(NETEASE_MC_DEV_LINK).httpClient(client).build().createCommentApi()
    override suspend fun send(commentId: String, content: String): GiftReplyOutcome {
        val response = api.replyComment(commentId, ReplyDTO(content))
        return if (response.status == "ok" || response.status == "success") GiftReplyOutcome(GiftReplyStatus.SUCCESS)
        else GiftReplyOutcome(GiftReplyStatus.REJECTED,
            if (response.status == "no_login") "登录已失效，回复已停止" else "平台拒绝回复：${response.errorMessage().take(160)}")
    }
    override fun close() = client.close()
}
