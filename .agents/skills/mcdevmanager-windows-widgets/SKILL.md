---
name: mcdevmanager-windows-widgets
description: 开发、扩展或诊断本项目 Windows 11 系统数据小组件，包括指标多选、同图多线、原生提供程序、登录共享及 MSIX 打包。不用于 iOS 小组件或 QQ 机器人。
---

# MCDevManager Windows 数据小组件

先读 `docs/WINDOWS_WIDGETS.md` 中的接口、安装与验证记录；不要把编译通过等同于 Win+W 宿主验收。

## 架构与入口

- `windowsWidgets/Core/Models.cs`：指标注册表、实例筛选配置、统计合计与缺口处理。
- `windowsWidgets/Core/NeteaseApi.cs`：普通/联机大厅数据追踪接口，固定网易HTTPS源、原始Cookie、禁重定向。
- `windowsWidgets/Core/ChartScale.cs`：混合单位相对峰值刻度；不是百分比增长率。
- `windowsWidgets/Provider/ChartRenderer.cs`：同图多彩折线PNG和原值图例。
- `windowsWidgets/Provider/Cards.cs`：Adaptive Cards 总览、图表与多选配置。
- `windowsWidgets/Provider/WidgetProvider.cs`：COM生命周期、实例CustomState、刷新并发、结果代次检查。
- `windowsWidgets/Provider/SecureStore.cs`：DPAPI当前用户加密，账号快照、按实例缓存、无敏感诊断。
- `shared/src/jvmMain/kotlin/com/lemon/mcdevmanagermp/WindowsWidgetSessionObserver.kt`：Room账号观察 + 已绑定内存Cookie，经stdin导入工具。未绑定启动状态不能清掉持久会话；注销不能重新导出旧DBCookie。
- `windowsWidgets/Packaging/AppxManifest.xml`：三个定义、COM激活及应用执行别名。普通ZIP不提供包身份。

## 增加一个指标

在 `Metric.All` 增加真实字段、单位及是否可累加，例如：

```csharp
new("wishlist_gifts", "愿望单赠送", "次")
```

同一次改动中核对 Kotlin `AnalyzeVO.kt`、数据追踪页的字段和口径；给 `windowsWidgets/Tests/Program.cs` 增加实际字段、缺失和合计测试。当前解析器/选择器/图例通过注册表自动接入。新比例或均值不能直接求和，需先设计可证明的加权算法；退款率现仅允许单模组。人均游玩时长尚未纳入。

## 不可破坏的约束

- 趋势使用 `day_detail/`，不是实时 `incomes/`；大厅传商品ID而非地图ID。
- DAU字段大写；账号日活合计未跨模组去重。日期/字段缺失是断点，不是0。
- 用户要求同图多条彩色线；混合单位明确相对峰值刻度并保留原值/单位，不用翻页替代同时显示。
- `WidgetSettings.Key` 和会话指纹属性必须 `JsonIgnore`；Key编码整个自身时不忽略会递归栈溢出。
- COM回调对象在回调外无生命周期保证，异步工作只能保存其复制出的字符串/大小。
- 配置切换、注销和账号删除时旧请求不得覆盖新页面；日志只输出无敏感类型、状态和数量。
- 主客户端关闭不是注销；提供程序必须独立运行。不要把密码写入桥接快照、命令行、CustomState或缓存。
- 紧凑多选、PNG data URI和最终卡片高度由宿主实测，不凭schema支持就宣称体验通过。
- 不静默导入信任证书；开发布局注册依赖开发者模式，正式MSIX侧载需要签名及信任。
- 本机用户选择不启用开发者模式；明确接受证书后，使用仅代码签名、非CA、不可导出私钥的本地测试证书。`trust-test-certificate.ps1` 校验发布者、指纹、EKU和有效期，仅导入 `LocalMachine/TrustedPeople`，不导入 Root。ZIP只含 `.cer` 公钥，不含私钥；这不等于公开发行可信签名。
- 手工 MSIX 打包合并 SDK 生成的 `WindowsAppSDK.manifest` 中 WinRT 类到包清单根级 `windows.activatableClass.inProcessServer`；DLL路径映射到 `Provider/` 并验证文件随包发布，不能只复制 EXE 的 SxS 清单或手写部分 Widgets 类。合并注册不代表宿主 API 已通过。
- 2026-10-06 本机签名侧载已通过、应用执行别名具有真实包身份，但 `WidgetManager.GetDefault()` 仍返回 `0x8000000F`，栈已进入 `IWidgetManagerStaticsMethods.GetDefault`。只注册 Widgets 类和合并完整 SDK 注册均未消除此错误，不能宣称包图缺失是根因。Win+W 添加/真实显示/刷新仍待确认；不得擅自升级或重置系统组件服务。

## 验证

执行 `windowsWidgets/build.ps1` 与 `:shared:jvmTest :desktopApp:compileKotlin`。遇运行问题先看无敏感诊断日志，再修对应分支。更新此技能和 `docs/WINDOWS_WIDGETS.md` 的契约/验收状态；不可把待用户操作写成已通过。
