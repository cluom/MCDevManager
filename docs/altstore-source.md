# 使用 AltStore / SideStore 安装与发布

## 为什么增加这个配置

可以把 Source 理解为一份“应用目录和更新清单”：它告诉 AltStore / SideStore 应用名称、版本以及去哪下载 IPA。用户添加一次源后，即可在客户端中查看版本、安装与发现更新，不必每次到 GitHub 下载 IPA 再手动导入。是否自动安装更新仍取决于侧载客户端、签名有效期与系统限制。

对维护者而言，这不是增加一套应用商店服务：文件托管在现有 GitHub Release，无需另购服务器、接入 Apple 开发者后台或收集用户 Apple ID。本改动不修改应用业务代码、登录流程、密码存储或运行时网络请求，不会给应用增加权限，也不会自动替用户签名。

构建侧增加的工作仅为：为 iOS 包设置真实版本/构建号，读取已生成的 IPA 并输出 JSON，再随现有草稿 Release 上传。权限字段只是对包内已有权限的声明，不授予新权限；草稿仍由维护者手动确认公开，工作流的 GitHub 权限保持原样。

影响边界也需注意：新增生成步骤若发现包信息错配会使 iOS 任务报错；源和 IPA 公开后任何人都能下载；仍需真机验证安装与更新。若暂时不提供软件源，可去掉生成/上传 JSON 的两个步骤，原有 IPA 手动下载签名方式不受影响。

## 给使用者：添加源

本功能面向 **AltStore Classic / SideStore**，两者使用同一份 Source JSON，无需分别维护。这里的 AltStore 不包含 PAL 公证版；IPA 仍需要使用你自己的 Apple ID 签名，免费账号的到期、应用数量等限制不会因此消失。

维护者首次发布包含 `source.json` 的公开正式 Release 后，可在 AltStore Classic 或 SideStore 的添加源入口中粘贴同一地址（具体按钮位置随客户端版本变化）：

```text
https://github.com/BitterLemonn/MCDevManager/releases/latest/download/source.json
```

然后在应用列表 / Browse 中找到 MCDevManager 安装。已有侧载版本请使用原 Apple ID 覆盖安装，不要先删除应用；操作前备份重要数据。添加源并不会自动恢复删除 AltStore / SideStore 后遗失的管理记录。如果 IPA 包含小组件等扩展，安装时保留需要的扩展。

**上述地址不是本 PR 一提交就生效**：它要求上游采纳后，通过下方 Release 流程发布真实 IPA 与 JSON。草稿、仅 Actions 产物、仅源代码 ZIP 都不是可安装的软件源。后续被设为 Latest 的 Release 也应包含 `source.json`，否则固定链接会失效。

## 给维护者：正常发布

1. 正常运行现有 **Release** 工作流。
2. iOS 任务把本次发布版本与 Actions 运行编号写入实际应用版本/构建号；打包后，从真实 IPA 读取 Bundle ID、版本、构建号、最低 iOS 版本、文件大小、主程序与所有扩展的权限，生成 `source.json`。
3. 现有汇总任务会把 IPA 与 `source.json` 一同上传到草稿 Release。**本改动不自动公开草稿，不修改工作流权限，不触发额外编译。**
4. 核对 JSON 的下载地址、版本与 IPA；确认附件都在后再发布正式 Release，并按需要设为 Latest。

默认下载地址由 `altstore/source.template.json` 中的 `downloadURL` 控制：

```json
"downloadURL": "https://github.com/BitterLemonn/MCDevManager/releases/download/{tag}/{filename}"
```

- `{tag}` 是传给工具的 Release 标签，如 `v1.2.9`。
- `{filename}` 是实际 IPA 文件名，工具会进行 URL 编码。
- 也可填写完整 HTTPS 直链，不使用占位符。不支持需要登录、临时签名或解压 ZIP 才能取得 IPA 的链接。
- 模板不是最终软件源：**不要把 `source.template.json` 的地址加进 AltStore / SideStore**。

## Fork / 自建下载站：只改下载地址

若继续分发同一 Bundle ID 的 MCDevManager，只是下载站或仓库不同，直接修改模板中这一项即可。例如：

```json
"downloadURL": "https://github.com/YOUR_NAME/MCDevManager/releases/download/{tag}/{filename}"
```

然后按相同流程生成。**不用手工填写版本、构建号、大小、最低系统版本和权限**，它们始终以本次 IPA 为准；模板的作者与图标默认保留上游署名，源名、描述等品牌信息可按需要另外定制。

若同时修改了应用 Bundle ID，则模板的 `bundleIdentifier` 也必须一致，工具会阻止错配。不要把完整源 JSON 永久复制后每次只换 URL：旧版本号/包大小可能让 AltStore 判断更新或验证安装失败。

## 不重新打包，给已有 IPA 生成源

使用 macOS 自带 `codesign` 与 Python 3.9+，无需安装第三方 Python 库：

```shell
python3 iosApp/scripts/generate_altstore_source.py \
  --ipa /path/to/MCDevManager.ipa \
  --release-tag v1.2.9 \
  --output build/altstore/source.json
```

把实际 IPA 与生成的 JSON 上传到配置对应的 HTTPS 服务即可。工具本身不签名、不改包、不访问 Apple 账号，也不会上传任何文件。

可选参数：`--template` 使用自定义模板；`--date` 指定带时区的 ISO 8601 发布时间；`--previous` 读取已发布源，保留版本历史。默认 Release 工作流只生成本次版本，不会主动下载外部旧 JSON。

```shell
python3 iosApp/scripts/generate_altstore_source.py \
  --ipa /path/to/MCDevManager.ipa --release-tag v1.2.9 \
  --previous old-source.json --output build/altstore/source.json
python3 -B -m unittest discover -s iosApp/scripts -p 'test_generate_altstore_source.py'
```

## 更新、权限与限制

- AltStore 按 `versions` 数组顺序与真实 `version` / `buildVersion` 判断更新，不按 IPA 文件名或发布日期判断。
- Release 工作流将 `MARKETING_VERSION` 设为本次输入的数字 `X.Y.Z`（允许前缀 `v`），`CURRENT_PROJECT_VERSION` 设为该工作流的运行编号。直接本地打包或使用其他工作流时，也应确保这两个值递增；只改 IPA 文件名不会改变包内版本，本工具不会伪造版本号。重跑同一次 Actions 的编号不变，同版本再次公开新版时应发起新的工作流运行。
- 完全未签名包没有内嵌 entitlement；有临时签名的定制包则读取实际签名权限，包括扩展 App Groups。不要在生成 JSON 后再修改/重签 IPA 却不重新生成。
- `--previous` 会拒绝旧版本置顶和同一版本/构建号指向不同地址或大小的包。历史版本必须保留对应下载文件。
- 发布前仍需在真机验证添加源、安装、覆盖更新和扩展。本工具及 CI 测试不能替代真机验收。
- 软件源不提供托管签名服务，不绕过 iOS 限制，也不会自动接管已安装但不在 AltStore 列表中的应用。

参考：[AltStore 官方源格式](https://faq.altstore.io/developers/make-a-source)、[更新规则](https://faq.altstore.io/developers/updating-apps)、[SideStore 添加 AltSource](https://docs.sidestore.io/docs/advanced/app-sources)。
