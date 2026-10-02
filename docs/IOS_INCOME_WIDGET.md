# iOS 今日 / 昨日收益小组件

## 功能与口径

- 小号、横向中号两种桌面尺寸；总览显示当前登录账号的今日/昨日钻石流水、更新时间。中号同时显示绿宝石（接口字段仍为 `total_points`）。仅修改小组件文案，不修改主程序页面。
- 底部左右按钮循环切换总览和今日作品收益明细。小号每页 2 条，中号每页 3 条，按钻石收益降序排列；只列出今日钻石或绿宝石收益非零的作品。同一作品的普通销售与大厅内购合并为一条，不增加查询次数。
- 翻页只读取共享缓存并保存当前尺寸的页码，不读取凭据、不触发收益刷新；小号与中号分别记忆页码。切换账号清空页码，旧账号按钮不能改变新账号页面。午夜后旧作品明细不显示为今日明细。
- 旧版本缓存没有作品明细时，总览金额仍可读取；首次进入明细页提示点击右上角刷新。完成一次手动刷新后保存金额与明细的完整快照。
- 第一版固定为 **PE 平台**，不是个人分账金额或税后人民币。汇总口径对应 App 的 PE「实时收益」：上架过的普通作品销售，加上联机大厅内购。
- 查询使用北京时间自然日；普通销售和大厅内购即使属于同一个作品，也分别统计。
- **只在点击右上角刷新按钮时请求收益**。添加小组件、回到桌面、系统索取 timeline、午夜切日和 Cookie 同步重载全部只读缓存，不自动联网。
- **无固定冷却**：成功或失败后都可立即再次点击。仅同一批请求尚未结束时暂时禁用按钮，多个尺寸和进程共享文件锁防止重复请求。旧版五分钟尝试记录不再阻止刷新。
- 点击立即触发一次 0.8 秒旋转，随后以“正在刷新收益…”和系统数据等待效果反馈请求状态，成功/失败后恢复按钮状态。外观为刷新按钮，底层使用 `Toggle` 的乐观状态，避免等待网络结束才给反馈。旋转只在开启请求时进行，不在恢复时反转；尊重减少动态效果及常亮屏省电设置。WidgetKit 动画最多两秒，不使用无限动画或持续时间线刷新模拟转圈。
- 任一请求失败、资源列表不完整或接口缺少总额字段时，保留上次完整快照并显示状态；首次失败显示横线，不伪装成 0。跨午夜先纠正日期归属，不用旧金额冒充今日。

## 安全与会话

- `WidgetSessionObserver` 单向观察主程序当前绑定的 Cookie；不导出密码，不改变主程序 Cookie 编码、网络请求或数据库持久化逻辑。
- Cookie 原样作为请求头发送，仅允许固定网易 HTTPS 主机，并禁止 HTTP 重定向。
- 共享 Cookie 放在 App Group 对应钥匙串中，使用 `AfterFirstUnlockThisDeviceOnly`；不存入 `state.json`、日志或 iCloud。
- 共享目录只保存金额与作品明细快照、展示页码、账号标识、版本标识、尝试时间；禁止备份，iOS 文件采用首次解锁后的保护。
- 小组件不回写主程序 Cookie，也不持久化接口返回的 `Set-Cookie`；如果共享会话被服务端判定过期，打开 App 刷新登录后再同步。该版本不另建心跳或自动重登。
- 切换账号、退出登录会清理快照；旧请求完成时会检查会话版本，避免覆盖新账号。系统已渲染的旧画面仍可能短暂保留到 iOS 完成重载，并非安全擦除屏幕的保证。
- 所有尺寸共享文件锁，网络请求期间不持锁。最多四个并发请求，单批最多 20 秒；进程被系统终止后最多 25 秒解除请求占用，允许下一次手动点击，不自动重试。每次请求使用独立 attempt ID，防止同一 Cookie 下旧请求迟到覆盖新结果。作品极多或网络较慢时可能超时，后续需要真实运行数据再评估请求优化。

## 构建、重签与安装

