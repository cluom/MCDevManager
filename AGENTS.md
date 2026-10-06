# MCDevManager 项目约定

涉及 Windows 11 系统小组件、指标扩展、会话共享或 MSIX 交付时，先完整阅读 `.agents/skills/mcdevmanager-windows-widgets/SKILL.md`，再核对 `docs/WINDOWS_WIDGETS.md` 的实际验收状态。

不要把 Windows 组件改动扩散到 iOS 或 QQ 机器人；未完成 Win+W 宿主实测时，不宣称安装、显示或独立刷新已通过。增加维度或调整数据口径时同步该项目技能与交接文档。

本机小组件测试采用已授权的自签 MSIX + TrustedPeople 叶证书；用户明确不启用开发者模式。证书不得进入 Root，也不得打包私钥。安装成功与卡片实际显示、刷新通过分别记录。
