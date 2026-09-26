# iOS 今日 / 昨日收益小组件

## 功能与口径

- 小号、横向中号两种桌面尺寸；显示当前登录账号的今日/昨日钻石流水、更新时间。中号同时显示积分。
- 第一版固定为 **PE 平台**，不是个人分账金额或税后人民币。汇总口径对应 App 的 PE「实时收益」：上架过的普通作品销售，加上联机大厅内购。
- 查询使用北京时间自然日；普通销售和大厅内购即使属于同一个作品，也分别统计。
- 系统索取 timeline、手动刷新共用同一持久化限频器：同一账号五分钟内不重复发起整批请求。失败、重启、重登不能绕过限频。
- 没有可靠的“每次可见”回调，不能承诺每次回桌面立即更新。当前向 WidgetKit 建议约 15 分钟后再调度，实际时机由 iOS 决定；可以手动点右上角刷新。
- 任一请求失败、资源列表不完整或接口缺少总额字段时，保留上次完整快照并显示状态；首次失败显示横线，不伪装成 0。跨午夜先纠正日期归属，不用旧金额冒充今日。

## 安全与会话

- `WidgetSessionObserver` 单向观察主程序当前绑定的 Cookie；不导出密码，不改变主程序 Cookie 编码、网络请求或数据库持久化逻辑。
- Cookie 原样作为请求头发送，仅允许固定网易 HTTPS 主机，并禁止 HTTP 重定向。
- 共享 Cookie 放在 App Group 对应钥匙串中，使用 `AfterFirstUnlockThisDeviceOnly`；不存入 `state.json`、日志或 iCloud。
- 共享目录只保存金额快照、账号标识、版本标识、尝试时间；禁止备份，iOS 文件采用首次解锁后的保护。
- 小组件不回写主程序 Cookie，也不持久化接口返回的 `Set-Cookie`；如果共享会话被服务端判定过期，打开 App 刷新登录后再同步。该版本不另建心跳或自动重登。
- 切换账号、退出登录会清理快照；旧请求完成时会检查会话版本，避免覆盖新账号。系统已渲染的旧画面仍可能短暂保留到 iOS 完成重载，并非安全擦除屏幕的保证。
- 所有尺寸共享文件锁，网络请求期间不持锁。最多四个并发请求，单批最多 20 秒；作品极多或网络较慢时可能超时，后续需要真实运行数据再评估请求优化。

## 构建、重签与安装

1. 原 `iosApp` target 已依赖并嵌入 `IncomeWidget.appex`；扩展不链接 Compose/Kotlin，控制扩展内存开销。
2. 主程序和扩展共用 `Configuration/IncomeWidget.entitlements` 中的 App Group，以及项目版本/构建号。
3. 三个 iOS CI 入口都会运行 `swift test --package-path iosApp`，随后编译 App 和扩展。
4. `prepare-widget.sh` 在打包前校验扩展、版本、分组，并为产物做临时 ad-hoc 签名以保留 App Groups 权限。**这个签名不能直接安装到 iPhone**，仍须 AltStore / SideStore / 正式证书重签。
5. AltStore 安装时要保留 App Extensions，不能选择移除扩展。额外扩展需要对应的 App ID / 描述文件；能否申请共享分组取决于实际签名账号和工具。
6. 重签适配优先读取 `ALTAppGroups` 中与原分组匹配的实际分组，然后尝试原配置；不盲目使用其他 App 的共享分组。无法共享时显示配置/登录错误，不降级成明文公共文件。
7. 安装完成后先启动 App 并登录一次，再在桌面添加「今日与昨日收益」。若无法显示，检查签名后的主程序和扩展是否都包含同一个 App Group。

### AltStore 3009 名称兼容

- 主程序及扩展的原始 `Info.plist` 使用 ASCII 名称 `MCDevManager` / `IncomeWidget`；中文桌面名称通过各自的 `zh-Hans.lproj/InfoPlist.strings` 保留。
- AltStore 注册扩展时会拼接主程序和扩展的原始显示名称；纯中文默认名称会触发其已知的非 ASCII 名称兼容问题。仅修改 IPA 文件名或 `CFBundleName` 不够，因为它优先读取 `CFBundleDisplayName`。
- 静态检查校验默认名称及本地化配置；打包脚本再次检查实际构建产物，确保英文注册名和中文资源都存在。应用标识、共享分组和数据口径保持不变。
- [AltStore 官方错误代码说明：3009](https://faq.altstore.io/altstore-classic/error-codes)

## 验证

Windows 可运行：

```powershell
node iosApp/scripts/check-widget-project.mjs
.\gradlew.bat :shared:jvmTest --tests '*WidgetSessionTest' --tests '*SessionCookieTest' --tests '*SessionCookieEncodingHttpTest' --no-daemon
```

macOS 可运行：

```sh
swift test --package-path iosApp
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphoneos \
  -destination 'generic/platform=iOS' -configuration Release -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO COMPILER_INDEX_STORE_ENABLE=NO DEBUG_INFORMATION_FORMAT=dwarf
bash iosApp/scripts/prepare-widget.sh build/Build/Products/Release-iphoneos/MCDevManagerMPR.app
```

Swift 测试覆盖：北京时间边界、五分钟边界/失败/重启/重登、多个存储实例抢占、失败保留快照、切换与退出时丢弃迟到响应、Cookie 更新、原始编码及不落明文状态文件、午夜日期、错误响应、销售/内购去重、整数溢出。

必须真机验收：

- 保留扩展重签后，可在桌面找到小号/中号小组件。
- 先打开 App 登录，金额与 App 同日 PE 实时收益一致（后台数据可能持续变化）。
- 连续点击刷新和同时放置两个尺寸时，同账号五分钟内仅一批请求。
- 模拟离线、登录过期、换账号、退出登录、午夜和重启，检查提示、旧数据标识与钥匙串可访问性。
- 确认扩展在较多作品的账号下仍能在系统执行/内存预算内完成。

本次开发环境为 Windows：12 项 JVM 会话/Cookie 测试通过，Xcode 工程静态检查、三个 plist 的 XML 检查、打包脚本的 Bash 语法检查通过。2026-09-26 的 [iOS Build #2](https://github.com/cluom/MCDevManager/actions/runs/36249433610) 已通过 12 项 Swift 测试、Xcode 编译和扩展打包校验。随后真机侧载报告 AltStore 3009，已据此补充上述名称兼容修复；修复版的构建结果及真机安装仍须另行验证。

参考：

- [Apple：WidgetKit 刷新调度](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date)
- [Apple：交互式小组件](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities)
- [Apple：共享钥匙串](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)
- [AltStore：重签时为主程序和扩展设置共享分组元数据](https://github.com/altstoreio/AltStore/blob/develop/AltStore/Operations/ResignAppOperation.swift)
