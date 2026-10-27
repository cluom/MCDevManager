# AltStore / SideStore 软件源

本源发布 `cluom/MCDevManager` 的扩展版本，不是 AltStore PAL，也不是上游官方发布渠道。用户在 2026-10-27 明确允许 IPA 公开发布；公开发布与软件源使用中性 MCDevManager 文案。

## 添加源

固定地址：

```text
https://raw.githubusercontent.com/cluom/MCDevManager/altstore-source/source.json
```

在 AltStore Classic 或 SideStore 的 Sources 页面添加上述 URL。源发布的是原始自签 IPA，安装时使用原 Apple ID，并保留 IncomeWidget 扩展。添加源本身不恢复删除 AltStore 后丢失的安装记录；必要时从源用同一账号覆盖安装，先备份重要数据，不要先删除原应用。首次部署须等待发布任务成功后再添加源。

原始 Bundle ID 保持 `com.lemon.mcdevmanagermp.MCDevManagerMPR`，不会另建一个应用副本。IPA 与源 JSON 都是公开文件，不包含开发者登录信息、签名证书或账号数据。GitHub 网络不可达时源也会受影响，本流程不引入未知第三方镜像。

## 发布结构

- `altstore-source` 是独立的静态资源分支，包含 `source.json`、`icon.png` 和历史整理的 `report.json`。通过一次 Git 提交原子更新，不占用默认分支、不更改应用源代码。
- 每个成功构建对应一个 `altstore-ios-<run_id>` Release，保留 IPA 和 `SHA256SUMS`，不覆盖已有不同内容的资产，也不改变仓库的 latest release 标记。
- IPA 链接无需登录、没有 Actions 产物的保留期；保留每个历史 Release，避免旧版本下载失效。
- 版本、构建号、最低 iOS 版本、大小、权限从真实 IPA 读取；不是从文件名或仓库的旧 xcconfig 推测。
- 同一 `version` 的不同 `buildVersion` 也可提示更新。不同安装包不得占用已发布的版本/构建号组合；不能用旧构建回滚源。
- 所有主包与扩展权限合并声明；保留旧版本使用的权限。必须检测到收益小组件和正确 App Group。
- 上传后匿名下载核对 SHA-256，再发布源 JSON。未完成 IPA 上传验证时，不改变源。

## 自动发布与复用旧构建

`.github/workflows/ios.yml` 的 **Release** 构建成功后调用 `altstore-publish.yml`；Debug 构建只提供测试产物，不进入源。当前 fork 默认分支与定制源码分支不同，GitHub 创建这些 Release 需要工作流文件写入权限，而普通 `GITHUB_TOKEN` 没有该权限。因此先用仓库所有者账号在网页创建公开 Release：标签 `altstore-ios-<run_id>` 必须指向该次原始构建的 `head_sha`。发布任务不修改账号权限、不替换默认分支、不伪造源码提交；Release 尚未准备好就停止，构建产物仍可下载。其他平台及原多平台 Release 工作流不受影响。

首次发布或对已有产物补发时，推送指向当前发布工具代码的标签：

```shell
git tag altstore-publish-<成功的iOS运行ID>-<唯一后缀>
git push origin altstore-publish-<成功的iOS运行ID>-<唯一后缀>
```

这只启动下载、校验、发布，不重新编译。也支持手动运行 Publish AltStore Source，并填写 `run_id`。只允许当前仓库 `.github/workflows/ios.yml` 的成功构建；缺失、过期、多个 IPA 或 Debug 包都会停止发布。

首次两版本验证按旧到新发布：`37314981699`（原始代码 `e7bfcc0ce49d85364296e1721e3a1a06e72adaac`，1.2.7 / 9），然后 `40298009421`（原始代码 `b2c91f63a6330e09ecf03413ce7beee703197270`，1.2.9 / 10）。每次仍以真实包内信息校验为准。没有 1.2.8 定制包，不重编、不改名冒充。源码版本与发布工具版本分别记录，不能把新工具提交冒充应用二进制源码。

## 仅整理历史（不构建）

`altstore/source.template.json` 从上游 PR 的通用模板派生为本 fork 的配置：`website` 指向 `https://github.com/cluom/MCDevManager`，`iconURL` 使用本仓库 `altstore-source/icon.png`，`developerName` 保留上游署名并标明定制维护者 `BitterLemonn / cluom`，`downloadURL` 指向本 fork 的 Release 资产。三个身份字段须与 `altstore_source.py` 的直接发布路径一致，避免历史整理覆盖成上游信息；回归测试会检查这项约束，不改变给上游提交的 PR 分支。

