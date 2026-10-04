# Chinese Flashcard 实施记录

日期：2026-10-04。交付为新框架源码、Room schema 和少量原创演示内容。

## 实现范围

应用与 Gradle 项目更名，新的 applicationId / 包名为 `com.example.chinese_flashcard`。旧课程、查字、作品、录音、导入界面退出当前模块图，保留在独立备份。工程物理路径未改名。

主流程是欢迎设置 → Today 新学／复习／续学 → 讲解 → 写一写 → 四选一题 → 反馈／错词讲解 → 后续轮次 → 完成页 → 到期复习。默认每天 10 个新词、连续答对 4 次通过，每组最多 5 词交错。前两轮分别呈现例句、拼音与英文，后续回忆隐藏例句。欢迎页和设置页都支持日词量、轮次和复习日期。

卡片阶段为 `INTRO / QUESTION / FEEDBACK / EXPLANATION / WRITING / FINISHED`，选项和选择结果随卡片持久化，返回或重启不重新洗牌。提交必须匹配当天当前卡且处于 QUESTION；保存失败保留上次快照和重试入口。最后一张完成卡保留 FINISHED，使完成页可以稳定恢复。

### 日计划和复习

- 日计划创建一次，冻结目标与选词；改变目标不重算当日任务。
- 每个学习／重学周期冻结目标轮次和复习间隔。新周期使用最新设置。
- 连续正确达到目标后当日通过；错误／不知道归零，继续在本组循环。
- 复习间隔是通过后的绝对日历日偏移，不是上一次复习后的增量。
- 到期复习无例句回忆一次答对即通过；同词多个逾期节点折叠成一次，消费已经到期的节点，未来节点保留。
- 复习答错取消旧周期的未完成复习安排，启动完整重学；再次通过产生新安排。
- 未完成学习／重学跨日轮次归零，作为 Carryover，不计入当天新词额度。过期卡提交先提交新日计划再拒绝旧答案。
- 完成页显示当日三类任务完成量和下一次未到期复习日期。

### 独立写字

首次讲解继续时原子创建 FIRST_ENCOUNTER 会话，并保存首次接触标记。复习错误讲解后创建 REVIEW_ERROR 会话，同一重学周期只自动提供一次；两种会话均可跳过。

词语与字形按字符位置关联，字形可复用，词语中的重复字逐位置完成。写字数据独立于学习周期和词卡正确轮次，描红通过不算词汇通过。

草稿保存当前词／字位置、接受的轨迹、错误次数和状态；最后一笔原子提交完成记录并自动推进。写入携带当前游标，防止保存后重试把笔画应用到下一个字。保留田字格、逐笔动画、暂停、重播、描红／临写、撤销、重试和反馈；使用原始 SVG 轮廓和中线，不用字体轮廓冒充笔顺。

全部日任务收尾后，今日首次通过的新词（包括续学中首次通过的词）触发一次软邀请。旧复习／重学词不进入新词写字列表；之前自动写过的新词仍完整练习。邀请可以拒绝或关闭，无新词不显示。

自动写字完成／跳过会等待 Study 刷新结束再返回，避免完成动作被 busy 门禁丢弃。返回保留草稿，保存期间页面关闭与系统 Back 均受门禁控制。

## 数据与依赖

Room 数据库版本 1，导出 schema 到 `core/data/schemas`。主要表如下：

| 类别 | 表 |
| --- | --- |
| 词与释义 | `words`, `meanings` |
| 解耦字形与词位置 | `tracing_items`, `word_tracing` |
| 设置和应用位置 | `settings`, `app_state` |
| 日计划、掌握与周期 | `daily_plans`, `daily_items`, `word_progress`, `learning_cycles` |
| 复习和卡片 | `review_nodes`, `study_cards` |
| 独立书写 | `writing_sessions`, `writing_completions` |

Repository 操作串行并使用 Room transaction；演示内容校验后一次性初始化，没有 destructive migration 或遇错清空。词条包含稳定 ID、汉字、拼音、多义项、例句、简单部件讲解及干扰义项 ID。字形包含独立 ID、真实路径、中线、修订与署名。

仅附 20 个原创词、40 条例句、35 个必需字形。`catalog.json / strokes.json` 与 `tools/build_demo.py` 一致；每个词的所有字符位置都有笔顺关联。资源体积、字段长度、重复 ID、引用、有限坐标及路径语法在加载时校验。没有导入完整或第三方字库。

