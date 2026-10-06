package com.lemon.mcdevmanagermp

import com.lemon.mcdevmanagermp.data.common.AppContext
import com.lemon.mcdevmanagermp.data.db.entity.AccountEntity
import com.lemon.mcdevmanagermp.utils.CookiesStore
import com.lemon.mcdevmanagermp.utils.Logger
import com.lemon.mcdevmanagermp.utils.WidgetSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** Windows 专用安全桥接：只把会话经标准输入交给 DPAPI 导入工具，不落明文临时文件。 */
object WindowsWidgetSessionObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started || !System.getProperty("os.name", "").startsWith("Windows", true)) return
        started = true
        scope.launch {
            val builder = WindowsWidgetSnapshotBuilder()
            val pending = MutableStateFlow<String?>(null)
            launch {
                // 导入失败或用户随后安装组件时重试；不轮询网易网络接口。
                pending.filterNotNull().collectLatest { snapshot ->
                    // 唯一导入消费者；旧导入结束后才处理新快照，防止注销被旧快照覆盖。
                    while (!import(snapshot)) delay(10_000)
                }
            }
            AppContext.database.accountDao().observeAllAccounts()
                .combine(CookiesStore.widgetSessions()) { accounts, session ->
                    Json.encodeToString(builder.build(accounts, session))
                }.collect { snapshot ->
                    pending.value = snapshot
                }
        }
    }

    private fun import(snapshot: String): Boolean {
        val helper = findHelper() ?: return false
        return try {
            val process = ProcessBuilder(helper.absolutePath, "--import-sessions")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val ok = finished && process.exitValue() == 0
            Logger.d("WINDOWS_WIDGET_DIAG event=session_sync success=$ok")
            ok
        } catch (e: Exception) {
            Logger.w("WINDOWS_WIDGET_DIAG event=session_sync_failed type=${e.javaClass.simpleName}")
            false
        }
    }

    private fun findHelper(): File? {
        val override = System.getProperty("mcdev.widgets.bridge")?.let(::File)
        if (override?.isFile == true) return override
        // MSIX 注册的应用执行别名，不依赖主程序的安装位置或附带另一份 .NET runtime。
        val local = System.getenv("LOCALAPPDATA") ?: return null
        return File(local, "Microsoft/WindowsApps/MCDevManagerWidgetBridge.exe").takeIf { it.exists() }
    }
}

@Serializable
internal data class WindowsSharedAccount(val id: String, val name: String, val cookies: Map<String, String>)

@Serializable
internal data class WindowsAccountSnapshot(val accounts: List<WindowsSharedAccount>)

internal class WindowsWidgetSnapshotBuilder {
    private var lastBoundId: Long? = null
    private val revoked = mutableSetOf<Long>()

    fun build(accounts: List<AccountEntity>, session: WidgetSessionSnapshot): WindowsAccountSnapshot {
        if (session.accountId == null && session.generation > 0) lastBoundId?.let { revoked.add(it) }
        if (session.accountId != null && session.cookies.isNotEmpty()) {
            revoked.remove(session.accountId)
            lastBoundId = session.accountId
        }
        val currentIds = accounts.map { it.id }.toSet()
        revoked.retainAll(currentIds)
        return WindowsAccountSnapshot(accounts.filter { it.id !in revoked }.mapNotNull { account ->
            val cookies = if (account.id == session.accountId) session.cookies else
                runCatching { Json.decodeFromString<Map<String, String>>(account.cookiesJson) }.getOrNull()
            if (cookies.isNullOrEmpty()) null else WindowsSharedAccount(account.id.toString(), account.nickname, cookies)
        })
    }
}