`sync_altstore_source.py` 分页扫描公开 Releases，从实际 IPA 读取版本、大小及权限；排除草稿，默认排除预发布。下载或校验失败不覆盖已有源。

当前维护分支为 `codex/conditional-gift-lottery`，默认分支暂不变。推送指向当前工具提交的唯一 `altstore-sync-*` 标签，可运行 **Sync AltStore / SideStore Source**，只整理和发布源，不启动 iOS 编译。工作流进入默认分支后也可在 Actions 页面手动执行。GitHub 默认令牌创建的事件不保证触发后续工作流，首次两包发布后需显式运行一次整理任务。

## 验证与回滚

### 版本更新说明（无需重打 IPA）

AltStore / SideStore 的 **What's New** 来自每个 `versions[]` 条目的 `localizedDescription`，不来自应用介绍，也不会自动读取 Git 提交或 Release 正文。

本 fork 使用 [`altstore/release-notes.json`](../altstore/release-notes.json) 按显示版本保存真实用户可见变化：`1.2.9` 和 `1.2.9.1` 分开维护，不把安装注意事项当更新日志。安装商店、原 Apple ID 和保留小组件等提示写在应用介绍及本页；发布说明避免声称未经真机验证的安装结果。

- 发布新 IPA 前补齐对应版本的说明；缺失或空白时停止源发布，不套用其他版本的内容。
- `altstore_source.py` 只设置本次版本的说明，原样保留其他历史版本条目。
- **Sync AltStore / SideStore Source** 整理历史时也按各自版本读取这份文件，避免整理后丢失日志。未传 `--release-notes` 的旧工作流会自动读取模板同目录的 `release-notes.json`；通用模板旁没有该文件时保持原行为。
- 只修正文案时，先取得 `altstore-source` 分支最新 `source.json`，运行下面的命令，再审查差异并只提交该静态分支的 JSON。不修改 IPA、版本号、构建号、日期、下载地址、大小或权限，不触发 iOS Build。

```shell
python3 iosApp/scripts/altstore_release_notes.py \
  --source /path/to/current/source.json \
  --output /path/to/current/source.json
```

此命令不会联网、下载或构建 IPA；只修正每条版本说明。所有已收录版本必须有对应说明，任何缺失都不写入输出文件。新增版本不会自动重写历史；需要纠正历史说明时才显式执行此命令。

2026-10-28：修复固定安装提示覆盖全部版本日志的问题，补正 1.2.9.1 的商店更新入口、准备/失败提示和修订号说明，并补齐 1.2.9、1.2.7 各自记录。本次不升版本、不重打包。历史说明依据实际发布源码 `e7bfcc0`、`b2c91f6`，1.2.9.1 依据 `9613d25`、`0f14263`、`d4e4025` 的已实现改动。

本次验证：68 项 Python 测试中 67 项通过，1 项仅 macOS 签名测试在 Windows 跳过；PyCharm 未打开本项目，IDE 检查不可用。公开源提交 `3843f91` 只增加三个版本各自的 `localizedDescription`，其余 JSON 字段逐项比较未变；固定公开地址回读与发布内容一致，无新 IPA 构建。源回滚可撤销该静态分支提交，不要删除历史安装包或调整版本顺序。

### 应用内跳转更新（定制版 1.2.9.1 起）

应用仍检查 `BitterLemonn/MCDevManager` 的上游版本，因此 `1.2.9.1` 不会屏蔽 `1.2.10` 的提醒。定制修订号递增及真实 iOS 元数据映射见 [iOS 版本说明](ios-version.md)。仅有定制修订更新时，由 AltStore / SideStore 软件源提供更新提醒；应用内暂未增加第二套定制版本通知。

iOS 弹窗分别提供「用 AltStore 更新」和「用 SideStore 更新」。两者先读取本 fork 的公开 `source.json`，只接受正确 Bundle ID、版本不低于目标上游且高于当前版本、下载地址属于本 fork GitHub Release 的 IPA。若尚未发布对应定制包，会明确提示等待合并打包，不自动改装上游原包或退回旧包。读取源使用独立网络客户端，不携带网易登录 Cookie。

