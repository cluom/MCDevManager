// swift-tools-version: 5.9
import PackageDescription

// 轻量数据层可以在 macOS 上测试，无需启动模拟器或编译 Compose。
let package = Package(
    name: "IncomeWidgetCore",
    platforms: [.macOS(.v13), .iOS("18.2")],
    targets: [
        .target(name: "IncomeWidgetCore", path: "WidgetShared"),
        .testTarget(name: "IncomeWidgetCoreTests", dependencies: ["IncomeWidgetCore"], path: "WidgetTests")
    ]
)