1. 原 `iosApp` target 已依赖并嵌入 `IncomeWidget.appex`；扩展不链接 Compose/Kotlin，控制扩展内存开销。
2. 主程序和扩展共用 `Configuration/IncomeWidget.entitlements` 中的 App Group，以及项目版本/构建号。
3. 三个 iOS CI 入口都会运行 `swift test --package-path iosApp`，随后编译 App 和扩展。
4. `prepare-widget.sh` 在打包前校验扩展、版本、分组，并为产物做临时 ad-hoc 签名以保留 App Groups 权限。**这个签名不能直接安装到 iPhone**，仍须 AltStore / SideStore / 正式证书重签。
5. AltStore 安装时要保留 App Extensions，不能选择移除扩展。额外扩展需要对应的 App ID / 描述文件；能否申请共享分组取决于实际签名账号和工具。
6. 重签适配优先读取 `ALTAppGroups` 中与原分组匹配的实际分组，然后尝试原配置；不盲目使用其他 App 的共享分组。无法共享时显示配置/登录错误，不降级成明文公共文件。
7. 安装完成后先启动 App 并登录一次，再在桌面添加「今日与昨日收益」，**点击右上角首次取数**。若无法显示，检查签名后的主程序和扩展是否都包含同一个 App Group。

### AltStore 3009 名称兼容