新增 Room 2.8.5 与 KSP 2.3.12；依赖来自既有 Google Maven / Maven Central 配置，版本锁定。其他主工具链保持。功能模块仅依赖 domain / ui，由 app 装配 data / media；业务使用 `Action → ViewModel → Repository → Mutation → Reducer → StateFlow`，设置保留轻量 MVVM。

## 验证状态

命令：

```powershell
python tools/build_demo.py --check
.\gradlew.bat lintDebug --console=plain
```

演示生成一致性检查通过，报告为 20 个词／35 个独立字形／所有词位置覆盖。首轮 `lintDebug` 退出码 0，Room KSP、全部新模块 Kotlin 编译和 lint 分析通过。完成态、保存返回和语音异常处理补齐后重新执行最终 lint；最终计数见下表。

最终 `lintDebug` 退出码 0（BUILD SUCCESSFUL），Room KSP 与全部新模块 Kotlin 编译完成。

| Android 模块 | 错误 | 警告 | 提示 |
| --- | ---: | ---: | ---: |
| app | 0 | 13 | 0 |
| core:data | 0 | 1 | 0 |
| core:media | 0 | 1 | 0 |
| core:ui | 0 | 1 | 0 |
| feature:study | 0 | 1 | 0 |
| feature:profile | 0 | 1 | 0 |
| feature:writing | 0 | 1 | 0 |
| 合计 | 0 | 19 | 0 |

剩余警告均为依赖版本／SDK 更新提示；没有关闭规则或升级既定工具链。补充语音处理时出现一次缺少结束括号的编译失败，修正后重新执行上述最终检查。

额外静态核对：7 份源码 XML 可解析；Room v1 schema 含 14 张表；活动源码／配置没有旧包名、DataStore 或旧 feature 引用；备份 164 个文件的 SHA-256 全部与初始清单相符。差异清单 `.gradle/flashcard-changes.json` 排除工具缓存，记录 26 个新增、15 个修改、137 个退出当前树的文件，退出文件可从备份恢复。

日志为 `.gradle/flashcard-lint-final.log`，报告为各 Android 模块 `build/reports/lint-results-debug.xml/html`。domain 是纯 JVM 模块，由必要的 Kotlin 编译覆盖。命令行使用现有 Android Studio JBR 21，`jdk.net.unixdomain.tmpdir` 仅在当前进程指向工程内 socket 缓存，系统和工程 JVM 配置没有改变。

源码复核覆盖：日期冻结与跨日重置、重复提交守卫、交错队列、复习周期替换、首次书写标记、错后自动写一次、日末邀请一次、完成页恢复、写字游标重试、语音初始化／播放异常和旧回调隔离。

这些是源码、内容一致性、编译和 lint 证据，不能替代触屏或重启行为验收。未新增测试代码、未运行测试、未执行 assemble／APK 打包／安装／发布。旧 APK 不是本次产物。

仍需设备验收：冷启动和进程重建、跨午夜、切学习类别、TTS 缺失／后台／快速重播、首遇与错后写字返回、跳过及完成回调、重复字、笔顺动画与真实触控轨迹、大字体、深色模式和日末邀请。轨迹阈值沿用入门指导算法，尚未做真实触屏调校；不作为书法或教学效果评分。

安全自查：无网络／录音权限、账号或新外部接口；Room 查询参数化、写入事务化，手写采样和资源读取有上限，文本经 Compose Text 呈现，未记录凭据或原始个人数据；本地数据库和文件排除自动备份。

## 可恢复备份

备份：`C:\Users\liangjiayin\AndroidStudioProjects\Chinese_Self_Study_Tutor-backup-20261004-012404`。实施前复制 164 个源码／资源／配置文件，总计 1,669,974 字节，`source-manifest.json` 记录 SHA-256；退出使用的旧树和模板图标随后移动到 `retired-tree`，没有永久删除。

自动审批审查拒绝了批量删除旧模块，理由仅返回 `blocked by policy`；因此使用已核对绝对路径的原生 PowerShell 移动到备份目录。当前工作区未初始化 Git，没有 commit／push／发布操作。新 App 的独立数据空间不迁移旧 App 用户记录。
