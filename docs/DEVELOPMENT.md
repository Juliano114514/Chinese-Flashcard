# 开发与维护

中文朗读保留内置 MeloTTS FP32 声线：单字采用统一上下文、时长截取及弱音重试，短词校正电平，长句沿用原前端边界＋0.7 倍语速。23 个已核听问题读法以固定 PCM 资源优先提供，资源准备与试听记录见 [中文语音维护](MELO_TTS_EVALUATION.md)。构建前执行 `python tools/build_chinese_resources.py --prepare` 准备固定模型及只增加时长输出声明的派生缓存；Gradle 只校验、复制派生模型，不自动下载。应用运行不联网获取模型。

当前仓库版本为 `1.7.0 / versionCode 24`，规则见 [AGENTS](../AGENTS.md)，变更与实际验证见 [VERSIONLOG](../VERSIONLOG.md)。本说明面向源码维护；实际打包、安装与发布状态分别记录。

## 工程与环境

Gradle 项目 `Chinese_Flashcard`；应用 `Chinese Flashcard`；applicationId `com.example.chinese_flashcard`。Room schema v18迁移实现（已有schema快照，本次提交未重新执行KSP）沿用 `chinese-flashcard-v2.db` 与现行迁移链路，不迁移更早课程应用的数据。

模块为 app、core/domain、data、ui、media 和 feature/study、profile、writing、wordlist；模块职责见 [README](../README.md)。沿用 Repository／Flow、MVI 和轻量 MVVM。

`build-logic` 是独立的 Gradle included build，不增加运行时模块。`flashcard.android.application`／`flashcard.android.library` 统一 compileSdk 36.1、minSdk 28 和 Java 11 字节码；`flashcard.android.compose` 只应用现有 Compose 插件并开启 Compose。构建插件与 ONNX 版本复用根版本目录，BOM 和 `api`／`implementation` 仍由模块声明。应用版本、targetSdk、R8、模型压缩要求、Room/KSP 和资源生成任务保留在所属模块。

学习提示音由 feature/study 自己持有并沿用原有生命周期，Study 不再依赖 core/media。应用级语音、统一预加载及唯一合成队列仍由 core/media 提供。app 的启动 ViewModel 与许可页面分别维护在独立文件，导航和实例作用域不变；数据层共享事务、学习状态机及语言隔离未拆分。

`StudySettingsRules` 是轮次、复习日期、语音参数范围与默认值的产品规则来源，欢迎页、设置页和模型校验共用。`DailyWordChoices` 继续限制新设置为 5–50、步长 5，历史每日词量仍接受 1–100。Room 历史迁移及列默认值、磁盘缓存底层保护范围保持原样，不与产品编辑范围混合。

2026-10-16 的 Replay 与架构整理保持 `1.5.3 / 13`，未提交或发布。实际执行 `:app:lintDebug :app:lintRelease`，两者均为 0 错误、13 条既有告警，无新增诊断：12 条版本／SDK 提示和 1 条图标资源目录提示。原基线的 compileSdk 更新提示随配置迁入独立构建工程后不再由 app lint 直接报告，不代表 SDK 升级或兼容性修复；未关闭检查规则。Gradle 实际配置检查确认八个 Android 模块的 SDK、Java／Compose 开关，以及应用版本、R8、native 压缩、模型不压缩与资源生成任务保留。641 个词库、模型／语音资源及 Room schema 文件的 SHA-256 与改动前一致；启动、许可页面和提示音主体迁移比对一致。未新增测试代码，未执行自动化测试、APK 打包、设备安装、运行排版或试听验收。安全自查：未新增应用权限、网络入口、运行依赖或敏感日志，学习事务、语言隔离及语音调度链路未改动。

使用 Gradle Wrapper 9.4.1、JDK 21、AGP 9.2.1、Kotlin 2.2.10、KSP 2.3.12、Room 2.8.5、Compose BOM 2025.08.01。compileSdk 36.1、targetSdk 36、minSdk 28，字节码目标 11。版本目录和各模块配置为依赖事实来源，不因整理升级依赖。

