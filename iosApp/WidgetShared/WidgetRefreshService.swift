import Foundation
import os

enum WidgetRefreshService {
    private static let logger = Logger(subsystem: "com.lemon.mcdevmanagermp", category: "IncomeWidget")

    static func cached() -> WidgetState {
        do { return try WidgetStore.live().read() }
        catch { return WidgetState(message: message(for: error)) }
    }

    static func refresh() async -> WidgetState {
        do {
            let store = try WidgetStore.live()
            if let credential = try store.reserve(now: Date()) {
                do {
                    let snapshot = try await IncomeAPI().load(credential: credential, now: Date())
                    try store.complete(credential: credential, snapshot: snapshot, message: nil)
                    logger.info("Refresh completed")
                } catch {
                    // 不输出 URL、响应体、账号、Cookie 或底层异常描述。
                    logger.warning("Refresh failed; retaining last complete snapshot")
                    try store.complete(credential: credential, snapshot: nil, message: message(for: error))
                }
            }
            return try store.read()
        } catch {
            logger.warning("Shared widget state unavailable")
            return WidgetState(message: message(for: error))
        }
    }

    static func message(for error: Error) -> String {
        if let error = error as? IncomeWidgetError { return error.message }
        if error is URLError { return "网络不可用，保留上次结果" }
        return "刷新失败，请稍后重试"
    }
}
