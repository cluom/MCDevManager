package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.utils.CookiesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** iOS 启动时注册一次。Swift 负责共享钥匙串；不复制账号密码、不回写 Cookie。 */
object WidgetSessionObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun start(onChange: (String, String) -> Unit) {
        job?.cancel()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            CookiesStore.widgetSessions().collect { snapshot ->
                // 启动时内存尚未自动登录，不要把已保存的有效共享会话当成退出登录。
                if (snapshot.accountId == null && snapshot.generation == 0L) return@collect
                if (snapshot.accountId == null || snapshot.cookies.isEmpty()) {
                    onChange("", "")
                } else {
                    onChange(snapshot.accountId.toString(), Json.encodeToString(snapshot.cookies))
                }
            }
        }
    }
}