- 主程序及扩展的原始 `Info.plist` 使用 ASCII 名称 `MCDevManager` / `IncomeWidget`；中文桌面名称通过各自的 `zh-Hans.lproj/InfoPlist.strings` 保留。
- AltStore 注册扩展时会拼接主程序和扩展的原始显示名称；纯中文默认名称会触发其已知的非 ASCII 名称兼容问题。仅修改 IPA 文件名或 `CFBundleName` 不够，因为它优先读取 `CFBundleDisplayName`。
- 静态检查校验默认名称及本地化配置；打包脚本再次检查实际构建产物，确保英文注册名和中文资源都存在。应用标识、共享分组和数据口径保持不变。
- [AltStore 官方错误代码说明：3009](https://faq.altstore.io/altstore-classic/error-codes)

## 验证

### 导出小组件诊断

- 小组件与主程序在共享目录保存 `WIDGET_DIAG` 事件，跨进程加锁，最多保留 512 行 / 256 KiB；写日志失败不影响刷新。
- 记录缓存读取入口（系统或预览）、手动请求、接口类别/耗时/HTTP 状态、解析错误类别、资源数量、结果保存或丢弃原因，以及导出时的缓存状态。不记录 Cookie、账号 ID、金额、请求 URL、响应正文或原始异常描述。新版 `refreshStarted` 只应出现 `trigger=manual`，系统更新只出现 `cacheRead`。
- 复现后打开 App → 设置 → 日志查看 → 刷新 → 导出当前日志。日志底部会附加共享小组件诊断和当前状态，不必连接 Mac 控制台；清除全部日志也会清除共享诊断，但不会删除会话或收益缓存。
- `reserveSkipped + alreadyInFlight` 表示已有请求进行中；`requestFailed + timeout` 表示网络超时；`completionDropped + revisionMismatch` 表示 Cookie 更新后旧请求结果被丢弃；`attemptMismatch` 表示同一会话的旧请求迟到或重复完成；`exportState` 能确认共享数据是否可读。历史日志中的 `cooldown` 仅适用于旧版。
- 无数据分别显示尚未取得数据、正在刷新、未完成、结果丢弃或具体错误。已取消五分钟限频，保留 20 秒总超时及会话版本保护。
- 2026-09-27 新日志确认主程序的资源列表请求 `/items/categories/pe` 返回 308，补尾斜杠后才返回 200；小组件禁止重定向，因此必须直接使用 `/items/categories/pe/`。收益仍使用实时收益页面相同的 `incomes/` 与 `lobby_incomes/` 接口、北京时间自然日范围和钻石/积分汇总字段。
- 同账号正常 Cookie 轮换保留上次刷新错误，只有开始下一次手动请求、后续结果或切换账号才更新该提示，避免失败被覆盖成泛泛的“暂无今日数据”。请求前读取凭据失败也在同一账号锁内记录可见错误，不因 timeline 改为只读缓存而丢失。该份日志只有限频跳过，没有小组件实际网络请求或结果丢弃记录，尚不能确认是否还存在超时或 Cookie 版本竞态；不因此放宽会话保护。

### 云端快速诊断构建

- 独立 `iOS Build` 工作流提供 `configuration` 选项：默认 Release 用于常规侧载，可手动选择 Debug 用于频繁真机诊断；正式 Release 工作流保持优化构建。Build #4 曾选择 Debug 加快诊断，并使用快速压缩，下载产物因此从 33.1 MiB 增至 48.0 MiB；之后恢复默认 Release 和标准 6 级压缩，继续保留两层缓存。
- Debug 减少 Kotlin/Native 的全程序优化耗时，包体和运行效率可能不及 Release。两者都保留小组件、签名检查和 Swift 测试，不以跳过验证换速度。
- 手动开发分支显式开启 Gradle 缓存写入；额外缓存 `~/.konan`，以运行机架构、Xcode/Swift/SDK 和 Kotlin 依赖版本隔离，不复用来路不明的 IPA 或跳过源码编译检查。
- IPA 已压缩，上传时关闭外层重复压缩。每轮记录编译耗时与原生缓存命中情况；首次运行仍需生成缓存，第二次同工具链构建才能衡量热缓存效果。
- 优化前基线：Build #3 总耗时 26 分 19 秒，编译 1502 秒。优化后的实际耗时以构建结果为准，不承诺固定分钟数。
- 首次优化验证：[Build #4](https://github.com/cluom/MCDevManager/actions/runs/36258688644) 的 Debug 冷缓存构建成功，总耗时 19 分 28 秒，编译 1003 秒（16 分 43 秒），相比基线编译缩短约 33%。Kotlin/Native 与 Gradle 缓存已成功保存；热缓存命中后的实际耗时尚未测量，不能把本次差异单独归因于缓存。

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

Swift 测试覆盖：北京时间边界、成功/失败/重启/重登后立即手动重试、仅合并进行中的请求、多个存储实例抢占、失败保留快照、切换与退出时丢弃迟到响应、同会话不同请求的迟到保护、Cookie 更新、原始编码及不落明文状态文件、午夜日期、错误响应、销售/内购去重、整数溢出。

必须真机验收：

- 保留扩展重签后，可在桌面找到小号/中号小组件。
- 两种尺寸的左右按钮可循环翻页，作品名称与金额清晰可读，第一页绿宝石文案正确；翻页不出现网络请求。升级后的旧缓存可显示总览，刷新后出现作品明细，午夜后不把旧明细当作今日数据。
- 先打开 App 登录，金额与 App 同日 PE 实时收益一致（后台数据可能持续变化）。
- 连续点击刷新和同时放置两个尺寸时，同一账号进行中的请求不会重复；完成后立即再点应再次请求，不等五分钟。
- 添加小组件、打开 App 同步 Cookie、反复回桌面均不出现 `requestStarted`；仅点击才请求。首次点击立即转一圈，成功/失败后恢复可点，不无限转动。减少动态效果/常亮屏下允许无旋转，但保留状态反馈。
- 模拟离线、登录过期、换账号、退出登录、午夜和重启，检查提示、旧数据标识与钥匙串可访问性。
- 确认扩展在较多作品的账号下仍能在系统执行/内存预算内完成。

本次开发环境为 Windows。2026-09-26 的 [iOS Build #3](https://github.com/cluom/MCDevManager/actions/runs/36253733327) 已通过 Swift 测试、Xcode 编译、扩展及名称校验；用户随后确认安装成功，但小组件一直显示等待刷新。2026-09-27 补充诊断与状态文案：17 项 JVM 日志导出/会话/Cookie 测试和 Xcode 工程静态检查通过；[iOS Build #4](https://github.com/cluom/MCDevManager/actions/runs/36258688644) 已通过新增 Swift 测试、Xcode 编译（包括主程序日志桥接）、扩展/名称/分组权限和版本校验，诊断 IPA 已上传。包内版本为 1.2.5，构建号为 4。仍需用户保留扩展重签安装后复现并导出日志；本次不据主程序日志推断已确认小组件故障根因。

2026-09-27 后续验证：[iOS Build #5](https://github.com/cluom/MCDevManager/actions/runs/36262860253) 的实时接口修复 Release 包构建成功，产物约 33.7 MiB，总耗时 16 分 52 秒。其后用户要求改为纯手动刷新并取消冷却，已在 `23a9436` 实现：点击乐观旋转一次、仅合并进行中的请求、完成后立即可重试、旧请求 attempt ID 保护。[iOS Build #6](https://github.com/cluom/MCDevManager/actions/runs/36265860766) 已通过工程静态检查和 28 项 Swift 测试，当前正在构建 Release IPA；尚未完成真机动画与交互验收。#5 不包含手动刷新改动，不应拿它验收新版行为。

参考：

- [Apple：WidgetKit 刷新调度](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date)
- [Apple：交互式小组件](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities)
- [Apple：小组件动画与两秒限制](https://developer.apple.com/documentation/widgetkit/animating-data-updates-in-widgets-and-live-activities)
- [Apple：共享钥匙串](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)
- [AltStore：重签时为主程序和扩展设置共享分组元数据](https://github.com/altstoreio/AltStore/blob/develop/AltStore/Operations/ResignAppOperation.swift)