本地配置 Android SDK 和 JDK；`local.properties`、IDE 状态、缓存及构建产物不入库。Windows 可使用 Android Studio 自带 JBR。遇到 `Unable to establish loopback connection` 时，可在当前 PowerShell 进程中设置 JBR 和临时目录参数；路径含空格时，`JAVA_TOOL_OPTIONS` 中的属性值必须保留双引号，例如 `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir="D:\AndroidStudioProjects\Chinese Flashcard\.gradle"'`。缺少引号会导致 JDK 探测 JVM 启动失败，Gradle 可能将其包装为 `jvms/metadata.bin` 缓存写入错误；先查看异常根因，不要直接清缓存。不要写成永久工程配置。

## 包体优化配置

Release 开启 AGP 自带的 R8 与资源收缩；Debug 保留原调试配置。当前采用保守 keep：完整保留 `com.example.chinese_flashcard.**` 与 `ai.onnxruntime.**` 的类和成员，阻止 R8 删除、改名或优化这些受保护定义。前者保护 Room `_Impl` 查找、持久化枚举名称、Compose 保存状态与业务接口；后者保护 ONNX JNI 通过名称访问的类、构造器和成员，也符合 [ORT 官方 Android 要求](https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work)。第三方依赖仍使用各自的 consumer rules，Release 的 `mapping.txt` 位于 `app/build/outputs/mapping/release/`，排查该包时使用同一次构建的映射。

app 与 core/media 的 `noCompress` 精确指定 `chinese-speech/model-duration.onnx`：中文模型继续通过 `assets.openFd` 映射，只打包这一份模型。派生文件只比原模型增加 40 字节的时长输出声明，原算子、权重、输入和声线元数据一致；来源与派生哈希分别固定。英语模型可 ZIP 无损压缩，沿用 `AssetManager.open` 流复制、大小限制、哈希验证及缓存文件路径。英语模型首次复制增加解压工作，已有模型缓存继续复用。

中文模型做过本地 JBR `java.util.zip.Deflater(6, true)` 无损压缩试算，从 170,429,550 到 157,834,154 字节，仅节省 12,595,396 字节（约 12.01 MiB）；Python zlib-ng 的同档试算约省 12.15 MiB，压缩实现不同会有小幅差异。若解压为独立文件再映射，将新增约 162.53 MiB 落盘，并引入首次准备、空间不足与中断恢复链路，因此保留现有 mmap，不新增应用冷启动解压步骤。此结果只衡量压缩量，不代表设备解压耗时或音质验证。

