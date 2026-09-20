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

## 脱敏诊断日志（2026-09-21）

本轮仅增加观测，不修改 Cookie 编码、登录、数据库内容或重试策略，也未改动上游 PR #42。

搜索 `SESSION_DIAG v=1`：

- `request`：进程内递增 `id`、HTTP 方法、脱敏接口路径、最终请求头中的 `wire` 摘要与 `store` 快照。`wire.headerBytes` 为实际 Cookie 头值的 UTF-8 字节数；`store.pairBytes` 只是未编码键值的估算，不能冒充线上头长度。
- `response`：同一 `id` 的状态码、耗时、内容类型、声明长度、网关类型和 Set-Cookie 结构摘要；在默认 HTTP 错误校验之前记录，不读取或消费正文。
- `request_failure` / `send_failure` / `receive_failure` / `cancelled`：异常类型链、耗时；Content-Length 不匹配时保留 expected/received 数字，不输出原始异常消息中的 URL、响应体或凭据。校验器另外兜住发送结束之后发生的状态校验和正文读取错误，同一失败可能有多个阶段记录。
- `cookie_update`：来源 `ktor_storage`、接口、入站编码枚举，以及值变化前后摘要。Ktor 存储回调也可能来自捕获请求 Cookie，不等同于服务端 Set-Cookie；需结合 response 事件判断。
- `cookie_persist` / `session_bind` / `session_clear`：本地账号数字 ID、结构摘要及清除事件，用于区分刷新、持久化、切换账号和真正注销。

单项摘要包含长度、UTF-8 字节数、等号数、末尾等号数、百分号数、百分号转义嵌套深度和控制字符标志。`depth>=2` 或长度达到 2048 时标记 `suspicious=true`，只是诊断提示，不自动判为登录失效。深度采用线性扫描，不实际解码、修复或保存 Cookie。

新日志不记录 Cookie 原值、凭据哈希、Authorization、密码、查询参数、未知动态路径段、请求/响应正文或全部响应头。每个事件最多列出 16 项 Cookie 详情。替换旧的 Ktor 原始请求日志，并避免统一错误处理和 UI 错误处理再次打印包含响应正文的 HTTP 异常。

日志仍沿用按天/5MB 分片和既有保留策略，安装版位于 `app/logs/app-YYYY-MM-DD*.log`。排查时取出故障前后相关事件，按请求 ID 关联。**旧日志及其他业务日志不受这一脱敏保证覆盖，分享前仍需检查。**

本轮真实账号只读对照：相同未读消息 GET 接口，数据库 Cookie 原值拼接与仅在内存消除 S_INFO 重复编码的版本均返回 HTTP 200、业务 status=ok；未写回数据库。此对照绕开了客户端 Ktor 的实际发送编码，不能据此证明重复编码是此前 502 的唯一或直接原因。最终发送链路需通过新增日志观测。

本机测试不需要真实 Cookie，覆盖请求编号与实际发送字节数、200/502、响应体不完整、深层编码、日志注入/脱敏和日志输出失败不改变请求结果。

## URL 重复编码修复（2026-09-21）

只读核对运行中的 JVM：沿 `CookiesStore.INSTANCE -> state -> State.cookies` 定位当前快照，并确认 `ApiFactory.cookiesStorage.store` 指向同一对象。两次稳定读取均显示 S_INFO 长度 3248，解除 268 层 URL 编码后为 32 字符；5 项 Cookie 与数据库逐项相同。未注入代理、暂停进程、修改目标内存、生成堆转储或重放网络请求。

根因是 `SessionCookieStorage.get()` 从字符串重建 `Cookie` 时使用默认 `URI_ENCODING`，把 HTTP 头中的原始值再次 URL 编码。Ktor 捕获请求头时又把该值按 RAW 写入存储；服务端 Set-Cookie 默认也是 RAW。这会在回存链路中保留多余转义，而不仅是影响一次请求。

修复约定：

- 存储字符串统一为 HTTP Cookie 的线格式（可直接发送的原始值），`get()` 明确使用 `CookieEncoding.RAW`。
- 收到带显式编码枚举的 `Cookie` 时，按该枚举转换成线格式一次，再存入 Map；普通 RAW 值保持不变。
- 不递归解码普通 Cookie，也不猜测百分号内容是否合法，避免破坏服务端有意编码的令牌。
- 本版本不自动修复历史数据库。用户已选择仅替换程序并重新登录；数据库、其他账号资料和原日志均保留。
- 新登录后若仍报 502，使用 `SESSION_DIAG` 对照实际 wire/store 摘要；不宣称本修复已证实解决所有 502。

回归测试包括 300 次请求头捕获/响应回存循环、合法百分号/加号/斜线/等号保持、显式 URI/Base64 编码转换、历史污染值不被擅自改写，以及本机 HTTP 服务的连续收发与不变快照零持久化。

验证结果：`:shared:jvmTest` 共 91 项测试，0 失败、0 错误；`:desktopApp:packagePortable` 构建成功。未运行 Android/iOS 测试，也未用真实账号联网验收新版本。

参考 [Ktor 3.5.0 HttpCookies](https://github.com/ktorio/ktor/blob/3.5.0/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/cookies/HttpCookies.kt) 和 [Cookie 编解码实现](https://github.com/ktorio/ktor/blob/3.5.0/ktor-http/common/src/io/ktor/http/Cookie.kt)。
