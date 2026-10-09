# 开发与维护

当前仓库版本为 `1.3.1 / versionCode 5`，规则见 [AGENTS](../AGENTS.md)，变更与实际验证见 [VERSIONLOG](../VERSIONLOG.md)。本说明面向源码维护；不表示应用已经打包或上线。

## 工程与环境

Gradle 项目 `Chinese_Flashcard`；应用 `Chinese Flashcard`；applicationId `com.example.chinese_flashcard`。Room schema v9 沿用 `chinese-flashcard-v2.db` 与现行迁移链路，不迁移更早课程应用的数据。

模块为 app、core/domain、data、ui、media 和 feature/study、profile、writing、wordlist；模块职责见 [README](../README.md)。沿用 Repository／Flow、MVI 和轻量 MVVM。

使用 Gradle Wrapper 9.4.1、JDK 21、AGP 9.2.1、Kotlin 2.2.10、KSP 2.3.12、Room 2.8.5、Compose BOM 2025.08.01。compileSdk 36.1、targetSdk 36、minSdk 28，字节码目标 11。版本目录和各模块配置为依赖事实来源，不因整理升级依赖。

本地配置 Android SDK 和 JDK；`local.properties`、IDE 状态、缓存及构建产物不入库。Windows 可使用 Android Studio 自带 JBR。遇到 `Unable to establish loopback connection` 时，可在当前 PowerShell 进程中设置 JBR 和 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<项目的 .gradle 目录>`；不要写成永久工程配置。

## 词库与资源

默认词库以根目录 `wordlist.csv` 为准：13,223 行、12 列，26,446 条例句应用。SHA-256 为 `21b2dbf3103513c8b1749a5f2cfc17a94f7df3f9ca63f89748d4f9718a9388a7`。五档数量为 0=2,499、1=6,722、2=2,561、3=637、4=804；学习阶段是 AI 编辑判断，不是官方教材逐词认证。

正式笔顺包共 6,324 字形，CSV 使用 6,322 个不同汉字；6,254 来自 Make Me a Hanzi、70 来自 AnimCJK，保留“唷”及一个当前未用补充字，避免重新划分既有分片。20 个演示词仅辅助手动写字，不初始化为默认词库。真实轮廓和中线不以字体描边或合成笔顺替代。

当前契约见 [CSV_IMPORT](CSV_IMPORT.md)；来源和许可见 [第三方声明](../THIRD_PARTY_NOTICES.md) 与 [版权处理](OPEN_SOURCE_COMPLIANCE.md)。CSV 限制为 32 MiB、单记录 32 KiB、词库总量 20,000、字形 10,000。

`tools/wordlist_rebuild/` 保存基线、ID 映射、教学字段、两轮追加与五档覆盖。重建器递归核对 content_review、两轮 expansion 和 stage_grading 的冻结哈希及审阅记录。目录名是历史再现契约，不改名、不统一换行、不重新编号。

`complete_wordlist.py`、`refine_existing_wordlist.py` 与 `wordlist_difficulty.py` 保留供当前重建器及冻结脚本导入的共用函数。它们的旧生产流程不支持当前格式，不作为日常制作入口；历史过程中不再需要的作者工作队列和生成入口已清理。必要词典来源、最终生产记录、原表及删除依据继续保留，方便追溯授权与内容变化。

历史 expansion／grading 目录中的脚本也可能是哈希冻结的证据；保留不等于其旧作者流程所需缓存仍在本机。日常使用上述三个检查及当前重建入口，历史制作流程需要另行准备对应输入，不能绕过完整性检查。

`tools/wordlist_rebuild/replacements-20261008.json` 在冻结的 2026-10-07 词库上，将“唉呀／哎唷”替换为“爱护／爱心”，保留“哎呀／哎哟”；行数和五档数量不变。独立记录原行、替换教学字段及前后哈希，历史冻结文件不改写。本次按用户要求不维护旧词库 ID 的兼容语义；旧安装的词库更新仍会受到已有 ID 归属检查限制，不能把新 CSV 检查通过当作旧库升级已验证。

## 检查与再现

已有只读检查：

```powershell
python tools/rebuild_wordlist.py --check
python tools/build_wordlist_strokes.py --check
python tools/build_demo.py --check
.\gradlew.bat :app:lintDebug
```

词库检查覆盖结构、引用、内容形状、资源与冻结清单；不代表词义、读音或版权全部正确。笔顺完整来源检查依赖本地 `.gradle/stroke-source/` 的固定原始文件与许可，不在 `--check` 时下载。缺少缓存时应先阅读工具中的固定版本与哈希，按明确授权准备来源；可以用不带 `--check` 的笔顺生成入口获取来源并重建，但该操作会联网并写资源，不能当作只读检查。重建后必须核对 diff 与哈希。

`python tools/rebuild_wordlist.py --apply` 会重写词库和两个生成清单；`--prepare` 会读取固定 CC-CEDICT 缓存并生成作者草稿。只核对再现时，把必需输入和字形复制到独立临时目录，在那里执行 `--apply`，再与工作区 CSV 逐字节比较，不覆盖工作区。

CSV 初始化／追加在事务中处理。导入取消不得落库，版本匹配不重复处理；默认库更新失败保持已有库可用，不清库或降级。学习／复习／续学、收藏／错词练习各自保持状态边界；写字完成不算词汇掌握。

Room 8→9 只新增主题字段、等级累计及每日奖励标记；学习记录沿用现行迁移链路。等级从启用后开始累计，显示默认从 1 级起步。每日打开与 Learn 完成各最多奖励一次；新学／续学或收藏学习通过最后一轮时才计词条次数，待消除错词从 true→false 才计消除次数。计数、奖励与对应学习结果在同一事务内写入，由现有操作锁和卡片阶段检查防重复。等级与主题保存到本地数据库，余数跨天保留。

## 资料管理与验证边界

保留工具读取、manifest 引用和来源证据闭包中的文件；无引用的候选、快照、重复报告和私有附件不提交。删除未跟踪的独有内容前，在仓库外备份并校验哈希。忽略规则不替代已跟踪文件或历史的清理。

2026-10-04／05 的实施、UI、旧词库及构建／安装记录属于历史版本，其原文可在 Git 历史找到。它们不能证明当前版本首次初始化、导入取消、Room 迁移、窄屏大字体、TTS、触摸写字或恢复行为。AI 语言审校、静态检查、lint、构建、安装和设备操作分别记录覆盖。

每轮提交的实际验证见 VERSIONLOG。1.3.0 提交任务执行已有数据检查与 lint；该轮未构建 APK、未签名或覆盖安装设备，未创建发布标签或 GitHub Release。手机操作仅复现更新前的主题问题，不能替代更新后运行验收。

GitHub CI 与自动签名发布的触发条件、Secrets、失败重试和证据边界见 [CI 与 Release](CI_RELEASE.md)。工作流不自动升级版本；维护者按仓库规则更新版本及日志后推送到 `main`。
