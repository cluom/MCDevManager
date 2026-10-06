## Why

用户希望在 Windows 11 的 Win+W 面板查看 MCDevManager 数据，关闭主程序后仍能手动刷新。现有桌面客户端没有原生小组件；仅保留托盘程序或创建悬浮窗不能满足要求。

## What Changes

- 增加原生 Windows 小组件提供程序，与主客户端分开运行。
- 提供首页总览、账号趋势、指定模组趋势三种定义，允许重复添加并独立配置。
- 趋势组件支持平台、天数和指标多选，同时显示钻石收益、下载、新增购买、日活、绿宝石及愿望单等数据追踪维度。
- 首页总览沿用 `data_analysis/overview`；趋势沿用数据追踪页 `day_detail` 接口，不使用实时收益接口。
- 通过本机当前用户加密保存登录会话；不导出密码，不改变 Cookie 编码。
- 增加可复现的 Windows 构建、打包和安装说明，记录系统宿主验收与代码验证的区别。

## Capabilities

### New Capabilities

- `windows-data-widgets`: 独立运行的 Windows 系统小组件、手动刷新、多维趋势配置、安全会话共享及打包。

### Modified Capabilities

无。现有 Android、iOS 和桌面页面的业务行为不变。

## Impact

新增 `windowsWidgets/` C# 项目、MSIX 清单与构建脚本；桌面客户端启动登录会话共享桥接；账号 DAO 增加只读观察接口。仅 Windows 新增本地依赖，不需要新的云后端，不替换既有数据库，不触及 QQ 机器人。