找到包后，完整编码下载地址：AltStore 按钮使用 `altstore-classic://install?url=...`，系统无法交付时再尝试旧版 `altstore://install?url=...`；SideStore 按钮仅使用官方 `sidestore://install?url=...`，不自动切换商店。采用 iOS 带完成回调的打开接口，不使用旧版同步 `openURL`。界面提供准备进度、失败提示和定制版发布页备用入口；准备期间禁用两个按钮，避免重复请求。

系统返回已打开，仅表示把链接交给了所选商店，**不表示签名或安装成功**。用户仍需在商店确认，优先使用原安装商店及原 Apple ID，并保留小组件扩展；按钮本身不迁移两家商店各自的安装记录。

本地共享代码及选择/安全校验测试通过；Windows 无法运行 iOS 原生编译与真机跳转，需云端构建后分别验证：已安装所选商店、未安装所选商店、源访问失败、上游新版尚未发布到定制源四种情况。历史 1.2.7 / 1.2.9 IPA 保持原样，不能把旧包伪装成 1.2.9.1。

依据：[AltStore 安装 URL scheme](https://noah978.gitbook.io/altstore-docs/url-schemes)、[SideStore 官方 URL Schema](https://docs.sidestore.io/docs/advanced/url-schema)、[Classic scheme 与 marketingVersion 发布记录](https://faq.altstore.io/release-notes/altstore)。

### 2026-10-27 双版本发布验收

- [1.2.7 复用发布](https://github.com/cluom/MCDevManager/actions/runs/40522710489)、[1.2.9 复用发布](https://github.com/cluom/MCDevManager/actions/runs/40523650272) 均成功，没有重新编译。第二次切换官方 `macos-15-intel` 队列，避免 ARM 机器容量不足；仅影响元数据读取，不改应用二进制。
- [仅整理历史](https://github.com/cluom/MCDevManager/actions/runs/40523857956) 成功：扫描 2 个公开 Release，收录 2 个版本，跳过 0，错误 0；源按 1.2.9（10）、1.2.7（9）排序。
- 1.2.9 IPA：35,809,380 字节，SHA-256 `14e49e57ba3a58d1ec3bf1130d4523b509fc7422ff11bb1b86193a0efdbc6739`。
- 1.2.7 IPA：35,516,591 字节，SHA-256 `3bc0f5c5ac31f7d6cc9872d172e9403651c4c8cc449d9cc55aa22342cfca4337`。
- 两包最低 iOS 均为 18.2，收益小组件与 App Group 检查通过；发布流程先匿名下载核验，再更新源。最终 `report.json` 再次下载两个公开 IPA 核验哈希与元信息。
- 公开 Release 标题、正文与当前软件源均使用中性 MCDevManager 名称，不使用工作室品牌。
- 上游 PR #54 的修复后 [Linux/macOS 检查](https://github.com/BitterLemonn/MCDevManager/actions/runs/40522231510) 均通过。此前一次失败来自测试复制受保护系统文件属性，另一次来自托管 macOS 无可用机器；不得混同为应用构建失败。
- 尚未进行 iPhone 添加源、安装、刷新及小组件真机验证。

```shell
python3 -B -m unittest discover -s iosApp/scripts -p 'test_*.py'
```

本地共 49 项测试（23 项原有测试与 26 项通用源工具测试），Windows 通过 48 项、跳过 1 项 macOS 原生签名测试。macOS 发布任务实际运行该签名测试并调用 codesign 读取主程序和扩展权限。首次实施时 PyCharm 只打开了 cultivation 项目，目标 Python 文件的 IDE 检查不可用；不将单元测试冒充 IDE 零警告结论。

发布成功只能证明公开下载与元数据验证通过，仍需 iPhone 中添加源、保留扩展安装/刷新及小组件显示实测。

回滚发布流程：撤销应用源码分支上本次工作流修改即可停止未来自动发布，不删除历史 IPA。回滚源内容应对 `altstore-source` 分支做一次显式恢复提交（勿强推历史），注意 AltStore 按数组首项判定更新，旧版本置顶会主动提示用户降级。删除一个历史 Release 前，必须确认源中没有指向该版本的链接。

参考：[AltStore 源格式](https://faq.altstore.io/developers/make-a-source)、[更新判定](https://faq.altstore.io/developers/updating-apps)。