native 采用 `packaging.jniLibs.useLegacyPackaging = true`：四种 ABI、12 个 `.so` 全部保留，以 ZIP 无损压缩，Android 在安装时提取适用 ABI 的库，沿用现有 JNI 加载与推理接口。两个变体的 native 解压字节哈希均与基线相同；native 条目压缩在 Debug／Release 分别节省约 77.084／81.657 MiB。这个选择减少 APK 文件体积，但安装时需要提取 native，不能据此推断安装后的磁盘占用或启动性能同步改善。官方默认直接映射与提取方式的区别见 [native 打包说明](https://developer.android.com/topic/performance/reduce-apk-size#avoid-extracting-native-libraries)。

当前 `minSdk=28` 已原生支持 multidex，不添加兼容库；AGP 9.2.1 随资源收缩自动启用整合的资源优化，不叠加旧 shrink-R 插件或旧 R8 开关。见 [R8 官方配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) 和 [multidex 说明](https://developer.android.com/build/multidex)。

2026-10-14 按官方仓库与文档评估大厂开源方案，未引入额外插件或运行依赖：

| 方案 | 当前项目的取舍 |
| --- | --- |
| 字节 [ByteX](https://github.com/bytedance/ByteX) | 官方仓库已归档，依赖旧 `registerTransform`；[Transform API 在 AGP 8.0 移除](https://developer.android.com/build/releases/gradle-plugin-api-updates)，接入需迁移处理框架，跳过。 |
| 滴滴 [Booster](https://github.com/didi/booster) | master 兼容表有 AGP 9.0.x／5.2.0+，但未证明本项目 9.2.1 可直接用；[发布页](https://github.com/didi/booster/releases)仍标 v5.1.0，[5.x 迁移说明](https://booster.johnsonlee.io/zh/migration/)称部分旧 Task 不再可用。R 内联收益与 R8 重叠，暂不接入。 |
| 微信 [AndResGuard](https://github.com/shwenzhang/AndResGuard)／腾讯 [Matrix APK Checker](https://github.com/Tencent/matrix#apk-checker-usage) | 前者会重写资源表、路径并重新打包，当前 `res/`＋资源表仅约 0.75 MiB，收益空间有限；后者可独立分析 APK，不是自动瘦身器。现有 ZIP、资源及哈希核验覆盖主要问题，暂不增加工具。 |
| Meta [ReDex](https://github.com/facebook/redex) | 二次改写 DEX，需另验映射、Profile、签名与运行路径；[Google 提醒此类处理可能影响 R8 优化](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)，不叠加。 |
| 微软 [ORT 定制构建](https://onnxruntime.ai/docs/build/custom.html) | 可按模型裁剪算子，但要维护四 ABI 构建；minimal build 还需模型格式转换，关闭异常会改为 abort。保留当前 ORT 1.31.0。 |
| 阿里 [MNN](https://github.com/alibaba/MNN)／[Atlas](https://github.com/alibaba/atlas)、腾讯 [ncnn](https://github.com/Tencent/ncnn) | 替换推理引擎或引入动态组件框架会改变模型、类与资源加载链路，超出本轮低侵入范围。 |

源码精简仅将 `SpeechDiskCache` 的同一锁内临时占用计算缓存为局部 `temporaryReserved`，复用临时项集合的求和结果；不改容量公式、淘汰顺序与保护条件、锁范围或缓存格式。未做其他业务重构，Room schema 与迁移链路不变。

历史第一阶段保持 `1.5.0 / 10`，以保存现有改动的本地 commit `71364fb` 为基线，仅开启保守 R8／资源收缩及英语模型压缩：Debug 为 395,148,415 字节（376.843 MiB），Release 未签名包为 362,331,860 字节（345.547 MiB）。这些是本地历史对照，不是当前交付包。

`1.5.1 / 11` 启用 native 压缩时，2026-10-14 本地构建结果如下。这些包早于本次短词／单字策略接入，不能作为新增语音改动的 APK 或手机验证。基线为上述 commit 构建的 `1.5.0 / 10`；两次构建使用同一依赖环境，对照包包含版本变化与上述局部缓存改码。表中 Release 大小使用未签名构建产物，手机使用的 debug key 签名副本仅作运行验证。

| 变体 | 本地基线字节 | 优化后字节 | 优化前后 MiB | 降幅 |
| --- | ---: | ---: | ---: | ---: |
| Debug | 398,668,819 | 314,245,027 | 380.200 → 299.687 | 21.18% |
| Release（未签名） | 385,587,839 | 276,630,076 | 367.725 → 263.815 | 28.26% |

`:app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease` 实际通过，两种 lint 均为 0 错误、14 条依赖／SDK 告警；五项现有词库、笔顺、演示、中英语音检查通过。基线 APK、构建日志、APK 静态对比和资源表记录在忽略目录 `.gradle/package-optimization/`；签名材料与设备检查产物不入库。

静态对照确认：Manifest 仅版本与 `extractNativeLibs` 变化，包名、权限与入口保留；全部 615 个业务 assets、12 个 native 库的路径及解压内容哈希保持一致，749 个业务资源的 ID、配置和值保留，264 个业务 `res/` 文件字节一致。Release 共 617 个 assets，其中 2 个编译 Profile 随 DEX 布局更新；Debug 共 615 个。词库、模型与四 ABI 保持完整。当前业务源码已有上述局部缓存精简，不能沿用第一阶段的 Debug DEX 全字节一致结论。

第一阶段另按 JVM 编译输入核对过 Release：840 个自有类、84 个 ONNX 原始类全部存在，原有非 synthetic 普通字段／方法名称及签名保留，33 个枚举的具名常量完整；29 处省略的静态初始化仅给本类 Compose `$stable` 赋整数常量，最终 DEX 静态初值逐项相同。记录为 `.gradle/package-optimization/source-and-resource-evidence.json`。R8 的 lambda 辅助类、访问范围与 Kotlin Metadata 表示可能变化，静态保留检查不证明所有方法体或运行行为等价。

设备验证在静音下完成代表路径检查：Debug 与 debug key 签名的非 debuggable Release 均通过 `adb install -r` 覆盖安装，保留首次安装时间、数据库、WAL 和 30 已学／3 学习中的计数。已观察首页预加载、两种学习方向的已有状态、词条及例句播放、写字、个人页、设置和缓存占用正常；两种变体均生成 MeloTTS 44,100 Hz 与 Kitten 24,000 Hz 缓存，Release 期间新生成的 20 个样本通过内部 SHA 校验且不是系统语音回退。英语首次提取模型 SHA 和安装后 3 个 arm64 库 SHA 均匹配原内容。Release 两次冷启动观测为 174／310 ms，仅是该设备的两个样本，不能作性能改善结论。最终恢复原语言配置，保留 `1.5.1 / 11` Debug，返回系统桌面并保持媒体音量 0。未覆盖全部词条、其他设备／ABI、存储耗尽或长期压力；静音检查不构成音质验收，不能宣称所有运行路径百分之百无回归。具体结果及日志提示见 [版本日志](../VERSIONLOG.md)。

未裁剪 ABI、量化或替换模型，也未收紧业务 keep；这些选择需要更充分的设备与声音验证。现有 Compose 仅依赖 `material-icons-core`；主要 ONNX native 已不含调试符号，额外符号裁剪空间有限。安全自查：未新增权限、依赖、网络调用或敏感日志，未将签名材料或检查产物纳入源码。

## 词库与资源

默认词库以根目录`wordlist.csv`为准：18,500行、12列、37,000例句、32,356,588字节；SHA-256 `6de1a9877f79ea7a809fd40d41383c15a86ce93580cb2954cf1344515d15639a`。五档数量为0=2,193、1=6,422、2=6,970、3=2,757、4=158，恢复原Stage 0–4及五套主题。当前源码修订尚未打包，单字／组词／拆解与实际验证见[五档与词库精修](WORDLIST_REFINEMENT_20261027.md)；此前1.6.9安装对应历史六档快照。

旧13,223个ID全部保留，扩充时新增5,277个ID；本轮仅更新既有条目，以显式白名单替换词头，不删除ID。冻结基线 ID／字节指纹见 `tools/vocabulary_v2/baseline-index.json`；当前指纹见 `docs/wordlist-validation.json`。18,500词扩充、内容修正、来源及兼容实现的历史说明见[1.6.8词库修订](VOCABULARY_REVISION_20261027.md)；当前罕度与内容见[精修报告](WORDLIST_REFINEMENT_20261027.md)。

笔顺只使用固定 Make Me a Hanzi／AnimCJK 真实轮廓及中线，模型和声线保持既有固定版本；字形、英／中文读音和 IPA 资源按当前快照重建并单独检查。20 个 demo 词仅辅助手动写字，不计默认词库行数。资源完整性检查不代表新词音频已经真人核听。

当前契约见 [CSV_IMPORT](CSV_IMPORT.md)，来源与许可见 [第三方声明](../THIRD_PARTY_NOTICES.md) 与 [版权处理](OPEN_SOURCE_COMPLIANCE.md)。单个 CSV 仍为 32 MiB、单记录 32 KiB、20,000 行、字形 10,000。普通导入沿用总量 20,000 上限；默认库升级为保留此前已导入词，允许旧库与新默认库有界合并到最多 40,000 条。默认词与自定义词独立归属；ID 冲突使用独立内部 ID，旧用户词和进度不被覆盖。

当前仅根目录 `wordlist.csv` 是正式词库。`tools/expand_vocabulary_v2.py` 可用锁定来源、Git 历史基线及明确编辑修正再现候选；原始公开语料只缓存于`.gradle/vocabulary-v2/`及`.gradle/wordlist-refinement-20261027/`，不把全集打包进应用。正式保留的来源索引、修正记录和原句逐句署名位于 `tools/vocabulary_v2/` 与 APK 的 `wordlist-sources/`。

`tools/refine_wordlist.py --check`核对当前五档、每词内容、全部稳定ID与显式替换许可；`regrade_vocabulary.py --check`转调该入口，六档决定留作历史输入。当前逐词表为`tools/vocabulary_v2/wordlist-refinement-full-review.csv`。完整生成器先复原锁定扩充输入，再应用五档和词头／内容清单；只在新释义影响选项唯一性时修复对应干扰引用。

`tools/rebuild_wordlist.py --check` 只读核对完整结构、旧 ID 保留和当前 manifest 的哈希、大小及统计；不自动接受新指纹。新增／修订 CSV 后必须先确认内容和兼容不变量，再显式更新快照、标签、署名和相关语音／笔顺索引。

## 检查与再现

已有只读检查：

```powershell
python tools/rebuild_wordlist.py --check
python tools/build_wordlist_strokes.py --check
python tools/build_demo.py --check
python tools/build_english_resources.py --check
python tools/build_chinese_resources.py --check
.\gradlew.bat :app:lintDebug
```

词库检查覆盖结构、引用、内容形状、字形索引覆盖与当前快照清单；不代表词义、读音或版权全部正确。笔顺完整来源检查依赖本地 `.gradle/stroke-source/` 的固定原始文件与许可，不在 `--check` 时下载。缺少缓存时应先阅读工具中的固定版本与哈希，按明确授权准备来源；可以用不带 `--check` 的笔顺生成入口获取来源并重建，但该操作会联网并写资源，不能当作只读检查。重建后必须核对 diff 与哈希。

词库维护以当前 CSV 为唯一输入；内容变化时必须同步当前快照清单，并保留稳定 ID 与来源说明。只读检查不生成或改写 CSV。

CSV 初始化／追加在事务中处理。导入取消不得落库，版本匹配不重复处理；默认库更新失败保持已有库可用，不清库或降级。学习／复习／续学、收藏／错词练习各自保持状态边界；写字完成不算词汇掌握。

Room 8→9 只新增主题字段、等级累计及每日奖励标记；学习记录沿用现行迁移链路。等级从启用后开始累计，显示默认从 1 级起步。每日打开与 Learn 完成各最多奖励一次；新学／续学或收藏学习通过最后一轮时才计词条次数，待消除错词从 true→false 才计消除次数。计数、奖励与对应学习结果在同一事务内写入，由现有操作锁和卡片阶段检查防重复。等级与主题保存到本地数据库，余数跨天保留。

## 资料管理与验证边界

保留当前词库、工具读取的运行资源与来源许可声明；无引用的候选、快照、重复报告和私有附件不提交。删除未跟踪的独有内容前，在仓库外备份并校验哈希。忽略规则不替代已跟踪文件或历史的清理。

2026-10-04／05 的实施、UI、旧词库及构建／安装记录属于历史版本，其原文可在 Git 历史找到。它们不能证明当前版本首次初始化、导入取消、Room 迁移、窄屏大字体、TTS、触摸写字或恢复行为。AI 语言审校、静态检查、lint、构建、安装和设备操作分别记录覆盖。

每轮提交的实际验证见 VERSIONLOG。1.3.0 提交任务执行已有数据检查与 lint；该轮未构建 APK、未签名或覆盖安装设备，未创建发布标签或 GitHub Release。手机操作仅复现更新前的主题问题，不能替代更新后运行验收。

GitHub CI 与自动签名发布的触发条件、Secrets、失败重试和证据边界见 [CI 与 Release](CI_RELEASE.md)。工作流不自动升级版本；维护者按仓库规则更新版本及日志后推送到 `main`。

## 双向学习与英语语音

Room v10 将旧 v9 学习记录全部归入 CN，新增按目标语言的学习上下文、进度、计划、复习与会话。收藏书签及成长等级共用；日奖励以全局日期去重。系统语言 EN/CN 只控制界面，学习目标 CN/EN 决定题面及进度。迁移不清库，不改变 CSV。

首页语言入口使用独立 `LanguagesDialog`，设置页继续保留两项编辑。中文目标的题干是中文词／拼音，选项为英文释义；英文目标的题干是逐行英文义项，选项为中文词／拼音。题干与自动朗读始终采用目标语言，答题前的例句不显示对照答案。英文学习页隐藏写字入口，词库详情仍保留手动中文写字。

UI 文案维护在 `core/ui/src/main/res/values/strings.xml`（英文默认）及 `values-zh/strings.xml`（中文）同名资源中。Compose 用 `uiString`／`uiPlural` 调用标准 `stringResource`／`pluralStringResource`；非 Compose 事件用 `uiText`。`AppLanguageProvider` 为当前界面提供局部语言配置，保留设备字体、密度和主题设置。错误、CSV 诊断与描红反馈使用 `UiMessageKey`，不以英文句子或正则反查翻译；旧数据库中的描红反馈只在读取时映射为键，草稿不清除。

英语资源由 `tools/build_english_resources.py` 生成；`--check` 只读验证冻结 CSV 哈希、模型／音标来源、分片哈希、token 覆盖与许可引用。资源生成和模型准备需要明确的联网下载与写入授权，不发生在普通应用构建或运行时。配置、固定版本、覆盖和复现方法见 [英语语音维护](ENGLISH_SPEECH.md)。APK 仅使用 ONNX Runtime、Kitten ONNX、Bella 原始浮点声线及发音 JSON，不携带构建期 phonemizer、Python 或其 GPL 运行代码。

本次功能未升级应用版本。实际 lint／数据检查与未覆盖的设备行为以本轮交付为准；词库来源的既有授权待核实项继续保留。

## 继续复习

当天到期复习完成后，首页入口主标题保持 Review，副标题显示 `Review {N} more`（N 为下一批实际可抽取数量）；复习完成页提供 Continue review 按钮。没有到期词但已有可用已学词时也可开始。每次点击从当前学习方向中随机抽取已学词，批内不重复，批次之间可重复；排除 skip、正在普通重学或阶段重复学习的词，以及当天已标记为仅浏览的复习词。没有可用词时不提供继续入口。

Settings 的“继续复习词数”默认 10，可输入 1–50 的整数，不足一批时使用全部可用词。设置在创建下一批时读取，已开始的批次不重抽。随机词追加到当天 Review 队列，保留此前完成记录及 Learn 配额；答对一次通过，答错、写字和重新排期沿用现有 Review 状态机。创建批次与选卡在同一事务内，校验日期、学习方向、上下文版本和原复习计划数，防止重复点击或过期请求追加批次。

Room 14→15 仅向 settings 新增默认 10 的 `reviewBatchSize` 列，已有学习数据保留；功能实现阶段未升级应用版本，随后按用户打包请求升级到 `1.6.2 / 16`，实际产物记录见 VERSIONLOG。

2026-10-17 实际执行 `:app:lintDebug` 成功，0 错误、13 条既有告警，诊断与执行前报告一致。KSP 导出 schema v15；结构对比确认只有 settings 新增该列，SQLite 内存核对确认原设置值保留、迁移后的表结构与 v15 一致。中英文资源解析、键名一致性与 diff 检查通过。未新增测试代码，未执行自动化测试、APK 打包或设备验收。

## 答题跳转

答对后始终手动点击 Next，默认不等待反馈音频结束。设置页“学习”中的“答对后需播放完音频才能跳转”默认不勾选，勾选后答对时等待现有反馈播放流程结束再开放 Next，仍不自动跳转。提前点击 Next 沿用页面切换时的播放取消机制。答错仍沿用原来的播放及解锁流程，不受该选项影响；音频关闭、失败的处理沿用原逻辑。Room 13→14 只新增默认 false 的设置列，旧用户也采用新默认，不改学习记录。

2026-10-17 实际执行 `:app:lintDebug :app:lintRelease` 成功，两者均为 0 错误、13 条既有告警，无新增诊断。KSP 已导出 schema v14；静态对比确认仅 settings 新增该列，其他实体不变，SQLite 核对迁移后的设置表与导出结构一致且保留原设置值。本轮未执行自动化测试、APK／AAB 打包、设备安装或运行验收；应用版本保持不变。

## 朗读与讲解偏好

设置页的朗读选项进入二级页，枚举首页、学习题干、答题前例句、初见与讲解自动朗读、答题反馈、词条详情自动朗读、点击词语、点击英语释义、点击例句及写字页共十个场景。各自可选不朗读、仅英语、仅汉语或两种都读，选择立即保存到本地。自动场景未单独设置时沿用当前学习语言；手动词语默认汉语、释义默认英语、例句默认双语。只有实际存在的对应文本可参与朗读，答题前不朗读对照答案；写字单字没有英语对应项时跳过英语。关闭声音不会阻塞答题反馈或推进。

讲解选项控制例句的原句、拼音、拆分直译和英语翻译。取消拆分直译后，原句与拼音使用整句；未全选时点击例句可展开或恢复，全部取消仍有展开入口。答题前展开仅恢复目标语言的内容。Room 10→11 只向设置表添加朗读模式与显示选项，不改词库和学习记录。

学习页保持词头、释义、例句、本义／引申义、部件及原有操作入口，重新组织字号、留白和反馈层级；底部操作固定，大字体时双按钮上下排列。本轮源码／lint 与资源检查不能替代真机语音延迟、音质、迁移和排版验收。

## 语音缓存与预加载

设置主页的“语音与缓存”保存学习预加载词数（默认 5，范围 0–20，0 关闭首页入口及后续词预加载，保留当前页按需准备）、语音缓存容量（默认 256 MiB，范围 64–2048 MiB）、首页 Shuffle 候选数（默认 5，范围 1–20）。学习页换卡后按该词数滚动准备后续词，设置修改立即应用；Shuffle 使用独立数量配置。Room 11→12 添加这三项设置；12→13 将旧默认 3 升为 5，其他已保存值及全部学习记录保持原样，同时同步设置表的默认值。

首页可见且学习预加载词数大于 0 时，通过只读 Repository 查询取得当前学习方向／阶段下各可进入模式的首词，包括恢复入口；空闲时只预热中文词音、英文义项及拆分项，不加载例句。查询沿用 Resume、日任务、练习会话和 Learn More 的选择顺序，不创建计划、卡片或会话，不改变学习进度。语言、阶段或首页状态变化时更新资源窗口，离页释放该来源的需求。

Learn、Review、Collections 和 Mistakes 共用滚动窗口：先准备当前词及后续 N 个不同词的词音，再按距离补齐例句；N 沿用学习预加载词数。初见、答题、反馈与讲解之间切换不重启窗口；换卡、学习上下文或数量变化时差量更新，重叠资源继续复用。沿用现有队列对学习方向、阶段、跳过词与练习范围的限制，不创建计划或推进学习进度。当前卡片的加载提示只反映当前词音及实际播放准备，后台补例句不延长整张卡的加载提示。Wordlist 列表不合成语音，打开详情后复用词音并补齐例句。

首页 Shuffle 候选队列只准备 HOME_WORD 场景实际允许的词音，不加载例句；候选保留在 ViewModel 中。离页取消未完成准备与待兑现点击，回来后重新核对实际缓存并补齐缺失项，不重新随机整个队列。Shuffle 文案保留原样，第二行以 12sp 显示 `x ready`，统计未展示的可播放候选数。无就绪资源时转圈禁用并在就绪时自动兑现一次点击；全部失败时恢复重试。

同一行左侧 Replay 读取点击时的当前展示词，复用 Shuffle 的词音请求构造和 HOME_WORD 朗读设置，不消费候选、不加载例句、不改变学习进度。无有效词、加载或忙碌、阶段不匹配、Shuffle 等待以及 Replay 准备中时禁用；首页朗读关闭时也禁用。Replay 有独立的播放来源标识，准备提示随现有播放器状态更新，播放开始后可再次点击从头重播。

`core/domain` 的 `SpeechCacheController` 是预加载、容量配置、清理及状态观察的统一入口，由 `core/media` 的应用级协调器实现；沿用手动单例注入，与播放器共享唯一 `SpeechResources`。ViewModel 声明有独立来源身份和上下文版本的资源窗口，通过 `StateFlow` 观察准备状态；选词与学习进度仍归业务层管理。调度顺序为实际播放、当前词音、后续词音、当前例句、后续例句、首页入口预热；只有实际播放抢占后台合成。同一音频跨入口合并需求，释放一个来源不会取消其他来源仍需要的资源。

缓存保存采样率、声道和分段的 Float32 PCM，并用模型／声线／发音资源／语速版本隔离旧声音；身份不包含页面、学习模式或卡片 ID，不同中文读音保持隔离。音频位于 `noBackupFilesDir/speech-cache`，模型文件不计入音频限额。容量不足时逐条淘汰最久未使用项，活动播放资源保护至释放，准备完成后不由协调器长期持有音频引用；损坏项重算，落盘失败仍可播放。后台暂停推测性合成，恢复后重新校验有效窗口；清理、淘汰或容量变化使就绪状态失效，仅在有效触发时有界补缺，避免小容量下循环重建。完整缓存跨重启保留。

手动播放按源按钮与操作编号显示准备动画，首次音频开始后恢复；取消、失败与超时均结束准备。系统文件合成的开始回调不是播放开始，不能用于清除按钮动画。静态检查不证明冷启动延迟、声音质量、设备 TTS 回调、快速点击或后台恢复行为。

## 标签与阶段筛选

标签定义与标注维护见 [词汇标签](WORD_TAGS.md)。独立的 `word-tags/catalog.json` 与 `annotations.json` 按受管来源 ID 关联词条，不改写冻结 CSV 或语音快照；无正式标注的词条及自定义导入词归入 Others。筛选按学习语言保存，跨 Stage 沿用；页面预览只读取计数，返回不应用草稿。

Learn／续学候选和首页 Shuffle 使用同一 Stage＋标签范围。Repository 仅保留当前上下文的活动队列成员；切换范围整体重建，撤销未开始的预排项，保留已开始词的周期、轮次与卡片。选词、恢复入口和语音预加载都读取当前成员，旧每日项不会自动叠入列表；上下文版本隔离迟到操作。新学自动补足全局今日剩余额度，每组最多五词；跨天仍将未完成且实际开始的周期按原规则重置轮次后续学。

当天目标按语言保存在 daily_plans，完整完成的 NEW／CARRYOVER 每天按词 ID 去重累计，跨 Stage 和标签共享。达标即完成，不要求清空暂停词单或复习；Learn more 主动组建最多五词的额外批次，每日学习奖励仍只发一次。复习、收藏、错词和干扰选项不受标签过滤。words、标签已学数和完成描边以 firstPassedDay 是否存在为准；Stage 轮次仍独立按全 Stage 判断，完成标签子集不会推进整阶段下一轮。

Study 标签按当前 Stage 的未 skip 词统计，Wordlist 标签按全库统计，均限定当前学习语言；每标签计数不受其他草稿条件影响。In total 为合成的空条件入口，不写入标签目录。默认单选，既有多选条件打开时保持多选；草稿返回不应用。底部区域背景全宽延伸，系统导航栏 inset 由筛选底部或祖先导航栏消费一次。

阶段轮次仍按全 Stage 判断完成；完成标签子集不会推进整阶段下一轮。切换筛选／阶段时递增上下文版本，防止旧候选、预加载结果或旧卡片操作覆盖新选择。Room 15→16 只为全局状态和语言上下文增加默认空筛选字段，保留词库、学习和书写记录；本轮未执行 KSP 或构建，v16 schema 快照需在下一次获准构建时由工具导出。

2026-10-27 实际执行：冻结词库 `rebuild_wordlist.py --check` 通过，13,223 词、12 列及字节哈希保持一致；标签 `build_word_tags.py --check` 与独立结构核对通过，36 个标签、13,223 条映射，200 个样本每 Stage 40 词，Others 与正式标签互斥。当前 6,137 词有正式标签、7,086 词归 Others，不表示全库已逐词语言审核。Kotlin 2.2.10 PSI 对本轮 13 个 Kotlin 文件解析为 0 语法错误（只验证语法，不含类型解析）；SQLite 内存核对 v15 DDL、两条增量迁移与新增查询计划；15 个新增标签文案的中英文 XML 键一致且无重复。未新增测试代码，未执行应用编译、KSP、APK 打包或设备验证。安全自查：未新增权限、网络入口、依赖或敏感日志，语言范围和旧进度迁移已静态复核。

2026-10-27 筛选与学习队列调整的实际验证：`:app:lintDebug` 成功，0 错误、14 条 SDK／依赖告警；包含本次 Kotlin 类型编译与 KSP，Room 实体、迁移和 schema 未变。中英文 XML 键一致、无重复，`git diff --check` 通过。Android Studio Preview 已观察首页三条进度、360dp 标签双列、320dp／1.3 倍字体单列，以及浅深色计数、完成描边和带系统栏的底部区域。未新增或执行自动化测试，未打包 APK、安装或验证真机交互；这些静态和预览证据不能证明跨天、进程重启、音频取消或全部导航模式的运行表现。安全自查：未新增权限、网络入口、依赖或敏感日志；学习语言范围和事务内队列更新已源码复核。
