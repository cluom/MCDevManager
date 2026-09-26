import SwiftUI

@main
struct iOSApp: App {
    init() {
        IncomeWidgetBridge.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
