package com.lemon.mcdevmanagermp.data.repository

import com.lemon.mcdevmanagermp.data.common.AppContext
import com.lemon.mcdevmanagermp.data.common.JSONConverter
import com.lemon.mcdevmanagermp.utils.CookiesStore
import com.lemon.mcdevmanagermp.utils.Logger
import com.lemon.mcdevmanagermp.utils.SessionDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

internal object SessionCookiePersistence {
    suspend fun persist() {
        try {
            CookiesStore.persistChanges { accountId, cookies ->
                // 只 UPDATE，不 upsert，避免注销/删除后的迟到响应重新创建账号。
                AppContext.database.accountDao().updateCookiesById(
                    accountId, JSONConverter.encodeToString(cookies)
                )
                Logger.d("SESSION_DIAG v=1 event=cookie_persist account=$accountId ${SessionDiagnostics.snapshot(cookies)}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 保存失败不能把成功的网络请求误判成登录失效，下次请求继续尝试。
            Logger.w("会话 Cookie 保存失败：${e::class.simpleName}，稍后重试")
        }
    }
}
