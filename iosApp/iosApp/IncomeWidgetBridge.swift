import Shared
import WidgetKit
import os

enum IncomeWidgetBridge {
    static func start() {
        WidgetSessionObserver.shared.start { accountID, cookiesJSON in
            do {
                if try WidgetStore.live().synchronize(accountID: accountID, cookiesJSON: cookiesJSON) {
                    WidgetCenter.shared.reloadTimelines(ofKind: IncomeWidgetConstants.kind)
                }
            } catch {
                // 主程序仍可正常使用；不把敏感会话值写进日志。
                Logger(subsystem: "com.lemon.mcdevmanagermp", category: "IncomeWidget")
                    .error("Unable to synchronize widget session; check shared-container signing")
                WidgetCenter.shared.reloadTimelines(ofKind: IncomeWidgetConstants.kind)
            }
        }
    }
}
