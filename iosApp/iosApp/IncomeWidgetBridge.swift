import Shared
import WidgetKit
import os

enum IncomeWidgetBridge {
    static func start() {
        WidgetSessionObserver.shared.configureDiagnostics(read: {
            do {
                let store = try WidgetStore.live()
                var details = try store.read().diagnosticDetails()
                details.trigger = .export
                return try store.diagnostics.export() + "\n" + WidgetDiagnostics.line(.exportState, details)
            } catch {
                return WidgetDiagnostics.line(.exportState, .failure(error))
            }
        }, clear: {
            try? WidgetStore.live().diagnostics.clear()
        })
        WidgetSessionObserver.shared.start { accountID, cookiesJSON in
            do {
                if try WidgetStore.live().synchronize(accountID: accountID, cookiesJSON: cookiesJSON) {
                    WidgetCenter.shared.reloadTimelines(ofKind: IncomeWidgetConstants.kind)
                }
            } catch {
                // 主程序仍可正常使用；不把敏感会话值写进日志。
                Logger(subsystem: "com.lemon.mcdevmanagermp", category: "IncomeWidget")
                    .error("Unable to synchronize widget session; check shared-container signing")
                let details = WidgetDiagnosticDetails.failure(error)
                if let store = try? WidgetStore.live() { store.diagnostics.record(.sessionSync, details) }
                else {
                    // Also included in exported app logs when no shared container can be opened.
                    WidgetSessionObserver.shared.reportDiagnostic(line: WidgetDiagnostics.line(.sessionSync, details))
                }
                WidgetCenter.shared.reloadTimelines(ofKind: IncomeWidgetConstants.kind)
            }
        }
    }
}
