# GitHub Actions 与 Release

工作流：[Android CI and Release](https://github.com/Juliano114514/Chinese-Flashcard/actions/workflows/android.yml)。只使用现行 Wrapper、依赖版本和 JDK 21，不自动修改版本配置。

## 日常使用

- push 到 `main` 或向 `main` 发起 PR：准备固定版本的上游笔顺缓存，执行三个现有 Python `--check`、`:app:lintDebug` 和 `:app:assembleDebug`。Debug APK 和 lint 报告保存 14 天。
- `main` push 前后 `app/build.gradle.kts` 的 `versionName` 变大且 `versionCode` 递增：上述 CI 成功后编译 Release，再签名、验证并发布 `v<versionName>`。版本回退或只改其中一个字段会拒绝发布。
- 普通代码或文档提交未改版本：只运行 CI。更新版本时同步 `VERSIONLOG.md`，每个版本保留一个条目；发布说明读取该条目。
- 首次发布或失败重试：在工作流页面点击 **Run workflow**，选择 `main`，勾选 `publish_release`。默认不勾选，手动运行仅检查。无需额外增加版本号，也无需手工推标签。

Release 提供 `Chinese_Flashcard_v<versionName>.apk` 和 `SHA256SUMS.txt`。先创建草稿，附件上传成功后公开；同版本的已公开 Release 不覆盖，标签已指向其他提交时拒绝操作。失败的草稿可在相同提交上重新运行；代码修复后如果草稿尚未发布且属于旧提交，应由维护者检查后处理旧草稿，不能自动替换标签或既有发布。

## 签名 Secrets

在 [仓库 Actions Secrets](https://github.com/Juliano114514/Chinese-Flashcard/settings/secrets/actions) 中配置以下名称：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 原始字节的 Base64 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | keystore 内私钥别名 |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

签名材料不写入 Gradle 配置、不提交仓库、不进入缓存或附件。仅 `main` 的发布 job 可以读取 Secrets；PR 检查不签名、不发布。私钥临时落在 runner 临时目录，退出签名步骤时删除；密码通过 `apksigner` 的环境变量参数读取。

工作流默认仅有 `contents: read`；发布 job 单独声明 `contents: write`，使用 GitHub 自动提供的 `GITHUB_TOKEN` 创建标签和 Release，无需另建 PAT 或将仓库默认权限改成可写。第三方 Actions 固定完整 commit SHA；Gradle setup 同时校验 Wrapper，Wrapper 下载沿用已配置的 SHA-256。

## 验证边界

CI 的笔顺准备只下载脚本已固定的来源并核对图形哈希，不重写词库或应用资源。发布检查 APK 签名、ZIP 对齐、applicationId、版本及非 debuggable 状态；下载完整性可用 `SHA256SUMS.txt` 核对。

不新增测试代码，不运行设备安装或 UI 验收。签名后的 Release APK 与此前 Debug APK 证书不同，不能直接覆盖 Debug 安装；同一发布私钥签名的后续 Release 才能保持签名兼容。设备数据处理需要独立安排，不由 CI 卸载应用。源码和构建检查也不代替语言、版权或运行验收。

实现参考：[GitHub Token 权限](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)、[AGP 9.2 工具兼容](https://developer.android.com/build/releases/agp-9-2-0-release-notes)、[APK 对齐与签名顺序](https://developer.android.com/tools/zipalign)。
