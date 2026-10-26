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

每次使用新标签，例如 `ios-build-1.2.9-widget-lottery-20261026-<短提交号>`。普通分支推送不触发打包，此标签不创建 GitHub Release，也不触发 Android / Windows / macOS 构建。下载 ZIP 继续带 `run_id` 和 `run_attempt`，避免与旧文件重名。
