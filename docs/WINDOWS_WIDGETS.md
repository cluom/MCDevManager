# Windows 11 数据小组件

## 功能

Win+W 面板中有三种组件，可重复添加：收益与下载总览、账号多维趋势、指定模组多维趋势。每个实例独立保存账号、平台、最近天数和多选指标。默认手动刷新，无冷却；正在刷新时不重复启动请求。主客户端关闭后，原生提供程序仍能独立请求数据。

多维趋势在**同一张图**使用不同颜色折线，同时显示。相同单位用原值刻度；不同单位用“相对趋势（各线周期峰值=100）”，即每条线除以该线周期最大绝对值后乘100。图例显示截止昨日原值、单位及峰值。该相对刻度不表示增长率，也不是钻石/人数的共同绝对单位。

指标：钻石收益、绿宝石收益、下载、新增购买、日活、新增粉丝、愿望单新增/赠送/购买/移除；单模组额外支持退款率。人均游玩时长尚未提供，避免未核实字段单位和跨模组加权方式。账号日活合计是模组日活之和，**未跨模组去重**。缺失字段/日期保留断点，不补零；目标模组缺日记录时，账号合计该日也视为不完整。

## 接口契约

固定 HTTPS 源：`https://mc-launcher.webapp.163.com/`，禁止跟随重定向传 Cookie。

| 用途 | 接口 |
|---|---|
| 首页截图的8项数据 | `data_analysis/overview` |
| 普通模组日统计（数据追踪页） | `data_analysis/day_detail/` |
| 联机大厅商品日统计（数据追踪页） | `data_analysis/goods/day_detail/` |
| 普通作品选择 | `items/categories/{pe或comp}/` |
| 联机大厅作品、商品选择 | `goods/pe/summary` → `goods/pe/{作品IID}/` |

趋势结束日期是本机昨日，开始日期按含端点的天数计算，参数采用 `yyyyMMdd`。联机大厅日统计使用**商品ID**、`platform=pe&category=pe&mc_type=1`，不传地图ID。不使用 `incomes/` 实时订单接口，不应用分账权重。

## 构建

需要 .NET 8 SDK 和 Windows SDK MakeAppx；不要求 Visual Studio IDE。项目内 C# 不依赖主客户端 JVM。独立 SDK 可使用 `D:\software\dotnet-sdk8\dotnet.exe`。

```powershell
cd D:\project\other\yohu_studio_qq_bot\MCDevManager
powershell -ExecutionPolicy Bypass -File windowsWidgets\build.ps1 -Dotnet D:\software\dotnet-sdk8\dotnet.exe
```

构建先执行核心测试，再自包含发布（含 .NET 和 Windows App SDK runtime），最后验证并生成 MSIX。产物在 `windowsWidgets/dist/`，ZIP 名含时间/构建编号，不同构建不重名。ZIP 不含开发密钥或真实账号数据。

## 安装

安装是独立包，不覆盖既有数据库。仅编译主客户端并不会让 Win+W 自动发现小组件。

