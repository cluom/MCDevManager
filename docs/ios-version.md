# iOS 包内版本同步

版本的权威来源是 `gradle/libs.versions.toml` 中的 `versions-name`。IPA 文件名只用于标记产物，不能代表应用内版本。

三个 iOS CI 入口都会在 Xcode 构建前运行：

```shell
python3 iosApp/scripts/version.py sync --build-number "$GITHUB_RUN_NUMBER"
```

本地 Xcode 构建前运行 `python3 iosApp/scripts/version.py sync`，保留本地构建号。共享版本必须采用 `主.次.修订` 的纯数字格式；定制版后缀仅放在产物文件名中。

打包后必须校验实际 IPA 内的 `CFBundleShortVersionString` 和 `CFBundleVersion`，不匹配就终止上传：

```shell
python3 iosApp/scripts/version.py verify path/to/app.ipa
python3 -B -m unittest discover -s iosApp/scripts -p 'test_*.py'
```

2026-09-26 修复：先前包内遗留 `1.0`，造成已有 1.2.5 修复版也提示更新。同步后包内显示 `1.2.5`；本地重封装包构建号为 `2`，不更改应用标识符与业务二进制。该未签名 IPA 仍需使用原 Apple 账号签名后覆盖安装。

## 专用打包标签入口（2026-10-26）

除原有手动入口外，推送 `ios-build-*` 标签也会触发独立 iOS Release 构建。此入口可复用已有 Git SSH 登录，不需要另存 GitHub API 令牌。标签后缀作为产物文件名标记，包内版本仍同步 `versions-name`；构建号仍取 `GITHUB_RUN_NUMBER`。

每次使用新标签，例如 `ios-build-1.2.9-widget-lottery-20261026-<短提交号>`。普通分支推送不触发打包，也不触发 Android / Windows / macOS 构建。下载 ZIP 继续带 `run_id` 和 `run_attempt`，避免与旧文件重名。2026-10-27 起，Release 构建成功后会额外公开发布 IPA 并更新 [AltStore 源](altstore-source.md)；Debug 构建不发布。

## iOS 1.2.9 定制包（2026-10-26）

- 拉取 `origin` / `upstream` 后确认稳定上游仍为 `41b4b0f`（1.2.9），已包含在当前分支；本次没有新的上游应用代码需要合并。
- 构建源码：`b2c91f63a6330e09ecf03413ce7beee703197270`；标签：`ios-build-1.2.9-widget-lottery-20261026-b2c91f63`。保留收益小组件、手动刷新、明细翻页、绿宝石文案，以及条件抽奖、报告补抽、自动回复和中文日期等本地功能。
- [iOS Build #10](https://github.com/cluom/MCDevManager/actions/runs/40298009421) 全部成功，Release 配置；包内版本 `1.2.9`、构建号 `10`，由云端同步及实际 IPA 校验步骤确认。开始于北京时间 17:14，完成于 17:49；运行记录总时长约 35 分 25 秒，主编译步骤 33 分 1 秒。
- 本地实际重跑 `:shared:jvmTest --rerun --offline --console=plain`：27 个测试套件、203 项测试，0 失败、0 错误、0 跳过；8 项 iOS 版本工具测试通过。云端小组件 Swift 测试、Xcode 工程检查、原生编译、扩展准备及 IPA 版本校验均通过。
- [下载 ZIP](https://github.com/cluom/MCDevManager/actions/runs/40298009421/artifacts/12640747028)：`ios-ipa-1.2.9-widget-lottery-20261026-b2c91f63-40298009421-1.zip`，35,809,646 字节。GitHub 记录的产物 SHA-256：`f5454b629023730de35cb9365c3f65cf9d26ca3605f033ebf500d75b9adaf7a4`。
- 包内 IPA 文件名：`MCDevManagerMP-V1.2.9-widget-lottery-20261026-b2c91f63-Release-ios-arm64.ipa`。该包需要用户自行重签，保留小组件扩展；本轮未下载到用户下载目录、未进行手机安装或真机验收，也未替换 Windows 客户端。
