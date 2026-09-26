import Foundation
import os

enum WidgetRefreshService {
    private static let logger = Logger(subsystem: "com.lemon.mcdevmanagermp", category: "IncomeWidget")

    static func cached() -> WidgetState {
        do {
            let store = try WidgetStore.live()
            let state = try store.read()
            var details = state.diagnosticDetails()
            details.trigger = .snapshot
            store.diagnostics.record(.cacheRead, details)
            return state
        }
        catch { return WidgetState(message: message(for: error)) }
    }

    static func refresh(trigger: WidgetRefreshTrigger = .timeline) async -> WidgetState {
        let started = Date()
        do {
            let store = try WidgetStore.live()
            var details = try store.read().diagnosticDetails()
            details.trigger = trigger
            store.diagnostics.record(.refreshStarted, details)
            if let credential = try store.reserve(now: Date()) {
                do {
                    let snapshot = try await IncomeAPI(diagnostics: store.diagnostics).load(credential: credential, now: Date())
                    try store.complete(credential: credential, snapshot: snapshot, message: nil)
                    logger.info("Network load completed; see completionSaved/completionDropped for cache outcome")
                } catch {
                    // 不输出 URL、响应体、账号、Cookie 或底层异常描述。
                    logger.warning("Refresh failed; retaining last complete snapshot")
                    var failure = WidgetDiagnosticDetails.failure(error)
                    failure.trigger = trigger
                    failure.elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
                    store.diagnostics.record(.refreshFailed, failure)
                    try store.complete(credential: credential, snapshot: nil, message: message(for: error))
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
            let failure = WidgetDiagnosticDetails.failure(error)
            if let store = try? WidgetStore.live() { store.diagnostics.record(.refreshFailed, failure) }
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