**当前分发前提：**[微软 Widgets 示例](https://github.com/microsoft/WindowsAppSDK-Samples/tree/main/Samples/Widgets#widgetprovider-app-distribution)将分发方式列为“侧载（需要开启开发者模式）”和“微软商店”。普通自签 MSIX 可以完成安装，并不代表 Win+W 会列出组件。本机用户要求不开开发者模式，目前签名侧载已安装但发现/添加未通过；不能继续把本地证书方案作为受支持的免开发者模式小组件交付方式。若保持此限制，应另行选择商店分发或不属于 Win+W 的独立桌面卡片，未经确认不得变更方案。

- 开发体验：需系统已开启开发者模式；在生成的构建目录执行 `install.ps1 -Development` 从 `payload/AppxManifest.xml` 注册。无需导入信任证书，注册后依赖本地 payload 目录，不能移动或删除。
- 正式侧载：MSIX 必须签名且证书受本机信任。构建脚本接受 `-CertificateThumbprint`，使用现有证书签名，**不会自动创建或导入信任证书**。不能把未签名包描述成双击即可安装。
- 自签包的安装步骤（只验证侧载安装，不替代上述组件发现前提）：用户明确接受后，可用仅代码签名的非CA自签叶证书。构建导出 `.cer` 公钥，私钥保持本地不可导出。以管理员权限执行 `trust-test-certificate.ps1 -CertificateFile <cer绝对路径> -ExpectedThumbprint <已核对指纹>`，只将指定证书信任到 `LocalMachine/TrustedPeople`，不进入 Root；随后双击签名 MSIX 或执行 `install.ps1`。此信任作用于本机所有用户，测试结束不需要时可移除，不是公开发行可信签名。参考[微软签名指南](https://learn.microsoft.com/en-us/windows/msix/package/sign-msix-package-guide)。
- 更新主客户端，启动并登录/切换需要用于小组件的账号。Windows 会话桥接自动发现 `MCDevManagerWidgetBridge.exe` 应用执行别名，经标准输入同步；别名被系统禁用时，在 Windows“应用执行别名”设置中重新开启。
- Win+W → 添加小组件 → 搜索 MCDevManager。先选账号，再保存并刷新。单模组组件切换账号/平台后先点“更新模组列表”。
- 多选选择器使用系统 `Input.ChoiceSet`；具体紧凑下拉/复选列表外观由 Windows 宿主决定。推荐大尺寸，维度太多时图例可能变密，分别添加实例可以改善可读性。

登录已失效时，回到主客户端重新登录。注销/删除会撤销对应共享凭据；关闭主程序不是注销，不清会话。切换账号过程的清空会话采取保守撤销，重新绑定后恢复对应账号。

## 安全与诊断

会话快照和业务缓存用 Windows DPAPI CurrentUser 加密，存于 `%USERPROFILE%\.mcdevmanager-widgets`。只共享账号ID、显示名和原始 Cookie，不共享密码/邮箱，不输出到命令行或日志。组件 CustomState 仅含筛选配置。缓存须同时匹配配置和会话指纹；提供程序观察快照变更，账号撤销后取消对应请求并清空卡片和实例缓存。首页总览始终标明数据所属月份和日期，避免跨午夜将旧缓存误称为“昨日”。

诊断文件：`%USERPROFILE%\.mcdevmanager-widgets\diagnostics.log`（轮转上限约512KB）。它只记录操作、数量、HTTP状态与耗时，禁止附上完整接口响应、URL查询串和 Cookie。主程序日志标记 `WINDOWS_WIDGET_DIAG`。

Windows 工具模式：`MCDevManagerWidgetBridge.exe --clear-sessions` 清除共享账号快照；`--import-sessions` 仅接收标准输入 JSON。不要手工把敏感 JSON 粘贴进 PowerShell 命令行历史。

## 验证记录

- 2026-10-06：C# 提供程序编译零警告、22项核心检查通过；Kotlin 主客户端编译与141项 JVM 测试通过，便携版 ZIP 构建通过。检查覆盖接口选择、联机大厅商品展开、Cookie 无二次编码、DPAPI往返、缓存隔离、缺失不补零、退款率限制、多选卡片、PNG图表生成与旧缓存绝对日期标注。
- MSIX构建：2026-10-06 自包含发布及 MakeAppx 清单验证通过；未签名开发包 `windowsWidgets/dist/20261006-first-preview/`。ZIP文件 `MCDevManager-Widgets-1.0.0.0-20261006-first-preview.zip`。构建脚本已处理 Windows PowerShell 5.1 的 UTF-8 XML 读取问题。
- 系统宿主：**待实测**。开发者模式/证书信任、添加三个定义、PNG显示、控件多选外观、实际刷新及主程序退出后刷新，不属于上述离线测试结论。
- 真实账号/网络数据：待实测，不使用虚构预览数据声称平台请求已经成功。

2026-10-06 用户明确不启用开发者模式，接受本地签名证书方案。已通过管理员确认将非CA代码签名叶证书放入 TrustedPeople；Root 没有此证书，开发者模式保持关闭。签名、系统验签与独立包安装通过，应用执行别名注册成功，未覆盖现有主客户端。当前安装版本 `1.0.0.2`，包身份 `Cluom.MCDevManager.Widgets_1.0.0.2_x64__5z9wt2vbecfee`；对应ZIP：`windowsWidgets/dist/MCDevManager-Widgets-1.0.0.2-20261006-signed-sdk-8a974ba6.zip`。

宿主 API 自检**尚未通过**：`WidgetManager.GetDefault()` 返回 `COMException/0x8000000F`（元数据类型找不到），方法栈已进入 `ABI.Microsoft.Windows.Widgets.Providers.IWidgetManagerStaticsMethods.GetDefault`。包外同程序返回参数错误；包内诊断进程经 `GetPackageFullName` 确认有真实包身份。先补 Widgets 类型注册，再按[微软 WinRT 注册说明](https://github.com/microsoft/WindowsAppSDK/blob/main/docs/Coding-Guidelines/WinRT-Registration.md)合并 SDK 完整注册；955项额外类型合并且 MakeAppx 验证通过，但此错误仍存在，**不能将包图缺失当作已确认根因或声称组件可用**。只启动并结束了本次诊断进程，没有重置或更新系统服务。系统 WidgetsPlatformRuntime 为 `1.6.19.0`，WebExperience 为 `526.21100.40.0`；是否与错误有关尚未证实。等待用户在 Win+W 搜索/添加后的实际结果，再进一步定位。

22项核心检查仍全部通过；PowerShell 脚本语法检查及项目技能校验通过。真实账号、卡片显示、多选外观和关闭主客户端后的刷新均未验收，OpenSpec 4.2 保持未勾选。用户需要新版桌面客户端同步会话，现有安装未替换；可自行使用本轮构建的便携版，不能把旧客户端未同步误判为账号丢失。

2026-10-06 后续用户截图确认：Win+W 的“添加小组件”列表没有 MCDevManager 三个定义。已核对官方示例的侧载/商店分发前提，撤回“只信任自签证书即可免开发者模式使用 Win+W”这一交付承诺。该前提与当前缺失现象一致，但未通过启用开发者模式对照实测，不能把它作为 `0x8000000F` 的唯一已证实原因。用户已明确拒绝开启，当前停止重打包/试装；不改系统组件、不发布商店、不自动卸载包或删除证书，等待用户决定后续方式。

## 回滚

```powershell
Get-AppxPackage -Name Cluom.MCDevManager.Widgets | Remove-AppxPackage
```

卸载组件不改主客户端数据库。清除共享会话使用上面的工具模式；不需要为回滚删除主程序账号。开发布局注册后，先卸载再删除构建产物。正式更新沿用身份 `Cluom.MCDevManager.Widgets`、发布者 `CN=cluom.MCDevManager` 和固定COM CLSID；递增四段 MSIX 版本。

自签测试结束后，可在管理员 PowerShell 中仅移除对应叶证书：`Remove-Item -LiteralPath 'Cert:\LocalMachine\TrustedPeople\<实际签名证书指纹>'`。保留 CurrentUser/My 的不可导出签名密钥用于后续更新，或在不再发布同签名包时单独移除；不要删除其他证书，不要改 Root。
