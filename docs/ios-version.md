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
