# AltStore Classic 软件源

本源发布 `cluom/MCDevManager` 的雨花工作室定制版，不是 AltStore PAL，也不是上游官方发布渠道。用户在 2026-10-27 明确允许 IPA 公开发布。

## 添加源

固定地址：

```text
https://raw.githubusercontent.com/cluom/MCDevManager/altstore-source/source.json
```

在 AltStore Classic 的 Sources 页面添加上述 URL。源发布的是原始自签 IPA，安装时使用原 Apple ID，并保留 IncomeWidget 扩展。添加源本身不恢复删除 AltStore 后丢失的安装记录；必要时从源用同一账号覆盖安装，先备份重要数据，不要先删除原应用。

原始 Bundle ID 保持 `com.lemon.mcdevmanagermp.MCDevManagerMPR`，不会另建一个应用副本。IPA 与源 JSON 都是公开文件，不包含开发者登录信息、签名证书或账号数据。GitHub 网络不可达时源也会受影响，本流程不引入未知第三方镜像。

## 发布结构

- `altstore-source` 是独立的静态资源分支，仅包含 `source.json` 与 `icon.png`。通过一次 Git 提交原子更新，不占用默认分支、不更改应用源代码。
- 每个成功构建对应一个 `altstore-ios-<run_id>` Release，保留 IPA 和 `SHA256SUMS`，不覆盖已有不同内容的资产，也不改变仓库的 latest release 标记。
- IPA 链接无需登录、没有 Actions 产物的保留期；保留每个历史 Release，避免旧版本下载失效。
- 版本、构建号、最低 iOS 版本、大小、权限从真实 IPA 读取；不是从文件名或仓库的旧 xcconfig 推测。
- 同一 `version` 的不同 `buildVersion` 也可提示更新。不同安装包不得占用已发布的版本/构建号组合；不能用旧构建回滚源。
- 所有主包与扩展权限合并声明；保留旧版本使用的权限。必须检测到收益小组件和正确 App Group。
- 上传后匿名下载核对 SHA-256，再发布源 JSON。未完成 IPA 上传验证时，不改变源。

## 自动发布与复用旧构建

`.github/workflows/ios.yml` 的 **Release** 构建成功后自动调用 `altstore-publish.yml`；Debug 构建只提供测试产物，不进入源。其他平台及原多平台 Release 工作流不受影响。

首次发布或对已有产物补发时，推送指向当前发布工具代码的标签：

```shell
git tag altstore-publish-<成功的iOS运行ID>-<唯一后缀>
git push origin altstore-publish-<成功的iOS运行ID>-<唯一后缀>
```

这只启动下载、校验、发布，不重新编译。也支持手动运行 Publish AltStore Source，并填写 `run_id`。只允许当前仓库 `.github/workflows/ios.yml` 的成功构建；缺失、过期、多个 IPA 或 Debug 包都会停止发布。

首次复用构建：`40298009421`，原始代码 `b2c91f63a6330e09ecf03413ce7beee703197270`，版本 `1.2.9` / 构建 `10`。源码版本与发布工具版本分别记录，不能把新工具提交冒充应用二进制源码。

## 验证与回滚

```shell
python3 -B -m unittest discover -s iosApp/scripts -p 'test_*.py'
```

本地共 23 项测试（15 项源生成测试、8 项既有版本测试）。macOS 发布任务另实际调用 codesign 读取主程序和扩展权限。首次实施时 PyCharm 只打开了 cultivation 项目，目标 Python 文件的 IDE 检查不可用；不将单元测试冒充 IDE 零警告结论。

发布成功只能证明公开下载与元数据验证通过，仍需 iPhone 中添加源、保留扩展安装/刷新及小组件显示实测。

回滚发布流程：撤销应用源码分支上本次工作流修改即可停止未来自动发布，不删除历史 IPA。回滚源内容应对 `altstore-source` 分支做一次显式恢复提交（勿强推历史），注意 AltStore 按数组首项判定更新，旧版本置顶会主动提示用户降级。删除一个历史 Release 前，必须确认源中没有指向该版本的链接。

参考：[AltStore 源格式](https://faq.altstore.io/developers/make-a-source)、[更新判定](https://faq.altstore.io/developers/updating-apps)。
