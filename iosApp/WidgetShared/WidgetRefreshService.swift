import Foundation
import os

enum WidgetRefreshService {
    private static let logger = Logger(subsystem: "com.lemon.mcdevmanagermp", category: "IncomeWidget")

    static func cached(store suppliedStore: WidgetStore? = nil, trigger: WidgetRefreshTrigger = .snapshot) -> WidgetState {
        do {
            let store = try suppliedStore ?? WidgetStore.live()
            let state = try store.read()
            var details = state.diagnosticDetails()
            details.trigger = trigger
            store.diagnostics.record(.cacheRead, details)
            return state
        }
        catch { return WidgetState(message: message(for: error)) }
    }

    // 只有手动 Intent 调用此入口；注入项供离线测试使用，不改变生产凭据或请求地址。
    static func refreshManually(store suppliedStore: WidgetStore? = nil, now: Date = Date(),
                                load: ((WidgetCredential, Date) async throws -> IncomeSnapshot)? = nil,
                                onStateChange: () -> Void = {}) async -> WidgetState {
        let trigger = WidgetRefreshTrigger.manual
        let started = Date()
        // 包括重复点击跳过与失败，均让乐观按钮恢复为真实持久化状态。
        defer { onStateChange() }
        do {
            let store = try suppliedStore ?? WidgetStore.live()
            var details = try store.read().diagnosticDetails()
            details.trigger = trigger
            store.diagnostics.record(.refreshStarted, details)
            if let reservation = try store.reserve(now: now) {
                let credential = reservation.credential
                // reserve 的文件锁已释放，通知 timeline 展示“正在刷新”。
                onStateChange()
                do {
                    let snapshot: IncomeSnapshot
                    if let load { snapshot = try await load(credential, now) }
                    else { snapshot = try await IncomeAPI(diagnostics: store.diagnostics).load(credential: credential, now: now) }
                    try store.complete(reservation: reservation, snapshot: snapshot, message: nil)
                    logger.info("Network load completed; see completionSaved/completionDropped for cache outcome")
                } catch {
                    // 不输出 URL、响应体、账号、Cookie 或底层异常描述。
                    logger.warning("Refresh failed; retaining last complete snapshot")
                    var failure = WidgetDiagnosticDetails.failure(error)
                    failure.trigger = trigger
                    failure.elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
                    store.diagnostics.record(.refreshFailed, failure)
                    try store.complete(reservation: reservation, snapshot: nil, message: message(for: error))
                }
            }
            let state = try store.read()
            var finished = state.diagnosticDetails()
            finished.trigger = trigger
            finished.elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
            store.diagnostics.record(.refreshFinished, finished)
            return state
        } catch {
            logger.warning("Shared widget state unavailable")
            var failure = WidgetDiagnosticDetails.failure(error)
            failure.trigger = trigger
            if let store = try? suppliedStore ?? WidgetStore.live() { store.diagnostics.record(.refreshFailed, failure) }
            else { logger.warning("\(WidgetDiagnostics.line(.refreshFailed, failure), privacy: .public)") }
            return WidgetState(message: message(for: error))
        }
    }

    static func message(for error: Error) -> String {
        if let error = error as? IncomeWidgetError { return error.message }
        if let error = error as? URLError {
            if error.code == .timedOut { return "刷新超时，稍后再试" }
            return "网络不可用，保留上次结果"
        }
        return "刷新失败，请稍后重试"
    }
}
