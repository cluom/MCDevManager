# 会话 Cookie 与轮询修复（2026-09-20）

## 已确认并修复

- 删除手写 `Set-Cookie` 兜底解析，统一使用 Ktor 的 HTTP Cookie 解析；保留值中的 `=`，处理空值、过期和删除指令。
- Cookie 使用不可变快照，避免多个响应并发修改共享 Map。
- 登录、自动登录、切换账号验证后保存最新 Cookie；运行时更新按显式绑定的账号 ID 写回 `account.cookiesJson`，只在快照变化时写入。
- 持久化失败只记录不含凭据的诊断信息，后续请求重试保存，不把磁盘异常当成认证失败。
- 新登录和退出时清除旧账号绑定；持久化仅 UPDATE 现有账号，不重新创建已删除的账号。
- Cookie 仅发往现有网易登录、开发者和上传主机，不发送给 GitHub 或任意下载地址。
- 移除自动登录时的完整 Cookie 日志，网络日志隐藏敏感头且不记录可能含令牌的正文。
- 业务 HTTP 客户端开启状态校验，502 HTML 响应进入服务器错误分支，401 保留认证错误分类。
- 首页和更新检查 ViewModel 交给导航条目的 ViewModelStore 管理，避免普通 `remember` 重建后旧 `viewModelScope` 仍存活。
- 未读消息轮询保持原先 60 秒间隔，定时和手动刷新共用在途请求，退出首页导航条目时自动取消；从子页面返回时刷新角标。

## 验证

```powershell
.\gradlew.bat :shared:jvmTest --offline --console=plain
.\gradlew.bat :desktopApp:packagePortable --no-configuration-cache --console=plain
```

测试覆盖 Cookie 值中的等号、非法头、删除、保存去重、保存重试、账号绑定和保存期间切换账号、验证后的最新快照、非业务域名隔离；HTTP 故障测试仅启动本机临时服务，不请求真实账号接口。

`packagePortable` 如遇已有 `downloadWix` 任务与配置缓存冲突，使用 `--no-configuration-cache`；不修改项目版本号和用户数据。

## 仍需实机观察

这不是“永久登录”保证。服务端主动过期、异地登录、网络代理及 502 的上游原因尚未证实。官网未读接口每 5 分钟轮询，但是否刷新服务器会话有效期没有证据。此次不新增猜测性的续期接口，也不通过提高频率掩盖错误。

现有 Cookie 模型仍以名称存储，不等同于完整浏览器的域名/路径 CookieJar；可信主机范围明确限制，但未扩展数据库来持久化全部 Cookie 属性。
